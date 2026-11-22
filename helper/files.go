package main

import (
	"bytes"
	"errors"
	"io"
	"io/fs"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"unicode/utf8"
)

// 文件浏览（ls、read）和共享文件夹（mounts）。

const (
	maxList  = 2000
	maxRead  = 4 * 1024 * 1024
	mountDir = "/mnt/shared"

	// access(2) 的 R_OK、W_OK、X_OK
	accessRead  = 4
	accessWrite = 2
	accessExec  = 1
)

func pathError(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return "missing"
	case errors.Is(err, syscall.ENOTDIR):
		return "not_dir"
	case errors.Is(err, fs.ErrPermission):
		return "denied"
	}
	return "error"
}

// fileProblem 把读路径出的错换成带代号的（pathError：missing、not_dir、denied、error），核心的 files.ls、files.read 答出错时带上它。
func fileProblem(err error) error { return codedError{code: pathError(err), err: err} }

// problemCode 是 fileProblem 给的代号。
func problemCode(err error) string {
	var coded codedError
	if errors.As(err, &coded) {
		return coded.code
	}
	return "error"
}

// displayName：文件名里不是 UTF-8 的字节换成替代字符（这样的项只能看，进不去）。
func displayName(name string) string { return decodeString([]byte(name)) }

// listing 是一个目录的列表（ls、核心的 files.ls）：Path 是规整过的路径，Total 是一共几项，Entries 只有前 maxList 项（Truncated）。
type listing struct {
	Path      string   `json:"path"`
	Total     int      `json:"total"`
	Truncated bool     `json:"truncated"`
	Entries   []lsItem `json:"entries"`
}

// lsItem 是目录里的一项：Kind 是 dir、file、link、other；Dir 为真的能点进去（指向目录的符号链接也算）；Size 是普通文件的大小
// （符号链接取它指向的）；Mtime 是修改时间（秒）；Target 是符号链接的目标，Broken 是它指向的不在了。
type lsItem struct {
	Name   string `json:"name"`
	Dir    bool   `json:"dir"`
	Kind   string `json:"kind"`
	Size   int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	Target string `json:"target,omitempty"`
	Broken bool   `json:"broken,omitempty"`
}

// cmdLs 列目录（listPath）：第一行概要，之后一行一项；读不了时只有第一行，带 error。
func cmdLs(o *emitter, path string) {
	l, err := listPath(path)
	if err != nil {
		o.out(obj{"type": "zeta_ls", "path": normPath(path), "error": problemCode(err)})
		return
	}
	o.out(obj{"type": "zeta_ls", "path": l.Path, "total": l.Total, "truncated": l.Truncated})
	for _, item := range l.Entries {
		o.out(item)
	}
}

// listPath 列目录。先只取名字和类型排好序（目录在前，再按名字不分大小写），再给前 maxList 项取大小和时间：
// 几万个文件的目录也不会输出太多。读不了时是 fileProblem。
func listPath(path string) (listing, error) {
	path = normPath(path)
	entries, err := listDir(path)
	if err != nil {
		return listing{}, fileProblem(err)
	}
	sort.Slice(entries, func(i, j int) bool {
		a, b := entries[i], entries[j]
		if a.dir != b.dir {
			return a.dir
		}
		if la, lb := strings.ToLower(a.name), strings.ToLower(b.name); la != lb {
			return la < lb
		}
		return a.name < b.name
	})
	l := listing{Path: path, Total: len(entries), Truncated: len(entries) > maxList, Entries: []lsItem{}}
	for _, entry := range entries[:min(len(entries), maxList)] {
		l.Entries = append(l.Entries, entry.describe(joinPath(path, entry.name)))
	}
	return l, nil
}

type lsEntry struct {
	name string
	dir  bool // 指向目录的符号链接也算目录，可以点进去
	link bool
	file bool
}

func listDir(path string) ([]lsEntry, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	list, err := f.ReadDir(-1)
	if err != nil {
		return nil, err
	}
	entries := make([]lsEntry, 0, len(list))
	for _, d := range list {
		e := lsEntry{name: d.Name(), link: d.Type()&fs.ModeSymlink != 0, file: d.Type().IsRegular()}
		if e.link {
			e.dir = isDirFollow(joinPath(path, e.name))
		} else {
			e.dir = d.IsDir()
		}
		entries = append(entries, e)
	}
	return entries, nil
}

// describe 是一项的详情：种类、大小（符号链接取它指向的）、修改时间（秒）、链接的目标。
func (e lsEntry) describe(full string) lsItem {
	item := lsItem{Name: displayName(e.name), Dir: e.dir, Kind: "other"}
	info, err := os.Lstat(full)
	if err != nil {
		return item
	}
	switch {
	case e.link:
		item.Kind = "link"
		target, err := os.Readlink(full)
		if err != nil {
			return item
		}
		item.Target = displayName(target)
		if followed, err := os.Stat(full); err == nil {
			info = followed // 链接本身的大小没有意义，取它指向的
		} else {
			item.Broken = true
		}
	case e.dir:
		item.Kind = "dir"
	case e.file:
		item.Kind = "file"
	}
	if info.Mode().IsRegular() {
		item.Size = info.Size()
	}
	item.Mtime = int64(mtimeOf(info))
	return item
}

