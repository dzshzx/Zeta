package hub

import (
	"context"
	"errors"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/egoist/mygo/i18n"
)

// Models 问这台设备上的 Claude Code 有哪些模型（设置里默认模型的下拉用）。阻塞，在后台 goroutine 上调用。
func (r *Remote) Models(ctx context.Context, cwd string) ([]chat.ModelOption, error) {
	return r.ModelsFor(ctx, cwd, sessionproto.HarnessClaude)
}

// ModelsFor 问这台设备上指定 harness（"claude" 或 "agy"）有哪些模型：开一个不发消息的临时对话调 conv.prepare 等清单推来。
func (r *Remote) ModelsFor(ctx context.Context, cwd, harness string) ([]chat.ModelOption, error) {
	conv, err := r.conn.client.Open(ctx, chat.Spec{Cwd: cwd, Harness: sessionproto.NormalizeHarness(harness)})
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
			if it.Kind == items.Notice && it.Code == "noticeStartFailed" {
				failed = it.Said(i18n.Locale())
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
