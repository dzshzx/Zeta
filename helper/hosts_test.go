package main

import (
	"errors"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// 等着回应的请求：主对话和后台子代理的确认可以同时挂着，按到达顺序全列出来；有了结果、被撤回或这一轮结束（子代理的除外）就去掉。
func TestScanHostListsEveryUnansweredPrompt(t *testing.T) {
	cases := []struct {
		name  string
		lines []string
		want  []string // 留下的 request_id
	}{
		{"两个同时等", []string{
			`{"type":"system","subtype":"init","session_id":"s"}`,
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"s1","agent_id":"a1"}}`,
		}, []string{"main", "sub"}},
		{"答了一个", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_use_id":"s1","agent_id":"a1"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"m1","content":"ok"}]}}`,
		}, []string{"sub"}},
		{"撤回", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_cancel_request","request_id":"main"}`,
		}, nil},
		{"一轮结束时子代理的还在等", []string{
			`{"type":"control_request","request_id":"main","request":{"subtype":"can_use_tool","tool_use_id":"m1"}}`,
			`{"type":"control_request","request_id":"sub","request":{"subtype":"can_use_tool","tool_use_id":"s1","agent_id":"a1"}}`,
			`{"type":"result","subtype":"success"}`,
		}, []string{"sub"}},
		{"elicitation 在那台服务器的调用出结果后去掉", []string{
			`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"mcp__my_srv__ask"},{"type":"tool_use","id":"t2","name":"mcp__other__x"}]}}`,
			`{"type":"control_request","request_id":"el","request":{"subtype":"elicitation","mcp_server_name":"my.srv","mode":"form"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"ok"}]}}`,
		}, []string{"el"}},
		{"elicitation 答过", []string{
			`{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"mcp__my_srv__ask"}]}}`,
			`{"type":"control_request","request_id":"el","request":{"subtype":"elicitation","mcp_server_name":"my.srv","mode":"form"}}`,
			`{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}}`,
		}, nil},
		{"手机工具的 MCP 消息不算", []string{
			`{"type":"control_request","request_id":"mcp","request":{"subtype":"mcp_message","server_name":"phone"}}`,
		}, nil},
	}
	for _, c := range cases {
		dir := t.TempDir()
		writeFile(t, dir+"/out.jsonl", strings.Join(c.lines, "\n")+"\n")
		scan, err := scanHost(dir)
		if err != nil {
			t.Fatal(err)
		}
		var got []string
		for _, p := range scan.pending {
			got = append(got, asString(asObj(p)["request_id"]))
		}
		if strings.Join(got, ",") != strings.Join(c.want, ",") {
			t.Errorf("%s: pending = %v, want %v", c.name, got, c.want)
		}
	}
}

// endedHost 摆一个 Claude Code 已经退出（有 exit，pid 文件记的进程不在了）的托管目录，返回它的路径。
func endedHost(t *testing.T, e *env, key string) string {
	t.Helper()
	dir := filepath.Join(e.hosts, key)
	writeLines(t, filepath.Join(dir, "out.jsonl"), `{"type":"system","subtype":"init","session_id":"s"}`)
	writeFile(t, filepath.Join(dir, "pid"), "0")
	writeFile(t, filepath.Join(dir, "exit"), "0")
	return dir
}

// attachRecord 照 zeta_host.sh 记下接着的一方 pid：attach.d/<pid>，内容是启动时刻 start。
func attachRecord(t *testing.T, dir string, pid int, start string) {
	t.Helper()
	writeFile(t, filepath.Join(dir, "attach.d", strconv.Itoa(pid)), start+"\n")
}

// 进程已经退出的托管目录：还有接着的一方时 hosts 不删（它还要读 exit 报 zeta_exit），也不列出来；都走了才删。
// 接着的一方的记录要进程还在、启动时刻对得上才算，进程号被复用了的（启动时刻不对）不算。
func TestHostsDoesNotRemoveAnEndedHostSomeoneIsStillAttachedTo(t *testing.T) {
	e := testEnv(t)
	me := os.Getpid()
	start, ok := procStart(me)
	if !ok {
		t.Skip("要 /proc")
	}
	attached := endedHost(t, e, "k-attached")
	attachRecord(t, attached, me, start)
	reused := endedHost(t, e, "k-reused")
	attachRecord(t, reused, me, start+"0")
	if out := mustCall(t, e, "hosts"); len(out) != 0 {
		t.Fatalf("listed an ended host: %v", out)
	}
	if !lexists(attached) {
		t.Fatal("removed a host dir someone is still attached to")
	}
	if lexists(reused) {
		t.Fatal("a record whose pid was reused kept the dir")
	}
	if err := os.Remove(filepath.Join(attached, "attach.d", strconv.Itoa(me))); err != nil {
		t.Fatal(err)
	}
	mustCall(t, e, "hosts")
	if lexists(attached) {
		t.Fatal("the last one left but the dir stayed")
	}
}