// preview 是读一个文本文件开头的结果（read、核心的 files.read）：Size 是文件大小；看着像二进制时 Binary 为真、没有 Text；
// Truncated 是只读了开头。
type preview struct {
	Size      int64  `json:"size"`
	Truncated bool   `json:"truncated"`
	Text      string `json:"text"`
	Binary    bool   `json:"binary,omitempty"`
}

// previewLine 是 read 输出的一行。
type previewLine struct {
	Type string `json:"type"`
	preview
}

// cmdRead 输出一行 readPreview 的结果；读不了时那一行只带 error。
func cmdRead(o *emitter, path string, limit int64) {
	p, err := readPreview(path, limit)
	if err != nil {
		o.out(obj{"type": "zeta_read", "error": problemCode(err)})
		return
	}
	o.out(previewLine{"zeta_read", p})
}

// readPreview 读一个文本文件的开头，最多 limit 字节（1 字节到 4 MB）。看着像二进制的只报大小。读不了时是 fileProblem，
// 不是普通文件时代号是 not_file。
func readPreview(path string, limit int64) (preview, error) {
	limit = max(1, min(limit, maxRead))
	info, err := os.Stat(path)
	if err != nil {
		return preview{}, fileProblem(err)
	}
	if !info.Mode().IsRegular() {
		return preview{}, codedError{code: "not_file", err: errors.New(path + " is not a regular file")}
	}
	data, err := readHead(path, int(limit)+1)
	if err != nil {
		return preview{}, fileProblem(err)
	}
	truncated := len(data) > int(limit)
	data = data[:min(len(data), int(limit))]
	text, ok := "", false
	if bytes.IndexByte(data, 0) < 0 {
		text, ok = decodeText(data, truncated), true
	}
	// 没有 NUL 的二进制文件也常见：解出来一堆控制字符或替代字符的，同样当二进制
	if ok && text != "" && oddChars(text)*20 > utf8.RuneCountInString(text) {
		ok = false
	}
	if !ok {
		return preview{Size: info.Size(), Binary: true}, nil
	}
	return preview{Size: info.Size(), Truncated: truncated, Text: text}, nil
}

func readHead(path string, n int) ([]byte, error) {
	f, err := openFile(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	buf := make([]byte, n)
	got, err := io.ReadFull(f, buf)
	if err == io.EOF || err == io.ErrUnexpectedEOF {
		err = nil
	}
	return buf[:got], err
}

func oddChars(text string) int {
	odd := 0
	for _, c := range text {
		if c == utf8.RuneError || (c < ' ' && !strings.ContainsRune("\t\n\r\f\v\b\x1b", c)) {
			odd++
		}
	}
	return odd
}

// decodeText 先按 UTF-8；截断处切开的半个字不算错；不是 UTF-8 再试 GB18030（中文 Windows 那边来的文件多半是这个）；
// 都不是就按 UTF-8 把坏字节换成替代字符。
func decodeText(data []byte, truncated bool) string {
	bad := firstInvalidUTF8(data)
	if bad < 0 {
		return string(data)
	}
	if truncated && bad >= len(data)-3 {
		return string(data[:bad])
	}
	if text, ok := decodeGB18030(data); ok {
		return text
	}
	return string(fixUTF8(data))
}

var octalEscape = regexp.MustCompile(`\\([0-7]{3})`)

// mountPoints 是本进程看到的挂载点（mountinfo 第 5 列，空格等字符写成 \ooo）。
// 共享文件夹和 /workspace 往往在同一个分区上，看设备号认不出这种绑定挂载，只能查这里。
func mountPoints(mountinfo string) map[string]bool {
	points := map[string]bool{}
	data, err := os.ReadFile(mountinfo)
	if err != nil {
		return points
	}
	for _, line := range strings.Split(decodeString(data), "\n") {
		fields := strings.Split(line, " ")
		if len(fields) > 4 {
			points[octalEscape.ReplaceAllStringFunc(fields[4], func(m string) string {
				n, _ := strconv.ParseUint(m[1:], 8, 32)
				return string(rune(n))
			})] = true
		}
	}
	return points
}

func cmdMounts(o *emitter, names []string) {
	points := mountPoints("/proc/self/mountinfo")
	for _, name := range names {
		p := joinPath(mountDir, name)
		o.out(obj{"name": name, "mounted": points[p], "read": syscall.Access(p, accessRead|accessExec) == nil, "write": syscall.Access(p, accessWrite) == nil})
	}
}
