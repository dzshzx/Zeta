package main

import (
	"bytes"
	"fmt"
	"os"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// 存储页的各类：每类列出能删的候选，规矩见 storage.go。

var (
	uploadName   = regexp.MustCompile(`^[0-9a-f]{8}$`)
	snapshotName = regexp.MustCompile(`^snapshot-[A-Za-z0-9]+-([0-9]{10,16})-[A-Za-z0-9]+(?:-[A-Za-z0-9._-]+)?\.sh$`)
	pipName      = regexp.MustCompile(`^pip[0-9.]*$`)
)

// planZetaFiles：应用送进来的附件。一小时内的、进行中的对话用到的不删。
func planZetaFiles(ctx *storageCtx) *plan {
	p := newPlan(ctx)
	root := ctx.e.uploads
	used := ctx.uploadsInUse()
	for _, name := range children(root) {
		u := p.count(joinPath(root, name))
		if uploadName.MatchString(name) && !used[name] && ctx.now-u.newest >= uploadKeepSeconds {
			p.add(root, name, true, u.bytes, "")
		}
	}
	return p
}

// planZetaHosts：后台对话的输出目录，进程和接入脚本都已经不在的。
func planZetaHosts(ctx *storageCtx) *plan {
	p := newPlan(ctx)
	root := ctx.e.hosts
	for _, name := range children(root) {
		path := joinPath(root, name)
		u := p.count(path)
		if ctx.now-u.newest >= freshSeconds && !hostAlive(path) {
			p.add(root, name, true, u.bytes, "")
		}
	}
	return p
}

// hostAlive：Zeta 托管的一个对话还在：Claude Code 进程活着，或者应用那头的接入脚本还连着。
func hostAlive(dir string) bool {
	pid, known := readPid(joinPath(dir, "pid"))
	if _, alive := claudeArgv(pid, known); alive {
		return true
	}
	attach, known := readPid(joinPath(dir, "attach"))
	if !known || attach == 0 {
		return false
	}
	argv, ok := procArgv(attach)
	if !ok {
		return false
	}
	for _, a := range argv {
		if string(a) == "zeta-host" {
			return true
		}
	}
	return false
}

// residuePlaces 是 Claude Code 按会话 id 存的东西：~/.claude 下的目录、名字的样子（第一组是会话 id）、是不是目录。
var residuePlaces = []struct {
	place   string
	pattern *regexp.Regexp
	dir     bool
}{
	{"file-history", regexp.MustCompile(`^(` + uuidText + `)$`), true},
	{"session-env", regexp.MustCompile(`^(` + uuidText + `)$`), true},
	{"tasks", regexp.MustCompile(`^(` + uuidText + `)$`), true},
	{"uploads", regexp.MustCompile(`^(` + uuidText + `)$`), true},
	{"todos", regexp.MustCompile(`^(` + uuidText + `)(?:-agent-[0-9a-f-]+)?\.json$`), false},
}

// planResidue：已经没有会话记录的会话留下的东西。只算残留，不算还有记录的会话的。
func planResidue(ctx *storageCtx) *plan {
	p := newPlan(ctx)
	consider := func(root, name, session string, dir bool, part string) {
		path := joinPath(root, name)
		if ctx.transcripts[session] || !lexists(path) {
			return
		}
		u := p.count(path)
		if !ctx.keep[session] && ctx.now-u.newest >= freshSeconds {
			p.add(root, name, dir, u.bytes, part)
		}
	}
	for _, place := range residuePlaces {
		root := joinPath(ctx.e.claude, place.place)
		for _, name := range children(root) {
			if m := place.pattern.FindStringSubmatch(name); m != nil {
				consider(root, name, m[1], place.dir, place.place)
			}
		}
	}
	// 项目目录里记录已经不在的同名目录（工具结果、子代理记录等）
	for _, project := range children(ctx.e.root) {
		root := joinPath(ctx.e.root, project)
		if !isRealDir(root) {
			continue
		}
		for _, name := range children(root) {
			if uuidName.MatchString(name) {
				consider(root, name, name, true, "projects")
			}
		}
	}
	return p
}

// planNamed：~/.claude/<place> 里名字合 pattern 的项。名字里带会话 id 的（第一组），那个会话在进行就不删；
// liveByTime 为真时，最早那个活着的进程启动以后建的也不删（看不出属于哪个进程的东西）。
func planNamed(ctx *storageCtx, place string, pattern *regexp.Regexp, dir, liveByTime bool) *plan {
	p := newPlan(ctx)
	root := joinPath(ctx.e.claude, place)
	for _, name := range children(root) {
		u := p.count(joinPath(root, name))
		m := pattern.FindStringSubmatch(name)
		if m == nil || (len(m) > 1 && ctx.keep[m[1]]) {
			continue
		}
		if ctx.now-u.newest < freshSeconds || (liveByTime && ctx.maybeLive(u.newest)) {
			continue
		}
		p.add(root, name, dir, u.bytes, "")
	}
	return p
}

// planSnapshots：Shell 快照，每个 Claude Code 进程一份，执行命令前先载入它。不删还活着的进程可能在用的（建于最早那个进程启动之后）。
func planSnapshots(ctx *storageCtx) *plan {
	p := newPlan(ctx)
	root := joinPath(ctx.e.claude, "shell-snapshots")
	for _, name := range children(root) {
		u := p.count(joinPath(root, name))
		m := snapshotName.FindStringSubmatch(name)
		if m == nil {
			continue
		}
		created, _ := strconv.ParseInt(m[1], 10, 64)
		if !ctx.maybeLive(float64(created)/1000) && !ctx.maybeLive(u.newest) {
			p.add(root, name, false, u.bytes, "")
		}
	}
	return p
}

// runningTool：有没有 HOME 和本进程相同的进程合 match（参数是命令行各段）：包管理器的缓存在 HOME 下，别的 HOME 的进程用不到这一份。
// maxAge 大于 0 时只看这么多秒内启动的。
func (ctx *storageCtx) runningTool(match func([]string) bool, maxAge float64) bool {
	home := realPath(ctx.e.tilde())
	for _, pid := range procIDs() {
		argv, ok := procArgv(pid)
		if !ok || len(argv) == 0 || len(argv[0]) == 0 {
			continue
		}
		var args []string
		for _, a := range argv {
			if len(a) > 0 {
				args = append(args, decodeString(a))
			}
		}
		if !match(args) {
			continue
		}
		if h, ok := procHome(pid); !ok || h != home {
			continue
		}
		if maxAge <= 0 {
			return true
		}
		if started, ok := procStarted(pid); ok && float64(time.Now().UnixNano())/1e9-started < maxAge {
			return true
		}
	}
	return false
}

// commandNames 是命令行前两段的文件名（node、python 起的脚本，名字在第二段）；node 改过进程标题时第一段是“npm install …”，取第一个词。
func commandNames(argv []string) []string {
	var names []string
	for _, a := range argv[:min(len(argv), 2)] {
		first, _, _ := strings.Cut(a, " ")
		names = append(names, baseName(first))
	}
	return names
}

func isNpm(argv []string) bool {
	for _, n := range commandNames(argv) {
		switch n {
		case "npm", "npx", "npm-cli.js", "npx-cli.js":
			return true
		}
	}
	return false
}

func isPip(argv []string) bool {
	names := commandNames(argv)
	for _, n := range names {
		if pipName.MatchString(n) {
			return true
		}
	}
	if contains(window(argv, 1, 3), "-m") && contains(window(argv, 1, 4), "pip") {
		return true
	}
	return len(names) > 0 && names[0] == "uv" && contains(window(argv, 1, 3), "pip")
}

// window 是 s[from:to]，越界的部分不算。
func window(s []string, from, to int) []string {
	return s[min(from, len(s)):min(to, len(s))]
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

// planHomeCache：家目录下的一个包管理器缓存，directory 下 names 里的项（nil 表示全部）能删；包管理器在跑时整类不删。
func planHomeCache(ctx *storageCtx, directory string, names []string, busy func() bool, busyName string) *plan {
	p := newPlan(ctx)
	for _, name := range children(directory) {
		u := p.count(joinPath(directory, name))
		if names == nil || contains(names, name) {
			p.add(directory, name, isRealDir(joinPath(directory, name)), u.bytes, "")
		}
	}
	if len(p.items) > 0 && busy() {
		p.busy = busyName
	}
	return p
}

// planNpm：_cacache 是下载缓存（npm cache clean 删的就是它），_logs 是日志；_npx 里是 npx 装的包，MCP 服务可能正从那里运行，不删。
func planNpm(ctx *storageCtx) *plan {
	return planHomeCache(ctx, ctx.e.home+"/.npm", []string{"_cacache", "_logs"},
		func() bool { return ctx.runningTool(isNpm, toolBusySeconds) }, "npm")
}

func planPip(ctx *storageCtx) *plan {
	return planHomeCache(ctx, ctx.e.home+"/.cache/pip", nil, func() bool { return ctx.runningTool(isPip, 0) }, "pip")
}

// planApt：apt 下载的软件包和包索引缓存（apt-get clean 删的那些）。要 root 删，由应用以 root 执行 apt-get clean，这里只统计。
func planApt(ctx *storageCtx) *plan {
	p := newPlan(ctx)
	root := ctx.e.aptRoot
	for _, name := range children(root) {
		u := p.count(joinPath(root, name))
		if _, ok := safeChild(root, name, false); ok && strings.HasSuffix(name, ".bin") {
			p.items = append(p.items, candidate{root, name, false, u.bytes})
		}
	}
	archives := joinPath(root, "archives")
	for _, name := range children(archives) {
		if _, ok := safeChild(archives, name, false); ok && strings.HasSuffix(name, ".deb") {
			p.items = append(p.items, candidate{archives, name, false, measure(joinPath(archives, name), map[inode]bool{}).bytes})
		}
	}
	return p
}

// openPaths 是本用户的进程打开着、当作工作目录、或映射进内存的，在 root 下面的路径。
func openPaths(root string) map[string]bool {
	prefix := strings.TrimRight(realPath(root), "/") + "/"
	paths := map[string]bool{}
	for _, pid := range procIDs() {
		base := "/proc/" + strconv.Itoa(pid)
		fds, err := readNames(base + "/fd")
		if err != nil {
			continue
		}
		links := []string{base + "/cwd"}
		for _, fd := range fds {
			links = append(links, base+"/fd/"+fd)
		}
		for _, link := range links {
			if target, err := os.Readlink(link); err == nil && strings.HasPrefix(target, prefix) {
				paths[target] = true
			}
		}
		mappedPaths(base+"/maps", prefix, paths)
	}
	return paths
}

// mappedPaths 把 maps 里映射的文件中在 prefix 下面的记进 paths。
func mappedPaths(maps, prefix string, paths map[string]bool) {
	data, err := os.ReadFile(maps)
	if err != nil {
		return
	}
	for _, line := range bytes.Split(data, []byte{'\n'}) {
		if name, ok := mappedName(decodeString(line)); ok && strings.HasPrefix(name, prefix) {
			paths[name] = true
		}
	}
}

// mappedName 是 maps 一行的第 6 列（映射的文件，可能含空格）；没有这一列时 ok 为假。
func mappedName(line string) (string, bool) {
	rest := line
	for i := 0; i < 5; i++ {
		rest = strings.TrimLeft(rest, " \t\v\f\r")
		end := strings.IndexAny(rest, " \t\v\f\r")
		if end < 0 {
			return "", false
		}
		rest = rest[end:]
	}
	rest = strings.TrimLeft(rest, " \t\v\f\r")
	return rest, rest != ""
}

// /tmp 里常驻服务的目录（tmux、ssh-agent 的套接字，运行时目录，Java 进程的性能数据），即使一天没动也不删
var tmpServicePrefixes = []string{".", "tmux-", "ssh-", "runtime-", "systemd-", "hsperfdata_"}

// tmpPlanner 是统计 /tmp 时的状态。
type tmpPlanner struct {
	*plan
	inUse map[string]bool
	uid   int
}

// planTmp：Debian 的 /tmp。本用户的、一天没动过的、没有进程在用（打开、当工作目录、映射）的、里面没有套接字和管道的。
// Claude Code 自己的 claude-<uid>/<工作目录>/<会话 id> 按会话看：会话在进行就不删。
func planTmp(ctx *storageCtx) *plan {
	root := storageTmp()
	t := &tmpPlanner{plan: newPlan(ctx), inUse: openPaths(root), uid: os.Getuid()}
	mine := fmt.Sprintf("claude-%d", t.uid)
	for _, name := range children(root) {
		path := joinPath(root, name)
		switch {
		case name == mine && isRealDir(path):
			t.claudeDir(path)
		case hasAnyPrefix(name, tmpServicePrefixes):
			t.count(path)
		default:
			t.removable(root, name, "other")
		}
	}
	if isLink(root) || realPath(root) == "/" {
		t.items, t.prune = nil, nil
	}
	return t.plan
}

func hasAnyPrefix(s string, prefixes []string) bool {
	for _, p := range prefixes {
		if strings.HasPrefix(s, p) {
			return true
		}
	}
	return false
}

// claudeDir 看 claude-<uid> 下的各工作目录：在进行的会话不删；里面的都要删、自己也一天没变过（目录的修改时刻随增删项变）的工作目录，
// 删完把空目录也去掉。
func (t *tmpPlanner) claudeDir(claude string) {
	for _, project := range children(claude) {
		parent := joinPath(claude, project)
		if !isRealDir(parent) {
			t.count(parent)
			continue
		}
		entries := children(parent)
		before := len(t.items)
		for _, entry := range entries {
			if t.ctx.keep[entry] {
				t.count(joinPath(parent, entry))
			} else {
				t.removable(parent, entry, "claude")
			}
		}
		fi, err := os.Lstat(parent)
		if err != nil {
			continue
		}
		if ownerOf(fi) == t.uid && t.ctx.now-mtimeOf(fi) >= tmpIdleSeconds && len(t.items)-before == len(entries) {
			t.prune = append(t.prune, [2]string{claude, project})
		}
	}
}

func (t *tmpPlanner) removable(parent, name, part string) {
	path := joinPath(parent, name)
	fi, err := os.Lstat(path)
	if err != nil {
		return
	}
	u := t.count(path)
	if ownerOf(fi) != t.uid || u.special || t.ctx.now-u.newest < tmpIdleSeconds {
		return
	}
	real := joinPath(realPath(parent), name)
	for p := range t.inUse {
		if p == real || strings.HasPrefix(p, real+"/") {
			return
		}
	}
	t.add(parent, name, fi.IsDir(), u.bytes, part)
}

func ownerOf(fi os.FileInfo) int {
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		return int(st.Uid)
	}
	return -1
}
