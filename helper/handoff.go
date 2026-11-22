package main

import (
	"errors"
	"io"
	"os"
	"slices"
	"syscall"
	"time"

	"github.com/dzshzx/Zeta/helper/conv"
)

// 同一个会话在终端的 Claude Code 和 Zeta 之间交接。同一时刻只能有一个进程写会话记录，所以交接就是一边先退出、另一边再 --resume：
//
//   occupant <id>  Zeta 之外占着会话 <id> 的进程闲不闲、能不能交过来（Zeta 的「别处运行」卡片轮询它）
//   handoff <id>   终端交给 Zeta：那边闲着（或在等确认）时给它发 SIGINT，Claude Code 照 /exit 那样退出，
//                  终端里留下 `claude --resume <id>` 的提示；那边正忙时不动
//   session-start  Zeta 交回终端，给 Claude Code 的 SessionStart hook 用（会话 id 从标准输入的 hook 输入里取，登记写法见 README）：
//                  两个包（zeta、zeta-dev）托管的、占着这个会话的进程闲着时照 zeta_host.sh stop 那样结束（releaseSession）。
//                  desk 窗口回车时也这样做，正忙时等它闲下来
//
// 交接只在两轮之间做：正跑着的一轮、后台任务都在进程里，进程退出就没了。

// handoffGrace 是 SIGINT 之后等终端的 Claude Code 退出的时间；测试里改短。
var handoffGrace = 15 * time.Second

// releaseGrace 是写 end_session 之后等托管的进程退出的时间（同 zeta_host.sh 的 ZETA_STOP_GRACE），再 SIGTERM、SIGKILL；测试里改短。
var releaseGrace = 10 * time.Second

// releasedMark 是 releaseSession 结束托管进程前在托管目录里留的记号：zeta_host.sh 报 zeta_exit 时带上 "reason":"released"，
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

// cmdOccupant 输出一行 occupancy 的结果（conv.Occupancy）。
func cmdOccupant(e *env, o *emitter, id string) error {
	o.out(e.occupancy(id))
	return nil
}

// occupancy：Zeta 之外占着会话 id 的进程的样子（conv.Occupancy）：没有时 status 是 null；有时是 idle、waiting、busy、shell，
// 旧版不报状态时是 unknown。hands_over：现在能交给 Zeta（handsOver）。核心的 conv.occupant 也用它。
func (e *env) occupancy(id string) conv.Occupancy {
	holders, status, waiting := e.occupied(id)
	if len(holders) == 0 {
		return conv.Occupancy{HandsOver: true}
	}
	result := conv.Occupancy{Status: &status, HandsOver: handsOver(status)}
	if status == "waiting" && waiting != "" {
		result.WaitingFor = &waiting
	}
	return result
}

// cmdHandoff 输出一行 handOff 的结果（conv.Handoff）。
func cmdHandoff(e *env, o *emitter, id string) error {
	o.out(e.handOff(id))
	return nil
}

// handOff 把会话 id 从别处接过来（conv.Handoff）：没人占着也算交出来了；那边正忙时 handed 为假、status 是它的状态；
// 发了 SIGINT 过了 handoffGrace 还不退的 status 是 stuck（不再升级成 SIGKILL：要强行停掉走 end）。核心的 conv.handoff 也用它。
func (e *env) handOff(id string) conv.Handoff {
	holders, status, _ := e.occupied(id)
	if len(holders) > 0 && !handsOver(status) {
		return conv.Handoff{Status: &status}
	}
	// 终端里的发 SIGINT；另一个包托管的进程照 Zeta 自己的规矩结束（end_session），两边一起等
	var terminal, others []registered
	for _, r := range holders {
		if r.host != "" {
			others = append(others, r)
		} else {
			terminal = append(terminal, r)
		}
	}
	signaled := signalHolders(terminal, syscall.SIGINT)
	endHosted(others)
	if len(waitGone(signaled, handoffGrace)) > 0 {
		stuck := "stuck"
		return conv.Handoff{Status: &stuck}
	}
	return conv.Handoff{Handed: true}
}

// hostedHolders 是两个包托管的进程里占着会话 id 的那些（登记着、认得出托管目录的，见 hostDirOf）。
// 终端那边不知道会话是哪个包托管的，两个都看。
func (e *env) hostedHolders(id string) []registered {
	var out []registered
	for _, r := range e.registeredSessions() {
		if r.host != "" && r.session == id {
			out = append(out, r)
		}
	}
	return out
}

