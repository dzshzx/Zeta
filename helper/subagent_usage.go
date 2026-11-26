package main

import (
	"bytes"
	"encoding/json/jsontext"
	jsonv2 "encoding/json/v2"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
)

// 子代理的用量
//
// 子代理的消息不在主对话的记录里，记在 <会话目录>/subagents/agent-<id>.jsonl，所以只读主记录算出的每轮用量不含子代理。
// history 输出一页时，对这一页里每个发起子代理的调用（meta.json 的 toolUseId 对得上的），在那条回答后面跟一行
// {"type":"subagent_usage","tool_use_id","models":[{"model","usage"}]}：它（连同它自己又派的子代理）各模型的 token 数之和，
// 核心把它算进那一轮（helper/usage 的 ObserveHistory）。只读这一页用到的子代理记录，里面也只解 assistant 行。

// event 是发起子代理的调用 toolUse 后面跟的那一行；子代理没有用量时是 nil。
func (s *subagentUsages) event(toolUse string) obj {
	models := s.total(toolUse, map[string]bool{})
	if len(models) == 0 {
		return nil
	}
	names := make([]string, 0, len(models))
	for m := range models {
		names = append(names, m)
	}
	slices.Sort(names)
	list := make([]any, 0, len(names))
	for _, m := range names {
		t := models[m]
		list = append(list, obj{"model": m, "usage": obj{
			"input_tokens": t.input, "output_tokens": t.output,
			"cache_read_input_tokens": t.cacheRead, "cache_creation_input_tokens": t.cacheWrite,
		}})
	}
	return obj{"type": "subagent_usage", "tool_use_id": toolUse, "models": list}
}

// subagentUsages 是一个会话的子代理：发起它的调用 → 记录文件，和读过的记录的用量。
type subagentUsages struct {
	files map[string]string // toolUseId → agent-<id>.jsonl
	read  map[string]*agentUsage
}

// agentUsage 是一个子代理记录里各模型的 token 数，和它又派的子代理（发起它们的调用）。
type agentUsage struct {
	models   map[string]tokenSum
	children []string
}

type tokenSum struct{ input, output, cacheRead, cacheWrite int64 }

func (t tokenSum) add(o tokenSum) tokenSum {
	return tokenSum{t.input + o.input, t.output + o.output, t.cacheRead + o.cacheRead, t.cacheWrite + o.cacheWrite}
}

// newSubagentUsages 读会话记录 path 旁边的 subagents 目录里各子代理的 meta（只认真实路径在会话目录里的）；没有时为 nil。
func newSubagentUsages(path string) *subagentUsages {
	dir := strings.TrimSuffix(path, ".jsonl") + "/subagents"
	base := realPath(filepath.Dir(path)) + "/"
	if !lexists(dir) || !isDirFollow(dir) || !strings.HasPrefix(realPath(dir), base) {
		return nil
	}
	names, err := readNames(dir)
	if err != nil {
		return nil
	}
	s := &subagentUsages{files: map[string]string{}, read: map[string]*agentUsage{}}
	for _, name := range names {
		if !strings.HasPrefix(name, "agent-") || !strings.HasSuffix(name, ".meta.json") {
			continue
		}
		toolUse := asString(readJSONObject(joinPath(dir, name))["toolUseId"])
		file := joinPath(dir, strings.TrimSuffix(name, ".meta.json")+".jsonl")
		if toolUse != "" && isRegularFollow(file) {
			s.files[toolUse] = file
		}
	}
	if len(s.files) == 0 {
		return nil
	}
	return s
}

// total 是调用 toolUse 发起的子代理连同它的子代理各模型的 token 数。seen 防着记录里绕回来。
func (s *subagentUsages) total(toolUse string, seen map[string]bool) map[string]tokenSum {
	if s == nil || seen[toolUse] {
		return nil
	}
	seen[toolUse] = true
	a := s.agent(toolUse)
	if a == nil {
		return nil
	}
	out := map[string]tokenSum{}
	for m, t := range a.models {
		out[m] = out[m].add(t)
	}
	for _, child := range a.children {
		for m, t := range s.total(child, seen) {
			out[m] = out[m].add(t)
		}
	}
	return out
}

func (s *subagentUsages) agent(toolUse string) *agentUsage {
	if a, ok := s.read[toolUse]; ok {
		return a
	}
	file, ok := s.files[toolUse]
	if !ok {
		return nil
	}
	a := s.readAgent(file)
	s.read[toolUse] = a
	return a
}

// agentLine 是子代理记录里一条 assistant 行用得着的部分。
type agentLine struct {
	Message struct {
		ID      string          `json:"id"`
		Model   string          `json:"model"`
		Usage   *agentLineUsage `json:"usage"`
		Content jsontext.Value  `json:"content"`
	} `json:"message"`
}

type agentLineUsage struct {
	Input      int64 `json:"input_tokens"`
	Output     int64 `json:"output_tokens"`
	CacheRead  int64 `json:"cache_read_input_tokens"`
	CacheWrite int64 `json:"cache_creation_input_tokens"`
}

// readAgent 数一个子代理记录的用量。一条消息按内容块拆成几行，每行都带同一份 usage，按消息 id 只算一次（取最后一行的）；
// Claude Code 合成的回答（模型是 <synthetic>）没有请求模型，不算。读不了时为 nil。
func (s *subagentUsages) readAgent(file string) *agentUsage {
	f, err := openFile(file)
	if err != nil {
		return nil
	}
	defer f.Close()
	type counted struct {
		model  string
		tokens tokenSum
	}
	var order []string
	byID := map[string]counted{}
	a := &agentUsage{models: map[string]tokenSum{}}
	lr := newLineReader(f)
	for n := 0; ; n++ {
		line, ok := lr.next()
		if !ok {
			break
		}
		if !has(line, `"type":"assistant"`) {
			continue
		}
		var l agentLine
		if jsonv2.Unmarshal(line, &l) != nil {
			continue
		}
		a.children = append(a.children, s.calls(l.Message.Content)...)
		m := l.Message
		if m.Usage == nil || m.Model == "" || strings.HasPrefix(m.Model, "<") {
			continue
		}
		id := m.ID
		if id == "" {
			id = "#" + strconv.Itoa(n)
		}
		if _, seen := byID[id]; !seen {
			order = append(order, id)
		}
		byID[id] = counted{m.Model, tokenSum{m.Usage.Input, m.Usage.Output, m.Usage.CacheRead, m.Usage.CacheWrite}}
	}
	for _, id := range order {
		c := byID[id]
		a.models[c.model] = a.models[c.model].add(c.tokens)
	}
	return a
}

// calls 是回答内容里发起了子代理的工具调用。
func (s *subagentUsages) calls(content jsontext.Value) []string {
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
		if _, ok := s.files[b.ID]; ok && b.Type == "tool_use" {
			out = append(out, b.ID)
		}
	}
	return out
}

// subagentCalls 是一条 history 事件（assistant）里发起了子代理的工具调用。
func (s *subagentUsages) subagentCalls(e obj) []string {
	if s == nil || e["type"] != "assistant" {
		return nil
	}
	var out []string
	for _, b := range asList(asObj(e["message"])["content"]) {
		m := asObj(b)
		if id := asString(m["id"]); m["type"] == "tool_use" && id != "" {
			if _, ok := s.files[id]; ok {
				out = append(out, id)
			}
		}
	}
	return out
}
