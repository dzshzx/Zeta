package main

import (
	"errors"
	"io"
	"os"
	"syscall"
	"time"
)

// 同一个会话在终端的 Claude Code 和 Zeta 之间交接。同一时刻只能有一个进程写会话记录，所以交接就是一边先退出、另一边再 --resume：
//
//   occupant <id>  Zeta 之外占着会话 <id> 的进程闲不闲（Zeta 的「别处运行」卡片轮询它）
//   handoff <id>   终端交给 Zeta：那边闲着（或在等确认）时给它发 SIGINT，Claude Code 照 /exit 那样退出，
//                  终端里留下 `claude --resume <id>` 的提示；那边正忙时不动
//   release <id> [<最多等几秒>]
//                  Zeta 交回终端：两个包（zeta、zeta-dev）托管的、占着会话 <id> 的进程闲着时照 zeta_host.sh stop 那样结束，
//                  正忙时等它闲下来，最多等这么多秒（默认不等）。终端接回之前调（desk 窗口）
//   session-start  同 release，给 Claude Code 的 SessionStart hook 用：会话 id 从标准输入的 hook 输入里取（登记写法见 README）
//
// 交接只在两轮之间做：正跑着的一轮、后台任务都在进程里，进程退出就没了。

// zetaNames 是两个包在 Linux 里的目录名（AppVariant）：release 两个都看，终端那边不知道会话是哪个包托管的。
var zetaNames = []string{"zeta", "zeta-dev"}

// handoffGrace 是 SIGINT 之后等终端的 Claude Code 退出的时间；测试里改短。
var handoffGrace = 15 * time.Second

// releaseGrace 是写 end_session 之后等托管的进程退出的时间（同 zeta_host.sh 的 ZETA_STOP_GRACE），再 SIGTERM、SIGKILL；测试里改短。
var releaseGrace = 10 * time.Second

// releasedMark 是 release 结束托管进程前在托管目录里留的记号：zeta_host.sh 报 zeta_exit 时带上 "released":true，
// 接着的 Zeta 据此知道会话是被终端接走了，不当成进程出错。
const releasedMark = "released"

// statusRank 是终端的 Claude Code 报的状态有多要紧：idle 最轻；旧版不报状态（unknown）时看不出忙闲；
// waiting（在等确认）交过来那个确认就作废；别的（busy、shell 等）都是正在干活，不能交接。
func statusRank(status string) int {
	switch status {
	case "idle":
		return 0
	case "unknown":
		return 1
	case "waiting":
		return 2
	}
	return 3
}

// handsOver：报这个状态的进程可以交给 Zeta。
func handsOver(status string) bool { return statusRank(status) < 3 }

// occupied 是 Zeta 之外占着会话 id 的进程，和它们里最要紧的状态（statusRank）。
func (e *env) occupied(id string) (holders []registered, status, waiting string) {
	for _, r := range e.outsideRegistrations() {
		if r.session != id {
			continue
		}
		s := orText(r.status, "unknown")
		if len(holders) == 0 || statusRank(s) > statusRank(status) {
			status, waiting = s, r.waiting
		}
		holders = append(holders, r)
	}
	return holders, status, waiting
}

func orText(s, fallback string) string {
	if s == "" {
		return fallback
	}
	return s
}

// cmdOccupant 输出一行 {"status": …, "waiting_for": …}：没有别处的进程占着时 status 是 null；
// 有时是 idle、waiting、busy、shell，旧版不报状态时是 unknown。
func cmdOccupant(e *env, o *emitter, id string) error {
	holders, status, waiting := e.occupied(id)
	if len(holders) == 0 {
		o.out(obj{"status": nil, "waiting_for": nil})
		return nil
	}
	var waitingFor any
	if status == "waiting" && waiting != "" {
		waitingFor = waiting
	}
	o.out(obj{"status": status, "waiting_for": waitingFor})
	return nil
}

// cmdHandoff 输出一行 {"handed": 交没交出来, "status": …}：没人占着也算交出来了；那边正忙时 handed 为假、status 是它的状态；
// 发了 SIGINT 过了 handoffGrace 还不退的 status 是 stuck（不再升级成 SIGKILL：要强行停掉走 end）。
func cmdHandoff(e *env, o *emitter, id string) error {
	holders, status, _ := e.occupied(id)
	if len(holders) > 0 && !handsOver(status) {
		o.out(obj{"handed": false, "status": status})
		return nil
	}
	// 另一个包托管的进程照 Zeta 自己的规矩结束（end_session），终端里的发 SIGINT
	hostedElsewhere := e.hostedDirs()
	signaled := map[int]string{}
	for _, r := range holders {
		if dir, ok := hostedElsewhere[r.pid]; ok {
			endHosted(hostedHolder{dir: dir, pid: r.pid, start: r.start})
		} else if _, alive := claudeArgv(r.pid, true); alive && syscall.Kill(r.pid, syscall.SIGINT) == nil {
			signaled[r.pid] = r.start
		}
	}
	if len(waitGone(signaled, handoffGrace)) > 0 {
		o.out(obj{"handed": false, "status": "stuck"})
		return nil
	}
	o.out(obj{"handed": true, "status": nil})
	return nil
}

// hostedHolder 是某个包托管的、占着会话的进程。
type hostedHolder struct {
	dir   string
	pid   int
	start string
}

