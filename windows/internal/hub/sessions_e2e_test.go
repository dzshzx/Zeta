package hub

import (
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/host"
)

// 侧栏的会话操作对着真的 serve 和核心（经 sshtest）：列表、搜索（边找边推的命中）、取图、改名、归档、导出、取回不在列表里的置顶、
// 删除（连同正开着、有进程的会话），改完的结果经 sessions.watch 推回来；别处的 Claude Code 占着时接管。
func TestSessionOperationsThroughCore(t *testing.T) {
	e := newE2E(t, `{"steps":[{"text":"好"}]}`+"\n"+`{"steps":[{"text":"又好"}]}`+"\n")
	h := e.hub
	const (
		login = "aaaaaaaa-0000-4000-8000-000000000001"
		other = "aaaaaaaa-0000-4000-8000-000000000002"
		gone  = "aaaaaaaa-0000-4000-8000-000000000099" // 置顶着，那边已经没有了
		held  = "aaaaaaaa-0000-4000-8000-000000000003" // 终端里的 Claude Code 占着
	)
	dir := filepath.Join(e.server.Home, ".claude/projects/-w")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	transcript := func(id, line string) string {
		path := filepath.Join(dir, id+".jsonl")
		if err := os.WriteFile(path, []byte(line+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
		return path
	}
	// 第 0 行第 1 块是一张 PNG（数据是 "png!" 的 base64）
	loginFile := transcript(login, `{"type":"user","cwd":"/w/proj","sessionId":"`+login+`","message":{"role":"user","content":[`+
		`{"type":"text","text":"改一下登录页的 NEEDLE 校验"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"cG5nIQ=="}}]}}`)
	transcript(other, `{"type":"user","cwd":"/w/proj","sessionId":"`+other+`","message":{"role":"user","content":"别的事"}}`)
	transcript(held, `{"type":"user","cwd":"`+e.server.Home+`","sessionId":"`+held+`","message":{"role":"user","content":"终端里开着的"}}`)
	x := h.Hosts()[0]
	h.TogglePin(hostlink.Session{Backend: x.ID, ID: gone})

	h.Start()
	byID := func(id string) hostlink.Session {
		i := slices.IndexFunc(h.Sessions(), func(s hostlink.Session) bool { return s.ID == id })
		if i < 0 {
			return hostlink.Session{}
		}
		return h.Sessions()[i]
	}
	e.until(t, "列出两个会话", func() bool { return byID(login).ID != "" && byID(other).ID != "" })
	e.until(t, "那边没有的置顶去掉", func() bool { return !h.Pinned(hostlink.Session{Backend: x.ID, ID: gone}) })

	// 搜索：命中边找边推来，按请求 id 对上
	h.SetQuery("needle")
	e.until(t, "搜完", func() bool { return h.SearchProgress() == SearchDone })
	if r := h.Results(hostlink.Session{}, time.Now()); len(r) != 1 || r[0].ID != login || !strings.Contains(h.Snippet(r[0]), "NEEDLE") {
		t.Fatalf("搜到 %+v，片段 %q", r, h.Snippet(byID(login)))
	}
	h.SetQuery("")

	// 取图（在后台 goroutine 上调用）
	var image []byte
	var imageErr error
	load := h.ImageLoader(x.ID)
	done := make(chan struct{})
	go func() { image, imageErr = load(loginFile, "0.1"); close(done) }()
	e.until(t, "取到图", func() bool {
		select {
		case <-done:
			return true
		default:
			return false
		}
	})
	if imageErr != nil || string(image) != "png!" {
		t.Fatalf("图 %q、%v", image, imageErr)
	}

	var failures []string
	failed := func(err error) { failures = append(failures, err.Error()) }

	// 改名、归档：界面先改，核心改完推来的和它一样
	h.Rename(byID(other), "  新的   名字 ", failed)
	h.SetArchived([]hostlink.Session{byID(other)}, true, failed)
	e.until(t, "改名、归档推回来", func() bool {
		data, _ := os.ReadFile(filepath.Join(dir, other+".jsonl"))
		return strings.Contains(string(data), "新的 名字") && byID(other).Archived && byID(other).Title == "新的 名字"
	})

	// 导出
	var exported *Exported
	h.Export([]hostlink.Session{byID(login)}, time.Date(2026, 11, 22, 9, 5, 0, 0, time.Local), func(x Exported) { exported = &x }, failed)
	e.until(t, "导出", func() bool { return exported != nil || len(failures) > 0 })
	if exported == nil || !strings.Contains(exported.Content, "NEEDLE") || !strings.HasSuffix(exported.Name, "-20261122-0905.md") {
		t.Fatalf("导出 %+v，没成的 %q", exported, failures)
	}

	// 删除：记录没了，列表跟着去掉
	h.Delete([]hostlink.Session{byID(login)}, failed)
	e.until(t, "删掉", func() bool {
		_, err := os.Stat(loginFile)
		return os.IsNotExist(err) && byID(login).ID == ""
	})

	// 删除正开着、有进程的会话：核心先结束进程再删记录，聊天窗格关掉它
	h.NewChat()
	e.until(t, "打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening })
	e.send(t, "你好")
	e.until(t, "答完", func() bool { return e.finished(chat.OutcomeDone) && e.state.Process && e.state.Session != "" })
	live := e.state.Session
	pid := hostedPID(t, e.server.Home)
	// 假 Claude 不写会话记录：照真的样子补一份
	transcript(live, `{"type":"user","cwd":"`+e.server.Home+`","sessionId":"`+live+`","message":{"role":"user","content":"你好"}}`)
	e.until(t, "列表里有它", func() bool { return byID(live).ID != "" })
	h.Delete([]hostlink.Session{byID(live)}, failed)
	if h.Chat() != nil {
		t.Fatal("删了还开着")
	}
	e.until(t, "进程结束、记录删掉", func() bool {
		files, _ := filepath.Glob(filepath.Join(e.server.Home, ".claude/projects/*", live+".jsonl"))
		return len(files) == 0 && syscall.Kill(pid, 0) != nil && byID(live).ID == ""
	})

	// 别处的 Claude Code 占着：打开时 blocked，核心查到它闲着推来，接管后它退出、核心解开 blocked（卡片收起）
	outside, outsideDone := terminalClaude(t, e.server.Home, held)
	h.OpenSession(byID(held))
	e.until(t, "打开占着的会话", func() bool { c := h.Chat(); return c != nil && !c.Opening && e.state.Blocked })
	e.until(t, "核心推来它闲着", func() bool {
		o := e.state.Occupant
		return o != nil && o.Status == sessionproto.StatusIdle && o.HandsOver
	})
	h.HandOver()
	e.until(t, "接管后解开 blocked", func() bool { return !e.state.Blocked && e.state.Occupant == nil })
	select {
	case <-outsideDone:
	case <-time.After(30 * time.Second):
		t.Fatalf("终端里的进程 %d 没退出", outside)
	}
	e.flush(t)
	if len(failures) != 0 {
		t.Errorf("没成的：%q", failures)
	}
}

// hostedPID 是唯一一个托管目录里记的进程号（zeta_host.sh 写的 pid 文件）。
func hostedPID(t *testing.T, home string) int {
	t.Helper()
	files, _ := filepath.Glob(filepath.Join(home, ".cache", host.LinuxName, "hosts", "*", "pid"))
	if len(files) != 1 {
		t.Fatalf("托管目录的 pid 文件：%v", files)
	}
	data, err := os.ReadFile(files[0])
	if err != nil {
		t.Fatal(err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || syscall.Kill(pid, 0) != nil {
		t.Fatalf("托管进程 %q 不在：%v", data, err)
	}
	return pid
}

// terminalClaude 起一个像终端里的 Claude Code 的进程（命令行里有 claude，收到 SIGINT 照 /exit 退出），
// 像 Claude Code 那样在 home 的 ~/.claude/sessions 里登记它在用会话 session、闲着。返回进程号和它退出时关上的通道。
func terminalClaude(t *testing.T, home, session string) (int, <-chan struct{}) {
	t.Helper()
	dir := t.TempDir()
	script := filepath.Join(dir, "claude")
	ready := filepath.Join(dir, "ready")
	if err := os.WriteFile(script, []byte("#!/bin/bash\ntrap 'exit 0' INT\n: > \"$1\"\nwhile :; do sleep 0.05; done\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(script, ready)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	go func() { _ = cmd.Wait(); close(done) }()
	t.Cleanup(func() { _ = cmd.Process.Kill(); <-done })
	for deadline := time.Now().Add(10 * time.Second); ; time.Sleep(10 * time.Millisecond) {
		if _, err := os.Stat(ready); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("终端里的进程没起来")
		}
	}
	pid := cmd.Process.Pid
	stat, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		t.Fatal(err)
	}
	fields := strings.Fields(string(stat[strings.LastIndexByte(string(stat), ')')+1:]))
	sessions := filepath.Join(home, ".claude", "sessions")
	if err := os.MkdirAll(sessions, 0o755); err != nil {
		t.Fatal(err)
	}
	entry := `{"pid":` + strconv.Itoa(pid) + `,"sessionId":"` + session + `","procStart":"` + fields[19] + `","kind":"interactive","status":"idle"}`
	if err := os.WriteFile(filepath.Join(sessions, strconv.Itoa(pid)+".json"), []byte(entry), 0o644); err != nil {
		t.Fatal(err)
	}
	return pid, done
}
