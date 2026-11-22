package chat

import (
	"context"
	"encoding/json"
	"fmt"
	"strconv"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// 编辑、重新生成、删除一轮、还原代码和分支（docs/core.md「对话」的 conv.rewrite、conv.drop、conv.code_changes、
// conv.restore_code、conv.fork）。剪会话、还原文件都由核心请 Claude Code 做，结果经推送回来：列表变了，
// 没做成时列表里多一条出错提示、新消息放回 recalled。这里只发请求，和按列表算出各轮能做什么。

// Rewrite 编辑或重新生成：剪掉 target（那条用户消息的条目 id）和之后的内容，再发 m。
func (c *Conversation) Rewrite(ctx context.Context, target string, m Outgoing) error {
	return c.call(ctx, "conv.rewrite", m.params(map[string]any{"target": target}))
}

// Drop 删掉 target 开始的这一轮和之后的（只剪不发）。
func (c *Conversation) Drop(ctx context.Context, target string) error {
	return c.call(ctx, "conv.drop", map[string]any{"target": target})
}

// CodeChanges 是把文件改回某条消息之前会动到的：哪些文件、加减几行。
type CodeChanges struct {
	Files      []string `json:"files"`
	Insertions int      `json:"insertions"`
	Deletions  int      `json:"deletions"`
}

// CodeChanges 问还原到 target 之前会动到哪些文件（只看不改）；没有可还原的时返回 nil。
func (c *Conversation) CodeChanges(ctx context.Context, target string) (*CodeChanges, error) {
	result, err := c.callResult(ctx, "conv.code_changes", map[string]any{"target": target})
	if err != nil || isNull(result) {
		return nil, err
	}
	var ch CodeChanges
	if err := json.Unmarshal(result, &ch); err != nil {
		return nil, fmt.Errorf("会改动的文件解不开：%w", err)
	}
	if len(ch.Files) == 0 {
		return nil, nil
	}
	return &ch, nil
}

// RestoreCode 把文件改回 target 之前。还原成了再接着做：resend 不为 nil 时照 Rewrite 剪掉这一轮再发它，
// 否则 drop 时删这一轮。结果以列表里的提示告知。
func (c *Conversation) RestoreCode(ctx context.Context, target string, drop bool, resend *Outgoing) error {
	params := map[string]any{"target": target}
	if drop {
		params["drop"] = true
	}
	if resend != nil {
		params = resend.params(params)
	}
	return c.call(ctx, "conv.restore_code", params)
}

// params 把消息的各项加进 p（文字总是带，图和文件有才带），返回 p。
func (m Outgoing) params(p map[string]any) map[string]any {
	p["text"] = m.Text
	if len(m.Images) > 0 {
		p["images"] = m.Images
	}
	if len(m.Files) > 0 {
		p["files"] = m.Files
	}
	return p
}

// Fork 从条目 last 所在那一轮之后分支：返回新的对话，由这个客户端拿着（核心推来它的整份状态和条目），原对话不动。
// 新对话还没有会话号，发出第一条消息时才有；在那之前断线重连按核心给的 key 重新打开。
func (c *Conversation) Fork(ctx context.Context, last string) (*Conversation, error) {
	cl := c.client
	c.mu.Lock()
	spec := Spec{Cwd: c.state.Cwd, Options: c.spec.Options}
	if spec.Cwd == "" {
		spec.Cwd = c.spec.Cwd
	}
	c.mu.Unlock()
	cl.mu.Lock()
	cl.seq++
	fork := newConversation(cl, "w"+strconv.Itoa(cl.seq), spec)
	fork.forked = true
	// 先登记再发：核心在应答之前就推来新对话的整份状态
	cl.convs[fork.name] = fork
	cl.mu.Unlock()
	if err := c.call(ctx, "conv.fork", map[string]any{"as": fork.name, "last": last}); err != nil {
		cl.forget(fork)
		return nil, err
	}
	fork.mu.Lock()
	fork.connected, fork.opened = true, true
	fork.mu.Unlock()
	cl.notify()
	return fork, nil
}

// Round 是聊天列表里的一轮：从一条用户消息开始，到下一条用户消息之前。
type Round struct {
	// User 是这一轮的用户消息。
	User items.Item
	// Last 是这一轮的操作排挂在哪一项下面：最后一段回答、工作过程或出错提示；末尾「已停止」这类灰色提示不算。
	// 这一轮除了用户消息只有这类提示时为空，只能从用户消息的菜单操作。
	Last string
	// Later 是之后还有几轮，确认时说会一起丢掉多少。
	Later int
	// next 是下一轮用户消息的 uuid（最后一轮没有）：从这一轮之后分支时要剪掉它。
	next string
}

// Rewindable：Claude Code 认得这条用户消息（有 uuid），能编辑、重新生成、删除、还原代码。
func (r Round) Rewindable() bool { return r.User.UUID != "" }

// Branchable：能从这一轮之后分支。最后一轮整个复制；中间的要剪掉下一轮，下一轮的消息得有 uuid。
func (r Round) Branchable() bool { return r.Later == 0 || r.next != "" }

// Rounds 是列表里的各轮，按 id 查。
type Rounds struct {
	list   []Round
	byUser map[string]int
	byLast map[string]int
}

// RoundsOf 把列表分成各轮。第一条用户消息之前的内容（比如载入历史时的提示）不属于任何一轮。
func RoundsOf(list []items.Item) Rounds {
	rs := Rounds{byUser: map[string]int{}, byLast: map[string]int{}}
	var starts []int
	for i, it := range list {
		if it.Kind == items.User {
			starts = append(starts, i)
		}
	}
	for n, start := range starts {
		end := len(list)
		if n+1 < len(starts) {
			end = starts[n+1]
		}
		r := Round{User: list[start], Later: len(starts) - n - 1}
		if end < len(list) {
			r.next = list[end].UUID
		}
		for i := end - 1; i > start; i-- {
			if it := list[i]; it.Kind != items.Notice || it.Error {
				r.Last = it.ID
				break
			}
		}
		rs.byUser[r.User.ID] = n
		if r.Last != "" {
			rs.byLast[r.Last] = n
		}
		rs.list = append(rs.list, r)
	}
	return rs
}

// ByUser 是用户消息 id 开始的那一轮。
func (rs Rounds) ByUser(id string) (Round, bool) { return rs.at(rs.byUser, id) }

// ByLast 是操作排挂在 id 下面的那一轮。
func (rs Rounds) ByLast(id string) (Round, bool) { return rs.at(rs.byLast, id) }

func (rs Rounds) at(index map[string]int, id string) (Round, bool) {
	n, ok := index[id]
	if !ok {
		return Round{}, false
	}
	return rs.list[n], true
}

// IsLast：r 是最后一轮。
func (rs Rounds) IsLast(r Round) bool {
	return len(rs.list) > 0 && rs.list[len(rs.list)-1].User.ID == r.User.ID
}
