package agystore

import (
	"bytes"
	"os"
	"path/filepath"
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
	if st, err := os.Stat(presenceDir); err != nil || !st.IsDir() {
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

func inspectProcLocks(pid int, presencePrefix, wantSession string) ([]Holder, bool) {
	matched := lockedSessionsForPID(pid, presencePrefix, wantSession)
	if len(matched) == 0 {
		return nil, false
	}
	start, state, ok := readProcStat(pid)
	if !ok || state == 'Z' {
		return nil, false
	}
	mark := readProcEnv(pid, "ZETA_HOST")
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

func readProcStat(pid int) (start string, state byte, ok bool) {
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

func readProcEnv(pid int, key string) string {
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

func entrypointOfCmdline(pid int) string {
	data, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/cmdline")
	if err != nil {
		return "cli"
	}
	for _, part := range bytes.Split(data, []byte{0}) {
		if string(part) == "acp" {
			return "sdk-cli"
		}
	}
	return "cli"
}
