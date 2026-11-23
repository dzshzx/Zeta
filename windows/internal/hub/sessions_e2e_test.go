package hub

import (
	"errors"
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
	"github.com/dzshzx/Zeta/windows/internal/settings"
)

// 端到端测试里的几个会话。
const (
	e2eLogin = "aaaaaaaa-0000-4000-8000-000000000001" // 内容里有 NEEDLE，带一张图
	e2eOther = "aaaaaaaa-0000-4000-8000-000000000002"
	e2eGone  = "aaaaaaaa-0000-4000-8000-000000000099" // 本机记着置顶，那边已经没有了
	e2eHeld  = "aaaaaaaa-0000-4000-8000-000000000003" // 终端里的 Claude Code 占着
)

// 侧栏的会话操作对着真的 serve 和核心（经 sshtest）：招呼里的主机名、本机置顶迁到核心、置顶（另一个客户端也看得到）、列表、
// 搜索（边找边推的命中按名字对上）、取图、改名、归档、导出（文件名按这边的时区）、删除（连同正开着、有进程的会话；
// 别处占着的核心不删），改完的结果经 sessions.watch 推回来；别处的 Claude Code 占着时接管。
func TestSessionOperationsThroughCore(t *testing.T) {
	s := newSessionsE2E(t)
	s.pins(t)
	s.search(t)
	s.image(t)
	s.renameAndArchive(t)
	s.export(t)
	s.deletes(t)
	s.handOver(t)
	s.flush(t)
	if len(s.failures) != 0 {
		t.Errorf("没成的：%q", s.failures)
	}
}

// sessionsE2E 是会话操作的端到端测试：一台设备上 -w 项目里有 e2eLogin、e2eOther、e2eHeld 三个会话，本机设置里从前记着
// e2eOther、e2eGone 的置顶；中枢已经订阅那台的列表。
type sessionsE2E struct {
	*e2e
	h        *Hub
	id       string // 设备编号
	dir      string // 会话记录所在的项目目录
	failures []string
	marks    int // settle 写过几份记号
}

func newSessionsE2E(t *testing.T) *sessionsE2E {
	e := newE2E(t, `{"steps":[{"text":"好"}]}`+"\n"+`{"steps":[{"text":"又好"}]}`+"\n")
	s := &sessionsE2E{e2e: e, h: e.hub, id: e.hub.Hosts()[0].ID, dir: filepath.Join(e.server.Home, ".claude/projects/-w")}
	if err := os.MkdirAll(s.dir, 0o755); err != nil {
		t.Fatal(err)
	}
	// 第 0 行第 1 块是一张 PNG（数据是 "png!" 的 base64）
	s.transcript(t, e2eLogin, `{"type":"user","cwd":"/w/proj","sessionId":"`+e2eLogin+`","message":{"role":"user","content":[`+
		`{"type":"text","text":"改一下登录页的 NEEDLE 校验"},{"type":"image","source":{"type":"base64","media_type":"image/png","data":"cG5nIQ=="}}]}}`)
	s.transcript(t, e2eOther, `{"type":"user","cwd":"/w/proj","sessionId":"`+e2eOther+`","message":{"role":"user","content":"别的事"}}`)
	s.transcript(t, e2eHeld, `{"type":"user","cwd":"`+e.server.Home+`","sessionId":"`+e2eHeld+`","message":{"role":"user","content":"终端里开着的"}}`)
	// 从前置顶记在本机设置里：连上时迁到核心，迁完本机不再记
	if err := s.h.settings.SetPins([]string{settings.PinKey(s.id, e2eOther), settings.PinKey(s.id, e2eGone)}); err != nil {
		t.Fatal(err)
	}
	s.h.pins = loadPins(s.h.settings)
	s.h.Start()
	s.until(t, "列出两个会话", func() bool { return s.byID(e2eLogin).ID != "" && s.byID(e2eOther).ID != "" })
	return s
}

