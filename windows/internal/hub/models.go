package hub

import (
	"context"
	"errors"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// Models 问这台设备上的 Claude Code 有哪些模型（设置里默认模型的下拉用，同手机版没有清单时调 conv.prepare 去取）：在工作目录
// cwd 上开一个不发消息的对话，让核心把进程起好（conv.prepare），等 initialize 报来清单，再结束进程（conv.end）、放开对话。
// 不发消息就没有会话号，不留会话记录，也不开 tmux 窗口。起不来时返回核心说的原因。阻塞，在后台 goroutine 上调用。
func (r *Remote) Models(ctx context.Context, cwd string) ([]chat.ModelOption, error) {
	conv, err := r.conn.client.Open(ctx, chat.Spec{Cwd: cwd})
	if err != nil {
		return nil, err
	}
	defer func() {
		// 等清单的 ctx 可能已经到期，收尾另给时限
		done, cancel := context.WithTimeout(context.WithoutCancel(ctx), chat.OpenTimeout)
		defer cancel()
		_ = conv.End(done)
		_ = conv.Close(done)
	}()
	if err := conv.Prepare(ctx); err != nil {
		return nil, err
	}
	var failed string
	err = conv.Wait(ctx, func(s chat.State, list []items.Item) bool {
		for _, it := range list {
			if it.Kind == items.Notice && strings.HasPrefix(it.Text, "启动失败") {
				failed = it.Text
			}
		}
		return len(s.Models) > 0 || failed != ""
	})
	switch {
	case err != nil:
		return nil, err
	case failed != "":
		return nil, errors.New(failed)
	}
	return conv.State().Models, nil
}
