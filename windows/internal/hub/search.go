package hub

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
)

// 侧栏的搜索（同 Kotlin 版 SessionPane 的搜索）：标题、首句当场比对；对话内容交给各台列着的设备上的核心（sessions.search，
// 10 秒、80 个为限），各台搜完一台出一台。换了关键词就把上一次的停掉。

// searchDelay 是关键词停下多久才去搜对话内容。
const searchDelay = 300 * time.Millisecond

// SearchProgress 是对话内容搜到哪了。
type SearchProgress int

const (
	// SearchIdle：没在搜（关键词是空的）。
	SearchIdle SearchProgress = iota
	// SearchRunning：还在搜。
	SearchRunning
	// SearchDone：各台都搜完了。
	SearchDone
	// SearchPartial：有一台结果太多只列了一部分，或没搜成。
	SearchPartial
	// SearchFailed：都没搜成，只比对了标题。
	SearchFailed
)

type search struct {
	query    string
	hits     map[string]string // 内容里找到的会话（按置顶键）→ 命中处的片段
	progress SearchProgress
	cancel   context.CancelFunc
}

// Query 是侧栏搜索框里的字（去掉两头空白之前的）。
func (h *Hub) Query() string { return h.search.query }

// Searching：侧栏在搜（关键词不是空的），列表换成搜索结果。
func (h *Hub) Searching() bool { return h.needle() != "" }

func (h *Hub) needle() string { return strings.TrimSpace(h.search.query) }

// SearchProgress 是对话内容搜到哪了。
func (h *Hub) SearchProgress() SearchProgress { return h.search.progress }

// SetQuery 换搜索框里的字：关键词变了就停掉上一次，等 SearchDelay 没再变再去各台搜对话内容。
func (h *Hub) SetQuery(q string) {
	before := h.needle()
	h.search.query = q
	needle := h.needle()
	if needle == before {
		return
	}
	if h.search.cancel != nil {
		h.search.cancel()
	}
	h.search.hits, h.search.cancel = map[string]string{}, nil
	if needle == "" {
		h.search.progress = SearchIdle
		return
	}
	h.search.progress = SearchRunning
	ctx, cancel := context.WithCancel(context.Background())
	h.search.cancel = cancel
	conns := map[string]*chatConn{}
	for _, id := range h.Listed() {
		conns[id] = h.chatConn(id)
	}
	delay := h.SearchDelay
	h.run(func() { h.searchHosts(ctx, conns, needle, delay) })
}

// searchTimeout 是一台搜一次最多等多久（核心自己限时 10 秒）。
const searchTimeout = 30 * time.Second

// searchHosts 在后台各台（conns：设备编号 → 给对话用的连接）同时搜 needle：命中边找边经 post 交回主线程，都搜完了报结局。
// ctx 取消（换了关键词）后不再交回。
func (h *Hub) searchHosts(ctx context.Context, conns map[string]*chatConn, needle string, delay time.Duration) {
	select {
	case <-ctx.Done():
		return
	case <-time.After(delay):
	}
	var (
		wg       sync.WaitGroup
		mu       sync.Mutex
		outcomes []searchOutcome
	)
	for id, conn := range conns {
		wg.Add(1)
		go func() {
			defer wg.Done()
			complete, err := conn.search(ctx, needle, func(session, snippet string) {
				key := pinKey(hostlink.Session{Backend: id, ID: session})
				// 几台同时在交：post 可能就地执行（测试里），一次只交一个
				mu.Lock()
				defer mu.Unlock()
				h.post(func() {
					if ctx.Err() == nil {
						h.search.hits[key] = snippet
					}
				})
			})
			var reply *hostlink.ReplyError
			if err != nil && !errors.As(err, &reply) && ctx.Err() == nil {
				h.post(func() { h.dropDeadConn(id, conn) })
			}
			mu.Lock()
			outcomes = append(outcomes, searchOutcome{complete, err == nil})
			mu.Unlock()
		}()
	}
	wg.Wait()
	progress := combined(outcomes)
	h.post(func() {
		if ctx.Err() == nil {
			h.search.progress = progress
		}
	})
}

type searchOutcome struct{ complete, ok bool }

// received 是连接推来的一行：搜索的命中交给在搜的那次，别的（对话推送）交给协议层。在读的 goroutine 上。
func (c *chatConn) received(line []byte) {
	var m struct {
		Type    string `json:"type"`
		Search  int64  `json:"search"`
		ID      string `json:"id"`
		Snippet string `json:"snippet"`
	}
	if !bytes.Contains(line, []byte(`"sessions.match"`)) || json.Unmarshal(line, &m) != nil || m.Type != "sessions.match" {
		c.client.Handle(line)
		return
	}
	c.searchMu.Lock()
	hit := c.searches[m.Search]
	c.searchMu.Unlock()
	if hit != nil && m.ID != "" {
		hit(m.ID, m.Snippet)
	}
}

// search 在这条连接的核心里搜 needle（sessions.search）：边找边推来的命中交给 hit（在读的 goroutine 上），搜完返回
// 是不是都搜完了（false 是到了时限或个数被截断）。同一个客户端发新的搜索时核心取消上一个。在后台调用。
func (c *chatConn) search(ctx context.Context, needle string, hit func(session, snippet string)) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, searchTimeout)
	defer cancel()
	var id int64
	raw, err := c.link.CallTagged(ctx, "sessions.search", map[string]any{"query": needle}, func(n int64) {
		id = n
		c.searchMu.Lock()
		if c.searches == nil {
			c.searches = map[int64]func(string, string){}
		}
		c.searches[n] = hit
		c.searchMu.Unlock()
	})
	c.searchMu.Lock()
	delete(c.searches, id)
	c.searchMu.Unlock()
	var r struct {
		Complete bool `json:"complete"`
	}
	if err == nil {
		err = json.Unmarshal(raw, &r)
	}
	return r.Complete, err
}

// combined 把各台的结局合成一个：都没搜成是 SearchFailed，有一台没搜全或没搜成是 SearchPartial，都搜完了是 SearchDone。
func combined(outcomes []searchOutcome) SearchProgress {
	if !slices.ContainsFunc(outcomes, func(o searchOutcome) bool { return o.ok }) {
		return SearchFailed
	}
	if slices.ContainsFunc(outcomes, func(o searchOutcome) bool { return !o.ok || !o.complete }) {
		return SearchPartial
	}
	return SearchDone
}

// Results 是搜索结果：列着的会话（连同 current，见 Groups）里内容找到的，和标题或首句里有关键词的（不分大小写），照列表的先后；
// 归档的也算。不在搜时是空。
func (h *Hub) Results(current hostlink.Session, now time.Time) []hostlink.Session {
	needle := strings.ToLower(h.needle())
	if needle == "" {
		return nil
	}
	var out []hostlink.Session
	for _, s := range h.withCurrent(current, now) {
		_, hit := h.search.hits[pinKey(s)]
		if hit || strings.Contains(strings.ToLower(s.Title), needle) || strings.Contains(strings.ToLower(s.Preview), needle) {
			out = append(out, s)
		}
	}
	return out
}

// Snippet 是会话 s 的对话内容里命中处的片段；内容里没找到时是空。
func (h *Hub) Snippet(s hostlink.Session) string { return h.search.hits[pinKey(s)] }
