package hub

import (
	"context"
	"encoding/json"
	"errors"
	"slices"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// modelsFixture：这台的对话连接上，conv.prepare 由 prepared 推来要推的（拿到这次的对话名），别的方法照常答空；记下发来的方法。
func modelsFixture(t *testing.T, prepared func(conv string) string) (*chatFixture, *[]string) {
	t.Helper()
	f := newChatFixture(t)
	var methods []string
	r := f.hub.Remote(f.id)
	if r == nil {
		t.Fatal("没有这台")
	}
	f.link().OnCall(func(c hubtest.Call) (json.RawMessage, error) {
		methods = append(methods, c.Method)
		switch c.Method {
		case "conv.open":
			pushAll(f.link(), hubtest.Recorded("open", c.Conv()))
		case "conv.prepare":
			f.link().Push(prepared(c.Conv()))
		}
		return nil, nil
	})
	return f, &methods
}

// 要到清单：在默认工作目录上开对话，不带会话号；之后结束进程、放开对话，连接上不再留着它。
func TestRemoteModels(t *testing.T) {
	f, methods := modelsFixture(t, func(conv string) string {
		return `{"type":"conv.state","conv":"` + conv + `","models":[{"value":"opus","resolvedModel":"claude-opus-5-5"}]}`
	})
	models, err := f.hub.Remote(f.id).Models(context.Background(), "/home/box/web")
	if err != nil || len(models) != 1 || models[0].ResolvedModel != "claude-opus-5-5" {
		t.Fatalf("清单：%+v %v", models, err)
	}
	if want := []string{"conv.open", "conv.prepare", "conv.end", "conv.close"}; !slices.Equal(*methods, want) {
		t.Fatalf("发的：%q，要 %q", *methods, want)
	}
	for _, c := range f.link().Took() {
		if c.Method == "conv.open" && (c.Str("cwd") != "/home/box/web" || c.Str("session") != "") {
			t.Errorf("conv.open：%s", c.JSON())
		}
	}
	if n := len(f.hub.chatConn(f.id).client.Conversations()); n != 0 {
		t.Fatalf("连接上还留着 %d 个对话", n)
	}
}

// 起不来时核心在条目里加「启动失败」：不等到超时，把它当原因交回去，照样放开对话。
func TestRemoteModelsStartFailure(t *testing.T) {
	f, methods := modelsFixture(t, func(conv string) string {
		return `{"type":"conv.items","conv":"` + conv + `","full":true,"items":[{"kind":"notice","id":"n1","text":"启动失败：没有 claude","code":"noticeStartFailed","args":{"reason":"没有 claude"}}]}`
	})
	_, err := f.hub.Remote(f.id).Models(context.Background(), "/home/box")
	if err == nil || err.Error() != "启动失败：没有 claude" {
		t.Fatalf("原因：%v", err)
	}
	if !slices.Contains(*methods, "conv.close") {
		t.Fatalf("没放开对话：%q", *methods)
	}

	// 一直不报：到期返回 ctx 的错误
	f2, _ := modelsFixture(t, func(conv string) string { return `{"type":"conv.state","conv":"` + conv + `","busy":false}` })
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := f2.hub.Remote(f2.id).Models(ctx, "/home/box"); !errors.Is(err, context.Canceled) {
		t.Fatalf("到期：%v", err)
	}
}