// hostedDirs 是两个包托管的、还没结束的进程：pid → 托管目录。
func (e *env) hostedDirs() map[int]string {
	owner := map[int]string{}
	for _, name := range zetaNames {
		hosts := newEnvAt(e.home, name).hosts
		for _, key := range globNames(hosts, true, nil) {
			dir := joinPath(hosts, key)
			if pid, ok := readPid(joinPath(dir, "pid")); ok && !existsFollow(joinPath(dir, "exit")) {
				owner[pid] = dir
			}
		}
	}
	return owner
}

// hostedHolders 是两个包托管的进程里占着会话 id 的那些，按 ~/.claude/sessions 的登记认（托管目录记着它的 pid）。
func (e *env) hostedHolders(id string) []hostedHolder {
	owner := e.hostedDirs()
	var out []hostedHolder
	for _, r := range e.registeredSessions() {
		if dir, ok := owner[r.pid]; ok && r.session == id {
			out = append(out, hostedHolder{dir: dir, pid: r.pid, start: r.start})
		}
	}
	return out
}

// hostBusy：托管的进程这一轮还在进行（含等着用户批准）或还有后台任务在跑，同 zeta_host.sh 的看门狗。读不了日志时当它忙。
func hostBusy(dir string) bool {
	scan, err := scanHost(dir)
	return err != nil || scan.busy || scan.tasks > 0
}

// releaseSession 结束占着会话 id 的托管进程（skip 里的进程号除外）；有在忙的时每秒看一次，最多等 wait，到时还忙就一个都不动、busy 为真。
func (e *env) releaseSession(id string, wait time.Duration, skip map[int]bool) (released int, busy bool) {
	deadline := time.Now().Add(wait)
	for {
		var holders []hostedHolder
		for _, h := range e.hostedHolders(id) {
			if !skip[h.pid] {
				holders = append(holders, h)
			}
		}
		busy = false
		for _, h := range holders {
			busy = busy || hostBusy(h.dir)
		}
		if !busy {
			for _, h := range holders {
				endHosted(h)
			}
			return len(holders), false
		}
		if !time.Now().Before(deadline) {
			return 0, true
		}
		time.Sleep(time.Second)
	}
}

// endHosted 照 zeta_host.sh 的 end_claude 结束一个托管进程：先留下 released 记号，往输入管道写 end_session，
// 过了 releaseGrace 还在就 SIGTERM，再过 5 秒 SIGKILL。托管目录留给接着的一方或 zeta-helper hosts 清。
func endHosted(h hostedHolder) {
	_ = os.WriteFile(joinPath(h.dir, releasedMark), nil, 0o644)
	if err := writeFifo(joinPath(h.dir, "in"),
		`{"type":"control_request","request_id":"zeta-release","request":{"subtype":"end_session"}}`+"\n"); err != nil {
		_ = syscall.Kill(h.pid, syscall.SIGTERM)
	}
	procs := map[int]string{h.pid: h.start}
	if len(waitGone(procs, releaseGrace)) == 0 {
		return
	}
	_ = syscall.Kill(h.pid, syscall.SIGTERM)
	if len(waitGone(procs, 5*time.Second)) > 0 {
		_ = syscall.Kill(h.pid, syscall.SIGKILL)
		waitGone(procs, 2*time.Second)
	}
}

// writeFifo 往命名管道写一行，不等读的一方：托管进程那段以读写方式一直开着它，没人开着时（进程刚好没了）报错而不是卡住。
func writeFifo(path, line string) error {
	f, err := os.OpenFile(path, os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	n, err := f.WriteString(line)
	if err == nil && n < len(line) {
		err = errors.New("short write")
	}
	return err
}

// cmdRelease 输出一行 {"released": 结束了几个, "busy": 是否因为还在忙没动}。
func cmdRelease(e *env, o *emitter, id string, wait time.Duration) error {
	released, busy := e.releaseSession(id, wait, nil)
	o.out(obj{"released": released, "busy": busy})
	return nil
}

// cmdSessionStart 是 Claude Code 的 SessionStart hook（在 matcher resume 上登记，写法见 README）：终端里接着一个会话时，
// 照 release 让 Zeta 那边占着它的进程退出。标准输入是 hook 的输入（session_id、source）。Zeta 那边正忙时不动它，
// 输出一条 systemMessage 提醒先别发消息；别的情况什么也不输出。出错也以 0 退出，不挡终端里的会话。
func cmdSessionStart(e *env, o *emitter, in io.Reader) error {
	data, err := io.ReadAll(io.LimitReader(in, 1<<20))
	if err != nil {
		return nil
	}
	input, _ := parseObject(data)
	id := asString(input["session_id"])
	if !isSessionID(id) || asString(input["source"]) != "resume" {
		return nil
	}
	// Zeta 自己起的进程接着会话时也会跑这个 hook：发起这次接回的 Claude Code 是本进程的祖先，不能把它自己结束掉
	if _, busy := e.releaseSession(id, 0, ancestors(os.Getpid())); busy {
		o.out(obj{"systemMessage": "Zeta 那边还在跑这个会话的这一轮。等它跑完再在这里发消息，否则两边会同时写同一份会话记录；" +
			"跑完后重新 claude --resume 一次，Zeta 那边就会退出。"})
	}
	return nil
}

// ancestors 是进程 pid 往上的各级父进程（不含 pid 自己），按 /proc/<pid>/stat 的第 4 项一级级找到 1 为止。
func ancestors(pid int) map[int]bool {
	out := map[int]bool{}
	for i := 0; i < 64; i++ {
		ppid, ok := procParent(pid)
		if !ok || ppid <= 1 || out[ppid] {
			break
		}
		out[ppid] = true
		pid = ppid
	}
	return out
}
