package ui

import (
	"context"
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
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/hub"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// bgScenario 是假 Claude 的剧本：一轮里用 Bash 起一个后台命令（同真 Claude Code 的 background_tasks_changed、task_started），
// 读它的输出得到 output，stop_task 停它时照真的那样报 killed、stopped。
const bgScenario = `{"steps":[{"tool":"Bash","input":{"command":"while :; do echo tick; sleep 1; done","run_in_background":true,"description":"每秒报时"},"result":"Command running in background with ID: bfake01"},` +
	`{"emit":{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bfake01","task_type":"local_bash","description":"每秒报时"}]}},` +
	`{"emit":{"type":"system","subtype":"task_started","task_id":"bfake01","tool_use_id":"@TOOL@","description":"每秒报时","task_type":"local_bash"},"output":"tick\ntick\n"},` +
	`{"text":"放到后台了"}]}
`

// 整条链路：经 SSH（sshtest，命令在临时家目录里真跑 bash）起 zeta-helper serve 和核心，核心起假 Claude（tools/fake-claude）。
// 发一条消息让它起一个后台命令：状态行出现「后台任务 1 个」，点进去看到它和它的输出，停止以后变成已停止。
func TestBackgroundTaskThroughServe(t *testing.T) {
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
	if err := os.WriteFile(filepath.Join(bin, "scenario.jsonl"), []byte(bgScenario), 0o644); err != nil {
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
	f := &fixture{hub: h, app: app, tt: ui.NewTester(app.View, 1280, 900)}
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
	h.Start()

	f.click(t, "新对话")
	until("打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening && c.Conv != nil })
	f.click(t, "消息")
	f.tt.Type("在后台报时")
	f.click(t, "发送")
	until("后台任务出现", func() bool { return f.shows("后台任务 1 个") && f.shows("放到后台了") })

	f.click(t, "后台任务 1 个")
	f.see(t, "每秒报时")
	if !strings.Contains(strings.Join(f.tt.Texts(), "\n"), "后台命令 · 已运行") {
		t.Fatalf("列表：%q", f.tt.Texts())
	}
	f.click(t, "每秒报时")
	until("读到输出", func() bool { return f.shows("tick\ntick") })
	f.see(t, "运行中", "while :; do echo tick; sleep 1; done", "输出 · 10 B")

	f.click(t, "停止这个任务")
	f.clickButton(t, "停止", 0)
	until("停下", func() bool { return f.shows("已停止") })
	f.notSee(t, "停止这个任务")
	f.click(t, "返回")
	if !strings.Contains(strings.Join(f.tt.Texts(), "\n"), "后台命令 · 已停止 · 用时") {
		t.Fatalf("停下以后的列表：%q", f.tt.Texts())
	}

	// 结束假 Claude，不留进程
	done := make(chan error, 1)
	h.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		done <- conv.End(ctx)
		return nil
	}, nil)
	until("结束进程", func() bool {
		select {
		case err := <-done:
			if err != nil {
				t.Fatal(err)
			}
			done = nil
		default:
		}
		return done == nil && !app.pane.state.Process
	})
}
