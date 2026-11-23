package chat

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/helper/coreapi"
)

func TestRewindRequests(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()
	img := []coreapi.Image{{MediaType: "image/png", Data: []byte("hi")}}
	if err := conv.Rewrite(ctx, "u1", Outgoing{Text: "改好的", Images: img, Files: []string{"/h/a.txt"}}); err != nil {
		t.Fatal(err)
	}
	if err := conv.Drop(ctx, "u1"); err != nil {
		t.Fatal(err)
	}
	if err := conv.RestoreCode(ctx, "u1", true); err != nil {
		t.Fatal(err)
	}
	if err := conv.Regenerate(ctx, "u1", false); err != nil {
		t.Fatal(err)
	}
	if err := conv.Regenerate(ctx, "u1", true); err != nil {
		t.Fatal(err)
	}
	want := []call{
		{"conv.rewrite", `{"conv":"w1","files":["/h/a.txt"],"images":[{"media_type":"image/png","data":"aGk="}],"target":"u1","text":"改好的"}`},
		{"conv.drop", `{"conv":"w1","target":"u1"}`},
		{"conv.restore_code", `{"conv":"w1","drop":true,"target":"u1"}`},
		{"conv.rewrite", `{"conv":"w1","resend":true,"target":"u1"}`},
		{"conv.restore_code", `{"conv":"w1","resend":true,"target":"u1"}`},
	}
	if got := link.took(); !reflect.DeepEqual(got, want) {
		t.Fatalf("请求：\n%v\n要的是\n%v", got, want)
	}

	// 核心拒绝时原因原样交回
	link.errs["conv.drop"] = errors.New("现在不能改：等这一轮结束、回应完等着的请求再试")
	if err := conv.Drop(ctx, "u1"); err == nil || err.Error() != "现在不能改：等这一轮结束、回应完等着的请求再试" {
		t.Fatalf("出错：%v", err)
	}
}

func TestCodeChanges(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()
	link.results["conv.code_changes"] = `{"files":["/h/p/a.go","/h/p/b.go"],"insertions":3,"deletions":1}`
	ch, err := conv.CodeChanges(ctx, "u1")
	if err != nil || ch == nil || len(ch.Files) != 2 || ch.Insertions != 3 || ch.Deletions != 1 {
		t.Fatalf("改动：%+v %v", ch, err)
	}
	link.results["conv.code_changes"] = `null`
	if ch, err := conv.CodeChanges(ctx, "u1"); ch != nil || err != nil {
		t.Fatalf("没有可还原的：%+v %v", ch, err)
	}
	if got := link.took(); len(got) != 2 || got[0].params != `{"conv":"w1","target":"u1"}` {
		t.Fatalf("请求：%v", got)
	}
}

func TestForkHoldsTheNewConversation(t *testing.T) {
	link, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","cwd":"/h/p"}`))
	link.onCall = func(method string, params map[string]any) {
		if method == "conv.fork" {
			as := params["as"].(string)
			// 核心在应答之前推来新对话的整份
			client.Handle(line(`{"type":"conv.state","conv":"` + as + `","full":true,"key":"c7","title":"分支 · 修 bug","can_rewind":true}`))
			client.Handle(line(`{"type":"conv.items","conv":"` + as + `","full":true,"items":[{"kind":"user","id":"a"}]}`))
		}
	}
	fork, err := conv.Fork(context.Background(), "b")
	if err != nil {
		t.Fatal(err)
	}
	if got := link.took(); len(got) != 1 || got[0].method != "conv.fork" || got[0].params != `{"as":"w2","conv":"w1","last":"b"}` {
		t.Fatalf("请求：%v", got)
	}
	u := fork.Take()
	if !u.Connected || u.State.Title != "分支 · 修 bug" || !u.State.CanRewind || len(u.Items) != 1 || fork.Session() != "" {
		t.Fatalf("新对话：%+v", u)
	}
	// 断线重连：还没有会话号，按 key 重新打开；原对话照会话号
	link.onCall = nil
	client.Disconnected()
	if err := client.Reconnected(context.Background()); err != nil {
		t.Fatal(err)
	}
	got := link.took()
	if len(got) != 2 || got[1].params != `{"conv":"w2","cwd":"/h/p","key":"c7","options":{"auto_title":true,"compact_percent":80}}` {
		t.Fatalf("重新打开：%v", got)
	}

	// 核心拒绝分支时不留下新对话
	link.errs["conv.fork"] = errors.New("这个对话还没有会话记录，不能分支")
	if _, err := conv.Fork(context.Background(), "b"); err == nil {
		t.Fatal("没报错")
	}
	if n := len(client.Conversations()); n != 2 {
		t.Fatalf("对话：%d 个", n)
	}
}
