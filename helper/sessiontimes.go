package main

import (
	"bytes"
	"io"
	"maps"
	"math"
	"os"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// 侧栏的几种排序要的时刻和位置（docs/core.md「会话」）：发起时间 created、最新回答时间 replied、手动排序的位置 rank。

// replyMark 是记录前 size 字节里最后一次回答完的时刻 at（毫秒，没有时是 0）。会话列表的订阅拿上次读到的这个，
// 文件只是变长了时只往回找到 size 为止，长时间跑工具、几十 MB 没有回答的会话不必每次从头找。
type replyMark struct {
	size, at int64
}

// replyChunk：结尾那段里没有回答时往回一次读这么多。
const replyChunk = 1 << 20

// isReply：这一行是主线上一轮回答完的那条 assistant 消息（stop_reason 不是 tool_use、pause_turn），返回它的时刻（毫秒）。
// 工具结果、元数据、子代理的消息都不算。
func isReply(line []byte) (int64, bool) {
	if !has(line, `"stop_reason":"`) || !has(line, `"type":"assistant"`) {
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

// entryTime 是记录里一行的 timestamp（毫秒）；没有或读不了时是 0。
func entryTime(o obj) int64 {
	t, err := time.Parse(time.RFC3339Nano, asString(o["timestamp"]))
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

// lastReplyIn 往回找 [lo, hi) 里最后一次回答完的时刻，没有时是 0。lo 要是一行的开头；hi 一般是一行的开头，不是时那半行不要。
func lastReplyIn(f io.ReaderAt, lo, hi int64) (int64, error) {
	var carry []byte // 上一块开头那半行：接在这一块后面
	for end := hi; end > lo; {
		start := max(lo, end-replyChunk)
		buf := make([]byte, end-start, end-start+int64(len(carry)))
		n, err := f.ReadAt(buf, start)
		if err != nil && err != io.EOF {
			return 0, err
		}
		data := append(buf[:n], carry...)
		carry = nil
		if start > lo {
			// 第一个换行之前的可能是半行，留给前一块
			i := bytes.IndexByte(data, '\n')
			if i < 0 {
				carry, end = data, start
				continue
			}
			carry, data = slices.Clone(data[:i+1]), data[i+1:]
		}
		lines := bytes.SplitAfter(data, []byte("\n"))
		for i := len(lines) - 1; i >= 0; i-- {
			if at, ok := isReply(lines[i]); ok {
				return at, nil
			}
		}
		end = start
	}
	return 0, nil
}

// sessionOrder 是手动排序时各会话的位置：会话 id → rank，大的在上面。没拖过的不在里面，位置就是它的发起时间（summary.rank），
// 所以新会话在最上面，几台设备的会话也能放在一起比。
func (e *env) sessionOrder() map[string]float64 {
	order := map[string]float64{}
	data, err := os.ReadFile(e.order)
	if err != nil {
		return order
	}
	for _, line := range strings.Split(decodeString(data), "\n") {
		id, value, ok := strings.Cut(strings.TrimSpace(line), " ")
		if !ok {
			continue
		}
		if rank, err := strconv.ParseFloat(value, 64); err == nil && !math.IsInf(rank, 0) && !math.IsNaN(rank) {
			order[id] = rank
		}
	}
	return order
}

// orderSession 把会话 id 的位置记成 rank（sessions.order）。文件 ~/.config/<name>/order 一行「会话号 位置」，
// 做法同 markSessions：持着 order.lock 读、改、换，顺手清掉记录已经不在的。
func orderSession(e *env, id string, rank float64) error {
	if err := os.MkdirAll(dirName(e.order), 0o777); err != nil {
		return err
	}
	lock, err := os.OpenFile(e.order+".lock", os.O_RDWR|os.O_CREATE, 0o666)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	order := e.sessionOrder()
	for other := range order {
		if len(e.sessionFilesNamed(other)) == 0 {
			delete(order, other)
		}
	}
	order[id] = rank
	var b strings.Builder
	for _, k := range slices.Sorted(maps.Keys(order)) {
		b.WriteString(k + " " + strconv.FormatFloat(order[k], 'g', -1, 64) + "\n")
	}
	return writeFileAtomic(e.order, []byte(b.String()))
}
