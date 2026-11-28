package main

import (
	"errors"
	"io/fs"
	"os"
	"path"
	"regexp"
	"sort"
	"strings"
	"syscall"

	"github.com/dzshzx/Zeta/helper/agystore"
)

// joinPath 接上一段路径：后一段是绝对路径时取它，前一段以斜杠结尾时不再加斜杠。不做别的规整，
// 输出里的路径和调用方给的写法一致。
func joinPath(a, b string) string {
	if strings.HasPrefix(b, "/") {
		return b
	}
	if a == "" || strings.HasSuffix(a, "/") {
		return a + b
	}
	return a + "/" + b
}

func baseName(p string) string { return p[strings.LastIndexByte(p, '/')+1:] }

// dirName：最后一个斜杠之前的部分，去掉末尾多余的斜杠（全是斜杠时保留）。
func dirName(p string) string {
	head := p[:strings.LastIndexByte(p, '/')+1]
	if head != "" && strings.Trim(head, "/") != "" {
		head = strings.TrimRight(head, "/")
	}
	return head
}

// normPath 按字面规整路径（不看符号链接）。POSIX 规定开头恰好两个斜杠的含义由实现决定，原样保留。
func normPath(p string) string {
	c := path.Clean(p)
	if strings.HasPrefix(p, "//") && !strings.HasPrefix(p, "///") {
		if c == "/" {
			return "//"
		}
		return "/" + c
	}
	return c
}

func absPath(p string) string {
	if !strings.HasPrefix(p, "/") {
		if wd, err := os.Getwd(); err == nil {
			p = joinPath(wd, p)
		}
	}
	return normPath(p)
}

// realPath 求真实路径：逐段解开符号链接；不存在的部分、读不了的链接和链接成环的地方按字面接上，不报错。
// 安全检查（会话记录在不在 ~/.claude/projects 里、要删的东西是不是在预期的那一层）都靠它。
func realPath(name string) string {
	r := resolver{seen: map[string]resolvedLink{}}
	if strings.HasPrefix(name, "/") {
		r.path = "/"
	} else if wd, err := os.Getwd(); err == nil {
		r.path = wd
	} else {
		r.path = "/"
	}
	r.push(strings.Split(strings.TrimRight(name, "/"), "/"))
	for r.count > 0 {
		r.step()
	}
	return r.path
}

type resolvedLink struct {
	path string
	done bool
}

// resolver 是 realPath 的状态：待处理的各段（倒序的栈，解开链接时插入“这条链接解完了”的记号）。
type resolver struct {
	path  string
	stack []resolverItem
	count int // 栈里待处理的段数（不含记号）
	seen  map[string]resolvedLink
}

type resolverItem struct {
	part   string
	marker bool // 记号：下面一项是链接本身的路径，弹到它时说明那条链接已解完
}

func (r *resolver) push(parts []string) {
	for i := len(parts) - 1; i >= 0; i-- {
		r.stack = append(r.stack, resolverItem{part: parts[i]})
	}
	r.count += len(parts)
}

func (r *resolver) pop() resolverItem {
	it := r.stack[len(r.stack)-1]
	r.stack = r.stack[:len(r.stack)-1]
	return it
}

func (r *resolver) step() {
	it := r.pop()
	if it.marker {
		r.seen[r.pop().part] = resolvedLink{path: r.path, done: true}
		return
	}
	r.count--
	switch it.part {
	case "", ".":
		return
	case "..":
		r.path = r.path[:strings.LastIndexByte(r.path, '/')]
		if r.path == "" {
			r.path = "/"
		}
		return
	}
	next := r.path + "/" + it.part
	if r.path == "/" {
		next = "/" + it.part
	}
	fi, err := os.Lstat(next)
	if err != nil || fi.Mode()&fs.ModeSymlink == 0 {
		r.path = next
		return
	}
	if link, ok := r.seen[next]; ok {
		// 解过的链接直接用结果；还没解完又遇到它，说明链接成环：按字面接上
		r.path = next
		if link.done {
			r.path = link.path
		}
		return
	}
	target, err := os.Readlink(next)
	if err != nil {
		r.path = next
		return
	}
	if strings.HasPrefix(target, "/") {
		r.path = "/"
	}
	r.seen[next] = resolvedLink{}
	r.stack = append(r.stack, resolverItem{part: next}, resolverItem{marker: true})
	r.push(strings.Split(target, "/"))
}

