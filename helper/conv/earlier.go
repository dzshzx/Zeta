package conv

import (
	"strconv"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// 长对话往上翻时载入更早的记录（conv.earlier）。打开对话时只载入最近的一段，客户端翻到顶附近时再要那之前的一页，接到列表前面。
// zeta-helper history 每次从头读整份记录（照剪切记号去掉被剪的部分）再按条数切页；剪切只去掉已载入那段里某条用户消息之后的内容，
// 新内容只接在后面，所以还没载入的那段不会变。要页时报上 earlierAnchor 给它核对，对不上（比如在终端里剪到了更早的地方）它就不给（stale），
// 客户端只能重新打开对话。

var (
	errNoEarlier    = locales.Err("errNoEarlier")
	errEarlierBusy  = locales.Err("errEarlierBusy")
	errEarlierStale = codedError{sessionproto.CodeHistoryChanged, locales.Err("errEarlierStale")}
)

// codedError 是带代号的出错：应答里另带 code（见 core.Client.Reply），客户端按代号认，不按原文；err 是给人看的那句。
type codedError struct {
	code string
	err  *locales.Error
}

func (e codedError) Error() string { return e.err.Error() }
func (e codedError) Code() string  { return e.code }
func (e codedError) Unwrap() error { return e.err }

// loadEarlier 开始载入更早的一页（rest 时一次载完剩下的全部，对话内查找用），返回的 channel 在接上（或没接上）后给出结果。
// 同一时间只有一个请求在读，所以读回来的正是现在没载入的那段。在对话的 goroutine 上调用。
func (c *Conversation) loadEarlier(rest bool) (<-chan error, error) {
	switch {
	case c.earlier == 0:
		return nil, errNoEarlier
	case c.earlierLoading || c.loadingHistory:
		return nil, errEarlierBusy
	}
	c.earlierLoading = true
	c.changed()
	file, skipped, anchor := c.earlierFile, c.earlier, c.earlierAnchor
	if anchor == "" {
		anchor = "-"
	}
	args := []string{file, strconv.Itoa(skipped), anchor}
	if rest {
		args = append(args, "all")
	}
	done := make(chan error, 1)
	go func() {
		out, err := c.deps.History(args...)
		c.do(func() {
			apply := func() { done <- c.applyEarlier(out, err, file, skipped) }
			if c.rewind != nil {
				c.deferredEarlier = apply
				return
			}
			apply()
		})
	}()
	return done, nil
}

// applyEarlier 把读回来的一页接到列表最前面。各页的条目编号按页的位置错开（每页的位置都不同）。
func (c *Conversation) applyEarlier(out []byte, err error, file string, skipped int) error {
	c.earlierLoading = false
	defer c.changed()
	if err != nil {
		return locales.Err("errEarlierReadFailed", "detail", err.Error())
	}
	read := readHistory(out, file, "p"+strconv.Itoa(skipped)+"-", false)
	if read.header.Stale {
		return errEarlierStale
	}
	c.r.Items = append(read.reducer.Items, c.r.Items...)
	c.earlier, c.earlierAnchor = read.header.Skipped, read.header.Anchor
	c.setTodoSeed(read.header.TodoSeed)
	c.usage.PrependHistory(read.usage())
	return nil
}
