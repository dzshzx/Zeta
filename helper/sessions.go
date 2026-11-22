package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

const listLimit = 300 // list、search 只看最新的这么多个会话

// summary 是会话列表里的一项。
type summary struct {
	id      string
	file    string
	cwd     any
	title   string
	preview string
	updated int64
}

func (s summary) item(external, archived map[string]bool) obj {
	return obj{"id": s.id, "file": s.file, "cwd": s.cwd, "title": s.title, "preview": s.preview, "updated": s.updated,
		"external": external[s.id], "archived": archived[s.id]}
}

// summaryScan 是读一份记录时攒下的东西。
type summaryScan struct {
	custom, ai    string // 改过的名字、自动起的标题
	first, prompt string // 第一句话（含斜杠命令）、第一句真正的话
	cwd           any
	// 开头读过 liteBytes 还没找到第一句真正的话：不再找，预览用第一句话（结尾那段里的话不是“第一句”）；
	// 剪到第一条消息之前时重新找
	settled bool
}

// liteBytes：概要读记录的开头和结尾各最多这么多字节（见 summarize）。
const liteBytes = 64 * 1024

// summarize 读一份记录的概要：标题、工作目录、预览。记录里一句用户的话都没有时 ok 为假。
//
// 和 Claude Code 自己的会话列表一样只读头尾，不读整份（记录常有几十上百 MB，列表一次要读几百份）：
// 开头读到第一句真正的话为止，首句是斜杠命令时再往后找，过了 liteBytes 就不找了，预览用那条斜杠命令；
// 结尾读最后 liteBytes 取标题：Claude Code 补写元数据时把改过的名字、自动起的标题再追加到末尾，最新的标题总在那里。
// 中间剪切过（剪到第一条消息之前）的会话，剪切记号不在头尾时预览还是剪掉之前的那句。
func summarize(path string) (summary, bool, error) {
	f, err := openFile(path)
	if err != nil {
		return summary{}, false, err
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		return summary{}, false, err
	}
	var s summaryScan
	read, err := s.head(f)
	if err != nil {
		return summary{}, false, err
	}
	if err := s.tail(f, read, fi.Size()); err != nil {
		return summary{}, false, err
	}
	if s.first == "" {
		return summary{}, false, nil
	}
	title := s.custom
	if title == "" {
		title = s.ai
	}
	preview := s.prompt
	if preview == "" {
		preview = s.first
	}
	return summary{id: dropRunes(baseName(path), 6), file: path, cwd: orValue(s.cwd, ""), title: title,
		preview: runePrefix(preview, 200), updated: millis(mtimeOf(fi))}, true, nil
}

// head 从头逐行读到第一句真正的话；首句是斜杠命令时接着找，读过 liteBytes 就停。返回读到哪里（最后读的那行的末尾）。
// 一句用户的话都还没有时一直读下去（比如第一条消息带着很大的图片，一行就超过 liteBytes）。
func (s *summaryScan) head(f *os.File) (int64, error) {
	lr := newLineReaderSize(f, liteBytes)
	var read int64
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		s.line(line)
		read += int64(len(line))
		if s.prompt != "" {
			break
		}
		if s.first != "" && read >= liteBytes {
			s.settled = true
			break
		}
	}
	return read, lr.err
}

// tail 读 [from, size) 里最后至多 liteBytes 字节中的整行：开头落在一行中间时那半行不要；最后一行没写完的照样读（同 head）。
func (s *summaryScan) tail(f *os.File, from, size int64) error {
	start := max(from, size-liteBytes)
	if start >= size {
		return nil
	}
	// 从 head 读到的地方往后接着读时正是一行的开头；否则多读前一个字节，它是换行才说明 start 是一行的开头
	cut := start > from
	if cut {
		start--
	}
	buf := make([]byte, size-start)
	n, err := f.ReadAt(buf, start)
	if err != nil && err != io.EOF {
		return err
	}
	buf = buf[:n]
	if cut {
		i := bytes.IndexByte(buf, '\n')
		if i < 0 {
			return nil
		}
		buf = buf[i+1:]
	}
	lr := newLineReaderSize(bytes.NewReader(buf), liteBytes)
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		s.line(line)
	}
	return lr.err
}

func (s *summaryScan) line(line []byte) {
	// 先按子串筛，绝大多数行（请求快照等）不必解析
	switch {
	case has(line, `"ai-title"`) || has(line, `"custom-title"`):
		s.title(line)
	case has(line, `"leafUuid":null`) && isRewind(line):
		// 剪到了第一条消息之前：之前的提问不算了，预览改用之后的第一句
		s.first, s.prompt, s.settled = "", "", false
	case s.prompt == "" && !s.settled && has(line, `"type":"user"`):
		s.user(line)
	case s.cwd == nil && has(line, `"cwd"`):
		if o, ok := parseObject(line); ok {
			s.cwd = o["cwd"]
		}
	}
}