// transcript 在项目目录里写一份只有 line 一行的会话记录，返回它的路径。
func (s *sessionsE2E) transcript(t *testing.T, id, line string) string {
	t.Helper()
	path := filepath.Join(s.dir, id+".jsonl")
	if err := os.WriteFile(path, []byte(line+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// byID 是中枢列着的会话 id；没有时是零值。
func (s *sessionsE2E) byID(id string) hostlink.Session { return find(s.h, id) }

// failed 记下没成的原因，测试最后核对。
func (s *sessionsE2E) failed(err error) { s.failures = append(s.failures, err.Error()) }

// pins：招呼里的主机名记下；本机记的置顶迁到核心（那边没有的也从本机删掉）；置顶记在核心里，另一个客户端看得到，它取消了推回这边。
// （核心经 sessions.watch 只推和上次推的不同的：两个客户端在核心的 debounce 之内改同一个，先改的一方会留着自己改的样子，
// 所以两边各改各的会话。）
func (s *sessionsE2E) pins(t *testing.T) {
	h := s.h
	hostname, err := os.Hostname() // 测试的 SSH 服务器在本机上
	if err != nil {
		t.Fatal(err)
	}
	s.until(t, "招呼里的主机名记下", func() bool { return h.Hosts()[0].Hostname == hostname })
	s.until(t, "本机的置顶迁到核心", func() bool { return len(h.settings.Pins()) == 0 && s.byID(e2eOther).Pinned })
	if h.Pinned(hostlink.Session{Backend: s.id, ID: e2eGone}) {
		t.Error("那边没有的还算置顶")
	}
	h.TogglePin(s.byID(e2eLogin), s.failed)
	s.settle(t) // 这边订阅的列表推过迁来的置顶，另一个客户端再改
	peer := s.another(t)
	peer.Start()
	pinnedAt := func(hub *Hub, id string) bool {
		x := find(hub, id)
		return x.ID != "" && hub.Pinned(x)
	}
	s.until(t, "另一个客户端看到置顶", func() bool { return pinnedAt(peer, e2eLogin) && pinnedAt(peer, e2eOther) })
	peer.TogglePin(find(peer, e2eOther), s.failed)
	s.until(t, "另一个客户端取消的推回这边", func() bool { return !pinnedAt(h, e2eOther) && pinnedAt(h, e2eLogin) })
}

// settle 等这边订阅的列表推过一次在此之前的变化：写一份新的会话记录，等它出现在列表里。
func (s *sessionsE2E) settle(t *testing.T) {
	t.Helper()
	s.marks++
	id := "aaaaaaaa-0000-4000-8000-1000000000" + strconv.Itoa(10+s.marks)
	s.transcript(t, id, `{"type":"user","cwd":"/w/proj","sessionId":"`+id+`","message":{"role":"user","content":"记号"}}`)
	s.until(t, "列表推来记号", func() bool { return s.byID(id).ID != "" })
}

// search：命中边找边推来，按这次搜索的名字对上；再搜一次，只算这次的。
func (s *sessionsE2E) search(t *testing.T) {
	h := s.h
	for _, c := range []struct{ query, hit, miss string }{{"needle", e2eLogin, e2eOther}, {"别的事", e2eOther, e2eLogin}} {
		h.SetQuery(c.query)
		s.until(t, "搜完 "+c.query, func() bool { return h.SearchProgress() == SearchDone })
		if !strings.Contains(strings.ToLower(h.Snippet(s.byID(c.hit))), strings.ToLower(c.query)) || h.Snippet(s.byID(c.miss)) != "" {
			t.Fatalf("搜 %s：片段 %q、%q", c.query, h.Snippet(s.byID(c.hit)), h.Snippet(s.byID(c.miss)))
		}
	}
	h.SetQuery("")
}

// image：取图（在后台 goroutine 上调用）。
func (s *sessionsE2E) image(t *testing.T) {
	var image []byte
	var imageErr error
	load := s.h.ImageLoader(s.id)
	done := make(chan struct{})
	go func() { image, imageErr = load(s.byID(e2eLogin).File, "0.1"); close(done) }()
	s.until(t, "取到图", func() bool {
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
}

// renameAndArchive：界面先改，核心改完推来的和它一样（改名换成核心压过空白的）。
func (s *sessionsE2E) renameAndArchive(t *testing.T) {
	s.h.Rename(s.byID(e2eOther), "  新的   名字 ", s.failed)
	s.h.SetArchived([]hostlink.Session{s.byID(e2eOther)}, true, s.failed)
	s.until(t, "改名、归档推回来", func() bool {
		data, _ := os.ReadFile(filepath.Join(s.dir, e2eOther+".jsonl"))
		x := s.byID(e2eOther)
		return strings.Contains(string(data), "新的 名字") && x.Archived && x.Title == "新的 名字"
	})
}

// export：同一台的两个由核心合成一份，文件名的时刻按这边的时区（和主机、测试机的都不一样）。
func (s *sessionsE2E) export(t *testing.T) {
	zone := time.FixedZone("", -(3*60+30)*60)
	before := time.Now().In(zone)
	var exported *Exported
	s.h.Export([]hostlink.Session{s.byID(e2eLogin), s.byID(e2eOther)}, before, func(x Exported) { exported = &x }, s.failed)
	s.until(t, "导出", func() bool { return exported != nil || len(s.failures) > 0 })
	after := time.Now().In(zone)
	if exported == nil || !strings.Contains(exported.Content, "NEEDLE") || !strings.Contains(exported.Content, "\n---\n\n") {
		t.Fatalf("导出 %+v，没成的 %q", exported, s.failures)
	}
	names := []string{sessionproto.ExportFileName("2个对话", before), sessionproto.ExportFileName("2个对话", after)}
	if !slices.Contains(names, exported.Name) {
		t.Errorf("文件名 %q，要 %q", exported.Name, names)
	}
}

// deletes：删掉的记录没了、列表跟着去掉；正开着、有进程的核心先结束进程再删记录，聊天窗格关掉它；
// 别处的 Claude Code 占着的核心不删，答正在进行，列表里放回去。
func (s *sessionsE2E) deletes(t *testing.T) {
	h := s.h
	loginFile := s.byID(e2eLogin).File
	h.Delete([]hostlink.Session{s.byID(e2eLogin)}, s.failed)
	s.until(t, "删掉", func() bool {
		_, err := os.Stat(loginFile)
		return os.IsNotExist(err) && s.byID(e2eLogin).ID == ""
	})

	h.NewChat()
	s.until(t, "打开新对话", func() bool { c := h.Chat(); return c != nil && !c.Opening })
	s.send(t, "你好")
	s.until(t, "答完", func() bool { return s.finished(chat.OutcomeDone) && s.state.Process && s.state.Session != "" })
	live := s.state.Session
	pid := hostedPID(t, s.server.Home)
	// 假 Claude 不写会话记录：照真的样子补一份
	s.transcript(t, live, `{"type":"user","cwd":"`+s.server.Home+`","sessionId":"`+live+`","message":{"role":"user","content":"你好"}}`)
	s.until(t, "列表里有它", func() bool { return s.byID(live).ID != "" })
	h.Delete([]hostlink.Session{s.byID(live)}, s.failed)
	s.until(t, "进程结束、记录删掉、聊天窗格关掉", func() bool {
		files, _ := filepath.Glob(filepath.Join(s.server.Home, ".claude/projects/*", live+".jsonl"))
		return len(files) == 0 && !running(pid) && s.byID(live).ID == "" && h.Chat() == nil
	})
}

// handOver：别处的 Claude Code 占着时核心不删；打开时 blocked，核心查到它闲着推来（conv.state 的 occupant），接管后它退出、
// 核心解开 blocked（卡片收起）。
func (s *sessionsE2E) handOver(t *testing.T) {
	h := s.h
	outside, outsideDone := terminalClaude(t, s.server.Home, e2eHeld)
	s.until(t, "列表里它被别处占着", func() bool { return s.byID(e2eHeld).External })
	var refused error
	h.Delete([]hostlink.Session{s.byID(e2eHeld)}, func(err error) { refused = err })
	s.until(t, "核心不删、放回列表", func() bool { return refused != nil && s.byID(e2eHeld).ID != "" })
	if !errors.Is(refused, ErrActive) {
		t.Errorf("删别处占着的：%v", refused)
	}

	h.OpenSession(s.byID(e2eHeld))
	s.until(t, "打开占着的会话", func() bool { c := h.Chat(); return c != nil && !c.Opening && s.state.Blocked })
	s.until(t, "核心推来它闲着", func() bool {
		o := s.state.Occupant
		return o != nil && status(o) == sessionproto.StatusIdle && o.HandsOver
	})
	h.HandOver()
	s.until(t, "接管后解开 blocked", func() bool { return !s.state.Blocked && s.state.Occupant == nil })
	select {
	case <-outsideDone:
	case <-time.After(30 * time.Second):
		t.Fatalf("终端里的进程 %d 没退出", outside)
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
	if err != nil || !running(pid) {
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

// running 看进程 pid 还在不在（发信号 0）。
func running(pid int) bool {
	p, err := os.FindProcess(pid)
	return err == nil && p.Signal(syscall.Signal(0)) == nil
}
