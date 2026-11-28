package main

import (
	"bytes"
	"encoding/json/jsontext"
	jsonv2 "encoding/json/v2"
	"maps"
	"slices"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/agystore"
	ctxusage "github.com/dzshzx/Zeta/helper/usage"
)

// 子代理的用量
//
// 子代理的消息不在主对话的记录里，记在 <会话目录>/subagents/agent-<id>.jsonl，所以只读主记录算出的每轮用量不含子代理。
// history 输出一页时，对这一页里每个发起子代理的调用（meta.json 的 toolUseId 对得上的），在那条回答后面跟一行
// {"type":"subagent_usage","tool_use_id","models":[{"model","usage"}]}：它（连同它自己又派的子代理）各模型的 token 数之和，
// 核心把它算进那一轮（helper/usage 的 ObserveHistory）。只读这一页用到的子代理记录，里面也只解 assistant 行。
// 每个调用只出现在一条回答里，所以一次 history 里同一份记录只读一遍，不另外缓存。

// subagentUsages 是一个会话的子代理：Claude 按记录文件路径记，agy 按预统计的各模型 token 数记。
type subagentUsages struct {
	claude map[string]string
	agy    map[string]map[string][3]int64
}

// newSubagentUsages 读会话记录 path 的会话目录（sessionDir）里 subagents 下各子代理的 meta（只认真实路径在会话目录里的）；
// 对 agy 会话则汇总其子会话 transcript 的 token 数。
func newSubagentUsages(e *env, path string) subagentUsages {
	if agyDir, _, ok := agyDirFromTranscript(path); ok {
		return subagentUsages{agy: agystore.ChildTokenModels(agyDir, path)}
	}
	session, ok := e.sessionDir(path)
	if !ok {
		return subagentUsages{}
	}
	dir := joinPath(session, "subagents")
	if !isDirFollow(dir) || !strings.HasPrefix(realPath(dir), session+"/") {
		return subagentUsages{}
	}
	s := map[string]string{}
	_ = eachSubagent(dir, func(_, file string, meta obj) bool {
		if toolUse := asString(meta["toolUseId"]); toolUse != "" {
			s[toolUse] = file
		}
		return true
	})
	return subagentUsages{claude: s}
}

// event 是发起子代理的调用 toolUse 后面跟的那一行；子代理没有用量时是 nil。
func (s subagentUsages) event(toolUse string) obj {
	models := map[string]ctxusage.Tokens{}
	if totals, ok := s.agy[toolUse]; ok {
		for m, v := range totals {
			models[m] = ctxusage.Tokens{Input: v[0], Output: v[1], CacheRead: v[2]}
		}
	} else {
		s.total(toolUse, models, map[string]bool{})
	}
	if len(models) == 0 {
		return nil
	}
	var list []any
	for _, m := range slices.Sorted(maps.Keys(models)) {
		list = append(list, obj{"model": m, "usage": ctxusage.APITokens(models[m])})
	}
	return obj{"type": "subagent_usage", "tool_use_id": toolUse, "models": list}
}

// total 把调用 toolUse 发起的子代理连同它的子代理各模型的 token 数加进 into。seen 防着记录里绕回来。
func (s subagentUsages) total(toolUse string, into map[string]ctxusage.Tokens, seen map[string]bool) {
	file, ok := s.claude[toolUse]
	if !ok || seen[toolUse] {
		return
	}
	seen[toolUse] = true
	for _, child := range s.readAgent(file, into) {
		s.total(child, into, seen)
	}
}

// agentLine 是子代理记录里一条 assistant 行用得着的部分。
type agentLine struct {
	Message struct {
		ID      string              `json:"id"`
		Model   string              `json:"model"`
		Usage   *ctxusage.APITokens `json:"usage"`
		Content jsontext.Value      `json:"content"`
	} `json:"message"`
}

// readAgent 把一个子代理记录的用量按模型加进 into，返回它又发起的子代理（调用的 id）。一条消息按内容块拆成几行，
// 每行都带同一份 usage，按消息 id 只算一次（取最后一行的）；Claude Code 合成的回答没有请求模型，不算。读不了时什么也不加。
func (s subagentUsages) readAgent(file string, into map[string]ctxusage.Tokens) (children []string) {
	f, err := openFile(file)
	if err != nil {
		return nil
	}
	defer f.Close()
	type counted struct {
		model  string
		tokens ctxusage.Tokens
	}
	byID := map[string]counted{}
	lr := newLineReader(f)
	for n := 0; ; n++ {
		line, ok := lr.next()
		if !ok {
			break
		}
		var l agentLine
		if !has(line, `"type":"assistant"`) || jsonv2.Unmarshal(line, &l) != nil {
			continue
		}
		children = append(children, s.calls(l.Message.Content)...)
		m := l.Message
		if m.Usage == nil || !realModel(m.Model) {
			continue
		}
		id := m.ID
		if id == "" {
			id = "#" + strconv.Itoa(n)
		}
		byID[id] = counted{m.Model, ctxusage.Tokens(*m.Usage)}
	}
	for _, c := range byID {
		into[c.model] = into[c.model].Add(c.tokens)
	}
	return children
}

// calls 是回答内容里发起了子代理的工具调用（内容还没解开）。只解每块的类型和 id：工具参数可能很大（写入的整个文件），不解开它。
func (s subagentUsages) calls(content jsontext.Value) []string {
	if !bytes.Contains(content, []byte(`"tool_use"`)) {
		return nil
	}
	var blocks []struct {
		Type string `json:"type"`
		ID   string `json:"id"`
	}
	if jsonv2.Unmarshal(content, &blocks) != nil {
		return nil
	}
	var out []string
	for _, b := range blocks {
		if s.isCall(b.Type, b.ID) {
			out = append(out, b.ID)
		}
	}
	return out
}

// subagentCalls 是一条 history 事件（assistant）里发起了子代理的工具调用。
func (s subagentUsages) subagentCalls(e obj) []string {
	if e["type"] != "assistant" {
		return nil
	}
	var out []string
	for _, b := range asList(asObj(e["message"])["content"]) {
		m := asObj(b)
		if id := asString(m["id"]); s.isCall(asString(m["type"]), id) {
			out = append(out, id)
		}
	}
	return out
}

// isCall：这个内容块是发起了子代理（登记过）的工具调用。
func (s subagentUsages) isCall(kind, id string) bool {
	if kind != "tool_use" {
		return false
	}
	_, inClaude := s.claude[id]
	_, inAgy := s.agy[id]
	return inClaude || inAgy
}
