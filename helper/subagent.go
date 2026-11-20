package main

import (
	"bytes"
	"io"
	"os"
	"sort"
	"strings"
	"time"
)

// 子代理
//
// Claude Code 把每个子代理的记录写在 <项目>/<会话 id>/subagents/agent-<id>.jsonl，旁边的 agent-<id>.meta.json 记着
// 发起它的 Agent 调用（toolUseId）、类型、说明、模型，做完时加 finished，被停时加 stoppedByUser（2.1.328，ClaudeSubagentContractTest 钉住）。

const subagentChunk = 4 * 1024 * 1024 // 一次最多读这么多字节，余下的下次接着读

var subagentMetaKeys = []string{"agentType", "description", "model", "finished", "stoppedByUser", "requestShape"}

type subagentRequest struct {
	offset, line int64  // 从记录的这个字节（第几行）起读
	toolUse      string // 发起它的 Agent 调用
	hint         string // 已经知道的 agent id（task_started 的 task_id），空串表示不知道
	sessions     []string
}

// subagentDirs 是这些会话的 subagents 目录（真实路径在 ~/.claude/projects 里的）。
func (e *env) subagentDirs(sessions []string) []string {
	root := realPath(e.root) + "/"
	var dirs []string
	for _, session := range sessions {
		for _, project := range globNames(e.root, true, nil) {
			p := joinPath(joinPath(joinPath(e.root, project), session), "subagents")
			if !lexists(p) {
				continue
			}
			if real := realPath(p); strings.HasPrefix(real, root) && isDirFollow(real) {
				dirs = append(dirs, real)
			}
		}
	}
	return dirs
}

// findSubagent 找发起子代理的 Agent 调用 toolUse 对应的记录，返回 agent id、记录文件、meta。
// hint 是已经知道的 agent id，有它就不用逐个翻 meta。
func (e *env) findSubagent(toolUse, hint string, sessions []string) (agent, path string, meta obj, found bool, err error) {
	for _, dir := range e.subagentDirs(sessions) {
		if hint != "" {
			p := joinPath(dir, "agent-"+hint+".jsonl")
			meta := readJSONObject(strings.TrimSuffix(p, ".jsonl") + ".meta.json")
			if id := meta["toolUseId"]; isRegularFollow(p) && (id == nil || id == toolUse) {
				return hint, p, orObj(meta), true, nil
			}
		}
		names, err := readNames(dir)
		if err != nil {
			return "", "", nil, false, err
		}
		sort.Strings(names)
		for _, name := range names {
			if !strings.HasPrefix(name, "agent-") || !strings.HasSuffix(name, ".meta.json") || len(name) < 16 {
				continue
			}
			meta := readJSONObject(joinPath(dir, name))
			if len(meta) == 0 || meta["toolUseId"] != toolUse {
				continue
			}
			agent := name[len("agent-") : len(name)-len(".meta.json")]
			if p := joinPath(dir, "agent-"+agent+".jsonl"); isRegularFollow(p) {
				return agent, p, meta, true, nil
			}
		}
	}
	return "", "", nil, false, nil
}

func orObj(m obj) obj {
	if m == nil {
		return obj{}
	}
	return m
}

// subagentEvent 把子代理记录里的一行转成和 history 一样形状的事件；不显示的行是 nil。number 是行号（图片的位置要用）。
func subagentEvent(o obj, number int64) obj {
	kind := o["type"]
	if (kind != "user" && kind != "assistant") || truthy(o["isMeta"]) {
		return nil
	}
	message := asObj(o["message"])
	content := message["content"]
	if kind == "assistant" {
		blocks := assistantBlocks(content)
		if len(blocks) == 0 {
			return nil
		}
		return obj{"type": "assistant", "message": obj{"id": message["id"], "content": blocks}}
	}
	if hasToolResult(content) {
		return toolResultEvent(o, asList(content), int(number))
	}
	// 交给它的指令（第一行），以及之后续派给它的消息
	text := userText(content)
	blocks := imageRefs(content, int(number))
	if text != "" {
		blocks = append(blocks, obj{"type": "text", "text": clipText(text)})
	}
	if len(blocks) == 0 {
		return nil
	}
	e := obj{"type": "user", "message": obj{"content": blocks}}
	if number == 0 {
		e["prompt"] = true
	}
	return e
}

// subagentRead 是一次增量读的结果：读到的整行、读完后的位置，读满了没有。
type subagentRead struct {
	data         []byte // 只含写完了的整行
	offset, line int64
	full         bool
}

