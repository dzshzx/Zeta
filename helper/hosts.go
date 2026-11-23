package main

import (
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// 托管的后台对话（~/.cache/<name>/hosts/<key>）：zeta_host.sh 起的 Claude Code 进程，输出追加在 out.jsonl。

var taskTools = map[string]bool{"Bash": true, "Monitor": true, "Agent": true, "Task": true}

const maxTaskCalls = 64 // 记着最近这么多个发起后台任务的工具调用

// cmdHosts 列出仍在运行的托管进程（应用被杀后重新接上用），顺手清掉已经结束的和过期的附件。
// 结束了的托管目录还有人接着时不删（liveAttachers）：接着的一方还要读 exit 报 zeta_exit，最后离开的那个自己删。
func cmdHosts(e *env, o *emitter) error {
	e.pruneUploads()
	dirs := globNames(e.hosts, false, nil)
	for i, name := range dirs {
		dirs[i] = joinPath(e.hosts, name)
	}
	sortNewestFirst(dirs)
	for _, dir := range dirs {
		pid, known := readPid(joinPath(dir, "pid"))
		if !hostRunning(dir, pid, known) {
			e.removeEndedHost(dir)
			continue
		}
		scan, err := scanHost(dir)
		if err != nil {
			continue
		}
		o.out(e.hostItem(dir, pid, scan))
	}
	return nil
}

func (e *env) hostItem(dir string, pid int, scan hostScan) obj {
	var files []string
	if s, ok := scan.session.(string); ok && s != "" {
		files = e.sessionFilesNamed(s)
	}
	// 会话记录里的标题（改过的名字、自动起的标题）比启动时记下的新；都没有时才整份读一遍取开头那句
	title := ""
	if len(files) > 0 {
		title = sessionTitle(files[0])
	}
	if title == "" {
		title = readText(joinPath(dir, "title"))
	}
	if title == "" && len(files) > 0 {
		if s, ok, err := summarize(files[0]); err == nil && ok {
			title = runePrefix(s.preview, 40)
		}
	}
	var file any
	if len(files) > 0 {
		file = files[0]
	}
	return obj{"key": baseName(dir), "pid": pid, "cwd": readText(joinPath(dir, "cwd")), "title": title,
		"session": scan.session, "file": file, "lines": scan.lines, "busy": scan.busy, "pending": scan.pending,
		"mode": scan.mode, "user_mode": readText(joinPath(dir, userModeFile)), "tasks": scan.tasks, "task_events": scan.events}
}

// userModeFile 是托管目录里记着用户在这个对话里的权限模式的文件（规矩见 zeta_host.sh 开头）。
const userModeFile = "user_mode"

// keepHostMode 把用户的权限模式 mode 记进托管进程 key 的托管目录：核心重启后接回时照它恢复（conv.Hosted.UserMode）。
// 托管目录已经没了时什么也不写，不另建目录。
func (e *env) keepHostMode(key, mode string) error {
	return e.writeHostFile(key, userModeFile, mode)
}

// writeHostFile 把一行 text 写成本包托管进程 key 的托管目录里的文件 name。托管目录已经没了时出错，不另建目录。
func (e *env) writeHostFile(key, name, text string) error {
	if key == "" || strings.HasPrefix(key, ".") || strings.Contains(key, "/") {
		return errors.New("托管标识不对：" + key)
	}
	return os.WriteFile(joinPath(joinPath(e.hosts, key), name), []byte(text+"\n"), 0o644)
}

// liveAttachers 是托管目录 dir 里还接着的一方（zeta_host.sh 的 attach 之后那段）的进程号，规矩同 zeta_host.sh 的 attachers：
// attach.d/<进程号> 的记录内容是那个进程的启动时刻，进程还在跑、启动时刻对得上才算。
func liveAttachers(dir string) []int {
	var out []int
	records := joinPath(dir, "attach.d")
	for _, name := range globNames(records, false, nil) {
		pid, err := strconv.Atoi(name)
		if err == nil && pid > 0 && running(pid, readText(joinPath(records, name))) {
			out = append(out, pid)
		}
	}
	return out
}

// hostRunning：托管目录 dir 的 Claude Code（pid 文件记的 pid，known 为假时没记）还在跑、也没留下退出码。
func hostRunning(dir string, pid int, known bool) bool {
	_, alive := claudeArgv(pid, known)
	return alive && !existsFollow(joinPath(dir, "exit"))
}

// removeEndedHost 删掉结束了、也没人接着的托管目录 dir。持着托管目录旁边的 hosts/.lock 再看一遍：zeta_host.sh start 从建目录到
// 写下 pid 一直持着它，这期间目录里还没有 pid，看着像结束了的。拿不到锁（正有 start 在建）时不删，下次扫到再说。
func (e *env) removeEndedHost(dir string) {
	f, err := os.OpenFile(joinPath(e.hosts, ".lock"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o666)
	if err != nil {
		return
	}
	defer f.Close()
	if syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) != nil {
		return
	}
	pid, known := readPid(joinPath(dir, "pid"))
	if !hostRunning(dir, pid, known) && len(liveAttachers(dir)) == 0 {
		removeTree(dir)
	}
}

func existsFollow(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// pruneUploads：应用送进来的附件留七天，够会话用完；之后清掉。
func (e *env) pruneUploads() {
	limit := float64(time.Now().UnixNano())/1e9 - 7*86400
	for _, name := range globNames(e.uploads, false, nil) {
		dir := joinPath(e.uploads, name)
		if fi, err := os.Stat(dir); err == nil && mtimeOf(fi) < limit {
			removeTree(dir)
		}
	}
}

// readText 读一个小文本文件，去掉首尾空白；读不了是空串。
func readText(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(decodeString(data))
}

// sessionTitle 是会话记录里的标题，规矩同 summarize：改过的名字（custom-title）压过自动起的（ai-title），不看先后。
// 只看这两种行（按字节筛，不解码别的行），没有标题是空串。
func sessionTitle(path string) string {
	var f titleFollower
	return f.read(path)
}

// titleFollower 跟读一份会话记录里的标题行（custom-title、ai-title）：每次只读上次之后新写完的整行，
// 换了文件或文件变短了从头读。改名是往记录末尾追加一行（cmdRename、Claude Code 的 /rename），跟读得到。
type titleFollower struct {
	path string
	off  int64
	s    summaryScan
}

// read 读到 path 现在为止的标题；path 是空串或读不了时给出上次读到的。
func (t *titleFollower) read(path string) string {
	if path != t.path {
		*t = titleFollower{path: path}
	}
	if f, err := openFile(path); err == nil {
		t.follow(f)
		f.Close()
	}
	if t.s.custom != "" {
		return t.s.custom
	}
	return t.s.ai
}

func (t *titleFollower) follow(f *os.File) {
	if fi, err := f.Stat(); err != nil || fi.Size() < t.off {
		*t = titleFollower{path: t.path}
	}
	if _, err := f.Seek(t.off, io.SeekStart); err != nil {
		return
	}
	lr := newLineReader(f)
	for line, ok := lr.next(); ok && line[len(line)-1] == '\n'; line, ok = lr.next() {
		t.off += int64(len(line))
		if has(line, `-title"`) && validUTF8(line) {
			t.s.title(line)
		}
	}
}

// hostScan 是读一个后台进程的输出日志得到的：到第几行了、是哪个会话、这一轮是否还在进行、有没有等着用户批准的调用、
// 还有几个后台任务在跑，以及这些任务的事件（接上后重放给任务清单）。
type hostScan struct {
	lines   int
	session any
	busy    bool
	pending []any // 等着用户回应的 control_request（can_use_tool、elicitation，整行），按到达顺序
	mode    any
	tasks   int
	events  []any
}

// hostReader 是 scanHost 的状态。后台任务：最后一份清单；各任务的 task_started（带上发起它的工具调用和开始时间）和最近一条 task_progress。
type hostReader struct {
	hostScan
	waiting  []obj             // 还没回应的请求，见 pending
	mcpCalls map[string]string // MCP 工具调用的 id → 工具名，elicitation 靠它认出调用已经有了结果
	listing  obj
	started  map[string]obj
	progress map[string]obj
	calls    orderedCalls
	stamp    int64 // 最近一条 user、assistant 行的时刻（毫秒），0 表示还不知道
}

// scanHost 读 out.jsonl。这一轮的起止和 zeta_host.sh 的 turn_running 同一口径：system init、command_lifecycle started 开始，
// result 结束，别的行不算。输出里还没有会话时，会话取 start 记下的（startedSession）。
func scanHost(dir string) (hostScan, error) {
	f, err := openFile(joinPath(dir, "out.jsonl"))
	if err != nil {
		return hostScan{}, err
	}
	defer f.Close()
	r := &hostReader{started: map[string]obj{}, progress: map[string]obj{}, mcpCalls: map[string]string{}}
	lr := newLineReader(f)
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		if line[len(line)-1] != '\n' {
			break // 还没写完的半行留给之后的跟读
		}
		r.lines++
		if has(runePrefixBytes(line, 40), `"stream_event"`) {
			continue // 回复的增量，量大，不解析
		}
		if o, ok := parseObject(line); ok {
			r.record(o)
		}
	}
	if lr.err != nil {
		return hostScan{}, lr.err
	}
	r.pending = []any{}
	for _, w := range r.waiting {
		r.pending = append(r.pending, w)
	}
	r.events = r.taskEvents()
	if !truthy(r.session) {
		if s := startedSession(dir); s != "" {
			r.session = s
		}
	}
	return r.hostScan, nil
}

func (r *hostReader) record(o obj) {
	if v := o["session_id"]; truthy(v) {
		r.session = v
	}
	switch o["type"] {
	case "result":
		r.busy = false
		// 后台子代理的确认（带 agent_id）不随主对话这一轮结束
		r.dropWaiting(func(w obj) bool { return !truthy(asObj(w["request"])["agent_id"]) })
	case "system":
		r.system(o)
	case "command_lifecycle":
		if o["state"] == "started" {
			r.busy = true
		}
	case "assistant", "user":
		r.message(o)
	case "control_request":
		if sub := asObj(o["request"])["subtype"]; sub == "can_use_tool" || sub == "elicitation" {
			r.waiting = append(r.waiting, o)
		}
	case "control_cancel_request":
		r.dropWaiting(func(w obj) bool { return sameValue(w["request_id"], o["request_id"]) })
	}
}

func (r *hostReader) system(o obj) {
	if v := o["permissionMode"]; truthy(v) {
		r.mode = v
	}
	switch o["subtype"] {
	case "init":
		// 后台任务跑完后 Claude Code 自己起的一轮也以 init 开头
		r.busy = true
	case "background_tasks_changed":
		// 每次都是完整清单：最后一条就是现在的情况。这一轮结束了它们也照样在跑
		r.tasks = sizeOf(orValue(o["tasks"], []any{}))
		r.listing = o
	case "task_started":
		if truthy(o["task_id"]) {
			if call, ok := r.calls.get(valueKey(o["tool_use_id"])); ok {
				o["zeta_tool"] = call
			}
			if r.stamp != 0 {
				o["zeta_at"] = r.stamp
			}
			r.started[valueKey(o["task_id"])] = o
		}
	case "task_progress":
		if truthy(o["task_id"]) {
			r.progress[valueKey(o["task_id"])] = o
		}
	}
}

func (r *hostReader) message(o obj) {
	if ms, ok := epochMillis(o["timestamp"]); ok && ms != 0 {
		r.stamp = ms
	}
	content := asObj(o["message"])["content"]
	// 发起后台任务的调用只看主对话的（子代理内部的行带 parent_tool_use_id）
	if o["type"] == "assistant" && !truthy(o["parent_tool_use_id"]) {
		for _, b := range asList(content) {
			if m, ok := b.(obj); ok && m["type"] == "tool_use" && taskTools[asString(m["name"])] {
				r.calls.set(valueKey(m["id"]), obj{"name": m["name"], "input": m["input"]})
			}
		}
	}
	if o["type"] == "assistant" {
		for _, b := range asList(content) {
			if m, ok := b.(obj); ok && m["type"] == "tool_use" && strings.HasPrefix(asString(m["name"]), "mcp__") {
				r.mcpCalls[valueKey(m["id"])] = asString(m["name"])
			}
		}
	}
	if o["type"] == "user" && len(r.waiting) > 0 {
		for _, b := range asList(content) {
			m, ok := b.(obj)
			if !ok || m["type"] != "tool_result" {
				continue
			}
			id := m["tool_use_id"]
			tool := r.mcpCalls[valueKey(id)]
			r.dropWaiting(func(w obj) bool {
				req := asObj(w["request"])
				if req["subtype"] == "elicitation" {
					// elicitation 不带调用号：发起它的那台 MCP 服务器有工具调用出了结果，就当已经答过
					return tool != "" && strings.HasPrefix(tool, "mcp__"+mcpToolPrefix(asString(req["mcp_server_name"]))+"__")
				}
				return sameValue(req["tool_use_id"], id)
			})
		}
	}
}

// dropWaiting 去掉 drop 为真的请求。
func (r *hostReader) dropWaiting(drop func(obj) bool) {
	kept := r.waiting[:0]
	for _, w := range r.waiting {
		if !drop(w) {
			kept = append(kept, w)
		}
	}
	r.waiting = kept
}

// mcpToolPrefix 是 MCP 服务器名在工具名里的写法（mcp__<它>__<工具>）：字母、数字、_ 和 - 以外的换成 _，和 Claude Code 一样。
func mcpToolPrefix(server string) string {
	return strings.Map(func(c rune) rune {
		if c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') {
			return c
		}
		return '_'
	}, server)
}

