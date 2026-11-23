package ui

import (
	"fmt"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/egoist/mygo/ui"
)

// totalsNote 是用量弹出框里累计下面的说明（同手机版的 TotalsSection）：费用是估的、有的轮次算不出费用、更早的记录没载入；
// 都没有时为空。earlier 是还没载入的更早记录条数。
func totalsNote(u usage.Usage, earlier int) string {
	var notes []string
	if u.CostEstimated {
		notes = append(notes, "带 ≈ 的是按 token 数和 API 标价估的：会话记录里没有费用，子代理的用量不在其中。")
	}
	if u.CostPartial {
		notes = append(notes, "有些轮次用的模型不在价目表里，费用没有计入。")
	}
	if earlier > 0 {
		notes = append(notes, "更早的记录没有载入，也没有计入。")
	}
	return strings.Join(notes, "")
}

// usageTurnsShown 是各轮明细最多列几轮（同手机版）：太长的对话只列最近的。
const usageTurnsShown = 50

// usageTurns 是用量弹出框里的各轮明细（同手机版用量弹框的「各轮」）：新的在上，每轮一行「第 N 轮 · 用时」和费用，
// 下面一行四类 token。列在限高的滚动区里，轮数多了弹出框也不会长出窗口。
func usageTurns(c *ui.Context, turns []usage.Turn) {
	t := c.Theme()
	ui.Text(c, "各轮").FontSize(13).Bold()
	ui.Scroll(c.Key("usage-turns")).FillWidth().MaxHeight(200).Label("各轮用量").Children(func() {
		ui.Column(c).FillWidth().Gap(8).Children(func() {
			for i := len(turns) - 1; i >= max(0, len(turns)-usageTurnsShown); i-- {
				head, cost, detail := turnLines(i+1, turns[i])
				ui.Column(c.Key(fmt.Sprint("turn", i))).FillWidth().Gap(2).Children(func() {
					ui.Row(c).Gap(12).Children(func() {
						ui.Text(c, head).FontSize(13)
						ui.Spacer(c)
						ui.Text(c, cost).FontSize(13).FontFeatures("tnum")
					})
					ui.Text(c, detail).FontSize(12).FontFeatures("tnum").TextColor(t.TextMuted)
				})
			}
		})
	})
}

// turnLines 是第 n 轮的三段字：「第 n 轮 · 用时」、费用（估的带 ≈，算不出是 —）、四类 token。
func turnLines(n int, turn usage.Turn) (head, cost, detail string) {
	head = fmt.Sprintf("第 %d 轮", n)
	if turn.DurationMs != nil {
		head += " · " + tasks.FormatElapsed(*turn.DurationMs)
	}
	cost = "—"
	if turn.CostUSD != nil {
		cost = formatCost(*turn.CostUSD)
		if turn.CostEstimated {
			cost = "≈" + cost
		}
	}
	k := turn.Tokens
	detail = "输入 " + tokens(k.Input) + " · 缓存读 " + tokens(k.CacheRead) + " · 缓存写 " + tokens(k.CacheWrite) + " · 输出 " + tokens(k.Output)
	return head, cost, detail
}