// readSubagentChunk 从 offset（第 line 行）起读最多 chunk 字节，只交出写完了的整行，半行留给下次。
// 一行比一次能读的还长（大块工具结果）时跳过这一行，下次从它后面接着读；这一行还没写完就原地不动。
func readSubagentChunk(f *os.File, offset, line int64, chunk int) (subagentRead, error) {
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return subagentRead{}, err
	}
	data, err := readUpTo(f, chunk)
	if err != nil {
		return subagentRead{}, err
	}
	r := subagentRead{offset: offset, line: line, full: len(data) == chunk}
	end := bytes.LastIndexByte(data, '\n') + 1
	if end > 0 || !r.full {
		r.data = data[:end]
		return r, nil
	}
	skip := int64(len(data))
	for {
		piece, err := readUpTo(f, chunk)
		if err != nil {
			return subagentRead{}, err
		}
		if len(piece) == 0 {
			return r, nil // 这一行还没写完
		}
		if i := bytes.IndexByte(piece, '\n'); i >= 0 {
			r.offset, r.line = offset+skip+int64(i)+1, line+1
			return r, nil
		}
		skip += int64(len(piece))
	}
}

// readUpTo 读满 n 字节，到文件末尾为止。
func readUpTo(f *os.File, n int) ([]byte, error) {
	buf := make([]byte, n)
	got, err := io.ReadFull(f, buf)
	if err == io.EOF || err == io.ErrUnexpectedEOF {
		err = nil
	}
	return buf[:got], err
}

// cmdSubagent 是子代理查看页的一次读：第一行是概要（记录文件、meta、读到哪了、这次读满了没有、多久没写了），
// 之后一行一个事件，形状同 history，交给它的指令带 prompt。找不到记录时只有一行 missing。
func cmdSubagent(e *env, o *emitter, req subagentRequest, chunk int) error {
	agent, path, meta, found, err := e.findSubagent(req.toolUse, req.hint, req.sessions)
	if err != nil {
		return err
	}
	if !found {
		o.out(obj{"type": "zeta_subagent", "missing": true})
		return nil
	}
	fi, err := os.Stat(path)
	if err != nil {
		return err
	}
	offset, line := req.offset, req.line
	reset := offset > fi.Size() // 记录只追加，变短了就是换了文件：从头读
	if reset {
		offset, line = 0, 0
	}
	f, err := openFile(path)
	if err != nil {
		return err
	}
	read, err := readSubagentChunk(f, offset, line, chunk)
	f.Close()
	if err != nil {
		return err
	}
	events, next := subagentEvents(read.data, read.line)
	skipped := max(0, len(events)-maxEvents)
	o.out(obj{"type": "zeta_subagent", "file": path, "agent_id": agent, "meta": pickKeys(meta, subagentMetaKeys),
		"offset": read.offset + int64(len(read.data)), "line": next, "more": read.full, "skipped": skipped, "reset": reset,
		"idle_ms": idleMillis(path)})
	for _, ev := range events[skipped:] {
		o.out(ev)
	}
	return nil
}

// subagentEvents 把读到的整行转成事件，返回事件和读完后的行号。
func subagentEvents(data []byte, line int64) ([]obj, int64) {
	var events []obj
	for len(data) > 0 {
		i := bytes.IndexByte(data, '\n')
		raw := data[:i]
		data = data[i+1:]
		number := line
		line++
		if !has(raw, `"type":"user"`) && !has(raw, `"type":"assistant"`) {
			continue
		}
		if o, ok := parseObject(raw); ok {
			if ev := subagentEvent(o, number); ev != nil {
				events = append(events, ev)
			}
		}
	}
	return events, line
}

func pickKeys(m obj, keys []string) obj {
	out := obj{}
	for _, k := range keys {
		if v, ok := m[k]; ok {
			out[k] = v
		}
	}
	return out
}

// idleMillis 是记录和 meta 多久都没动了。结局（meta 的 finished、输出流的 task_notification）可能先于最后几行写下，
// 界面据此判断收齐了没有（子代理等一个很久的回答时记录早就不动了，结束时 meta 才刚写）。
func idleMillis(path string) int64 {
	newest := 0.0
	for _, p := range []string{path, strings.TrimSuffix(path, ".jsonl") + ".meta.json"} {
		if fi, err := os.Stat(p); err == nil {
			newest = max(newest, mtimeOf(fi))
		}
	}
	now := float64(time.Now().UnixNano()) / 1e9
	return max(0, millis(now-newest))
}
