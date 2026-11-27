package usage

import (
	"bytes"
	"os"

	"github.com/dzshzx/Zeta/helper/lastline"
)

// Carried 是一个 Claude Code 进程在这边接上之前已经累计到的数：之后 result 行的 modelUsage、total_cost_usd 从它接着加。
//
//   - 全新会话起的进程是零。
//   - --resume 续上会话（含 --fork-session 分支）时，Claude Code 从那份会话记录里最后一行 cost-state 接着累计
//     （2.1.332 实测：cost-state 只在进程退出时写下，正常退出和 SIGTERM 都写，SIGKILL 不写；被 SIGKILL 的那个进程的用量
//     Claude Code 自己也丢了，续上时还是从最后一行接着算；cost-state 的累计含子代理）。所以起进程前读同一行（LastCostState）。
//   - 接上已在跑的进程时，是它输出日志里接上那一行之前最后一个 result 的累计（LastResultBefore）。
type Carried struct {
	Tokens  Tokens
	CostUSD float64
}

// cumulative 是 cost-state 行和 result 行里累计的那几项（两种行的费用字段名不一样）。
type cumulative struct {
	Type       string                `json:"type"`
	CostState  float64               `json:"totalCostUSD"`
	Result     *float64              `json:"total_cost_usd"`
	ModelUsage map[string]modelUsage `json:"modelUsage"`
}

// 只用来先筛候选行：记录里的文字（比如工具结果）提到它们时引号是转义过的，对不上，对上了也再按整行核一遍。
var (
	costStateMarker = []byte(`"type":"cost-state"`)
	resultMarker    = []byte(`"type":"result"`)
)

// LastCostState 是会话记录 path 里最后一行 cost-state 记的累计；一行都没有时是零（Claude Code 也从零开始）。读不了时出错。
func LastCostState(path string) (Carried, error) {
	var c Carried
	_, err := findLast(path, 0, costStateMarker, func(s cumulative) bool {
		if s.Type != "cost-state" {
			return false
		}
		c.Tokens, _ = sumModels(s.ModelUsage)
		c.CostUSD = s.CostState
		return true
	})
	return c, err
}

// LastResultBefore 是托管输出日志 path 里第 line 行（从 1 起）之前最后一个 result 行的累计；ok 为 false 时那之前没有
// 带累计的 result（进程还一轮没跑完）。读不了时出错。
func LastResultBefore(path string, line int) (c Carried, ok bool, err error) {
	ok, err = findLast(path, line, resultMarker, func(s cumulative) bool {
		if s.Type != "result" || s.Result == nil || len(s.ModelUsage) == 0 {
			return false
		}
		c.Tokens, _ = sumModels(s.ModelUsage)
		c.CostUSD = *s.Result
		return true
	})
	return c, ok, err
}

// findLast 往回找 path 里第 before 行之前（before 为 0 时是整个文件）最后一行带 marker、match 认的。
func findLast(path string, before int, marker []byte, match func(cumulative) bool) (bool, error) {
	f, err := os.Open(path)
	if err != nil {
		return false, err
	}
	defer f.Close()
	var hi int64
	if before > 0 {
		hi, err = lastline.LineStart(f, before)
	} else {
		var info os.FileInfo
		if info, err = f.Stat(); err == nil {
			hi = info.Size()
		}
	}
	if err != nil {
		return false, err
	}
	return lastline.Find(f, 0, hi, func(line []byte) bool {
		var s cumulative
		return bytes.Contains(line, marker) && decode(line, &s) && match(s)
	})
}
