package main

import (
	"syscall"
	"time"
)

// end <id>：结束正占着会话 <id> 的、Zeta 之外的 Claude Code 进程（比如终端或 tmux 里开着的交互式 claude）。
// 进程按 ~/.claude/sessions 的登记找（见 outsideRegistrations：启动时刻对得上、不在托管目录里），命令行里还要有 claude；
// Zeta 自己托管的进程不在这里结束，它们由 zeta_host.sh stop 经 end_session 结束。
// 先 SIGTERM（交互式 Claude Code 收到后自己收尾退出，删掉登记，实测 2.1.330 约 2 秒），
// 过了 endGrace 还在的 SIGKILL。输出一行 {"ended": 结束了几个}；一个也没有时是 0，不算出错。

// endGrace 是 SIGTERM 之后等进程自己退出的时间；测试里改短。
var endGrace = 10 * time.Second

const endPoll = 100 * time.Millisecond

func cmdEnd(e *env, o *emitter, id string) error {
	var holders []registered
	for _, r := range e.outsideRegistrations() {
		if r.session == id {
			holders = append(holders, r)
		}
	}
	signaled := signalHolders(holders, syscall.SIGTERM)
	escalate(signaled, endGrace, syscall.SIGKILL)
	o.out(obj{"ended": len(signaled)})
	return nil
}

// signalHolders 给这些登记的进程里还在跑、命令行里有 claude 的发 sig；给出发出去的（pid → 启动时刻）。
func signalHolders(holders []registered, sig syscall.Signal) map[int]string {
	signaled := map[int]string{}
	for _, r := range holders {
		if _, alive := claudeArgv(r.pid, true); alive && syscall.Kill(r.pid, sig) == nil {
			signaled[r.pid] = r.start
		}
	}
	return signaled
}

// escalate 等 procs 退出最多 grace，还在的依次发 signals 里的信号：发 SIGKILL 后再等 2 秒，别的信号后再等 5 秒。
func escalate(procs map[int]string, grace time.Duration, signals ...syscall.Signal) {
	left := waitGone(procs, grace)
	for _, sig := range signals {
		if len(left) == 0 {
			return
		}
		for pid := range left {
			_ = syscall.Kill(pid, sig)
		}
		wait := 5 * time.Second
		if sig == syscall.SIGKILL {
			wait = 2 * time.Second
		}
		left = waitGone(left, wait)
	}
}

// running：进程 pid 还在跑（僵尸不算），而且还是启动时刻为 start 的那一个（不是复用了 pid 的别的进程）。
func running(pid int, start string) bool {
	now, state, ok := procStat(pid)
	return ok && now == start && state != 'Z'
}

// waitGone 等这些进程（pid → 启动时刻）都退出，最多等 limit；给出到时还在跑的。
func waitGone(procs map[int]string, limit time.Duration) map[int]string {
	left := map[int]string{}
	for pid, start := range procs {
		left[pid] = start
	}
	deadline := time.Now().Add(limit)
	for {
		for pid, start := range left {
			if !running(pid, start) {
				delete(left, pid)
			}
		}
		if len(left) == 0 || !time.Now().Before(deadline) {
			return left
		}
		time.Sleep(endPoll)
	}
}
