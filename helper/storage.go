package main

import (
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"os"
	"regexp"
	"sort"
	"strings"
	"syscall"
	"time"
)

// 存储：设置里“存储”页的统计和清理
//
// 统计只看大小和个数，不读文件内容（只有两处例外：在进行中的会话记录里找它用到的附件目录，在 settings.json 里取 cleanupPeriodDays）。
// 清理只删各类计划列出的候选，删之前每一项再照 safeChild 核一遍，规矩同 sessionDir：只认预期的那一层、
// 不跟符号链接、真实路径落在预期目录下。正在进行的对话一律不碰：Claude Code 进程还活着的会话（终端里的、
// 两个 Zeta 包的都算）加上应用传进来的。会话记录本身（<项目>/<id>.jsonl）不在任何一类里，删会话走 delete。

const (
	freshSeconds      = 600   // 十分钟内动过的不删：可能正在写
	uploadKeepSeconds = 3600  // 附件一小时内的不删：可能还在输入框里，没发出去
	tmpIdleSeconds    = 86400 // /tmp 里一天内动过的不删
	toolBusySeconds   = 600   // 十分钟内启动的 npm 进程可能正在往缓存里写
	uuidText          = `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`
)

var (
	uuidName      = regexp.MustCompile(`^` + uuidText + `$`)
	sessionInArgv = regexp.MustCompile(`"session_id":"(` + uuidText + `)"`)
	cwdField      = regexp.MustCompile(`"cwd":("(?:[^"\\]|\\.)*")`)
)

// storageTmp 是 Debian 的 /tmp。测试经 ZETA_STORAGE_TEST_TMP 换成临时目录：名字必须以 zeta-test-tmp 开头，设错了也落不到别处。
func storageTmp() string {
	test := os.Getenv("ZETA_STORAGE_TEST_TMP")
	if strings.HasPrefix(test, "/") && strings.HasPrefix(baseName(strings.TrimRight(test, "/")), "zeta-test-tmp") {
		return test
	}
	return "/tmp"
}

// inode 认硬链接：同一个文件只算一次。
type inode struct{ dev, ino uint64 }

// usage 是 path 占的磁盘空间（不跟符号链接），算法同 du：按块计，硬链接只算一次（seen 记着算过的）。
type usage struct {
	bytes, files int64
	newest       float64 // 最近一次修改的时刻
	special      bool    // 里面有套接字、管道之类的特殊文件
}

func measure(path string, seen map[inode]bool) usage {
	var u usage
	stack := []string{path}
	for len(stack) > 0 {
		current := stack[len(stack)-1]
		stack = stack[:len(stack)-1]
		fi, err := os.Lstat(current)
		if err != nil {
			continue
		}
		st, _ := fi.Sys().(*syscall.Stat_t)
		mode := fi.Mode()
		if st != nil && uint64(st.Nlink) > 1 && !mode.IsDir() {
			key := inode{uint64(st.Dev), st.Ino}
			if seen[key] {
				continue
			}
			seen[key] = true
		}
		if st != nil {
			u.bytes += st.Blocks * 512
		}
		u.newest = max(u.newest, mtimeOf(fi))
		switch {
		case mode.IsDir():
			names, _ := readNames(current)
			for _, n := range names {
				stack = append(stack, joinPath(current, n))
			}
		case mode.IsRegular():
			u.files++
		case mode&fs.ModeSymlink == 0:
			u.special = true
		}
	}
	return u
}

// storageCtx 是一次统计或清理共用的信息：要保护的会话、还有记录的会话、还活着的 Claude Code 进程里最早的启动时刻。
type storageCtx struct {
	e           *env
	now         float64
	seen        map[inode]bool
	keep        map[string]bool
	oldest      float64
	hasOldest   bool
	transcripts map[string]bool
}

func newStorageCtx(e *env, keep []string) *storageCtx {
	ctx := &storageCtx{e: e, now: float64(time.Now().UnixNano()) / 1e9, seen: map[inode]bool{}, keep: map[string]bool{},
		transcripts: e.sessionIDs()}
	for _, id := range keep {
		ctx.keep[id] = true
	}
	for _, r := range e.registeredSessions() {
		ctx.live(r.pid, r.session)
	}
	// Zeta 托管的对话，两个包的目录都看：登记文件还没写出来时也认得
	for _, dir := range e.allHostDirs() {
		pid, known := readPid(joinPath(dir, "pid"))
		if argv, alive := claudeArgv(pid, known); alive {
			ctx.live(pid, hostSession(dir, argv))
		}
	}
	return ctx
}

