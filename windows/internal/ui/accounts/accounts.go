// Package accounts 是设置里一台主机的「账号与额度」页，和聊天页用量弹出框里的额度明细。只画，状态在 hub/accounts。
// 页面画在设置窗口给它的位置里，不管窗口、标题和滚动。
package accounts

import (
	"strconv"
	"strings"
	"time"

	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	acct "github.com/dzshzx/Zeta/hub/accounts"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// Page 是一台主机的「账号与额度」页：那台上当前账号的订阅额度，claude-switch 管着的几个账号，切换、记下、移除；
// 中转（relay.go）的切换、添加、编辑、删除。
// 切换改的是那台上 Linux 用户的 Claude Code 登录状态，两个 Zeta 和终端里的 claude 用的都是这一份；各台设备各管各的。
// 添加账号：主机上跑登录命令，这里打开授权网页、收授权码（login.go）；在终端里登录的号也能在这里「记下」。
type Page struct {
	Accounts *acct.Service
	Quota    *acct.Quota
	Harness  string

	code string // 登录时输入框里的授权码

	switching, removing  coreapi.Account
	askSwitch, askRemove bool

	// 中转：要换过去的、在编辑的（草稿开着时整页换成编辑页）、编辑页上的模型筛选和密钥显不显示、要删的。
	switchingRelay coreapi.Relay
	askSwitchRelay bool
	edit           *acct.RelayDraft
	modelFilter    string
	showKey        bool
	removingRelay  string
	askRemoveRelay bool
}

// View 画这一页（照手机版的账号与额度页：订阅额度、账号、中转三组卡片；在用中转时没有订阅额度一组），编辑中转时是编辑页。第一次画时读一遍账号、
// 数不够新就问一次额度；离开页面时上一次没做成的原因不再挂着。
func (p *Page) View(c *ui.Context) {
	page := ui.Column(c.Key("accounts-page")).FillWidth()
	ui.Local(page, "opened", func() bool {
		p.Accounts.Refresh()
		p.Quota.Refresh(false)
		return true
	})
	page.OnUnmount(p.Accounts.DismissProblem)
	page.Children(func() {
		if p.edit != nil {
			p.editor(c, p.edit)
			return
		}
		p.quota(c)
		p.accounts(c)
		p.relays(c)
		if problem := p.Accounts.Problem(); problem != "" {
			ui.Column(c).Padding(0, 16, 16, 16).Children(func() { widget.Notice(c, problem, widget.Failure) })
		}
	})
	if widget.Alert(c, &p.askSwitch, locales.AccountsSwitchTitle(p.switching.Title()), locales.AccountsSwitchMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.AccountsSwitch(), Style: widget.Recommend}) == 1 {
		p.Accounts.Switch(p.switching)
	}
	if widget.Alert(c, &p.askRemove, locales.AccountsRemoveTitle(p.removing.Title()), locales.AccountsRemoveMessage(),
		widget.Action{Label: locales.CommonCancel()}, widget.Action{Label: locales.CommonRemove(), Style: widget.Destructive}) == 1 {
		p.Accounts.Remove(p.removing)
	}
	p.relayAlerts(c)
}

func (p *Page) quota(c *ui.Context) {
	q := p.Quota
	if !q.Shown() { // 在用中转（在用的那个在中转一组里写着「当前」）、还不知道时没有这一组
		return
	}
	widget.Group(c, locales.AccountsQuotaTitle(), q.Footnote(c.Now()), func(l *widget.List) {
		if s, ok := q.Snapshot(); ok && !s.Unavailable && (len(s.Windows) > 0 || (s.Extra != nil && s.Extra.Enabled)) {
			l.Custom(func() {
				ui.Column(c).Grow(1).Padding(4, 0).Children(func() { Windows(c, s, c.Now()) })
			})
		}
		l.Custom(func() {
			ui.Spacer(c)
			label := locales.CommonRefresh()
			if q.Refreshing() {
				label = locales.AccountsRefreshing()
			}
			widget.SmallButton(c, label).Disabled(q.Refreshing()).OnClick(func() { q.Refresh(true) })
		})
	})
}

func (p *Page) isAgy() bool {
	return sessionproto.NormalizeHarness(p.Harness) == sessionproto.HarnessAgy
}

func (p *Page) accounts(c *ui.Context) {
	svc := p.Accounts
	st, loaded := svc.State()
	footer := locales.AccountsFooter()
	if p.isAgy() {
		footer = locales.AccountsAgyFooter()
	}
	widget.Group(c, locales.AccountsTitle(), footer, func(l *widget.List) {
		switch {
		case !loaded:
			l.Message(locales.CommonLoading())
		case st.Current == nil && len(st.Accounts) == 0 && st.CurrentRelay() == nil:
			l.Message(locales.AccountsNotSignedIn())
		}
		for _, a := range st.Accounts {
			p.row(l, a, st.IsCurrent(a), c.Now())
		}
		if _, logging := svc.Login(); loaded && !logging {
			l.Custom(func() {
				ui.Text(c, locales.AccountsAdd()).FontSize(widget.SizeHeadlineXS).TextColor(widget.Of(c).Text).Grow(1)
				widget.SmallButton(c, locales.CommonAdd()).Disabled(svc.Working() != "").OnClick(func() {
					p.code = ""
					svc.StartLogin()
				})
			})
		}
		if w := svc.Working(); w != "" {
			l.Custom(func() {
				ui.Spinner(c).Size(16, 16).Label(w)
				ui.Text(c, w).FontSize(widget.SizeHeadlineXS).TextColor(widget.Of(c).Text)
			})
		}
	})
	if l, ok := svc.Login(); ok {
		p.login(c, l)
	}
}

