package accounts

import (
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// QuotaButton 是额度小控件：一个胶囊写着最要紧的那个窗口用了多少（「每周 · 全部模型 86%」），按紧张程度上色；
// 点开是各个窗口的明细、多久以前的数和刷新（样子照 COUI 的弹出菜单）。展开时数不够新就去问一次。
type QuotaButton struct {
	Quota *acct.Quota
	open  bool
}

// View 画这个小控件。还没有数、或这种登录没有订阅额度时写「额度」。
func (b *QuotaButton) View(c *ui.Context) {
	q := b.Quota
	t := c.Theme()
	k := coui.Of(c)
	text, color := "额度", k.Text
	s, ok := q.Snapshot()
	if ok {
		s = s.At(c.Now())
		if h, has := s.Headline(); has && !s.Unavailable {
			text, color = h.Label+" "+percent(h.Percent()), Color(t, h.Severity, k.Text)
			if s.Blocked {
				text, color = "已达上限", t.Danger
			}
		}
	}
	button := ui.ButtonBase(c.Key("quota-button")).Label("订阅额度").Radius(999).Padding(6, 12).AlignItems(ui.Center).
		Cursor(ui.CursorPointer).Background(k.Tint)
	switch {
	case button.Pressed():
		button.Background(k.Press)
	case button.Hovered():
		button.Background(k.Hover)
	}
	button.Children(func() {
		ui.Text(c, text).FontSize(coui.SizeBodyS).FontWeight(coui.Medium).FontFeatures("tnum").TextColor(color).SingleLine()
	})
	if button.Clicked() {
		b.open = !b.open
		if b.open {
			q.Refresh(false)
		}
	}
	ui.PopoverBase(c, button, &b.open, func(panel ui.Element) {
		panel.Column().Width(300).Padding(16).Gap(12).Margin(6, 0, 0, 0)
		coui.Surface{Shape: coui.ShapePopup, Fill: k.PopupGlass, Blur: coui.BlurPopup}.Paint(panel).Shadow(0, 6, 24, 0, k.Shadow)
		panel.Children(func() { QuotaDetails(c, q) })
	})
}

// QuotaDetails 是额度的明细：标题和套餐、各个窗口、多久以前的数、刷新。额度小控件的弹出框和聊天页的用量弹出框里用。
func QuotaDetails(c *ui.Context, q *acct.Quota) {
	k := coui.Of(c)
	s, ok := q.Snapshot()
	ui.Row(c).AlignItems(ui.Center).Children(func() {
		ui.Text(c, "订阅额度").FontSize(coui.SizeHeadlineXS).FontWeight(coui.Medium).TextColor(k.Text).Grow(1)
		if ok && s.Subscription != "" {
			ui.Text(c, acct.PlanName(s.Subscription)).FontSize(coui.SizeBodyM).TextColor(k.Tertiary)
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
	coui.SmallButton(c.Key("quota-refresh"), label).Disabled(q.Refreshing()).AlignSelf(ui.Start).
		OnClick(func() { q.Refresh(true) })
}
