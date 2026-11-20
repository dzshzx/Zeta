package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"
)

// 导出：照 Eta 的会话导出，标题，然后按顺序是用户和助手的话；工具调用只留一行摘要，思考过程折叠起来，图片只记张数。

// exportBlock 是导出里的一块。工具调用的结果在后面几行，先记下调用，最后一起写。
type exportBlock struct {
	kind   string // user、notice、text、thinking、tool
	text   string
	images int
	toolID any
	name   string
	detail string
}

// toolDetailKeys 是各工具调用的一行摘要取哪个参数：命令、路径、关键词之类，照 Eta 导出里的“参数”。
var toolDetailKeys = map[string][]string{"Bash": {"command"}, "Read": {"file_path"}, "Write": {"file_path"}, "Edit": {"file_path"},
	"MultiEdit": {"file_path"}, "NotebookEdit": {"notebook_path"}, "Grep": {"pattern"},
	"Glob": {"pattern"}, "WebSearch": {"query"}, "WebFetch": {"url"}, "Task": {"description", "prompt"},
	"Agent": {"description", "prompt"}, "Skill": {"skill", "command"}}

func cmdExport(e *env, o *emitter, paths []string) {
	for _, path := range paths {
		md, title, err := exportSession(path)
		if err != nil { // 一个会话导不出来不影响别的
			o.out(obj{"type": "zeta_export_failed", "file": path})
			continue
		}
		o.out(obj{"type": "zeta_export", "file": path, "title": title, "markdown": md})
	}
}

func exportSession(path string) (markdown, title string, err error) {
	s, _, err := summarize(path)
	if err != nil {
		return "", "", err
	}
	title = s.title
	if title == "" {
		title = runePrefix(s.preview, 40)
	}
	if title == "" {
		title = "对话"
	}
	title = squash(title)
	blocks, results, err := exportBlocks(path)
	if err != nil {
		return "", "", err
	}
	return renderExport(title, blocks, results), title, nil
}

// exportReader 攒下导出要的块；results 是各工具调用的结果（按调用的 id）。
type exportReader struct {
	blocks  []exportBlock
	owners  []any // 每一块出自哪一行（uuid），遇到剪切记号时据此去掉被剪的部分
	results map[string]obj
	row     any
}

func (r *exportReader) add(b exportBlock) {
	r.blocks = append(r.blocks, b)
	r.owners = append(r.owners, r.row)
}

func exportBlocks(path string) ([]exportBlock, map[string]obj, error) {
	f, err := openFile(path)
	if err != nil {
		return nil, nil, err
	}
	defer f.Close()
	r := &exportReader{results: map[string]obj{}}
	lr := newLineReader(f)
	for line, ok := lr.next(); ok; line, ok = lr.next() {
		if !hasAny(line, `"type":"user"`, `"type":"assistant"`, `"queued_command"`, `"compact_boundary"`, `"rewound":true`) {
			continue
		}
		if cut, leaf, ok := rewindCut(line); ok {
			keep := cutAt(r.owners, cut, leaf)
			r.blocks, r.owners = r.blocks[:keep], r.owners[:keep]
			continue
		}
		if o, ok := parseObject(line); ok {
			r.row = o["uuid"]
			r.record(o, line)
		}
	}
	return r.blocks, r.results, lr.err
}

func (r *exportReader) record(o obj, line []byte) {
	if truthy(o["isSidechain"]) {
		return
	}
	kind := o["type"]
	content := asObj(o["message"])["content"]
	switch {
	case kind == "system":
		if o["subtype"] == "compact_boundary" {
			r.add(exportBlock{kind: "notice", text: "上下文已压缩"})
		}
	case kind == "user" && hasToolResult(content):
		for _, b := range asList(content) {
			if m, ok := b.(obj); ok && m["type"] == "tool_result" {
				r.results[valueKey(m["tool_use_id"])] = m
			}
		}
	case kind == "user" && !truthy(o["isMeta"]) && !truthy(o["isCompactSummary"]):
		r.user(content, line)
	case kind == "attachment":
		if _, text := spokenText(o); text != "" {
			r.add(exportBlock{kind: "user", text: text, images: imageCount(asObj(o["attachment"])["prompt"])})
		}
	case kind == "assistant":
		r.assistant(asList(content), line)
	}
}

func (r *exportReader) user(content any, line []byte) {
	raw := userText(content)
	if raw == "" && has(line, `"[Request interrupted`) {
		r.add(exportBlock{kind: "notice", text: "已停止"})
		return
	}
	if images := imageCount(content); raw != "" || images > 0 {
		r.add(exportBlock{kind: "user", text: raw, images: images})
	}
}