// 旧版脚本只写 attach：它记的进程命令行里有 zeta-host 才算还接着（不是 zeta-host 的进程号是被复用了）。
func TestLegacyAttachFileCountsOnlyAZetaHostProcess(t *testing.T) {
	e := testEnv(t)
	cmd := exec.Command("bash", "-c", "while :; do sleep 0.05; done", "zeta-host", "attach")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = cmd.Process.Kill(); _ = cmd.Wait() })
	// Start 返回时子进程可能还在 exec 当中，命令行读出来是空的：等它换成 bash 的再往下（线上的 attach 进程早就跑起来了）
	waitFor(t, func() bool {
		argv, _ := procArgv(cmd.Process.Pid)
		return slices.ContainsFunc(argv, func(a []byte) bool { return string(a) == "zeta-host" })
	})
	legacy := endedHost(t, e, "k-legacy")
	writeFile(t, filepath.Join(legacy, "attach"), strconv.Itoa(cmd.Process.Pid))
	other := endedHost(t, e, "k-other")
	writeFile(t, filepath.Join(other, "attach"), strconv.Itoa(os.Getpid()))
	if got := liveAttachers(legacy); len(got) != 1 || got[0] != cmd.Process.Pid {
		t.Fatalf("legacy attachers %v", got)
	}
	if !hostAlive(legacy) {
		t.Fatal("storage would clean a host the old script is attached to")
	}
	mustCall(t, e, "hosts")
	if !lexists(legacy) || lexists(other) {
		t.Fatalf("legacy kept %v, other kept %v", lexists(legacy), lexists(other))
	}
}

// 存储页只清托管目录，不清 start 用的锁 hosts/.lock。
func TestStorageLeavesTheStartLockAlone(t *testing.T) {
	e := testEnv(t)
	lock := filepath.Join(e.hosts, ".lock")
	writeFile(t, lock, "")
	age(t, lock, 48*time.Hour)
	ended := endedHost(t, e, "k-old")
	for _, p := range []string{ended, filepath.Join(ended, "out.jsonl"), filepath.Join(ended, "pid"), filepath.Join(ended, "exit")} {
		age(t, p, 48*time.Hour)
	}
	p := planZetaHosts(newStorageCtx(e, nil))
	var names []string
	for _, item := range p.items {
		names = append(names, item.name)
	}
	if strings.Join(names, ",") != "k-old" {
		t.Fatalf("planned %v", names)
	}
}

// 核心把用户的权限模式记进托管目录的 user_mode，hosts 列出来（接回时恢复）；目录没了不另建，标识不对的不写。
func TestKeepHostModeIsListedByHosts(t *testing.T) {
	e := testEnv(t)
	dir := filepath.Join(e.hosts, "k1")
	writeLines(t, filepath.Join(dir, "out.jsonl"), `{"type":"system","subtype":"init","session_id":"s","permissionMode":"acceptEdits"}`)
	if err := e.keepHostMode("k1", "plan"); err != nil {
		t.Fatal(err)
	}
	scan, err := scanHost(dir)
	if err != nil {
		t.Fatal(err)
	}
	item := e.hostItem(dir, 0, scan)
	if item["user_mode"] != "plan" || item["mode"] != "acceptEdits" {
		t.Fatalf("user_mode %v，mode %v", item["user_mode"], item["mode"])
	}
	if err := e.keepHostMode("gone", "plan"); !errors.Is(err, fs.ErrNotExist) || lexists(filepath.Join(e.hosts, "gone")) {
		t.Fatalf("目录没了时：%v", err)
	}
	for _, key := range []string{"", ".lock", "../x"} {
		if err := e.keepHostMode(key, "plan"); err == nil {
			t.Fatalf("标识 %q 也写了", key)
		}
	}
}

// settings.json 的默认权限模式：没变时用记着的，改了（大小或修改时刻变了）重读，删了为空。
func TestDefaultModeCacheRereadsWhenSettingsChange(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	c := &defaultModeCache{path: path}
	if got := c.get(); got != "" {
		t.Fatalf("没有设置文件：%q", got)
	}
	writeFile(t, path, `{"permissions":{"defaultMode":"plan"}}`)
	if got := c.get(); got != "plan" {
		t.Fatalf("读到：%q", got)
	}
	writeFile(t, path, `{"permissions":{"defaultMode":"acceptEdits"}}`)
	age(t, path, time.Minute)
	if got := c.get(); got != "acceptEdits" {
		t.Fatalf("改了以后：%q", got)
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if got := c.get(); got != "" {
		t.Fatalf("删了以后：%q", got)
	}
}

// zeta_host.sh start 建了目录、还没写下 pid 时（这期间持着 hosts/.lock），hosts 不把它当结束了的删掉；锁放开以后照常删。
func TestHostsLeavesADirBeingStarted(t *testing.T) {
	e := testEnv(t)
	dir := filepath.Join(e.hosts, "k-starting")
	writeFile(t, filepath.Join(dir, "out.jsonl"), "")
	lock, err := os.OpenFile(filepath.Join(e.hosts, ".lock"), os.O_CREATE|os.O_WRONLY, 0o666)
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		t.Fatal(err)
	}
	hosts := func() {
		o := newEmitter(io.Discard)
		if err := cmdHosts(e, o); err != nil {
			t.Fatal(err)
		}
		o.flush()
	}
	hosts()
	if _, err := os.Stat(dir); err != nil {
		t.Fatalf("start 还持着锁时目录被删了：%v", err)
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_UN); err != nil {
		t.Fatal(err)
	}
	hosts()
	if _, err := os.Stat(dir); !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("锁放开以后没有 pid 的目录还在：%v", err)
	}
}