// taskEvents 是重放的事件：各任务的 task_started，再是清单（据此列出），最后是进展。
func (r *hostReader) taskEvents() []any {
	events := []any{}
	if r.listing == nil {
		return events
	}
	var ids []string
	for _, item := range asList(orValue(r.listing["tasks"], []any{})) {
		ids = append(ids, valueKey(asObj(item)["task_id"]))
	}
	for _, id := range ids {
		if s, ok := r.started[id]; ok {
			events = append(events, s)
		}
	}
	events = append(events, r.listing)
	for _, id := range ids {
		if p, ok := r.progress[id]; ok {
			events = append(events, p)
		}
	}
	return events
}

// orderedCalls 记着最近的 maxTaskCalls 个工具调用，按第一次记下的先后，满了丢最早的。
type orderedCalls struct {
	order []string
	byKey map[string]obj
}

func (c *orderedCalls) set(key string, call obj) {
	if c.byKey == nil {
		c.byKey = map[string]obj{}
	}
	if _, ok := c.byKey[key]; !ok {
		c.order = append(c.order, key)
	}
	c.byKey[key] = call
	if len(c.order) > maxTaskCalls {
		delete(c.byKey, c.order[0])
		c.order = c.order[1:]
	}
}

func (c *orderedCalls) get(key string) (obj, bool) {
	call, ok := c.byKey[key]
	return call, ok
}

