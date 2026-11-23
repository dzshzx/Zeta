package accounts

import (
	"github.com/dzshzx/Zeta/helper/coreapi"
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// QuotaDetails 是额度的明细：标题和套餐、各个窗口、多久以前的数、刷新。聊天页的用量弹出框里用。
func QuotaDetails(c *ui.Context, q *acct.Quota) {
	k := widget.Of(c)
	s, ok := q.Snapshot()
	ui.Row(c).AlignItems(ui.Center).Children(func() {
		ui.Text(c, "订阅额度").FontSize(widget.SizeHeadlineXS).FontWeight(widget.Medium).TextColor(k.Text).Grow(1)
		if ok && s.Subscription != "" {
			ui.Text(c, coreapi.PlanName(s.Subscription)).FontSize(widget.SizeBodyM).TextColor(k.Tertiary)
		}
	})
	if ok && !s.Unavailable && len(s.Windows) > 0 {
		Windows(c, s, c.Now())
	}
	note(c, q.Footnote(c.Now()))
	label := "刷新"
	if q.Refreshing() {
		label = "正在刷新"
	}
	widget.SmallButton(c.Key("quota-refresh"), label).Disabled(q.Refreshing()).AlignSelf(ui.Start).
		OnClick(func() { q.Refresh(true) })
}
