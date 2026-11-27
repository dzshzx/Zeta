package usage

import (
	"bytes"
	"os"
)

// Carried 是一个 Claude Code 进程起来时带着的累计：之后 result 行的 modelUsage、total_cost_usd 从它接着加。
// 全新会话是零。
//
// --resume 续上会话（含 --fork-session 分支）时，Claude Code 从那份会话记录里最后一行 cost-state 接着累计
// （2.1.332 实测：cost-state 只在进程退出时写下，正常退出和 SIGTERM 都写，SIGKILL 不写；被 SIGKILL 的那个进程的用量
// Claude Code 自己也丢了，续上时还是从最后一行接着算；cost-state 的累计含子代理）。所以起进程前读同一行就是它的基线。
type Carried struct {
	Tokens  Tokens
	CostUSD float64
}

// costState 是会话记录里 cost-state 一行用得着的部分。
type costState struct {
	Type         string                `json:"type"`
	TotalCostUSD float64               `json:"totalCostUSD"`
	ModelUsage   map[string]modelUsage `json:"modelUsage"`
}

// costStateMarker 只用来找候选行；记录里的文字（比如工具结果）提到它时引号是转义过的，对不上，对上了也再按整行核一遍。
var costStateMarker = []byte(`"type":"cost-state"`)

// costStateChunk 是从文件末尾往前一次读多少。最后一行 cost-state 通常就在末尾附近（上一个进程退出时写的）。
const costStateChunk = 1 << 20

// LastCostState 是会话记录 path 里最后一行 cost-state 记的累计；一行都没有时是零（Claude Code 也从零开始）。
// 从文件末尾往前一段段找，读不了时出错。
func LastCostState(path string) (Carried, error) {
	f, err := os.Open(path)
	if err != nil {
		return Carried{}, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return Carried{}, err
	}
	var tail []byte // 上一段开头那行没读全的部分（属于更前面一段的那行的后半截）
	for end := info.Size(); end > 0; {
		start := max(0, end-costStateChunk)
		buf := make([]byte, end-start, end-start+int64(len(tail)))
		if _, err := f.ReadAt(buf, start); err != nil {
			return Carried{}, err
		}
		data := append(buf, tail...)
		// 这一段里第一个换行之前的那行可能从更前面开始，留到下一段拼完整再看
		whole := data
		if start > 0 {
			nl := bytes.IndexByte(data, '\n')
			if nl < 0 {
				tail, end = data, start
				continue
			}
			whole, tail = data[nl+1:], data[:nl+1]
		}
		if c, ok := lastCostStateIn(whole); ok {
			return c, nil
		}
		end = start
	}
	return Carried{}, nil
}

// lastCostStateIn 是 data（若干整行）里最后一行 cost-state。
func lastCostStateIn(data []byte) (Carried, bool) {
	for i := bytes.LastIndex(data, costStateMarker); i >= 0; i = bytes.LastIndex(data, costStateMarker) {
		lineStart := bytes.LastIndexByte(data[:i], '\n') + 1
		line := data[lineStart:]
		if nl := bytes.IndexByte(line, '\n'); nl >= 0 {
			line = line[:nl]
		}
		var s costState
		if decode(line, &s) && s.Type == "cost-state" {
			tokens, _ := sumModels(s.ModelUsage)
			return Carried{Tokens: tokens, CostUSD: s.TotalCostUSD}, true
		}
		data = data[:lineStart]
	}
	return Carried{}, false
}
