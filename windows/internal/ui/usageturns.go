package ui

import (
	"fmt"
	"strings"

	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// totalsNote 是用量弹出框里累计下面的说明（同手机版的 TotalsSection）：费用是估的、有的轮次算不出费用、更早的记录没载入；
// 都没有时为空。earlier 是还没载入的更早记录条数。
func totalsNote(u usage.Usage, earlier int) string {
	var notes []string
	if u.CostEstimated {
		notes = append(notes, locales.UsageCostEstimated())
	}
	if u.CostPartial {
		notes = append(notes, locales.UsageCostPartial())
	}
	if earlier > 0 {
		notes = append(notes, locales.UsageEarlierNotCounted())
	}
	return strings.Join(notes, locales.UsageNoteSeparator())
}

// usageTurnsShown 是各轮明细最多列几轮（同手机版）：太长的对话只列最近的。
const usageTurnsShown = 50

// usageTurns 是用量弹出框里的各轮明细（同手机版用量弹框的「各轮」）：新的在上，每轮一行「第 N 轮 · 用时」和费用，
// 下面一行四类 token。不单独滚动：整个弹出框的内容一起滚（usagePanelHeight）。
func usageTurns(c *ui.Context, turns []usage.Turn) {
	t := c.Theme()
	ui.Text(c, locales.UsageTurns()).FontSize(13).Bold()
	ui.Column(c.Key("usage-turns")).FillWidth().Gap(8).Children(func() {
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
}

// turnLines 是第 n 轮的三段字：「第 n 轮 · 用时」、费用（估的带 ≈，算不出是 —）、四类 token。
func turnLines(n int, turn usage.Turn) (head, cost, detail string) {
	head = locales.UsageTurn(n)
	if turn.DurationMs != nil {
		head += " · " + tasks.FormatElapsedIn(i18n.Locale(), *turn.DurationMs)
	}
	cost = "—"
	if turn.CostUSD != nil {
		cost = usage.FormatCost(*turn.CostUSD)
		if turn.CostEstimated {
			cost = "≈" + cost
		}
	}
	k := turn.Tokens
	detail = locales.UsageTurnTokens(usage.FormatTokens(k.Input), usage.FormatTokens(k.CacheRead), usage.FormatTokens(k.CacheWrite), usage.FormatTokens(k.Output))
	return head, cost, detail
}
