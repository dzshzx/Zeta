package main

import (
	"bytes"
	"os"
	"strconv"
	"syscall"
	"time"
)

// end <id>：结束正占着会话 <id> 的、Zeta 之外的 Claude Code 进程（比如终端或 tmux 里开着的交互式 claude）。
// 进程按 ~/.claude/sessions 的登记找（见 registeredSessions：启动时刻对得上才算），命令行里要有 claude；
// Zeta 自己托管的进程不在这里结束，它们由 zeta_host.sh stop 经 end_session 结束。
// 先 SIGTERM（交互式 Claude Code 收到后自己收尾退出，删掉登记，实测 2.1.330 约 2 秒），
// 过了 endGrace 还在的 SIGKILL。输出一行 {"ended": 结束了几个}；一个也没有时是 0，不算出错。

// endGrace 是 SIGTERM 之后等进程自己退出的时间；测试里改短。
var endGrace = 10 * time.Second

const endPoll = 100 * time.Millisecond

func cmdEnd(e *env, o *emitter, id string) error {
	mine := e.hostPids()
	started := map[int]string{}
	for _, r := range e.registeredSessions() {
		if s, ok := r.session.(string); !ok || s != id || mine[r.pid] {
			continue
		}
		if _, alive := claudeArgv(r.pid, true); !alive {
			continue
		}
		mark, ok := procStart(r.pid)
		if !ok || syscall.Kill(r.pid, syscall.SIGTERM) != nil {
			continue
		}
		started[r.pid] = mark
	}
	waitGone(started, endGrace)
	for pid, mark := range started {
		if sameProcess(pid, mark) {
			_ = syscall.Kill(pid, syscall.SIGKILL)
		}
	}
	waitGone(started, 2*time.Second)
	o.out(obj{"ended": len(started)})
	return nil
}

// sameProcess：进程 pid 还在跑（僵尸不算），而且还是启动时刻为 mark 的那一个（不是复用了 pid 的别的进程）。
func sameProcess(pid int, mark string) bool {
	now, ok := procStart(pid)
	return ok && now == mark && !zombie(pid)
}

// zombie：进程已经退出、只等父进程收尸（/proc/<pid>/stat 的状态是 Z）。
func zombie(pid int) bool {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return false
	}
	i := bytes.LastIndexByte(data, ')')
	return i >= 0 && i+2 < len(data) && data[i+2] == 'Z'
}

// waitGone 等这些进程都退出，最多等 limit。
func waitGone(procs map[int]string, limit time.Duration) {
	deadline := time.Now().Add(limit)
	for time.Now().Before(deadline) {
		alive := false
		for pid, mark := range procs {
			if sameProcess(pid, mark) {
				alive = true
				break
			}
		}
		if !alive {
			return
		}
		time.Sleep(endPoll)
	}
}
