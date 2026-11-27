package chat

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/hub/chat/items"
)

// Wait 等到推来的状态满足条件：Prepare 以后 initialize 报来模型清单。不满足的推送不让它返回，ctx 到期时返回它的错误。
func TestWaitForModelsAfterPrepare(t *testing.T) {
	link, client, conv, _ := opened(t)
	link.onCall = func(method string, params map[string]any) {
		if method == "conv.prepare" {
			// 核心先推一次不相干的变化，再推模型清单（各在应答之前，同一个 goroutine 里）
			go func() {
				client.Handle(line(`{"type":"conv.state","conv":"` + params["conv"].(string) + `","busy":false}`))
				client.Handle(line(`{"type":"conv.state","conv":"` + params["conv"].(string) + `","models":[{"value":"opus","resolvedModel":"claude-opus-5-5"}]}`))
			}()
		}
	}
	if err := conv.Prepare(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 1 || got[0].method != "conv.prepare" {
		t.Fatalf("发出的：%+v", got)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	err := conv.Wait(ctx, func(s State, _ []items.Item) bool { return len(s.Models) > 0 })
	if err != nil {
		t.Fatal(err)
	}
	if m := conv.State().Models; len(m) != 1 || m[0].ResolvedModel != "claude-opus-5-5" {
		t.Fatalf("模型：%+v", m)
	}

	// 等不到时到期返回
	short, stop := context.WithCancel(context.Background())
	stop()
	if err := conv.Wait(short, func(State, []items.Item) bool { return false }); !errors.Is(err, context.Canceled) {
		t.Fatalf("到期：%v", err)
	}
}