func (r *exportReader) assistant(content []any, line []byte) {
	var inputs []json.RawMessage // 工具参数的原文（保留键序），要时才取
	for index, b := range content {
		m, ok := b.(obj)
		if !ok {
			continue
		}
		kind := asString(m["type"])
		switch {
		case kind == "text" && strings.TrimSpace(asString(m["text"])) != "":
			r.add(exportBlock{kind: "text", text: strings.TrimSpace(asString(m["text"]))})
		case kind == "thinking" && strings.TrimSpace(asString(m["thinking"])) != "":
			r.add(exportBlock{kind: "thinking", text: strings.TrimSpace(asString(m["thinking"]))})
		case kind == "tool_use" || kind == "server_tool_use":
			if inputs == nil {
				inputs = rawInputs(line)
			}
			name := asString(m["name"])
			if name == "" {
				name = "工具"
			}
			var raw json.RawMessage
			if index < len(inputs) {
				raw = inputs[index]
			}
			r.add(exportBlock{kind: "tool", toolID: m["id"], name: name, detail: toolDetail(asString(m["name"]), m["input"], raw)})
		case truthy(m["tool_use_id"]) && strings.HasSuffix(kind, "_tool_result"):
			r.results[valueKey(m["tool_use_id"])] = obj{} // 服务端工具（advisor、网页搜索）的结果跟在同一条消息里
		}
	}
}

// rawInputs 是一行回答里各块 input 参数的原文，下标同消息内容。
func rawInputs(line []byte) []json.RawMessage {
	if !utf8.Valid(line) {
		line = fixUTF8(line)
	}
	var top, message map[string]json.RawMessage
	var content []json.RawMessage
	if json.Unmarshal(line, &top) != nil || json.Unmarshal(top["message"], &message) != nil ||
		json.Unmarshal(message["content"], &content) != nil {
		return []json.RawMessage{}
	}
	inputs := make([]json.RawMessage, len(content))
	for i, c := range content {
		var block map[string]json.RawMessage
		if json.Unmarshal(c, &block) == nil {
			inputs[i] = block["input"]
		}
	}
	return inputs
}

// toolDetail 是工具调用的一行摘要：认得的工具取命令、路径之类（多行只取第一行），别的把参数写成一行 JSON。
func toolDetail(name string, input any, raw json.RawMessage) string {
	data, ok := input.(obj)
	if !ok {
		return ""
	}
	for _, key := range toolDetailKeys[name] {
		if v := data[key]; truthy(v) {
			text := strings.TrimSpace(plainText(v))
			first, _, multi := strings.Cut(text, "\n")
			if multi {
				first += " …"
			}
			return oneLine(first, 120)
		}
	}
	if len(data) == 0 {
		return ""
	}
	text, ok := spacedJSON(raw)
	if !ok {
		text, _ = spacedJSON([]byte(compactJSON(data)))
	}
	return oneLine(text, 120)
}

func quoteBlock(lines []string) string {
	parts := strings.Split(strings.Join(lines, "\n\n"), "\n")
	for i, l := range parts {
		if l == "" {
			parts[i] = ">"
		} else {
			parts[i] = "> " + l
		}
	}
	return strings.Join(parts, "\n")
}

func renderExport(title string, blocks []exportBlock, results map[string]obj) string {
	parts := []string{"# " + title}
	answering := false
	for _, b := range blocks {
		switch b.kind {
		case "user":
			parts = append(parts, renderUser(b))
			answering = false
			continue
		case "notice":
			parts = append(parts, quoteBlock([]string{b.text}))
			answering = false
			continue
		}
		// 一轮回答里的文字、思考和工具调用归在同一个“助手”标题下
		if !answering {
			parts = append(parts, "## 助手")
			answering = true
		}
		switch b.kind {
		case "text":
			parts = append(parts, b.text)
		case "thinking":
			parts = append(parts, "<details>\n<summary>思考过程</summary>\n\n"+b.text+"\n\n</details>")
		default:
			parts = append(parts, renderTool(b, results))
		}
	}
	return strings.Join(parts, "\n\n") + "\n"
}

func renderUser(b exportBlock) string {
	var files, body []string
	for _, l := range strings.Split(b.text, "\n") {
		if p, ok := attachedPath(l); ok {
			files = append(files, p)
		} else {
			body = append(body, l)
		}
	}
	block := "## 用户"
	if text := strings.TrimSpace(strings.Join(body, "\n")); text != "" {
		block += "\n\n" + text
	}
	for _, name := range files {
		block += "\n\n- " + name
	}
	if b.images > 0 {
		// 图片本体太大，只记张数
		block += fmt.Sprintf("\n\n_图片：%d 张_", b.images)
	}
	return block
}

func renderTool(b exportBlock, results map[string]obj) string {
	result, known := results[valueKey(b.toolID)]
	status := "结果未知"
	if known {
		status = "已完成"
		if truthy(result["is_error"]) {
			status = "失败"
		}
	}
	lines := []string{fmt.Sprintf("工具：%s（%s）", b.name, status)}
	if b.detail != "" {
		lines = append(lines, "参数："+b.detail)
	}
	if known && truthy(result["is_error"]) {
		if text := errorText(result["content"]); squash(text) != "" {
			lines = append(lines, "结果："+oneLine(ansiEscape.ReplaceAllString(text, ""), 120))
		}
	}
	return quoteBlock(lines)
}

// errorText 是出错的工具结果的文字：内容是块数组时把文字块用空格连起来。
func errorText(content any) string {
	list, ok := content.([]any)
	if !ok {
		if !truthy(content) {
			return ""
		}
		return plainText(content)
	}
	var parts []string
	for _, b := range list {
		if m, ok := b.(obj); ok && m["type"] == "text" {
			parts = append(parts, asString(m["text"]))
		}
	}
	return strings.Join(parts, " ")
}
