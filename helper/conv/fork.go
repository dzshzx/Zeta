package conv

// 分支：`--resume <原会话> --fork-session` 起一个新进程，它在内存里复制原会话，发出第一条消息时才写出新的记录文件，
// 原会话一字不动。从中间某一轮分支时，先在新进程里把下一轮剪掉（rewind_conversation），再发消息。

import (
	"cmp"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// forkState 是一个还没发过消息的分支要做的剪切。
type forkState struct {
	cut      string   // 发第一条消息前要剪掉的下一轮的用户消息；空是整个复制
	lastSeen string   // 剪的时候报给 Claude Code 的最后一条用户消息：进程里是原会话的全部内容，报原会话的
	cutIn    *process // 已经在这个进程里剪过了；换了进程要重新剪
}

// forkPending：这是个还没发过消息的分支，当前进程里还是原会话的全部内容。
func (c *Conversation) forkPending() bool {
	return c.fork != nil && c.session == "" && (c.proc == nil || c.fork.cutIn != c.proc)
}

// forkCutDone：在进程 p 里剪过了（改写、删除一轮的那一刀也算：它剪得比分支要剪的更靠前）。
func (c *Conversation) forkCutDone(p *process) {
	if c.fork != nil {
		c.fork.cutIn = p
	}
}

// sessionArgs 是起进程时接上哪个会话：接着原会话；分支从原会话复制出一个新会话，标题用 --name 定；新对话什么都不加。
func (c *Conversation) sessionArgs() []string {
	switch {
	case c.session != "":
		return []string{"--resume", c.session}
	case c.forkSource != "":
		args := []string{"--resume", c.forkSource, "--fork-session"}
		if strings.TrimSpace(c.title) != "" {
			args = append(args, "--name", c.title)
		}
		return args
	}
	return nil
}

// deliver 把消息交给进程。分支的第一条消息要先在新进程里剪掉原会话的后几轮，剪成了再交；没剪成时消息放回输入框。
func (c *Conversation) deliver(p *process, m *outgoing) {
	if !c.forkPending() || c.fork.cut == "" {
		c.transmit(p, m)
		return
	}
	c.startRewind(p, rewindConversation(c.fork.cut, c.fork.lastSeen), rewindFailure,
		func() {
			c.forkCutDone(p)
			c.transmit(p, m)
		},
		func(reason string) {
			if m.item != nil {
				c.r.Remove(m.item)
			}
			c.abandon(m, "分支没有建成："+reason)
		})
}

// forkPlan 是从一个对话分支时从它那里带走的：新对话先显示的条目、要剪的、原对话还没载入的更早历史，
// 和原对话当时显示的模型、用的思考强度和权限模式。
//
// 模型不带进启动参数：--fork-session 复制出的会话由 Claude Code 照原会话的记录接着用原来的模型，
// shownModel 只是 Claude Code 报来之前先显示的。思考强度 Claude Code 不恢复，effort 接着用；权限模式也一样。
type forkPlan struct {
	source   string
	cut      string
	lastSeen string
	items    []*transcript.Item
	cwd      string
	title    string

	earlier       int
	earlierAnchor string
	earlierFile   string
	todoSeed      json.RawMessage

	shownModel string
	effort     string
	mode       string
	options    Options // 发起分支的客户端给原对话的设置
}

var (
	errForkNoSession = errors.New("这个对话还没有会话记录，不能分支")
	errForkHere      = errors.New("不能从这里分支：找不到这一项，或下一轮的消息 Claude Code 不认得")
)

// forkPlan 从条目 last 所在那一轮的末尾分支：那一轮之后的内容丢掉。
func (c *Conversation) forkPlan(client int, last string) (*forkPlan, error) {
	switch {
	case c.session == "":
		return nil, errForkNoSession
	case !c.canRewind():
		return nil, errNotNow
	}
	end, cut, ok := branchEnd(c.r.Items, last)
	if !ok {
		return nil, errForkHere
	}
	shown := c.reportedModel
	if shown == "" && c.model != nil {
		shown = *c.model
	}
	return &forkPlan{
		source: c.session, cut: cut, lastSeen: lastSeenUser(c.r.Items), items: copyItems(c.r.Items[:end]),
		cwd: c.cwd, title: c.title,
		earlier: c.earlier, earlierAnchor: c.earlierAnchor, earlierFile: cmp.Or(c.earlierFile, c.file), todoSeed: c.todoSeed,
		shownModel: shown, effort: c.effort, mode: c.modes.user, options: c.options[client],
	}, nil
}

// startFork 把 plan 摆进刚建的对话：先显示到那一轮为止的内容和一句说明，起进程时从原会话复制。
func (c *Conversation) startFork(plan *forkPlan) {
	// 新内容的编号要和原对话带来的那些错开
	c.r = transcript.New("b" + newUUID()[:8] + "-")
	c.r.Items = plan.items
	c.r.AddNotice("从这里分支出的新对话，原对话不变。发出消息后保存", false)
	c.forkSource = plan.source
	c.fork = &forkState{cut: plan.cut, lastSeen: plan.lastSeen}
	// 接着原会话的记录：选默认模型时要明说 default（见 chooseModel）
	c.openedSession = plan.source
	c.earlier, c.earlierAnchor, c.earlierFile, c.todoSeed = plan.earlier, plan.earlierAnchor, plan.earlierFile, plan.todoSeed
	c.reportedModel, c.effort, c.modes.user = plan.shownModel, plan.effort, plan.mode
	c.changed()
}

// branchEnd 找条目 last 所在那一轮的末尾（下一条用户消息的位置，没有下一轮时是列表长度）和要剪掉的下一条用户消息的 uuid。
// 第一条用户消息之前的内容不属于任何一轮；下一条用户消息没有 uuid（剪不准）时 ok 为假。
func branchEnd(items []*transcript.Item, last string) (end int, cut string, ok bool) {
	isUser := func(it *transcript.Item) bool { return it.Kind == transcript.KindUser }
	i := slices.IndexFunc(items, func(it *transcript.Item) bool { return it.ID == last })
	if i < 0 || !slices.ContainsFunc(items[:i+1], isUser) {
		return 0, "", false
	}
	end = len(items)
	if next := slices.IndexFunc(items[i+1:], isUser); next >= 0 {
		end = i + 1 + next
		if cut = items[end].UUID; cut == "" {
			return 0, "", false
		}
	}
	return end, cut, true
}

// copyItems 深拷贝条目：分支和原对话各在自己的 goroutine 里改自己的那份。
func copyItems(items []*transcript.Item) []*transcript.Item {
	b, err := json.Marshal(items)
	if err != nil {
		panic(fmt.Sprintf("条目编不成 JSON：%v", err))
	}
	var out []*transcript.Item
	if err := json.Unmarshal(b, &out); err != nil {
		panic(fmt.Sprintf("条目解不回来：%v", err))
	}
	return out
}

var branchPrefix = regexp.MustCompile(`^分支(?: \d+)? · `)

// branchTitle 是分支的标题：原标题前加「分支 · 」，和 taken 里的重名时编号成「分支 2 · 」；从分支再分支不叠前缀
// （Claude Code 自己的 /branch 也是这样）。标记放在前面，是因为侧栏和标题栏都窄，长标题的尾巴会被省略号吃掉。
func branchTitle(title string, taken []string) string {
	base := cmp.Or(strings.TrimSpace(branchPrefix.ReplaceAllString(strings.TrimSpace(title), "")), "新对话")
	for n := 1; ; n++ {
		t := "分支 · " + base
		if n > 1 {
			t = "分支 " + strconv.Itoa(n) + " · " + base
		}
		if !slices.Contains(taken, t) {
			return t
		}
	}
}

// fork：客户端 client 从 src 的条目 r.Last 所在那一轮之后分支。新对话由这个客户端以名字 r.As 拿着（和 conv.open 一样推整份），
// 原对话不动。标题避开这台主机上同一工作目录里已有的会话标题。
func (m *Manager) fork(client int, r request, src *Conversation) error {
	if r.As == "" {
		return errors.New("缺 as")
	}
	v, err := src.Call(func() (any, error) { return src.forkPlan(client, r.Last) })
	if err != nil {
		return err
	}
	plan := v.(*forkPlan)
	var taken []string
	if m.deps.SessionTitles != nil {
		taken = m.deps.SessionTitles(plan.cwd)
	}
	m.mu.Lock()
	m.seq++
	id := "c" + strconv.Itoa(m.seq)
	m.mu.Unlock()
	c := New(id, Spec{Cwd: plan.cwd, Title: branchTitle(plan.title, taken)}, m.deps, m.send)
	if _, err := c.Call(func() (any, error) { c.startFork(plan); return nil, nil }); err != nil {
		return err
	}
	m.mu.Lock()
	if m.byName[client][r.As] != nil {
		m.mu.Unlock()
		c.close()
		return errors.New("这条连接已经有叫 " + r.As + " 的对话")
	}
	m.all[c] = true
	if m.byName[client] == nil {
		m.byName[client] = map[string]*Conversation{}
	}
	m.byName[client][r.As] = c
	m.mu.Unlock()
	opts := plan.options
	if r.Options != nil {
		opts = *r.Options
	}
	return c.Hold(client, r.As, opts)
}
