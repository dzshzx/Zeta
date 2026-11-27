package sshtest

import (
	"os"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// WaitGone 等 pidFile 里记的那边进程结束：本机超时只关通道，那边的命令要靠在那边限时（sshlink.Limited）自己到点结束。
func WaitGone(t *testing.T, pidFile string) {
	t.Helper()
	b, err := os.ReadFile(pidFile)
	if err != nil {
		t.Fatalf("远端命令没写下 pid：%v", err)
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil {
		t.Fatal(err)
	}
	proc, err := os.FindProcess(pid)
	if err != nil {
		return
	}
	deadline := time.Now().Add(10 * time.Second)
	for proc.Signal(syscall.Signal(0)) == nil {
		if time.Now().After(deadline) {
			t.Fatalf("远端进程 %d 超时后还在跑", pid)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
