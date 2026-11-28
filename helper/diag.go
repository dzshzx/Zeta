package main

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/helper/redact"
)

// 诊断：这台主机上 Claude Code 那边的日志（docs/core.md「诊断」）。只读，不改 ~/.claude 和托管目录里的任何东西。
// 读的位置和份数同手机版诊断页的 DiagScripts：在跑的对话进程的标准错误（托管目录的 err.log）、结束的对话留下的那份
// （zeta_host.sh 存到 ~/.cache/<名字>/stderr/）、Claude Code 自己的调试日志（~/.claude/debug/）。

const (
	diagTailBytes     = 64 * 1024 // 看日志、打包时每个文件只取最后这么多字节
	diagFinishedLimit = 20        // 结束的对话进程留几份（和 zeta_host.sh 里留的份数一致）
	diagDebugLimit    = 30        // 调试日志列几个
	diagTitleBytes    = 200       // 在跑的对话的标题最多取这么长
)

// 日志文件的类别，按这个顺序列。
const (
	diagRunning  = "running"
	diagFinished = "finished"
	diagDebug    = "debug"
)

// diagFile 是一个能看的日志文件。Modified 是毫秒；Title 是对话标题，只有在跑的有。
type diagFile struct {
	Kind     string `json:"kind"`
	Path     string `json:"path"`
	Size     int64  `json:"size"`
	Modified int64  `json:"modified"`
	Title    string `json:"title,omitempty"`
}

type diagFilesReply struct {
	Files []diagFile `json:"files"`
}

// diagTailReply 是一个文件的尾部：Text 是最后 diagTailBytes 字节（按 UTF-8 解，坏字节换成替代符），Truncated 是前面还有没取的。
type diagTailReply struct {
	Text      string `json:"text"`
	Size      int64  `json:"size"`
	Truncated bool   `json:"truncated"`
}

// diagCache 是 Zeta 自己（这个包）的缓存目录 ~/.cache/<名字>。
func (e *env) diagCache() string { return filepath.Dir(e.hosts) }

func diagFilesMethod(e *env, _ oneShotParams) (any, error) {
	files := diagRunningFiles(e)
	files = append(files, newestFiles(diagFinished, e.diagCache()+"/stderr", ".log", diagFinishedLimit)...)
	debug := newestFiles(diagDebug, e.claude+"/debug", "", diagDebugLimit)
	debug = append(debug, newestFiles(diagDebug, e.agy+"/log", ".log", diagDebugLimit)...)
	sortNewest(debug)
	files = append(files, debug...)
	return diagFilesReply{Files: files}, nil
}

// diagRunningFiles 是各托管目录的 err.log，新的在前，带对话标题。
func diagRunningFiles(e *env) []diagFile {
	paths, _ := filepath.Glob(e.hosts + "/*/err.log")
	var out []diagFile
	for _, p := range paths {
		f, ok := regularFile(diagRunning, p)
		if !ok {
			continue
		}
		f.Title = runningTitle(filepath.Dir(p) + "/title")
		out = append(out, f)
	}
	sortNewest(out)
	return out
}

// runningTitle 读托管目录的 title（start 时写下的标题），换行和制表符换成空格。
func runningTitle(path string) string {
	fh, err := os.Open(path)
	if err != nil {
		return ""
	}
	defer fh.Close()
	b, _ := io.ReadAll(io.LimitReader(fh, diagTitleBytes))
	t := strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == '\t' {
			return ' '
		}
		return r
	}, strings.ToValidUTF8(string(b), "\uFFFD"))
	return strings.TrimSpace(t)
}

// newestFiles 是 dir 下（不进子目录）名字以 suffix 结尾的普通文件里最新的 limit 个。符号链接不算（~/.claude/debug/latest）。
func newestFiles(kind, dir, suffix string, limit int) []diagFile {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []diagFile
	for _, en := range entries {
		if !strings.HasSuffix(en.Name(), suffix) {
			continue
		}
		if f, ok := regularFile(kind, dir+"/"+en.Name()); ok {
			out = append(out, f)
		}
	}
	sortNewest(out)
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

func regularFile(kind, path string) (diagFile, bool) {
	st, err := os.Lstat(path)
	if err != nil || !st.Mode().IsRegular() {
		return diagFile{}, false
	}
	return diagFile{Kind: kind, Path: path, Size: st.Size(), Modified: st.ModTime().UnixMilli()}, true
}

func sortNewest(files []diagFile) {
	slices.SortStableFunc(files, func(a, b diagFile) int {
		switch {
		case a.Modified > b.Modified:
			return -1
		case a.Modified < b.Modified:
			return 1
		}
		return strings.Compare(a.Path, b.Path)
	})
}

func diagTailMethod(e *env, p oneShotParams) (any, error) {
	if !diagReadable(e, p.Path) {
		return nil, errBadRequest
	}
	text, size, truncated, err := tailFile(p.Path, diagTailBytes)
	if err != nil {
		return nil, err
	}
	if p.Redact {
		text = redact.Text(text)
	}
	return diagTailReply{Text: text, Size: size, Truncated: truncated}, nil
}

// diagReadable：只许读 diag.files 列的那几处：~/.claude/debug/ 下的，这个包的 stderr/ 下的，和托管目录的 err.log。
// 路径要是绝对的、规整的（没有 .、..、连着的斜杠）。
func diagReadable(e *env, path string) bool {
	if !strings.HasPrefix(path, "/") || filepath.Clean(path) != path {
		return false
	}
	child := func(dir string) bool {
		rest, ok := strings.CutPrefix(path, dir+"/")
		return ok && rest != "" && !strings.Contains(rest, "/")
	}
	if child(e.claude+"/debug") || child(e.diagCache()+"/stderr") || (child(e.agy+"/log") && strings.HasSuffix(path, ".log")) {
		return true
	}
	rest, ok := strings.CutPrefix(path, e.hosts+"/")
	key, file, two := strings.Cut(rest, "/")
	return ok && two && key != "" && file == "err.log"
}

// tailFile 读 path 的最后 n 字节。截断时丢掉开头被切开的半个 UTF-8 字符。只读普通文件（不跟符号链接）。
func tailFile(path string, n int64) (text string, size int64, truncated bool, err error) {
	st, err := os.Lstat(path)
	if err != nil {
		return "", 0, false, err
	}
	if !st.Mode().IsRegular() {
		return "", 0, false, errors.New("不是普通文件")
	}
	fh, err := os.Open(path)
	if err != nil {
		return "", 0, false, err
	}
	defer fh.Close()
	size = st.Size()
	start := max(size-n, 0)
	b, err := io.ReadAll(io.NewSectionReader(fh, start, size-start))
	if err != nil {
		return "", 0, false, err
	}
	if start > 0 {
		for i := 0; i < utf8.UTFMax-1 && len(b) > 0 && !utf8.RuneStart(b[0]); i++ {
			b = b[1:]
		}
	}
	return strings.ToValidUTF8(string(b), "\uFFFD"), size, start > 0, nil
}