// row 是一个账号：在用的右边写「当前」（还没记下名字的给「记下」），别的给「切换」「移除」。
func (p *Page) row(l *widget.List, a coreapi.Account, current bool, now time.Time) {
	c := l.Context()
	idle := p.Accounts.Working() == ""
	row := widget.Row{Key: "account:" + a.Name, Title: a.Title(), Summary: summary(a, now)}
	if current {
		row.Value = locales.AccountsCurrent()
		if a.Name == "" {
			row.End = func() { widget.SmallPrimaryButton(c, locales.AccountsSave()).Disabled(!idle).OnClick(p.Accounts.Save) }
		}
	} else {
		row.End = func() {
			widget.SmallButton(c, locales.AccountsSwitch()).Disabled(!idle).OnClick(func() { p.switching, p.askSwitch = a, true })
			widget.SmallDangerButton(c, locales.CommonRemove()).Disabled(!idle).OnClick(func() { p.removing, p.askRemove = a, true })
		}
	}
	l.Row(row)
}

// summary 是账号一行的说明：名字（和终端里 claude-switch 列的一样）、组织、套餐、过期了没有。
func summary(a coreapi.Account, now time.Time) string {
	var parts []string
	for _, s := range []string{a.Name, a.Organization, a.PlanLabel()} {
		if s != "" {
			parts = append(parts, s)
		}
	}
	if a.Expired(now) {
		parts = append(parts, locales.AccountsExpired())
	}
	if a.Name == "" {
		parts = append(parts, locales.AccountsUnnamed())
	}
	return strings.Join(parts, " · ")
}

// note 是额度下面的一句小字（12，次要文字色）。
func note(c *ui.Context, text string) ui.Element {
	return ui.Text(c, text).FontSize(widget.SizeBodyXS).LineHeight(1.35).TextColor(widget.Of(c).Secondary)
}

// Windows 画各个额度窗口（同手机版 QuotaControls 的 QuotaWindows）：名字、已用比例、细进度条、什么时候重置；
// 被拒时先说一句，开了额外用量时最后一行。页面和小控件共用。
func Windows(c *ui.Context, s coreapi.QuotaSnapshot, now time.Time) {
	s = s.At(now)
	k := widget.Of(c)
	ui.Column(c).FillWidth().Gap(12).Children(func() {
		if s.Blocked {
			text := locales.AccountsBlocked()
			if r := acct.ResetText(s.BlockedUntil, now); r != "" {
				text = locales.AccountsBlockedUntil(r)
			}
			note(c, text).TextColor(k.Error)
		}
		for _, w := range s.Windows {
			line(c, w.LabelIn(c.Locale()), percent(w.Percent()), &w.Used, w.Severity, acct.ResetText(w.ResetsAt, now))
		}
		if e := s.Extra; e != nil && e.Enabled {
			var amounts []string
			for _, m := range []string{acct.Money(e.UsedMin, e.Currency), acct.Money(e.LimitMin, e.Currency)} {
				if m != "" {
					amounts = append(amounts, m)
				}
			}
			value := strings.Join(amounts, " / ")
			if value == "" {
				value = locales.AccountsExtraOn()
			}
			label := locales.AccountsExtraUsage()
			if strings.EqualFold(e.Currency, "credits") {
				label = "AI Credits"
			}
			line(c, label, value, e.Used, coreapi.SeverityNormal, "")
		}
	})
}

func line(c *ui.Context, label, value string, used *float64, sev coreapi.Severity, detail string) {
	t := c.Theme()
	k := widget.Of(c)
	ink := k.Text.Alpha(0.88 * float32(k.Text.A) / 255)
	ui.Column(c).FillWidth().Children(func() {
		ui.Row(c).AlignItems(ui.Center).Children(func() {
			ui.Text(c, label).FontSize(widget.SizeBodyS).TextColor(ink).Grow(1)
			ui.Text(c, value).FontSize(widget.SizeBodyS).FontWeight(widget.Medium).FontFeatures("tnum").TextColor(Color(t, sev, ink))
		})
		if used != nil {
			UsageBar(c, float32(min(max(*used, 0), 1)), Color(t, sev, k.Primary)).Label(label).Margin(5, 0, 0, 0)
		}
		if detail != "" {
			note(c, detail).Margin(3, 0, 0, 0)
		}
	})
}

// UsageBar 是用量的细条（同手机版 UsageBar）：6 高、3 的圆角，淡灰的槽上一段 color。额度和用量弹出框的上下文共用。
func UsageBar(c *ui.Context, fraction float32, color ui.Color) ui.Element {
	k := widget.Of(c)
	track := k.Text.Alpha(0.07)
	if k.Dark {
		track = k.Text.Alpha(0.12)
	}
	bar := ui.Box(c).FillWidth().Height(6).Radius(3).Background(track).Clip().Role(ui.RoleProgress)
	bar.Children(func() {
		ui.Box(c).WidthPercent(max(fraction, 0.01) * 100).Height(6).Radius(3).Background(color)
	})
	return bar
}

// Color 是紧张程度的颜色：紧张用 Danger，要注意用 Warning，平时用 normal。
func Color(t *ui.Theme, sev coreapi.Severity, normal ui.Color) ui.Color {
	switch sev {
	case coreapi.SeverityCritical:
		return t.Danger
	case coreapi.SeverityWarning:
		return t.Warning
	}
	return normal
}

func percent(n int) string { return strconv.Itoa(n) + "%" }