// allHostDirs 是 ~/.cache/zeta*/hosts/* ：两个包的托管目录。
func (e *env) allHostDirs() []string {
	cache := e.home + "/.cache"
	var dirs []string
	for _, z := range globNames(cache, true, func(n string) bool { return strings.HasPrefix(n, "zeta") }) {
		hosts := joinPath(joinPath(cache, z), "hosts")
		if !lexists(hosts) {
			continue
		}
		for _, name := range globNames(hosts, false, nil) {
			dirs = append(dirs, joinPath(hosts, name))
		}
	}
	return dirs
}

func (ctx *storageCtx) live(pid int, session any) {
	if s, ok := session.(string); ok && s != "" {
		ctx.keep[s] = true
	}
	if started, ok := procStarted(pid); ok && (!ctx.hasOldest || started < ctx.oldest) {
		ctx.oldest, ctx.hasOldest = started, true
	}
}

// maybeLive：moment 时刻建的东西可能属于还活着的进程（在最早那个进程启动之后），或者是刚写的。
func (ctx *storageCtx) maybeLive(moment float64) bool {
	if ctx.now-moment < freshSeconds {
		return true
	}
	return ctx.hasOldest && moment >= ctx.oldest-60
}

// hostSession 是托管对话的会话 id：命令行里 --resume 的，或者输出开头 init 里的 session_id。
func hostSession(dir string, argv [][]byte) any {
	for _, flag := range []string{"--resume", "--session-id"} {
		for i, a := range argv {
			if string(a) != flag {
				continue
			}
			if i < len(argv)-1 {
				if value := decodeString(argv[i+1]); uuidName.MatchString(value) {
					return value
				}
			}
			break
		}
	}
	f, err := openFile(joinPath(dir, "out.jsonl"))
	if err != nil {
		return nil
	}
	defer f.Close()
	head, err := readUpTo(f, 65536)
	if err != nil {
		return nil
	}
	if m := sessionInArgv.FindSubmatch(head); m != nil {
		return string(m[1])
	}
	return nil
}

// uploadsInUse 是进行中的会话的记录里提到的附件目录（~/.cache/<name>/files/<目录>/…）。
func (ctx *storageCtx) uploadsInUse() map[string]bool {
	pattern := regexp.MustCompile(`/\.cache/` + regexp.QuoteMeta(ctx.e.name) + `/files/([0-9a-f]{8})/`)
	used := map[string]bool{}
	for session := range ctx.keep {
		if !uuidName.MatchString(session) {
			continue
		}
		for _, path := range ctx.e.sessionFilesNamed(session) {
			scanChunks(path, pattern, used)
		}
	}
	return used
}

// scanChunks 按 1 MB 一块读文件找 pattern，块与块之间留 256 字节的重叠，跨块的也找得到。
func scanChunks(path string, pattern *regexp.Regexp, found map[string]bool) {
	f, err := openFile(path)
	if err != nil {
		return
	}
	defer f.Close()
	var tail []byte
	buf := make([]byte, 1<<20)
	for {
		n, err := io.ReadFull(f, buf)
		if n == 0 {
			return
		}
		data := append(tail, buf[:n]...)
		for _, m := range pattern.FindAllSubmatch(data, -1) {
			found[string(m[1])] = true
		}
		tail = append([]byte(nil), data[max(0, len(data)-256):]...)
		if err != nil {
			return
		}
	}
}

// candidate 是一个能删的候选：根目录下的一项，是不是目录，占多少。
type candidate struct {
	root, name string
	dir        bool
	size       int64
}

// plan 是一类东西：一共占多少，能删的候选，按来源分的小计。
type plan struct {
	ctx          *storageCtx
	bytes, files int64
	items        []candidate
	parts        map[string][2]int64
	busy         string      // 包管理器正在跑时整类不删：它的名字
	prune        [][2]string // 清完以后试着删掉的空目录（根目录, 名字）
}