// waitOwnHostGone 等本包托管、占着会话 id 的进程都退出，最多 wait（sessions.delete 结束了开着的进程以后，删记录之前）。
func (e *env) waitOwnHostGone(id string, wait time.Duration) {
	deadline := time.Now().Add(wait)
	for slices.ContainsFunc(e.hostedHolders(id), e.ownHost) && time.Now().Before(deadline) {
		time.Sleep(100 * time.Millisecond)
	}
}

// hostBusy：托管的进程这一轮还在进行（含等着用户批准）或还有后台任务在跑，同 zeta_host.sh 的看门狗。读不了日志时当它忙。
func hostBusy(dir string) bool {
	scan, err := scanHost(dir)
	return err != nil || scan.busy || scan.tasks > 0
}

// selfAncestors 是本进程的各级父进程；测试里换掉。
var selfAncestors = func() map[int]bool { return ancestors(os.Getpid()) }

// releaseSession 结束占着会话 id 的托管进程；有在忙的时每秒看一次，最多等 wait，到时还忙就一个都不动、busy 为真。
// 本进程的祖先不算：Zeta 自己起的 claude --resume 也会跑 SessionStart hook，发起这次接回的就是它，不能把它结束掉。
func (e *env) releaseSession(id string, wait time.Duration) (released int, busy bool) {
	deadline := time.Now().Add(wait)
	skip := selfAncestors()
	for {
		var holders []registered
		for _, h := range e.hostedHolders(id) {
			if !skip[h.pid] {
				holders = append(holders, h)
			}
		}
		busy = false
		for _, h := range holders {
			busy = busy || hostBusy(h.host)
		}
		if !busy {
			endHosted(holders)
			return len(holders), false
		}
		if !time.Now().Before(deadline) {
			return 0, true
		}
		time.Sleep(time.Second)
	}
}

// endHosted 照 zeta_host.sh 的 end_claude 结束这些托管进程：先各留下 released 记号、往输入管道写 end_session，
// 一起等 releaseGrace，还在的 SIGTERM，再过 5 秒 SIGKILL。托管目录留给接着的一方或 zeta-helper hosts 清。
func endHosted(holders []registered) {
	procs := map[int]string{}
	for _, h := range holders {
		_ = os.WriteFile(joinPath(h.host, releasedMark), nil, 0o644)
		if err := writeFifo(h.host,
			`{"type":"control_request","request_id":"zeta-release","request":{"subtype":"end_session"}}`+"\n"); err != nil {
			_ = syscall.Kill(h.pid, syscall.SIGTERM)
		}
		procs[h.pid] = h.start
	}
	escalate(procs, releaseGrace, syscall.SIGTERM, syscall.SIGKILL)
}

// writeFifo 往托管目录 dir 的输入管道 in 写一行，不等读的一方：托管进程那段以读写方式一直开着它，没人开着时（进程刚好没了）报错而不是卡住。
// 和 zeta_host.sh 的 write_line 一样持着 dir/lock 写，不插进接着的一方正写着的大行中间；锁最多等 fifoLockWait，等不到也照写。
func writeFifo(dir, line string) error {
	if lock, err := os.OpenFile(joinPath(dir, "lock"), os.O_WRONLY|os.O_CREATE|os.O_APPEND, 0o644); err == nil {
		defer lock.Close()
		deadline := time.Now().Add(fifoLockWait)
		for syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) != nil && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
		}
	}
	f, err := os.OpenFile(joinPath(dir, "in"), os.O_WRONLY|syscall.O_NONBLOCK, 0)
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

// fifoLockWait 是 writeFifo 最多等写锁的时间（同 zeta_host.sh write_line 的 2 秒）。
const fifoLockWait = 2 * time.Second

// cmdSessionStart 是 Claude Code 的 SessionStart hook（在 matcher resume 上登记，写法见 README）：终端里接着一个会话时，
// 让 Zeta 那边占着它的进程退出（releaseSession）。标准输入是 hook 的输入（session_id、source）。Zeta 那边正忙时不动它，
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
	if _, busy := e.releaseSession(id, 0); busy {
		o.out(obj{"systemMessage": "Zeta 一侧仍在执行此会话的本轮回答。请等它结束后再在这里发送消息，否则两侧会同时写入同一份会话记录；" +
			"结束后重新执行一次 claude --resume，Zeta 一侧即会退出。"})
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
