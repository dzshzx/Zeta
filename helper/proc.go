package main

import (
	"bytes"
	"encoding/binary"
	"os"
	"strconv"
	"strings"
)

// 进程信息，都从 /proc 读。

// procStart 是进程的启动时刻（/proc/<pid>/stat 第 22 项，开机后的时钟滴答数，原样的文字），用来识别 pid 被别的进程复用。
func procStart(pid int) (string, bool) {
	start, _, ok := procStat(pid)
	return start, ok
}

// procStat 读一次 /proc/<pid>/stat，给出启动时刻（同 procStart）和状态（第 3 项，比如 R、S、Z）。
func procStat(pid int) (start string, state byte, ok bool) {
	fields := procStatFields(pid)
	if len(fields) < 20 {
		return "", 0, false
	}
	return fields[19], fields[0][0], true
}

// procParent 是进程的父进程号（/proc/<pid>/stat 第 4 项）；进程不在了时 ok 为假。
func procParent(pid int) (int, bool) {
	fields := procStatFields(pid)
	if len(fields) < 2 {
		return 0, false
	}
	ppid, err := strconv.Atoi(fields[1])
	return ppid, err == nil
}

// procStatFields 是 /proc/<pid>/stat 里进程名（括号）之后的各项：[0] 是第 3 项（状态）。读不了时是 nil。
func procStatFields(pid int) []string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return nil
	}
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return nil
	}
	return strings.Fields(decodeString(data[i+1:]))
}

// readPid 读托管目录里记的进程号（pid、attach 文件）。
func readPid(path string) (int, bool) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(decodeString(data)))
	return pid, err == nil
}

// procArgv 是进程的命令行各段（按 NUL 分开，末尾那段通常是空的）。
func procArgv(pid int) ([][]byte, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil {
		return nil, false
	}
	return bytes.Split(data, []byte{0}), true
}

// claudeArgv：进程 pid 是活着的 Claude Code 时返回它的命令行；没有 pid（0 也算没有）、进程不在了、pid 被别的进程复用了时 ok 为假。
func claudeArgv(pid int, known bool) ([][]byte, bool) {
	if !known || pid == 0 {
		return nil, false
	}
	argv, ok := procArgv(pid)
	if !ok {
		return nil, false
	}
	for _, a := range argv {
		if bytes.Contains(a, []byte("claude")) {
			return argv, true
		}
	}
	return nil, false
}

// procStarted 是进程启动的时刻（秒，Unix 时间）；进程不在了时 ok 为假。
// 启动时刻读到了、但换算不出来时给 0：当它很早以前就在了，按时间判断的东西一律不删。
func procStarted(pid int) (float64, bool) {
	ticks, ok := procStart(pid)
	if !ok {
		return 0, false
	}
	boot, ok := bootTime()
	n, err := strconv.ParseInt(ticks, 10, 64)
	if !ok || err != nil {
		return 0, true
	}
	return float64(boot) + float64(n)/float64(clockTicks()), true
}

func bootTime() (int64, bool) {
	data, err := os.ReadFile("/proc/stat")
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(data), "\n") {
		if rest, ok := strings.CutPrefix(line, "btime "); ok {
			fields := strings.Fields(rest)
			if len(fields) == 0 {
				return 0, false
			}
			n, err := strconv.ParseInt(fields[0], 10, 64)
			return n, err == nil
		}
	}
	return 0, false
}

// clockTicks 是每秒的时钟滴答数（sysconf(_SC_CLK_TCK)，内核经辅助向量 AT_CLKTCK 给出）；读不到时取 Linux 的惯用值 100。
func clockTicks() int64 {
	const atClkTck = 17
	data, err := os.ReadFile("/proc/self/auxv")
	if err != nil || strconv.IntSize != 64 {
		return 100
	}
	for i := 0; i+16 <= len(data); i += 16 {
		if binary.NativeEndian.Uint64(data[i:]) == atClkTck {
			if v := binary.NativeEndian.Uint64(data[i+8:]); v > 0 {
				return int64(v)
			}
		}
	}
	return 100
}

// procHome 是进程的 HOME 环境变量（真实路径）；读不了（别的用户的进程）、没有时 ok 为假。
func procHome(pid int) (string, bool) {
	home, ok := procEnv(pid, "HOME")
	if !ok {
		return "", false
	}
	return realPath(home), true
}

// procEnv 是进程启动时的环境变量 name（/proc/<pid>/environ，进程起来后自己改的看不到）；读不了（别的用户的进程）、没有时 ok 为假。
func procEnv(pid int, name string) (string, bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/environ")
	if err != nil {
		return "", false
	}
	for _, item := range bytes.Split(data, []byte{0}) {
		if v, ok := bytes.CutPrefix(item, []byte(name+"=")); ok {
			return decodeString(v), true
		}
	}
	return "", false
}

// procIDs 是 /proc 下的进程号。
func procIDs() []int {
	var pids []int
	for _, name := range children("/proc") {
		if isDigits(name) {
			if pid, err := strconv.Atoi(name); err == nil {
				pids = append(pids, pid)
			}
		}
	}
	return pids
}
