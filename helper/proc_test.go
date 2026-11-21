package main

import (
	"os/exec"
	"strconv"
	"testing"
	"time"
)

// claudeArgv 认活着的 Claude Code：命令行里有 claude 的算，别的程序、僵尸、内核线程不算。
// 命令行读出来是空的那一下（进程正在 exec）也算活着；HostTasksTest 起的假 Claude 就可能被 hosts 碰在这一下。
func TestClaudeArgvAlive(t *testing.T) {
	start := func(t *testing.T, args ...string) *exec.Cmd {
		cmd := exec.Command(args[0], args[1:]...)
		if err := cmd.Start(); err != nil {
			t.Fatal(err)
		}
		return cmd
	}
	t.Run("命令行里有 claude", func(t *testing.T) {
		cmd := start(t, "bash", "-c", "exec -a claude-fake sleep 30")
		defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
		// 起来后马上看（可能正在 exec）、等 exec 完再看，都算活着
		if _, alive := claudeArgv(cmd.Process.Pid, true); !alive {
			t.Error("刚起来时不算活着")
		}
		waitFor(t, func() bool { a, _ := procArgv(cmd.Process.Pid); return len(a) > 0 && string(a[0]) == "claude-fake" })
		if argv, alive := claudeArgv(cmd.Process.Pid, true); !alive || string(argv[0]) != "claude-fake" {
			t.Errorf("exec 完了不算活着：%q %v", argv, alive)
		}
	})
	t.Run("别的程序", func(t *testing.T) {
		cmd := start(t, "sleep", "30")
		defer func() { _ = cmd.Process.Kill(); _ = cmd.Wait() }()
		waitFor(t, func() bool { a, _ := procArgv(cmd.Process.Pid); return len(a) > 0 && string(a[0]) == "sleep" })
		if _, alive := claudeArgv(cmd.Process.Pid, true); alive {
			t.Error("sleep 算成了 Claude Code")
		}
	})
	t.Run("僵尸", func(t *testing.T) {
		cmd := start(t, "bash", "-c", "exec -a claude-fake true")
		defer func() { _ = cmd.Wait() }()
		waitFor(t, func() bool { _, state, _ := procStat(cmd.Process.Pid); return state == 'Z' })
		if _, alive := claudeArgv(cmd.Process.Pid, true); alive {
			t.Error("没收尸的进程（命令行是空的）算成了活着")
		}
	})
	t.Run("内核线程", func(t *testing.T) {
		pid := kernelThread()
		if pid == 0 {
			t.Skip("看不到内核线程（比如在 WSL 里）")
		}
		if _, alive := claudeArgv(pid, true); alive {
			t.Errorf("内核线程 %d（命令行是空的）算成了活着", pid)
		}
	})
}

// kernelThread 是 /proc 里看得到的一个内核线程（stat 的 flags 带 PF_KTHREAD），没有时是 0。
func kernelThread() int {
	for _, pid := range procIDs() {
		fields := procStatFields(pid)
		if len(fields) < 7 {
			continue
		}
		if flags, err := strconv.ParseUint(fields[6], 10, 64); err == nil && flags&0x00200000 != 0 {
			return pid
		}
	}
	return 0
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatal("等了 5 秒没等到")
}
