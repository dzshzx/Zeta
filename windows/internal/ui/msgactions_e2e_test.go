package ui

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// 整条链路的编辑重发：窗口经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，核心起按剧本回话的
// 假 Claude（tools/fake-claude）。发一条、答完以后右键编辑、改了再发：核心请假 Claude 剪掉原来那一轮（rewind_conversation），
// 再发改过的，列表里换成新的一轮。
func TestEditResendThroughCore(t *testing.T) {
	for _, tool := range []string{"bash", "jq"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Fatalf("假 Claude 要 %s：%v", tool, err)
		}
	}
	helper, err := host.LoadProgram(hostlink.ZetaHelper)
	if err != nil {
		t.Fatal(err)
	}
	key := sshtest.NewKey(t)
	server := sshtest.Start(t, t.TempDir(), key.PublicKey())
	fake, err := os.ReadFile("../../../tools/fake-claude/fake-claude.sh")
	if err != nil {
		t.Fatal(err)
	}
	bin := filepath.Join(server.Home, ".local/bin")
	if err := os.MkdirAll(bin, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bin, "claude"), fake, 0o755); err != nil {
		t.Fatal(err)
	}
	scenario := `{"steps":[{"text":"第一版回答"}]}
{"steps":[{"text":"改过以后的回答"}]}
`
	if err := os.WriteFile(filepath.Join(bin, "scenario.jsonl"), []byte(scenario), 0o644); err != nil {
		t.Fatal(err)
	}
	x := sshlink.Host{ID: "r-e2e", Target: "zeta@127.0.0.1:" + strconv.Itoa(server.Port), Home: server.Home,
		DefaultCwd: server.Home, Via: sshlink.ViaDirect, HostKey: sshlink.EncodeHostKey(server.HostKey.PublicKey())}
	s, err := settings.Load(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetHosts([]sshlink.Host{x}); err != nil {
		t.Fatal(err)
	}
	devices := noSSHConfig{&host.Devices{Links: sshlink.NewLinks(sshlink.KeyAuth{Signer: key}, time.Minute), Helper: helper}}
	posts := make(chan func(), 1000)
	run := func(f func()) { go f() }
	post := func(f func()) { posts <- f }
	h := hub.New(s, devices, run, post)
	app := New(h, run, post)
	f := &fixture{hub: h, app: app, tt: ui.NewTester(app.View, 1280, 860)}
	t.Cleanup(func() { stopCore(server.Home, helper) })
	until := func(what string, done func() bool) {
		t.Helper()
		deadline := time.After(60 * time.Second)
		for {
			f.tt.Frame()
			if done() {
				return
			}
			select {
			case fn := <-posts:
				fn()
			case <-time.After(20 * time.Millisecond):
			case <-deadline:
				t.Fatalf("60 秒内没等到%s；界面上：%q", what, f.tt.Texts())
			}
		}
	}
	texts := func() string {
		var out []string
		for _, it := range app.pane.list.Items() {
			if it.Kind == items.User || it.Kind == items.Answer {
				out = append(out, string(it.Kind)+":"+it.Text)
			}
		}
		return strings.Join(out, " | ")
	}

	h.NewChat()
	until("打开新对话", func() bool { return app.pane != nil && app.pane.src.Conv != nil && app.pane.connected })
	f.click(t, "消息")
	f.tt.Type("第一个问题")
	f.tt.Key(0, ui.KeyEnter)
	until("答完第一轮", func() bool {
		return texts() == "user:第一个问题 | answer:第一版回答" && app.pane.state.CanRewind
	})

	// 侧栏和标题上也有这句话：点聊天里的气泡（最靠右的那处）
	var bubble ui.Rect
	for _, a := range f.tt.AccessAll() {
		if a.Name == "第一个问题" && a.Bounds.X > bubble.X {
			bubble = a.Bounds
		}
	}
	f.tt.RightClickAt(bubble.X+bubble.W/2, bubble.Y+bubble.H/2)
	if err := f.tt.ChooseMenuItem("编辑"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	if app.pane.draft != "第一个问题" || !f.tt.HasText("编辑消息") {
		t.Fatalf("开始编辑：输入栏 %q", app.pane.draft)
	}
	app.pane.draft = "改过的问题"
	f.tt.Frame()
	f.click(t, "发送")
	until("改写以后答完", func() bool {
		return texts() == "user:改过的问题 | answer:改过以后的回答" && !app.pane.state.Busy && !app.pane.state.Rewinding
	})
	if app.pane.failed != "" {
		t.Fatalf("出错：%s", app.pane.failed)
	}
}
