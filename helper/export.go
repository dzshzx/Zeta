package main

import (
	"cmp"
	"encoding/json"
	"strings"
	"unicode/utf8"

	"github.com/dzshzx/Zeta/helper/agystore"
	"github.com/dzshzx/Zeta/helper/locales"
)

// 导出：照 Eta 的会话导出，标题，然后按顺序是用户和助手的话；工具调用只留一行摘要，思考过程折叠起来，图片只记张数。

// exportBlock 是导出里的一块。工具调用的结果在后面几行，先记下调用，最后一起写。
type exportBlock struct {
	kind   string // user、peer（另一个会话发来的，name 是发信会话）、notice、text、thinking、tool
	text   string
	notice locales.Msg // notice：核心自己写的那句，导出时按请求的语言出字
	images int
	toolID any
	name   string
	detail string
}

// toolDetailKeys 是各工具调用的一行摘要取哪个参数：命令、路径、关键词之类，照 Eta 导出里的“参数”。
var toolDetailKeys = map[string][]string{
	"Bash": {"command"}, "Read": {"file_path"}, "Write": {"file_path"}, "Edit": {"file_path"},
	"MultiEdit": {"file_path"}, "NotebookEdit": {"notebook_path"}, "Grep": {"pattern"},
	"Glob": {"pattern"}, "WebSearch": {"query"}, "WebFetch": {"url"}, "Task": {"description", "prompt"},
	"Agent": {"description", "prompt"}, "Skill": {"skill", "command"},
	"run_command": {"CommandLine", "command"}, "read_file": {"Path", "file_path"},
	"write_file": {"Path", "file_path"}, "edit_file": {"Path", "file_path", "Target"},
	"grep_search": {"Query", "SearchPath"}, "find_by_name": {"Pattern", "SearchDirectory"},
	"list_dir": {"DirectoryPath", "Path"}, "search_web": {"query", "Query"},
	"read_url_content": {"Url", "url"}, "invoke_subagent": {"Role", "Prompt"},
	"send_message": {"Recipient", "Message"}, "run_workflow": {"Description", "ScriptPath"},
	"manage_task": {"Action", "TaskId"}, "schedule": {"Prompt"},
}

func cmdExport(e *env, o *emitter, paths []string) {
	for _, path := range paths {
		md, title, err := exportSession(path, "")
		if err != nil { // 一个会话导不出来不影响别的
			o.out(obj{"type": "zeta_export_failed", "file": path})
			continue
		}
		o.out(obj{"type": "zeta_export", "file": path, "title": title, "markdown": md})
	}
}

// exportSession 导出会话记录 path：标题和 Markdown。核心自己写的字用语言 lang（发起导出的客户端的语言）。
func exportSession(path, lang string) (markdown, title string, err error) {
	s, _, err := summarize(path)
	if err != nil {
		return "", "", err
	}
	title = s.title
	if title == "" && s.previewCode() != "" {
		title = locales.M("previewImage").In(lang)
	}
	if title == "" {
		title = runePrefix(s.preview, 40)
	}
	if title == "" {
		title = locales.M("exportUntitled").In(lang)
	}
	title = squash(title)
	blocks, results, err := exportBlocks(path)
	if err != nil {
		return "", "", err
	}
	return renderExport(title, blocks, results, lang), title, nil
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
	if _, _, ok := agyDirFromTranscript(path); ok {
		return exportAgyBlocks(path)
	}
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

func exportAgyBlocks(path string) ([]exportBlock, map[string]obj, error) {
	tl, err := agystore.ReadTimeline(path)
	if err != nil {
		return nil, nil, err
	}
	r := &exportReader{results: map[string]obj{}}
	for i, ev := range tl.Events {
		if i < len(tl.Owners) {
			r.row = tl.Owners[i]
		}
		r.record(ev, nil)
	}
	return r.blocks, r.results, nil
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
			r.add(exportBlock{kind: "notice", notice: locales.M("noticeCompacted")})
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
		if name, body, ok := peerMessage(asObj(o["attachment"])); ok {
			if !truthy(o["isSidechain"]) && body != "" {
				r.add(exportBlock{kind: "peer", name: name, text: body})
			}
			return
		}
		if _, text := spokenText(o, ""); text != "" { // 另一个会话发来的在上面，这里没有核心加的字
			r.add(exportBlock{kind: "user", text: text, images: imageCount(asObj(o["attachment"])["prompt"])})
		}
	case kind == "assistant":
		r.assistant(asList(content), line)
	}
}

func (r *exportReader) user(content any, line []byte) {
	raw := userText(content)
	if raw == "" && has(line, `"[Request interrupted`) {
		r.add(exportBlock{kind: "notice", notice: locales.M("noticeStopped")})
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
			name := asString(m["name"]) // 空的导出时写「工具」
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

// renderExport 把块写成 Markdown；导出里核心自己写的字（标题、状态）用语言 lang。
func renderExport(title string, blocks []exportBlock, results map[string]obj, lang string) string {
	parts := []string{"# " + title}
	answering := false
	for _, b := range blocks {
		switch b.kind {
		case "user":
			parts = append(parts, renderUser(b, lang))
			answering = false
			continue
		case "peer":
			parts = append(parts, "## "+locales.M("exportPeer", "name", b.name).In(lang)+"\n\n"+b.text)
			answering = false
			continue
		case "notice":
			parts = append(parts, quoteBlock([]string{b.notice.In(lang)}))
			answering = false
			continue
		}
		// 一轮回答里的文字、思考和工具调用归在同一个“助手”标题下
		if !answering {
			parts = append(parts, "## "+locales.M("exportAssistant").In(lang))
			answering = true
		}
		switch b.kind {
		case "text":
			parts = append(parts, b.text)
		case "thinking":
			parts = append(parts, "<details>\n<summary>"+locales.M("exportThinking").In(lang)+"</summary>\n\n"+b.text+"\n\n</details>")
		default:
			parts = append(parts, renderTool(b, results, lang))
		}
	}
	return strings.Join(parts, "\n\n") + "\n"
}

func renderUser(b exportBlock, lang string) string {
	var files, body []string
	for _, l := range strings.Split(b.text, "\n") {
		if p, ok := attachedPath(l); ok {
			files = append(files, p)
		} else {
			body = append(body, l)
		}
	}
	block := "## " + locales.M("exportUser").In(lang)
	if text := strings.TrimSpace(strings.Join(body, "\n")); text != "" {
		block += "\n\n" + text
	}
	for _, name := range files {
		block += "\n\n- " + name
	}
	if b.images > 0 {
		// 图片本体太大，只记张数
		block += "\n\n_" + locales.M("exportImages", "count", b.images).In(lang) + "_"
	}
	return block
}

func renderTool(b exportBlock, results map[string]obj, lang string) string {
	result, known := results[valueKey(b.toolID)]
	status := locales.M("exportToolUnknown")
	switch {
	case known && truthy(result["is_error"]):
		status = locales.M("exportToolFailed")
	case known:
		status = locales.M("exportToolDone")
	}
	name := cmp.Or(b.name, locales.M("exportToolNameless").In(lang))
	lines := []string{locales.M("exportTool", "name", name, "status", status).In(lang)}
	if b.detail != "" {
		lines = append(lines, locales.M("exportToolInput", "detail", b.detail).In(lang))
	}
	if known && truthy(result["is_error"]) {
		if text := errorText(result["content"]); squash(text) != "" {
			lines = append(lines, locales.M("exportToolResult", "detail", oneLine(ansiEscape.ReplaceAllString(text, ""), 120)).In(lang))
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