// title：和 Claude Code 一样，改过的名字（custom-title）压过自动起的（ai-title），不看先后；
// Claude Code 补写元数据时会把两行按 custom、ai 的顺序再追加一遍。
func (s *summaryScan) title(line []byte) {
	o, ok := parseObject(line)
	if !ok {
		return
	}
	if t := asString(o["customTitle"]); o["type"] == "custom-title" && t != "" {
		s.custom = t
	} else if t := asString(o["aiTitle"]); o["type"] == "ai-title" && t != "" {
		s.ai = t
	}
}

func (s *summaryScan) user(line []byte) {
	o, ok := parseObject(line)
	if !ok || o["type"] != "user" || truthy(o["isSidechain"]) || truthy(o["isMeta"]) {
		return
	}
	if !truthy(s.cwd) {
		s.cwd = o["cwd"]
	}
	content := asObj(o["message"])["content"]
	text := userText(content)
	if text == "" && imageCount(content) > 0 {
		text = "[图片]"
	}
	if text == "" {
		return
	}
	// 附件行不当预览；斜杠命令开头的会话，预览优先用后面第一句真正的话
	text = previewText(text)
	if s.first == "" {
		s.first = text
	}
	if !strings.HasPrefix(text, "/") {
		s.prompt = text
	}
}

// previewText 去掉附件行；全是附件行时取第一行附件的名字。
func previewText(text string) string {
	var kept []string
	lines := strings.Split(text, "\n")
	for _, l := range lines {
		if _, ok := attachedName(l); !ok {
			kept = append(kept, l)
		}
	}
	if joined := strings.TrimSpace(strings.Join(kept, "\n")); joined != "" {
		return joined
	}
	if name, ok := attachedName(lines[0]); ok {
		return name
	}
	return text
}

func cmdList(e *env, o *emitter) error {
	files := e.sessionFiles()
	sortNewestFirst(files)
	external := e.externalSessions()
	archived := e.archivedSessions()
	for _, path := range files[:min(len(files), listLimit)] {
		s, ok, err := summarize(path)
		if err == nil && ok {
			o.out(s.item(external, archived))
		}
	}
	return nil
}

func cmdLookup(e *env, o *emitter, ids []string) error {
	for _, item := range lookupSessions(e, ids) {
		o.out(item)
	}
	return nil
}

// lookupSessions 按 id 取会话概要（形状同 list 的每一项），按 ids 的顺序，找不到的不在里面。
// zeta-helper lookup 和核心的 sessions.lookup 都用它。
func lookupSessions(e *env, ids []string) []obj {
	external := e.externalSessions()
	archived := e.archivedSessions()
	items := []obj{}
	for _, id := range ids {
		for _, path := range e.sessionFilesNamed(id) {
			s, ok, err := summarize(path)
			if err == nil && ok {
				items = append(items, s.item(external, archived))
				break
			}
		}
	}
	return items
}

// archivedSessions 是归档的会话 id。归档的会话只是不在列表里显示，记录文件原样留着；文件里一行一个 id。
func (e *env) archivedSessions() map[string]bool {
	ids := map[string]bool{}
	data, err := os.ReadFile(e.archive)
	if err != nil {
		return ids
	}
	for _, line := range strings.Split(decodeString(data), "\n") {
		if id := strings.TrimSpace(line); id != "" {
			ids[id] = true
		}
	}
	return ids
}

func cmdArchive(e *env, o *emitter, sessions []string, on bool) error {
	if err := archiveSessions(e, sessions, on); err != nil {
		return err
	}
	for _, id := range sessions {
		o.out(obj{"type": "zeta_archived", "id": id, "archived": on})
	}
	return nil
}

