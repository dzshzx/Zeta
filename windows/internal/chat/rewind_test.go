package chat

import (
	"context"
	"errors"
	"reflect"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

func TestRewindRequests(t *testing.T) {
	link, _, conv, _ := opened(t)
	ctx := context.Background()
	img := []Image{{MediaType: "image/png", Data: "AAAA"}}
	if err := conv.Rewrite(ctx, "u1", Outgoing{Text: "改好的", Images: img, Files: []string{"/h/a.txt"}}); err != nil {
		t.Fatal(err)
	}
	if err := conv.Drop(ctx, "u1"); err != nil {
		t.Fatal(err)
	}
	if err := conv.RestoreCode(ctx, "u1", true, nil); err != nil {
		t.Fatal(err)
	}
	if err := conv.RestoreCode(ctx, "u1", false, &Outgoing{Text: "再来"}); err != nil {
		t.Fatal(err)
	}
	want := []call{
		{"conv.rewrite", `{"conv":"w1","files":["/h/a.txt"],"images":[{"media_type":"image/png","data":"AAAA"}],"target":"u1","text":"改好的"}`},
		{"conv.drop", `{"conv":"w1","target":"u1"}`},
		{"conv.restore_code", `{"conv":"w1","drop":true,"target":"u1"}`},
		{"conv.restore_code", `{"conv":"w1","target":"u1","text":"再来"}`},
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

func TestRounds(t *testing.T) {
	list := []items.Item{
		{Kind: items.Notice, ID: "n0", Text: "载入历史"},
		{Kind: items.User, ID: "u1", UUID: "x1"},
		{Kind: items.Work, ID: "w1"},
		{Kind: items.Answer, ID: "a1"},
		{Kind: items.User, ID: "u2"}, // 没有 uuid：改不了，上一轮也分支不了
		{Kind: items.Notice, ID: "e2", Error: true},
		{Kind: items.Notice, ID: "s2", Text: "已停止"},
		{Kind: items.User, ID: "u3", UUID: "x3"},
		{Kind: items.Notice, ID: "s3", Text: "已停止"},
	}
	rs := RoundsOf(list)
	r1, ok := rs.ByUser("u1")
	if !ok || r1.Last != "a1" || r1.Later != 2 || !r1.Rewindable() || r1.Branchable() || rs.IsLast(r1) {
		t.Fatalf("第一轮：%+v", r1)
	}
	if r, ok := rs.ByLast("a1"); !ok || r.User.ID != "u1" {
		t.Fatalf("按回答查：%+v", r)
	}
	r2, _ := rs.ByLast("e2")
	if r2.User.ID != "u2" || r2.Rewindable() || !r2.Branchable() || r2.Later != 1 {
		t.Fatalf("第二轮：%+v", r2)
	}
	r3, _ := rs.ByUser("u3")
	if r3.Last != "" || !rs.IsLast(r3) || !r3.Branchable() {
		t.Fatalf("第三轮：%+v", r3)
	}
	if _, ok := rs.ByLast("s3"); ok {
		t.Fatal("灰色提示下面不该有操作排")
	}
	if _, ok := rs.ByUser("n0"); ok {
		t.Fatal("第一条用户消息之前的不属于哪一轮")
	}
}
