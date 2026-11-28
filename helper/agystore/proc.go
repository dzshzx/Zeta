package agystore

import (
	"bytes"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
)

// Holder 是一个正打开着 ~/.gemini/antigravity-cli/presence/<id>.lock 的 agy 进程。
type Holder struct {
	PID        int
	Start      string
	Session    string
	HostMark   string // 进程环境里的 ZETA_HOST=<pkg>/<key>，没有时为空
	Entrypoint string // "cli"（终端）或 "acp"
}

// ActiveHolders 扫描 /proc/<pid>/fd，找出正持有着 presence/<session>.lock（session 为空时为全部会话）的本用户进程。
func ActiveHolders(agyDir, session string) []Holder {
	presenceDir := filepath.Clean(filepath.Join(agyDir, "presence"))
	if !hasActiveLockFile(presenceDir, session) {
		return nil
	}
	if resolved, err := filepath.EvalSymlinks(presenceDir); err == nil {
		presenceDir = resolved
	}
	prefix := presenceDir + string(filepath.Separator)
	procEntries, err := os.ReadDir("/proc")
	if err != nil {
		return nil
	}
	self := os.Getpid()
	var out []Holder
	for _, pe := range procEntries {
		pid, err := strconv.Atoi(pe.Name())
		if err != nil || pid <= 1 || pid == self {
			continue
		}
		if h, ok := inspectProcLocks(pid, prefix, session); ok {
			out = append(out, h...)
		}
	}
	return out
}

func hasActiveLockFile(presenceDir, _ string) bool {
	st, err := os.Stat(presenceDir)
	return err == nil && st.IsDir()
}

func inspectProcLocks(pid int, presencePrefix, wantSession string) ([]Holder, bool) {
	matched := lockedSessionsForPID(pid, presencePrefix, wantSession)
	if len(matched) == 0 {
		return nil, false
	}
	start, state, ok := ReadProcStat(pid)
	if !ok || state == 'Z' {
		return nil, false
	}
	mark := ReadProcEnv(pid, "ZETA_HOST")
	ep := entrypointOfCmdline(pid)
	holders := make([]Holder, 0, len(matched))
	for _, id := range matched {
		holders = append(holders, Holder{
			PID:        pid,
			Start:      start,
			Session:    id,
			HostMark:   mark,
			Entrypoint: ep,
		})
	}
	return holders, true
}

func lockedSessionsForPID(pid int, presencePrefix, wantSession string) []string {
	fdDir := "/proc/" + strconv.Itoa(pid) + "/fd"
	fds, err := os.ReadDir(fdDir)
	if err != nil {
		return nil
	}
	var matched []string
	seen := map[string]bool{}
	for _, fd := range fds {
		id, ok := parseLockFDTarget(fdDir+"/"+fd.Name(), presencePrefix)
		if !ok || seen[id] || (wantSession != "" && id != wantSession) {
			continue
		}
		seen[id] = true
		matched = append(matched, id)
	}
	return matched
}

func parseLockFDTarget(fdPath, presencePrefix string) (string, bool) {
	target, err := os.Readlink(fdPath)
	if err != nil {
		return "", false
	}
	target = strings.TrimSuffix(target, " (deleted)")
	rest, ok := strings.CutPrefix(target, presencePrefix)
	if !ok {
		return "", false
	}
	id, ok := strings.CutSuffix(rest, ".lock")
	return id, ok && IsSessionID(id)
}

// ReadProcStat 读 /proc/<pid>/stat，返回启动时刻（第 22 项）和状态字符（第 3 项）。
func ReadProcStat(pid int) (start string, state byte, ok bool) {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
	if err != nil {
		return "", 0, false
	}
	i := bytes.LastIndexByte(data, ')')
	if i < 0 {
		return "", 0, false
	}
	fields := strings.Fields(string(data[i+1:]))
	if len(fields) < 20 {
		return "", 0, false
	}
	return fields[19], fields[0][0], true
}

// ReadProcEnv 读取 /proc/<pid>/environ 里指定环境变量的值，不存在或不可读时为空。
func ReadProcEnv(pid int, key string) string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/environ")
	if err != nil {
		return ""
	}
	prefix := []byte(key + "=")
	for _, item := range bytes.Split(data, []byte{0}) {
		if v, ok := bytes.CutPrefix(item, prefix); ok {
			return string(v)
		}
	}
	return ""
}

// ReadCmdline 读取 /proc/<pid>/cmdline，去掉空段后返回参数切片。
func ReadCmdline(pid int) []string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil {
		return nil
	}
	var out []string
	for _, part := range bytes.Split(data, []byte{0}) {
		if len(part) > 0 {
			out = append(out, string(part))
		}
	}
	return out
}

// IsAgySessionCmdline 识别活着的 agy 会话进程（交互式 agy、agy acp 或 zeta-helper agy-bridge），排除短命令与 -p 单次调用。
func IsAgySessionCmdline(argv []string) bool {
	if len(argv) == 0 {
		return false
	}
	if len(argv) >= 2 && argv[1] == "agy-bridge" {
		return true
	}
	if filepath.Base(argv[0]) != "agy" {
		return false
	}
	if len(argv) >= 2 {
		switch argv[1] {
		case "mcp", "models", "update", "--version", "-v", "--help", "-h":
			return false
		case "acp":
			return !slices.Contains(argv[2:], "--login")
		}
	}
	return !slices.Contains(argv[1:], "-p")
}

func entrypointOfCmdline(pid int) string {
	if slices.Contains(ReadCmdline(pid), "acp") {
		return "sdk-cli"
	}
	return "cli"
}
