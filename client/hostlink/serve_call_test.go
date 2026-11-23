package hostlink

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
)

// fakeServe 是假的 serve：打招呼，good 答 ok 带 result，bad 答出错，stale 答出错带 code，nameless 答出错不带原因，hang 不答直接退出（连接断了）。
const fakeServe = `echo '{"type":"zeta_hello","protocol":1,"build":7,"version":"0.7.2","hostname":"box"}'
while IFS= read -r l; do
  id=$(printf '%s' "$l" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  case "$l" in
    *'"method":"good"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true,\"result\":{\"echo\":$(printf '%s' "$l" | sed -n 's/.*"n":\([0-9]*\).*/\1/p')}}" ;;
    *'"method":"empty"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true}" ;;
    *'"method":"bad"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"error\":\"不认识的方法\"}" ;;
    *'"method":"stale"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"error\":\"会话记录变了\",\"code\":\"history_changed\"}" ;;
    *'"method":"nameless"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":false,\"code\":\"busy\"}" ;;
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
		// 包了一层也认得
		if code, ok := ReplyCode(fmt.Errorf("包着：%w", err)); !ok || code != want[1] {
			t.Errorf("%s 的 ReplyCode：%q %v", method, code, ok)
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

// 每次连上都把招呼里的主机名交给 Hello，在报连上之前。
func TestServeHelloGivesTheHostname(t *testing.T) {
	b := newBox(t)
	hellos := make(chan string, 1)
	var greeted atomic.Bool
	s := StartServe(ServeOptions{
		Open:    func() (*sshlink.Process, error) { return b.shell.StartPlain(fakeServe) },
		Hello:   func(h Hello) { greeted.Store(true); hellos <- h.Hostname },
		Message: func(string, []byte) {},
		Problem: func(why string) {
			if why == "" && !greeted.Load() {
				t.Error("报连上时还没交出招呼")
			}
		},
	})
	defer s.Close()
	if got := <-hellos; got != "box" {
		t.Errorf("主机名 %q", got)
	}
}

// 不是 serve 答的出错（连不上、超时）没有 code，ok 为假。
func TestReplyCodeOfOtherErrors(t *testing.T) {
	for _, err := range []error{nil, errors.New("连不上"), context.DeadlineExceeded} {
		if code, ok := ReplyCode(err); ok || code != "" {
			t.Errorf("%v：%q %v", err, code, ok)
		}
	}
}

// 给了 Device 时每次连上先发 core.client 报设备名，排在别的请求前面；应答 ok 时不报问题。
func TestServeReportsTheDeviceFirst(t *testing.T) {
	b := newBox(t)
	// first 是连上后收到的第一行；whoami 把它原样答回
	const fake = `echo '{"type":"zeta_hello","protocol":1,"build":7,"version":"0.7.2","hostname":"box"}'
first=
while IFS= read -r l; do
  id=$(printf '%s' "$l" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
  [ -z "$first" ] && first=$l
  case "$l" in
    *'"method":"whoami"'*) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true,\"result\":$first}" ;;
    *) echo "{\"type\":\"zeta_reply\",\"id\":$id,\"ok\":true}" ;;
  esac
done`
	ev := newEvents()
	s := StartServe(ServeOptions{
		Open:          func() (*sshlink.Process, error) { return b.shell.StartPlain(fake) },
		Subscriptions: []string{"sessions.watch"},
		Device:        "DESKTOP-7",
		Message:       func(string, []byte) {},
		Problem:       ev.problem,
	})
	defer s.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	got, err := s.Call(ctx, "whoami", nil)
	if err != nil || !strings.Contains(string(got), `"method":"core.client"`) || !strings.Contains(string(got), `"device":"DESKTOP-7"`) {
		t.Fatalf("第一行：%s, %v", got, err)
	}
	ev.mu.Lock()
	defer ev.mu.Unlock()
	for _, p := range ev.problems {
		if p != "" {
			t.Errorf("报了问题：%q", p)
		}
	}
}