// epochMillis 把记录里的 ISO 8601 时刻（2026-11-20T08:00:00.123Z，或带时区偏移，或不带时区按本地时间）换成毫秒。
func epochMillis(v any) (int64, bool) {
	s, ok := v.(string)
	if !ok {
		return 0, false
	}
	s = strings.ReplaceAll(s, "Z", "+00:00")
	for _, layout := range []string{"2006-01-02T15:04:05.999999999Z07:00", "2006-01-02 15:04:05.999999999Z07:00"} {
		if t, err := time.Parse(layout, s); err == nil {
			return isoMillis(t), true
		}
	}
	for _, layout := range []string{"2006-01-02T15:04:05.999999999", "2006-01-02 15:04:05.999999999", "2006-01-02T15:04", "2006-01-02"} {
		if t, err := time.ParseInLocation(layout, s, time.Local); err == nil {
			return isoMillis(t), true
		}
	}
	return 0, false
}

// isoMillis 精确到微秒，换算方法同 datetime.timestamp() * 1000 再取整（与旧版逐位一致）。
func isoMillis(t time.Time) int64 {
	return int64(float64(t.UnixMicro()) / 1e6 * 1000)
}

// runePrefixBytes 是一行开头的 n 个字符（非法字节各算一个）。
func runePrefixBytes(line []byte, n int) []byte {
	i := 0
	for k := 0; k < n && i < len(line); k++ {
		if line[i] < 0x80 {
			i++
			continue
		}
		size, _ := utf8Sequence(line[i:])
		i += size
	}
	return line[:i]
}

func validUTF8(b []byte) bool { return firstInvalidUTF8(b) < 0 }