// archiveSessions 把这些会话记成归档（on）或取消归档，顺手清掉记录文件已经不在的；先写临时文件再改名换上。
// zeta-helper archive 和核心的 sessions.archive 都用它。
func archiveSessions(e *env, sessions []string, on bool) error {
	// 顺手清掉记录文件已经不在的
	alive := e.sessionIDs()
	keep := map[string]bool{}
	for id := range e.archivedSessions() {
		if alive[id] {
			keep[id] = true
		}
	}
	for _, id := range sessions {
		if on {
			keep[id] = true
		} else {
			delete(keep, id)
		}
	}
	ids := make([]string, 0, len(keep))
	for id := range keep {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	var b strings.Builder
	for _, id := range ids {
		b.WriteString(id + "\n")
	}
	if err := os.MkdirAll(dirName(e.archive), 0o777); err != nil {
		return err
	}
	if err := os.WriteFile(e.archive+".tmp", []byte(b.String()), 0o666); err != nil {
		return err
	}
	return os.Rename(e.archive+".tmp", e.archive)
}

// cmdRename 给会话改名（renameSession），报出实际写下的标题。
func cmdRename(o *emitter, path, title string) error {
	title, err := renameSession(path, title)
	if err != nil {
		return err
	}
	o.out(obj{"type": "zeta_renamed", "title": title})
	return nil
}

// renameSession 给会话记录 path 改名：空白压成一个空格、最多 200 字，追加一行 custom-title
// （与 Claude Code 自己的 /rename 写的一样，紧凑的 JSON），文件末尾没有换行时先补一个。返回实际写下的标题。
// zeta-helper rename 和核心的 conv.title 都用它。
func renameSession(path, title string) (string, error) {
	title = runePrefix(squash(title), 200)
	if title == "" {
		return "", errors.New("empty title")
	}
	f, err := openFile(path)
	if err != nil {
		return "", err
	}
	needsNewline, err := lacksFinalNewline(f)
	f.Close()
	if err != nil {
		return "", err
	}
	line := []byte{}
	if needsNewline {
		line = append(line, '\n')
	}
	line = append(line, `{"type":"custom-title","customTitle":`...)
	line = appendQuoted(line, title)
	line = append(line, `,"sessionId":`...)
	line = appendQuoted(line, dropRunes(baseName(path), 6))
	line = append(line, "}\n"...)
	w, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o666)
	if err != nil {
		return "", err
	}
	if _, err := w.Write(line); err != nil {
		w.Close()
		return "", err
	}
	return title, w.Close()
}

func lacksFinalNewline(f *os.File) (bool, error) {
	size, err := f.Seek(0, 2)
	if err != nil || size == 0 {
		return false, err
	}
	last := make([]byte, 1)
	if _, err := f.ReadAt(last, size-1); err != nil {
		return false, err
	}
	return last[0] != '\n', nil
}

// cmdDelete 删这些会话记录（deleteRecord），每份输出一行 zeta_deleted。
func cmdDelete(e *env, o *emitter, paths []string) error {
	for _, path := range paths {
		d, err := deleteRecord(e, path)
		if err != nil {
			return err
		}
		o.out(obj{"type": "zeta_deleted", "file": d.File, "dir": d.Dir})
	}
	return nil
}

// deletedRecord 是删掉的一份会话记录；Dir 是它的同名目录删掉了（没有同名目录时为假）。
type deletedRecord struct {
	File string `json:"file"`
	Dir  bool   `json:"dir"`
}

// deleteRecord 删会话记录 path 和它的同名目录（工具结果、子代理记录等）。目录用 removeTree，删到符号链接只删链接本身，不跟进去。
// 记录已经不在不算出错。zeta-helper delete 和核心的 sessions.delete 都用它。
func deleteRecord(e *env, path string) (deletedRecord, error) {
	dir, hasDir := e.sessionDir(path)
	if err := unlinkFile(path); err != nil && !isNotExist(err) {
		return deletedRecord{}, err
	}
	cleaned := false
	if hasDir {
		removeTree(dir)
		cleaned = !lexists(dir)
	}
	return deletedRecord{File: path, Dir: cleaned}, nil
}

// registered 是 Claude Code 某个进程登记的会话。
type registered struct {
	pid     int
	start   string // 进程的启动时刻（procStart）
	session any
	status  string // Claude Code 报的忙闲：idle、busy（正在跑一轮）、shell（在跑 ! 命令）、waiting（在等确认）；旧版没有，是空串
	waiting string // status 是 waiting 时在等什么（waitingFor），没有是空串
	host    string // 托管它的 Zeta 托管目录（hostDirOf），不是 Zeta 托管的是空串
}

// hostMark 是 zeta_host.sh 给它起的 Claude Code 设的环境变量，值是 <包的目录名>/<托管目录名>（比如 zeta-dev/abc），
// 为什么按它认、不按 pid 文件认见 zeta_host.sh 开头 pid 一项。zeta_host.sh、zeta_accounts.sh 照同一个样子认：
// 包名以 zeta 开头，托管目录名不空、不含 /、不以 . 开头。
const hostMark = "ZETA_HOST"

// hostDirOf 是登记的进程（pid、会话 session）所属的托管目录（~/.cache/<包>/hosts/<名字>，两个包都认）。
// 记号会传给 Claude Code 起的子进程（Bash 工具里的命令、里面再起的 claude），所以还要会话对得上：
// 托管目录现在的会话（hostSessionNow）就是登记的会话，才是那个目录托管的 Claude Code。不是时 ok 为假。
func (e *env) hostDirOf(pid int, session any) (string, bool) {
	mark, ok := procEnv(pid, hostMark)
	if !ok {
		return "", false
	}
	pkg, key, ok := strings.Cut(mark, "/")
	if !ok || !strings.HasPrefix(pkg, "zeta") || key == "" || key[0] == '.' || strings.Contains(key, "/") {
		return "", false
	}
	dir := joinPath(newEnvAt(e.home, pkg).hosts, key)
	if s, _ := session.(string); s == "" || hostSessionNow(dir) != s {
		return "", false
	}
	return dir, true
}

