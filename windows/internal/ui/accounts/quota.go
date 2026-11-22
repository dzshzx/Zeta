package accounts

import (
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/egoist/mygo/ui"
)

// QuotaButton 是额度小控件：一个按钮写着最要紧的那个窗口用了多少（「每周 · 全部模型 86%」），按紧张程度上色；
// 点开是各个窗口的明细、多久以前的数和刷新。展开时数不够新就去问一次。
type QuotaButton struct {
	Quota *acct.Quota
	open  bool
}

// View 画这个小控件。还没有数、或这种登录没有订阅额度时写「额度」。
func (b *QuotaButton) View(c *ui.Context) {
	q := b.Quota
	t := c.Theme()
	text, color := "额度", t.Text
	s, ok := q.Snapshot()
	if ok {
		s = s.At(c.Now())
		if h, has := s.Headline(); has && !s.Unavailable {
			text, color = h.Label+" "+percent(h.Percent()), Color(t, h.Severity, t.Text)
			if s.Blocked {
				text, color = "已达上限", t.Danger
			}
		}
	}
	button := ui.Button(c.Key("quota-button"), text).TextColor(color).Label("订阅额度")
	if button.Clicked() {
		b.open = !b.open
		if b.open {
			q.Refresh(false)
		}
	}
	ui.Popover(c, button, &b.open, func() {
		ui.Column(c).Width(280).Padding(12).Gap(10).Children(func() {
			ui.Row(c).Children(func() {
				ui.Text(c, "订阅额度").Bold().Grow(1)
				if ok && s.Subscription != "" {
					ui.Text(c, acct.PlanName(s.Subscription)).TextColor(t.TextMuted)
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
			ui.Button(c.Key("quota-refresh"), label).Disabled(q.Refreshing()).AlignSelf(ui.Start).OnClick(func() { q.Refresh(true) })
		})
	})
}