func newPlan(ctx *storageCtx) *plan { return &plan{ctx: ctx, parts: map[string][2]int64{}} }

func (p *plan) count(path string) usage {
	u := measure(path, p.ctx.seen)
	p.bytes += u.bytes
	p.files += u.files
	return u
}

func (p *plan) add(root, name string, dir bool, size int64, part string) {
	if _, ok := safeChild(root, name, dir); !ok {
		return
	}
	p.items = append(p.items, candidate{root, name, dir, size})
	if part != "" {
		total := p.parts[part]
		p.parts[part] = [2]int64{total[0] + size, total[1] + 1}
	}
}

// storageKind 是存储页的一类（storage 的 zeta_storage 行、核心的 storage.scan 的 kinds）：一共占多少、几个文件，能清掉多少、几项，
// 按来源的小计；Busy 是正在运行、这一类先不清的工具的名字（没有时为 null）；Error 是这一类没统计成（别的字段都是 0）。
type storageKind struct {
	Key      string        `json:"key"`
	Bytes    int64         `json:"bytes"`
	Files    int64         `json:"files"`
	Freeable int64         `json:"freeable"`
	Items    int           `json:"items"`
	Busy     *string       `json:"busy"`
	Parts    []storagePart `json:"parts"`
	Error    bool          `json:"error,omitempty"`
}

// storagePart 是一类里按来源的小计。
type storagePart struct {
	Name  string `json:"name"`
	Bytes int64  `json:"bytes"`
	Items int64  `json:"items"`
}

// nonEmpty：空串是 nil（JSON 里写成 null）。
func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func (p *plan) report(key string) storageKind {
	var freeable int64
	for _, c := range p.items {
		freeable += c.size
	}
	names := make([]string, 0, len(p.parts))
	for name := range p.parts {
		names = append(names, name)
	}
	sort.Strings(names)
	parts := []storagePart{}
	for _, name := range names {
		parts = append(parts, storagePart{Name: name, Bytes: p.parts[name][0], Items: p.parts[name][1]})
	}
	return storageKind{Key: key, Bytes: p.bytes, Files: p.files, Freeable: freeable, Items: len(p.items), Busy: nonEmpty(p.busy), Parts: parts}
}

// removeCandidate 删一个候选，删之前再核一遍。目录按文件描述符逐层删，不跟进符号链接，删到一半被人换成链接也删不到外面去。
// 返回腾出的字节数；没删成时 ok 为假。
func removeCandidate(c candidate) (int64, bool) {
	real, ok := safeChild(c.root, c.name, c.dir)
	if !ok {
		return 0, false
	}
	if c.dir {
		removeTree(real)
	} else {
		_ = unlinkFile(real)
	}
	if !lexists(real) {
		return c.size, true
	}
	return max(0, c.size-measure(real, map[inode]bool{}).bytes), true
}

// storagePlans 是存储页的各类，顺序就是统计时输出的顺序。
var storagePlans = []struct {
	key  string
	plan func(*storageCtx) *plan
}{
	{"zeta-files", planZetaFiles},
	{"zeta-hosts", planZetaHosts},
	{"claude-residue", planResidue},
	{"claude-debug", func(ctx *storageCtx) *plan {
		return planNamed(ctx, "debug", regexp.MustCompile(`^(`+uuidText+`)\.txt$`), false, false)
	}},
	{"claude-snapshots", planSnapshots},
	{"claude-paste", func(ctx *storageCtx) *plan {
		return planNamed(ctx, "paste-cache", regexp.MustCompile(`^[0-9a-f]{16}\.txt$`), false, true)
	}},
	{"claude-image-cache", func(ctx *storageCtx) *plan {
		return planNamed(ctx, "image-cache", regexp.MustCompile(`^(`+uuidText+`)$`), true, false)
	}},
	{"claude-telemetry", func(ctx *storageCtx) *plan {
		return planNamed(ctx, "telemetry", regexp.MustCompile(`^1p_failed_events\.(`+uuidText+`)\.[0-9a-f-]+\.json$`), false, false)
	}},
	{"apt", planApt},
	{"npm", planNpm},
	{"pip", planPip},
	{"tmp", planTmp},
}

func findPlan(key string) func(*storageCtx) *plan {
	for _, p := range storagePlans {
		if p.key == key {
			return p.plan
		}
	}
	return nil
}