func lexists(p string) bool {
	_, err := os.Lstat(p)
	return err == nil
}

// isDirFollow：是目录（跟着符号链接看）。
func isDirFollow(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}

// isRealDir：是目录本身，不是指向目录的符号链接。
func isRealDir(p string) bool {
	fi, err := os.Lstat(p)
	return err == nil && fi.IsDir()
}

func isLink(p string) bool {
	fi, err := os.Lstat(p)
	return err == nil && fi.Mode()&fs.ModeSymlink != 0
}

func isRegularFollow(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.Mode().IsRegular()
}

// readNames 按目录本身的顺序列出名字（不排序）；出错时给出已读到的部分和错误。
func readNames(dir string) ([]string, error) {
	f, err := os.Open(dir)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	return f.Readdirnames(-1)
}

// children 是目录里的全部名字，按字典序；读不了的目录当作空的。
func children(dir string) []string {
	names, err := readNames(dir)
	if err != nil {
		return nil
	}
	sort.Strings(names)
	return names
}

// globNames 列目录里不以点开头、合 match 的名字，按目录本身的顺序（同 shell 通配）；dirsOnly 时只要目录（跟着链接看）。
func globNames(dir string, dirsOnly bool, match func(string) bool) []string {
	names, _ := readNames(dir)
	var out []string
	for _, n := range names {
		if strings.HasPrefix(n, ".") || (match != nil && !match(n)) {
			continue
		}
		if dirsOnly && !isDirFollow(joinPath(dir, n)) {
			continue
		}
		out = append(out, n)
	}
	return out
}

func agyDirFromTranscript(p string) (agyDir, id string, ok bool) {
	return agystore.ParseTranscriptPath(p)
}

func sessionIDOfPath(p string) string {
	if _, id, ok := agyDirFromTranscript(p); ok {
		return id
	}
	return dropRunes(baseName(p), 6)
}

// sessionFiles 是全部会话记录（~/.claude/projects/*/*.jsonl 与 agy 顶层 transcript.jsonl）。
func (e *env) sessionFiles() []string {
	var files []string
	for _, project := range globNames(e.root, true, nil) {
		dir := joinPath(e.root, project)
		for _, name := range globNames(dir, false, func(n string) bool { return strings.HasSuffix(n, ".jsonl") }) {
			files = append(files, joinPath(dir, name))
		}
	}
	files = append(files, agystore.ListedTranscriptFiles(e.agy)...)
	return files
}

// sessionFilesNamed 是各项目里叫 <id>.jsonl 的记录（存在就算，包括断了的链接）以及对应的 agy transcript.jsonl。
func (e *env) sessionFilesNamed(id string) []string {
	var files []string
	for _, project := range globNames(e.root, true, nil) {
		p := joinPath(joinPath(e.root, project), id+".jsonl")
		if lexists(p) {
			files = append(files, p)
		}
	}
	if agystore.IsSessionID(id) {
		if ap := agystore.TranscriptPath(e.agy, id); lexists(ap) {
			files = append(files, ap)
		}
	}
	return files
}

// sessionIDs 是全部会话记录的 id（Claude 与 agy）。
func (e *env) sessionIDs() map[string]bool {
	ids := map[string]bool{}
	for _, p := range e.sessionFiles() {
		ids[sessionIDOfPath(p)] = true
	}
	return ids
}

// mtimeOf 是修改时刻（秒，浮点）。浮点的算法定死（整秒加纳秒乘 1e-9），排序和换算成毫秒时与按 stat 浮点时间写的旧版一致。
func mtimeOf(fi fs.FileInfo) float64 {
	t := fi.ModTime()
	return float64(t.Unix()) + float64(float64(t.Nanosecond())*1e-9)
}

// mtimeFollow 是 path 指向的东西的修改时刻；取不到时为 0。
func mtimeFollow(p string) float64 {
	fi, err := os.Stat(p)
	if err != nil {
		return 0
	}
	return mtimeOf(fi)
}

