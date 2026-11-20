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

// displayName：文件名里不是 UTF-8 的字节换成替代字符（这样的项只能看，进不去）。
func displayName(name string) string { return decodeString([]byte(name)) }

// cmdLs 列目录。先只取名字和类型排好序（目录在前，再按名字不分大小写），再给前 maxList 项取大小和时间：
// 几万个文件的目录也不会输出太多。
func cmdLs(o *emitter, path string) {
	path = normPath(path)
	entries, err := listDir(path)
	if err != nil {
		o.out(obj{"type": "zeta_ls", "path": path, "error": pathError(err)})
		return
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
	o.out(obj{"type": "zeta_ls", "path": path, "total": len(entries), "truncated": len(entries) > maxList})
	for _, entry := range entries[:min(len(entries), maxList)] {
		o.out(entry.describe(joinPath(path, entry.name)))
	}
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
func (e lsEntry) describe(full string) obj {
	item := obj{"name": displayName(e.name), "dir": e.dir, "kind": "other", "size": 0, "mtime": 0}
	info, err := os.Lstat(full)
	if err != nil {
		return item
	}
	switch {
	case e.link:
		item["kind"] = "link"
		target, err := os.Readlink(full)
		if err != nil {
			return item
		}
		item["target"] = displayName(target)
		if followed, err := os.Stat(full); err == nil {
			info = followed // 链接本身的大小没有意义，取它指向的
		} else {
			item["broken"] = true
		}
	case e.dir:
		item["kind"] = "dir"
	case e.file:
		item["kind"] = "file"
	}
	if info.Mode().IsRegular() {
		item["size"] = info.Size()
	}
	item["mtime"] = int64(mtimeOf(info))
	return item
}

// cmdRead 读一个文本文件的开头，最多 limit 字节（1 字节到 4 MB）。看着像二进制的只报大小。
func cmdRead(o *emitter, path string, limit int64) {
	limit = max(1, min(limit, maxRead))
	info, err := os.Stat(path)
	if err != nil {
		o.out(obj{"type": "zeta_read", "error": pathError(err)})
		return
	}
	if !info.Mode().IsRegular() {
		o.out(obj{"type": "zeta_read", "error": "not_file"})
		return
	}
	data, err := readHead(path, int(limit)+1)
	if err != nil {
		o.out(obj{"type": "zeta_read", "error": pathError(err)})
		return
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
		o.out(obj{"type": "zeta_read", "size": info.Size(), "binary": true})
		return
	}
	o.out(obj{"type": "zeta_read", "size": info.Size(), "truncated": truncated, "text": text})
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
