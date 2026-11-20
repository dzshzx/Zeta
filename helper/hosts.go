package main

import (
	"os"
	"strings"
	"time"
)

// 托管的后台对话（~/.cache/<name>/hosts/<key>）：zeta_host.sh 起的 Claude Code 进程，输出追加在 out.jsonl。

var taskTools = map[string]bool{"Bash": true, "Monitor": true, "Agent": true, "Task": true}

const maxTaskCalls = 64 // 记着最近这么多个发起后台任务的工具调用

// cmdHosts 列出仍在运行的托管进程（应用被杀后重新接上用），顺手清掉已经结束的和过期的附件。
func cmdHosts(e *env, o *emitter) error {
	e.pruneUploads()
	dirs := globNames(e.hosts, false, nil)
	for i, name := range dirs {
		dirs[i] = joinPath(e.hosts, name)
	}
	sortNewestFirst(dirs)
	for _, dir := range dirs {
		pid, known := readPid(joinPath(dir, "pid"))
		if _, alive := claudeArgv(pid, known); !alive || existsFollow(joinPath(dir, "exit")) {
			removeTree(dir)
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
		"mode": scan.mode, "tasks": scan.tasks, "task_events": scan.events}
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
	f, err := openFile(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	var s summaryScan
	lr := newLineReader(f)
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		if has(line, `-title"`) && validUTF8(line) {
			s.title(line)
		}
	}
	if s.custom != "" {
		return s.custom
	}
	return s.ai
}

// hostScan 是读一个后台进程的输出日志得到的：到第几行了、是哪个会话、这一轮是否还在进行、有没有等着用户批准的调用、
// 还有几个后台任务在跑，以及这些任务的事件（接上后重放给任务清单）。
type hostScan struct {
	lines   int
	session any
	busy    bool
	pending any // 等着批准的 control_request（整行），没有是 nil
	mode    any
	tasks   int
	events  []any
}

// hostReader 是 scanHost 的状态。后台任务：最后一份清单；各任务的 task_started（带上发起它的工具调用和开始时间）和最近一条 task_progress。
type hostReader struct {
	hostScan
	pendingCall obj
	listing     obj
	started     map[string]obj
	progress    map[string]obj
	calls       orderedCalls
	stamp       int64 // 最近一条 user、assistant 行的时刻（毫秒），0 表示还不知道
}

// scanHost 读 out.jsonl。这一轮的起止和 zeta_host.sh 的 turn_running 同一口径：system init、command_lifecycle started 开始，
// result 结束，别的行不算。
func scanHost(dir string) (hostScan, error) {
	f, err := openFile(joinPath(dir, "out.jsonl"))
	if err != nil {
		return hostScan{}, err
	}
	defer f.Close()
	r := &hostReader{started: map[string]obj{}, progress: map[string]obj{}}
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
	if r.pendingCall != nil {
		r.pending = r.pendingCall
	}
	r.events = r.taskEvents()
	return r.hostScan, nil
}

func (r *hostReader) record(o obj) {
	if v := o["session_id"]; truthy(v) {
		r.session = v
	}
	switch o["type"] {
	case "result":
		r.busy = false
		r.pendingCall = nil
	case "system":
		r.system(o)
	case "command_lifecycle":
		if o["state"] == "started" {
			r.busy = true
		}
	case "assistant", "user":
		r.message(o)
	case "control_request":
		if asObj(o["request"])["subtype"] == "can_use_tool" {
			r.pendingCall = o
		}
	case "control_cancel_request":
		if r.pendingCall != nil && sameValue(r.pendingCall["request_id"], o["request_id"]) {
			r.pendingCall = nil
		}
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
	if o["type"] == "user" && r.pendingCall != nil {
		wanted := asObj(r.pendingCall["request"])["tool_use_id"]
		for _, b := range asList(content) {
			if m, ok := b.(obj); ok && sameValue(m["tool_use_id"], wanted) {
				r.pendingCall = nil
				break
			}
		}
	}
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
