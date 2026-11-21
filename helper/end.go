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
	signaled := map[int]string{}
	for _, r := range e.outsideRegistrations() {
		if r.session != id {
			continue
		}
		if _, alive := claudeArgv(r.pid, true); alive && syscall.Kill(r.pid, syscall.SIGTERM) == nil {
			signaled[r.pid] = r.start
		}
	}
	stubborn := waitGone(signaled, endGrace)
	for pid := range stubborn {
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
	waitGone(stubborn, 2*time.Second)
	o.out(obj{"ended": len(signaled)})
	return nil
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
