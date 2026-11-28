package chat

import (
	"context"
	"strconv"

	"github.com/dzshzx/Zeta/helper/coreapi"
)

// 编辑、重新生成、删除一轮、还原代码和分支（docs/core.md「对话」的 conv.rewrite、conv.drop、conv.code_changes、
// conv.restore_code、conv.fork）。剪会话、还原文件都由核心请 Claude Code 做，结果经推送回来：列表变了，
// 没做成时列表里多一条出错提示、新消息放回 recalled。
// 这里只发请求；各轮能做什么由核心算好，放在用户条目上（items.Item 的 Rewindable、Forkable、RoundLast）。

// Rewrite 编辑：剪掉 target（那条用户消息的条目 id）和之后的内容，再发 m。
func (c *Conversation) Rewrite(ctx context.Context, target string, m coreapi.Outgoing) error {
	return c.do(func(v coreapi.Conv) error { return v.Rewrite(ctx, target, m) })
}

// Drop 删掉 target 开始的这一轮和之后的（只剪不发）。
func (c *Conversation) Drop(ctx context.Context, target string) error {
	return c.do(func(v coreapi.Conv) error { return v.Drop(ctx, target) })
}

// CodeChanges 问还原到 target 之前会动到哪些文件（只看不改）；没有可还原的时返回 nil。
func (c *Conversation) CodeChanges(ctx context.Context, target string) (*coreapi.CodeChanges, error) {
	if err := c.ready(); err != nil {
		return nil, err
	}
	return c.api.CodeChanges(ctx, target)
}

// RestoreCode 把文件改回 target 之前；还原成了、drop 时再删这一轮。结果以列表里的提示告知。
func (c *Conversation) RestoreCode(ctx context.Context, target string, drop bool) error {
	return c.do(func(v coreapi.Conv) error { return v.RestoreCode(ctx, coreapi.Restore{Target: target, Drop: drop}) })
}

// Regenerate 重新生成：剪掉 target 那条用户消息和之后的内容，再照它原样重发（文字、图片和附件路径都由核心从那一项取，
// 会话记录里的图由核心自己取回）。restoreCode 时先把文件改回那条消息之前（conv.restore_code），还原成了再剪、再发。
func (c *Conversation) Regenerate(ctx context.Context, target string, restoreCode bool) error {
	return c.do(func(v coreapi.Conv) error {
		if restoreCode {
			return v.RestoreCode(ctx, coreapi.Restore{Target: target, Resend: true})
		}
		return v.Regenerate(ctx, target)
	})
}

// Fork 从条目 last 所在那一轮之后分支：返回新的对话，由这个客户端拿着（核心推来它的整份状态和条目），原对话不动。
// 新对话还没有会话号，发出第一条消息时才有；在那之前断线重连按核心给的 key 重新打开。
func (c *Conversation) Fork(ctx context.Context, last string) (*Conversation, error) {
	cl := c.client
	c.mu.Lock()
	spec := Spec{Cwd: c.state.Cwd, Harness: c.state.Harness, Options: c.spec.Options}
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
	// 不带 options：核心沿用这个客户端给原对话的，和 spec.Options 一样
	if err := c.do(func(v coreapi.Conv) error { return v.Fork(ctx, coreapi.ForkSpec{As: fork.name, Last: last}) }); err != nil {
		cl.forget(fork)
		return nil, err
	}
	fork.mu.Lock()
	fork.connected, fork.opened = true, true
	fork.mu.Unlock()
	cl.notify()
	return fork, nil
}