// sortNewestFirst 按修改时刻从新到旧排（时刻相同的保持原来的先后）。
func sortNewestFirst(paths []string) {
	times := make(map[string]float64, len(paths))
	for _, p := range paths {
		times[p] = mtimeFollow(p)
	}
	sort.SliceStable(paths, func(i, j int) bool { return times[paths[i]] > times[paths[j]] })
}

func millis(seconds float64) int64 { return int64(seconds * 1000) }

// openFile 打开一个文件来读；是目录时也算打不开。
func openFile(p string) (*os.File, error) {
	f, err := os.Open(p)
	if err != nil {
		return nil, err
	}
	if fi, err := f.Stat(); err == nil && fi.IsDir() {
		f.Close()
		return nil, &fs.PathError{Op: "open", Path: p, Err: syscall.EISDIR}
	}
	return f, nil
}

// safeChild 判断 root 下名叫 name 的一项能不能删：只认 root 的直接下一层；它自己和 root 都不是符号链接；
// 种类和预期一致（dir 为真要真目录，否则要普通文件）；真实路径就是 root 的真实路径下的 name。能删返回真实路径。
func safeChild(root, name string, dir bool) (string, bool) {
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\x00") {
		return "", false
	}
	if isLink(root) || !isDirFollow(root) {
		return "", false
	}
	p := joinPath(root, name)
	fi, err := os.Lstat(p)
	if err != nil {
		return "", false
	}
	mode := fi.Mode()
	if mode&fs.ModeSymlink != 0 || (dir && !mode.IsDir()) || (!dir && !mode.IsRegular()) {
		return "", false
	}
	real := realPath(p)
	if real != joinPath(realPath(root), name) {
		return "", false
	}
	return real, true
}

var sessionIDName = regexp.MustCompile(`^[0-9a-fA-F-]{8,64}$`)

// sessionDir 是会话记录 <项目>/<会话 id>.jsonl 旁边 Claude Code 自己建的同名目录（tool-results、subagents 等）。
// 只认 ~/.claude/projects/<项目>/<会话 id>/ 这一层，项目目录和它都按 safeChild 核，记录文件本身也得真在这个项目目录里。
func (e *env) sessionDir(p string) (string, bool) {
	if !strings.HasSuffix(p, ".jsonl") {
		return "", false
	}
	project := dirName(absPath(p))
	name := strings.TrimSuffix(baseName(p), ".jsonl")
	if !sessionIDName.MatchString(name) || dirName(realPath(project)) != realPath(e.root) {
		return "", false
	}
	real, ok := safeChild(project, name, true)
	if !ok || dirName(realPath(p)) != dirName(real) {
		return "", false
	}
	return real, true
}

// insideRoot：真实路径在 ~/.claude/projects 里（以 .jsonl 结尾），或是 e.agy 下的有效会话 transcript.jsonl。
func (e *env) insideRoot(p string) bool {
	real := realPath(p)
	if strings.HasPrefix(real, realPath(e.root)+"/") && strings.HasSuffix(real, ".jsonl") {
		return true
	}
	agyDir, _, ok := agyDirFromTranscript(real)
	return ok && agyDir == realPath(e.agy)
}

// removeTree 删一个真目录和里面的一切，不跟进符号链接（os.RemoveAll 按文件描述符逐层删）；能删多少删多少，不报错。
// p 本身是符号链接或不是目录时不动。
func removeTree(p string) {
	if !isRealDir(p) {
		return
	}
	_ = os.RemoveAll(p)
}

// unlinkFile 删一个文件（不删目录）。
func unlinkFile(p string) error {
	if err := syscall.Unlink(p); err != nil {
		return &fs.PathError{Op: "unlink", Path: p, Err: err}
	}
	return nil
}

func isNotExist(err error) bool { return errors.Is(err, fs.ErrNotExist) }

// writeFileAtomic 先写 path.tmp 再改名成 path：读的一方看到的是旧的或新的整份，不会是写了一半的。
func writeFileAtomic(path string, data []byte) error {
	if err := os.WriteFile(path+".tmp", data, 0o666); err != nil {
		return err
	}
	return os.Rename(path+".tmp", path)
}
