package main

import (
	"io"
	"math"
	"strconv"

	"github.com/dzshzx/Zeta/helper/lastline"
)

// 侧栏的几种排序要的时刻和位置（docs/core.md「会话」）：发起时间 created、最新回答时间 replied、手动排序的位置 rank。

// replyMark 是记录前 size 字节里最后一次回答完的时刻 at（毫秒，没有时是 0）。会话列表的订阅拿上次读到的这个，
// 文件只是变长了时只往回找到 size 为止，长时间跑工具、几十 MB 没有回答的会话不必每次从头找。
type replyMark struct {
	size, at int64
}

// isReply：这一行是主线上一轮回答完的那条 assistant 消息（stop_reason 不是 tool_use、pause_turn），返回它的时刻（毫秒）。
// 工具结果、元数据、子代理的消息都不算。
func isReply(line []byte) (int64, bool) {
	// 先按子串筛：跑工具的会话里多是 tool_use 的行，常带着很大的工具输入，不必解析
	if !has(line, `"stop_reason":"`) || !has(line, `"type":"assistant"`) ||
		has(line, `"stop_reason":"tool_use"`) || has(line, `"stop_reason":"pause_turn"`) || has(line, `"isSidechain":true`) {
		return 0, false
	}
	o, ok := parseObject(line)
	if !ok || o["type"] != "assistant" || truthy(o["isSidechain"]) {
		return 0, false
	}
	switch asString(asObj(o["message"])["stop_reason"]) {
	case "", "tool_use", "pause_turn":
		return 0, false
	}
	at := entryTime(o)
	return at, at > 0
}

// entryTime 是记录里一行的 timestamp（毫秒，epochMillis）；没有或读不了时是 0。
func entryTime(o obj) int64 {
	ms, _ := epochMillis(o["timestamp"])
	return ms
}

// lastReplyIn 往回找 [lo, hi) 里最后一次回答完的时刻，没有时是 0。lo 要是一行的开头；hi 一般是一行的开头，不是时那半行不要。
func lastReplyIn(f io.ReaderAt, lo, hi int64) (int64, error) {
	var at int64
	_, err := lastline.Find(f, lo, hi, func(line []byte) bool {
		t, ok := isReply(line)
		at = t
		return ok
	})
	return at, err
}

// sessionOrder 是手动排序时各会话的位置：会话 id → rank，大的在上面。没拖过的不在里面，位置就是它的发起时间（summary.rank），
// 所以新会话在最上面，几台设备的会话也能放在一起比。
func (e *env) sessionOrder() map[string]float64 {
	order := map[string]float64{}
	for id, value := range readMarkLines(e.order) {
		if rank, err := strconv.ParseFloat(value, 64); err == nil && !math.IsInf(rank, 0) && !math.IsNaN(rank) {
			order[id] = rank
		}
	}
	return order
}

// orderSession 把会话 id 的位置记成 rank（sessions.order）。文件 ~/.config/<name>/order 一行「会话号 位置」（editMarks）。
func orderSession(e *env, id string, rank float64) error {
	return editMarks(e, e.order, func(marks map[string]string) {
		marks[id] = strconv.FormatFloat(rank, 'g', -1, 64)
	})
}
