package hostlink

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"time"
)

// SearchHit 是对话内容里找到的一个会话：会话号和命中处前后的一段（zeta-helper search 的 zeta_match）。
type SearchHit struct {
	ID      string `json:"id"`
	Snippet string `json:"snippet"`
}

// searchLine 解 zeta-helper search 的一行：命中时 hit 不为空；搜完的那行 done 为真，complete 说是不是全部搜完了
// （没有限时限量截断）。别的行两样都没有。
func searchLine(line []byte) (hit *SearchHit, done, complete bool) {
	var msg struct {
		Type     string `json:"type"`
		ID       string `json:"id"`
		Snippet  string `json:"snippet"`
		Complete bool   `json:"complete"`
	}
	if json.Unmarshal(line, &msg) != nil {
		return nil, false, false
	}
	switch msg.Type {
	case "zeta_match":
		if msg.ID != "" {
			return &SearchHit{ID: msg.ID, Snippet: msg.Snippet}, false, false
		}
	case "zeta_search_done":
		return nil, true, msg.Complete
	}
	return nil, false, false
}

// Search 让 s 那台的 zeta-helper 在对话内容里找 query（没装先装上），边找边把命中交给 hit（在调用的 goroutine 上）。
// 返回是不是全部搜完了：helper 限时限量，结果太多时只列一部分（false）。没搜成时是错误；ctx 取消时停掉那边的进程、返回 ctx 的错误。
// 阻塞，最多十几秒（helper 自己限时 10 秒）。
func (s Shell) Search(ctx context.Context, p *Program, query string, hit func(SearchHit)) (complete bool, err error) {
	r, err := p.Ensure(s.plainExec(time.Minute), s.Name)
	if err != nil {
		return false, errors.New("连不上「" + s.Host.Name() + "」：" + err.Error())
	}
	if problem := s.LinkProblem(r); problem != "" {
		return false, errors.New(problem)
	}
	if r.Code != 0 {
		return false, errors.New(orDefault(tail(r.Stderr, 300), p.Name+" 没装上"))
	}
	proc, err := s.StartPlain(p.Command(s.Name, "search", query))
	if err != nil {
		return false, errors.New("连不上「" + s.Host.Name() + "」：" + err.Error())
	}
	_ = proc.Stdin.Close()
	stop := context.AfterFunc(ctx, proc.Close)
	defer stop()
	var stderr strings.Builder
	drained := make(chan struct{})
	go func() { // 读走，免得写满管道卡住 helper
		_, _ = io.Copy(&stderr, io.LimitReader(proc.Stderr, 64<<10))
		_, _ = io.Copy(io.Discard, proc.Stderr)
		close(drained)
	}()
	done := false
	lines := bufio.NewScanner(proc.Stdout)
	lines.Buffer(make([]byte, 64<<10), 1<<20)
	for lines.Scan() {
		h, end, all := searchLine(lines.Bytes())
		switch {
		case h != nil:
			hit(*h)
		case end:
			done, complete = true, all
		}
	}
	code := proc.Wait()
	<-drained
	switch {
	case ctx.Err() != nil:
		return false, ctx.Err()
	case done:
		return complete, nil
	case code != 0 && strings.TrimSpace(stderr.String()) != "":
		return false, errors.New(tail(stderr.String(), 300))
	}
	return false, errors.New(p.Name + " search 没有搜完")
}