// hostSessionNow 是托管目录 dir 的 Claude Code 现在的会话：输出里最后一个 session_id（lastSessionID）；
// 还没有输出（第一条消息之前）时是 start 记下的要接着的会话（startedSession）。都没有时是空串。
func hostSessionNow(dir string) string {
	if s := lastSessionID(joinPath(dir, "out.jsonl")); s != "" {
		return s
	}
	return startedSession(dir)
}

// startedSession 是 zeta_host.sh start 接着旧会话（--resume、不分支）时在托管目录 dir 里写下的会话号（session 文件）；
// 没有、样子不对时是空串。
func startedSession(dir string) string {
	if s := readText(joinPath(dir, "session")); isSessionID(s) {
		return s
	}
	return ""
}

// lastSessionID 是输出日志 path 里最后一个 session_id。同一个进程里会换会话（/clear），所以取最后的、不取开头的；
// 每个事件行都带 session_id，读最后 64 KB 就够。还没有输出、读不了时是空串。
func lastSessionID(path string) string {
	f, err := openFile(path)
	if err != nil {
		return ""
	}
	defer f.Close()
	size, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		return ""
	}
	start := max(0, size-65536)
	buf := make([]byte, size-start)
	n, err := f.ReadAt(buf, start)
	if err != nil && err != io.EOF {
		return ""
	}
	found := sessionInArgv.FindAllSubmatch(buf[:n], -1)
	if len(found) == 0 {
		return ""
	}
	return string(found[len(found)-1][1])
}

// ownHost：登记的进程是本包托管的。
func (e *env) ownHost(r registered) bool {
	return strings.HasPrefix(r.host, e.hosts+"/")
}

// registeredSessions：Claude Code 每个进程都在 ~/.claude/sessions/<pid>.json 登记自己的会话（stream-json 的也一样）。
// 给出进程还活着的；pid 被别的进程复用了（启动时刻对不上）的不算。
func (e *env) registeredSessions() []registered {
	var out []registered
	for _, name := range globNames(e.sessions, false, func(n string) bool { return strings.HasSuffix(n, ".json") }) {
		info := readJSONObject(joinPath(e.sessions, name))
		pid, ok := jsonInt(info["pid"])
		if info == nil || !ok {
			continue
		}
		started, ok := procStart(pid)
		if !ok {
			continue
		}
		if mark := info["procStart"]; truthy(mark) && plainText(mark) != started {
			continue
		}
		host, _ := e.hostDirOf(pid, info["sessionId"])
		out = append(out, registered{pid: pid, start: started, session: info["sessionId"],
			status: asString(info["status"]), waiting: asString(info["waitingFor"]), host: host})
	}
	return out
}

// jsonInt 把登记文件里的 pid 转成整数：整数、写成小数的数（取整数部分）、写成数字的字符串。
func jsonInt(v any) (int, bool) {
	switch t := v.(type) {
	case string:
		n, err := strconv.Atoi(strings.TrimSpace(t))
		return n, err == nil
	case json.Number:
		if n, err := t.Int64(); err == nil {
			return int(n), true
		}
		f, err := t.Float64()
		if err != nil || math.Abs(f) > 1<<62 {
			return 0, false
		}
		return int(f), true
	}
	return 0, false
}

// externalSessions 是正被别的 Claude Code 进程（比如终端里的）占用的会话 id。
func (e *env) externalSessions() map[string]bool {
	out := map[string]bool{}
	for _, r := range e.outsideRegistrations() {
		out[r.session.(string)] = true
	}
	return out
}

// outsideRegistrations 是本包之外（终端里的、另一个包托管的）的进程登记的、带会话 id 的那些：侧栏标“在别处运行”、end 结束的都是它们。
func (e *env) outsideRegistrations() []registered {
	var out []registered
	for _, r := range e.registeredSessions() {
		if _, ok := r.session.(string); ok && !e.ownHost(r) {
			out = append(out, r)
		}
	}
	return out
}

// readJSONObject 读一个 JSON 对象文件；读不了、不是合法 UTF-8、不是对象时为 nil。
func readJSONObject(path string) obj {
	f, err := openFile(path)
	if err != nil {
		return nil
	}
	defer f.Close()
	data, err := io.ReadAll(f)
	if err != nil || !utf8.Valid(data) {
		return nil
	}
	m, _ := parseObject(data)
	return m
}
