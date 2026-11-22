package hostlink

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// fakeServe 是假的 serve：打招呼，good 答 ok 带 result，stream 先推一行带这个请求 id 的 match 再答，bad 答出错，stale 答出错带 code，nameless 答出错不带原因，hang 不答直接退出（连接断了）。
const fakeServe = `echo '{"type":"zeta_hello","protocol":1}'
while IFS= read -r l; do
  id=$(printf '%s' "$l" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  case "$l" in
    *'"method":"good"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true,\"result\":{\"echo\":$(printf '%s' "$l" | sed -n 's/.*"n":\([0-9]*\).*/\1/p')}}" ;;
    *'"method":"empty"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true}" ;;
    *'"method":"bad"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"error\":\"不认识的方法\"}" ;;
    *'"method":"stale"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"error\":\"会话记录变了\",\"code\":\"history_changed\"}" ;;
    *'"method":"nameless"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"code\":\"busy\"}" ;;
    *'"method":"stream"'*) echo "{\"type\":\"match\",\"search\":$id}"; echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true}" ;;
    *'"method":"hang"'*) exit 3 ;;
  esac
done`

func TestServeCallMatchesReplies(t *testing.T) {
	b := newBox(t)
	ev := newEvents()
	s := StartServe(ServeOptions{
		Open:    func() (*sshlink.Process, error) { return b.shell.StartPlain(fakeServe) },
		Message: func(string, []byte) {},
		Problem: ev.problem,
		Backoff: func(int) time.Duration { return 10 * time.Millisecond },
	})
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	// 刚起就发：等连上再发。
	got, err := s.Call(ctx, "good", map[string]any{"n": 7})
	if err != nil || string(got) != `{"echo":7}` {
		t.Fatalf("good: %s, %v", got, err)
	}
	if got, err := s.Call(ctx, "empty", nil); err != nil || got != nil {
		t.Fatalf("empty: %s, %v", got, err)
	}
	if _, err := s.Call(ctx, "bad", nil); err == nil || err.Error() != "不认识的方法" {
		t.Fatalf("bad: %v", err)
	}
	if _, err := s.Call(ctx, "hang", nil); !errors.Is(err, errDropped) {
		t.Fatalf("hang: %v", err)
	}
	// 断了以后重连上，照样能发。
	if got, err := s.Call(ctx, "good", map[string]any{"n": 8}); err != nil || string(got) != `{"echo":8}` {
		t.Fatalf("重连后 good: %s, %v", got, err)
	}
	if len(ev.problems) != 0 && strings.Contains(strings.Join(ev.problems, ""), "拒绝") {
		t.Errorf("Call 的出错应答不该报成连接的问题：%q", ev.problems)
	}

	_ = s.Close()
	if _, err := s.Call(ctx, "good", nil); err == nil || !strings.Contains(err.Error(), "已关闭") {
		t.Fatalf("Close 后：%v", err)
	}
}

// 出错的应答带 code 时，Call 的错误能取到它（ReplyError），原因照旧是 error 字段，没有时是默认的一句。
func TestServeCallKeepsTheReplyCode(t *testing.T) {
	b := newBox(t)
	s := StartServe(ServeOptions{
		Open:    func() (*sshlink.Process, error) { return b.shell.StartPlain(fakeServe) },
		Message: func(string, []byte) {},
		Problem: func(string) {},
	})
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for method, want := range map[string][2]string{
		"stale":    {"会话记录变了", "history_changed"},
		"nameless": {"zeta-helper serve 拒绝了请求", "busy"},
		"bad":      {"不认识的方法", ""},
	} {
		_, err := s.Call(ctx, method, nil)
		var replyErr *ReplyError
		if !errors.As(err, &replyErr) || err.Error() != want[0] || replyErr.Code() != want[1] {
			t.Errorf("%s：%v（%T），要 %q、code %q", method, err, err, want[0], want[1])
		}
	}
}

func TestServeCallGivesUpWhenItNeverConnected(t *testing.T) {
	ev := newEvents()
	s := StartServe(ServeOptions{
		Open:    func() (*sshlink.Process, error) { return nil, errors.New("连不上「测试机」：拒绝连接") },
		Message: func(string, []byte) {},
		Problem: ev.problem,
	})
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if _, err := s.Call(ctx, "good", nil); err == nil || err.Error() != "连不上「测试机」：拒绝连接" {
		t.Fatalf("%v", err)
	}
}

// CallTagged 在写出请求之前交出 id：应答之前推来的、用 id 指回请求的那行对得上。
func TestServeCallTaggedGivesTheIDBeforeSending(t *testing.T) {
	b := newBox(t)
	pushed := make(chan string, 1)
	s := StartServe(ServeOptions{
		Open: func() (*sshlink.Process, error) { return b.shell.StartPlain(fakeServe) },
		Message: func(kind string, line []byte) {
			if kind == "match" {
				pushed <- string(line)
			}
		},
		Problem: func(string) {},
	})
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var tagged int64
	if _, err := s.CallTagged(ctx, "stream", nil, func(id int64) { tagged = id }); err != nil {
		t.Fatal(err)
	}
	if got := <-pushed; tagged == 0 || got != `{"type":"match","search":`+strconv.FormatInt(tagged, 10)+`}` {
		t.Errorf("id %d，推来 %s", tagged, got)
	}
}