// cleanable：能清的类别（apt 要 root 删，由应用以 root 执行 apt-get clean，这里只统计）。
func cleanable(key string) bool { return key != "apt" && findPlan(key) != nil }

// storageReport 是存储页的统计（核心的 storage.scan）：各类（顺序同 storagePlans）、~/.claude/projects 下的各项目，
// 和 settings.json 里的 cleanupPeriodDays（没设时为 null）。
type storageReport struct {
	Kinds             []storageKind    `json:"kinds"`
	Projects          []storageProject `json:"projects"`
	CleanupPeriodDays *json.Number     `json:"cleanupPeriodDays"`
}

// storageProject 是 ~/.claude/projects 下的一个项目目录：占多少、几个文件、几个会话记录，和它对应的工作目录（看不出来时是空串）。
type storageProject struct {
	Dir      string `json:"dir"`
	Bytes    int64  `json:"bytes"`
	Files    int64  `json:"files"`
	Sessions int    `json:"sessions"`
	Cwd      string `json:"cwd"`
}

// cmdStorage 输出 scanStorage 的结果：每类一行 zeta_storage，每个项目一行 zeta_storage_project，再是 zeta_storage_settings 和
// 收尾的 zeta_storage_done。
func cmdStorage(e *env, o *emitter, keep []string) {
	r := scanStorage(e, keep)
	for _, k := range r.Kinds {
		o.out(struct {
			Type string `json:"type"`
			storageKind
		}{"zeta_storage", k})
	}
	for _, p := range r.Projects {
		o.out(struct {
			Type string `json:"type"`
			storageProject
		}{"zeta_storage_project", p})
	}
	o.out(obj{"type": "zeta_storage_settings", "cleanupPeriodDays": r.CleanupPeriodDays})
	o.out(obj{"type": "zeta_storage_done"})
}

// scanStorage 统计各类和各项目；keep 是客户端认为正在进行、要保护的会话。storage 和核心的 storage.scan 共用。
func scanStorage(e *env, keep []string) storageReport {
	ctx := newStorageCtx(e, keep)
	r := storageReport{Kinds: []storageKind{}, Projects: []storageProject{}, CleanupPeriodDays: cleanupPeriod(e)}
	for _, p := range storagePlans {
		r.Kinds = append(r.Kinds, safeReport(ctx, p.key, p.plan))
	}
	for _, project := range children(e.root) {
		path := joinPath(e.root, project)
		if !isRealDir(path) {
			continue
		}
		u := measure(path, ctx.seen)
		var sessions []string
		for _, n := range children(path) {
			if strings.HasSuffix(n, ".jsonl") {
				sessions = append(sessions, n)
			}
		}
		r.Projects = append(r.Projects, storageProject{Dir: project, Bytes: u.bytes, Files: u.files, Sessions: len(sessions),
			Cwd: projectCwd(path, sessions)})
	}
	return r
}

// safeReport 统计一类；一类没统计成不影响别的。
func safeReport(ctx *storageCtx, key string, planOf func(*storageCtx) *plan) (report storageKind) {
	defer func() {
		if recover() != nil {
			report = storageKind{Key: key, Parts: []storagePart{}, Error: true}
		}
	}()
	return planOf(ctx).report(key)
}

// cleanupPeriod 是 ~/.claude/settings.json 里的 cleanupPeriodDays（只取这一项，要是整数）；没设或读不了是 nil。
func cleanupPeriod(e *env) *json.Number {
	if n, ok := readJSONObject(joinPath(e.claude, "settings.json"))["cleanupPeriodDays"].(json.Number); ok && isIntegerLiteral(n) {
		return &n
	}
	return nil
}

// projectCwd 是项目对应的工作目录：最新那个会话记录开头的 cwd 字段；找不到时是空串。
func projectCwd(dir string, sessions []string) string {
	paths := make([]string, len(sessions))
	for i, n := range sessions {
		paths[i] = joinPath(dir, n)
	}
	sortNewestFirst(paths)
	for _, p := range paths[:min(len(paths), 3)] {
		f, err := openFile(p)
		if err != nil {
			continue
		}
		head, err := readUpTo(f, 65536)
		f.Close()
		if err != nil {
			continue
		}
		m := cwdField.FindSubmatch(head)
		if m == nil {
			continue
		}
		if v, ok := parseJSON(m[1]); ok {
			if s, ok := v.(string); ok {
				return s
			}
		}
	}
	return ""
}

