package ui

import (
	"fmt"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
)

// 用量弹出框里有各轮明细（同手机版）：新的在上，一行「第 N 轮 · 用时」和费用（估的带 ≈，算不出是 —），下面一行四类 token；
// 累计下面注明费用是估的、更早的记录没载入。
func TestUsagePopoverListsTurns(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"earlier":120,"usage":{"context_tokens":42000,"context_window":100000,"fraction":0.42,"level":"normal",` +
		`"turns":[` +
		`{"tokens":{"input":1234,"output":56,"cache_read":0,"cache_write":7000},"cost_usd":0.0123,"duration_ms":65000},` +
		`{"tokens":{"input":10,"output":2000,"cache_read":123456,"cache_write":0}},` +
		`{"tokens":{"input":5,"output":300,"cache_read":26618,"cache_write":0},"cost_usd":1.5,"cost_estimated":true,"duration_ms":3000}],` +
		`"total":{"input":1249,"output":2356,"cache_read":150074,"cache_write":7000},"total_cost":1.5123,"cost_partial":true,"cost_estimated":true}`)
	f.click(t, "上下文 42%")
	f.see(t, "本对话累计 · 3 轮", "各轮",
		"第 1 轮 · 1 分 05 秒", "$0.0123", "输入 1.2K · 缓存读 0 · 缓存写 7K · 输出 56",
		"第 2 轮", "—", "输入 10 · 缓存读 123K · 缓存写 0 · 输出 2K",
		"第 3 轮 · 3 秒", "≈$1.50", "输入 5 · 缓存读 26.6K · 缓存写 0 · 输出 300",
		"带 ≈ 的金额按 token 数与 API 标价估算", "部分轮次所用模型不在价目表中", "更早的记录尚未载入，未计入统计。")
	// 新的在上
	newest, _ := f.tt.Find("第 3 轮 · 3 秒")
	oldest, _ := f.tt.Find("第 1 轮 · 1 分 05 秒")
	if newest.Y >= oldest.Y {
		t.Errorf("第 3 轮在 %v，第 1 轮在 %v：新的要在上", newest, oldest)
	}
}

// 还没有一轮时不列「各轮」。
func TestUsagePopoverWithoutTurns(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"usage":{"level":"normal","total":{}}`)
	f.click(t, "上下文")
	f.see(t, "本对话累计 · 0 轮")
	f.notSee(t, "各轮", "第 1 轮")
}

// 太长的对话只列最近 50 轮（同手机版）。
func TestUsageTurnsKeepsLatest(t *testing.T) {
	turns := make([]string, 55)
	for i := range turns {
		turns[i] = fmt.Sprintf(`{"tokens":{"input":%d,"output":0,"cache_read":0,"cache_write":0}}`, i+1)
	}
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"usage":{"level":"normal","total":{},"turns":[` + strings.Join(turns, ",") + `]}`)
	f.click(t, "上下文")
	if !f.shows("第 55 轮") {
		t.Fatalf("没有最新的一轮：%q", f.tt.Texts())
	}
	if !f.shows("第 6 轮") {
		t.Errorf("没有第 6 轮：%q", f.tt.Texts())
	}
	if f.shows("第 5 轮") {
		t.Errorf("第 5 轮不在最近 50 轮里")
	}
}

// 用量弹出框的数字右对齐，滚动区右边留出滚动条的宽：鼠标移进去、滚动条出来时不挡字。
func TestUsagePopoverLeavesRoomForScrollbar(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"usage":{"context_tokens":42000,"context_window":100000,"fraction":0.42,"level":"normal","total":{}}`)
	f.click(t, "上下文 42%")
	box, ok := f.tt.Access(locales.ComposerUsage())
	if !ok {
		t.Fatalf("没有用量的滚动区：%q", f.tt.Texts())
	}
	value, ok := f.tt.Find("42K / 100K · 42.0%")
	if !ok {
		t.Fatalf("没有上下文那一行：%q", f.tt.Texts())
	}
	if right, limit := value.X+value.W, box.Bounds.X+box.Bounds.W-widget.ScrollbarTrack; right > limit+0.5 {
		t.Errorf("数字右边到 %v，滚动条从 %v 起：会被挡住", right, limit)
	}
}