// sessionUsage 是项目里的一个会话：记录文件加同名目录一共占多少，修改时刻（毫秒），Live 是它正在进行（这时不能删）。
type sessionUsage struct {
	ID      string `json:"id"`
	File    string `json:"file"`
	Bytes   int64  `json:"bytes"`
	Updated int64  `json:"updated"`
	Live    bool   `json:"live"`
}

// projectUsage 是一个项目里各会话的占用（核心的 storage.sessions），Rest 是不属于任何会话的那部分。
type projectUsage struct {
	Sessions []sessionUsage `json:"sessions"`
	Rest     int64          `json:"rest"`
}

func cmdStorageSessions(e *env, o *emitter, project string, keep []string) error {
	u, err := storageSessions(e, project, keep)
	if err != nil {
		return err
	}
	for _, s := range u.Sessions {
		o.out(struct {
			Type string `json:"type"`
			sessionUsage
		}{"zeta_storage_session", s})
	}
	o.out(obj{"type": "zeta_storage_rest", "bytes": u.Rest})
	return nil
}

// storageSessions 量 ~/.claude/projects 下的项目目录 project 里每个会话的占用。storage-sessions 和核心的 storage.sessions 共用。
func storageSessions(e *env, project string, keep []string) (projectUsage, error) {
	dir, ok := safeChild(e.root, project, true)
	if !ok {
		return projectUsage{}, errors.New("no such project")
	}
	ctx := newStorageCtx(e, keep)
	names := children(dir)
	sessions := map[string]bool{}
	for _, n := range names {
		if stem := dropRunes(n, 6); strings.HasSuffix(n, ".jsonl") && uuidName.MatchString(stem) {
			sessions[stem] = true
		}
	}
	u := projectUsage{Sessions: []sessionUsage{}}
	for _, name := range names {
		path := joinPath(dir, name)
		stem := dropRunes(name, 6)
		switch {
		case sessions[stem] && strings.HasSuffix(name, ".jsonl"):
			file := joinPath(joinPath(e.root, project), name)
			size := measure(path, ctx.seen).bytes
			if _, ok := e.sessionDir(file); ok {
				size += measure(joinPath(dir, stem), ctx.seen).bytes
			}
			u.Sessions = append(u.Sessions, sessionUsage{ID: stem, File: file, Bytes: size, Updated: millis(mtimeFollow(path)),
				Live: ctx.keep[stem]})
		case !sessions[name]:
			u.Rest += measure(path, ctx.seen).bytes
		}
	}
	return u, nil
}

// cleaned 是清掉一类的结果（clean、核心的 storage.clean）：腾出多少字节、删了几项、几项核对不过没删；Busy 不为 null 时那个工具
// 正在运行，这次整类没清。
type cleaned struct {
	Key     string  `json:"key"`
	Freed   int64   `json:"freed"`
	Removed int64   `json:"removed"`
	Failed  int64   `json:"failed"`
	Busy    *string `json:"busy"`
}

func cmdClean(e *env, o *emitter, key string, keep []string) {
	o.out(struct {
		Type string `json:"type"`
		cleaned
	}{"zeta_cleaned", cleanStorage(e, key, keep)})
}

// cleanStorage 清掉一类（key 要是 cleanable 的）里能安全删的。clean 和核心的 storage.clean 共用。
func cleanStorage(e *env, key string, keep []string) cleaned {
	p := findPlan(key)(newStorageCtx(e, keep))
	r := cleaned{Key: key, Busy: nonEmpty(p.busy)}
	if p.busy != "" {
		return r
	}
	for _, c := range p.items {
		if done, ok := removeCandidate(c); ok {
			r.Freed += done
			r.Removed++
		} else {
			r.Failed++
		}
	}
	for _, pr := range p.prune {
		if _, ok := safeChild(pr[0], pr[1], true); ok && len(children(joinPath(pr[0], pr[1]))) == 0 {
			_ = syscall.Rmdir(joinPath(pr[0], pr[1]))
		}
	}
	return r
}
