// Package accounts 是设置里一台主机的「账号与额度」页，和聊天页上的额度小控件。只画，状态在 internal/accounts。
// 页面画在设置窗口给它的位置里，不管窗口、标题和滚动。
package accounts

import (
	"strconv"
	"strings"
	"time"

	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/egoist/mygo/ui"
)

// Page 是一台主机的「账号与额度」页：那台上当前账号的订阅额度，claude-switch 管着的几个账号，切换、记下、移除。
// 切换改的是那台上 Linux 用户的 Claude Code 登录状态，两个 Zeta 和终端里的 claude 用的都是这一份；各台设备各管各的。
// 添加账号（登录）在那台的终端里做：登录后这里「记下」，之后就能切换。
type Page struct {
	Accounts *acct.Service
	Quota    *acct.Quota

	switching, removing  acct.Account
	askSwitch, askRemove bool
}

// View 画这一页。第一次画时读一遍账号、数不够新就问一次额度；离开页面时上一次没做成的原因不再挂着。
func (p *Page) View(c *ui.Context) {
	page := ui.Column(c.Key("accounts-page")).Gap(24)
	ui.Local(page, "opened", func() bool {
		p.Accounts.Refresh()
		p.Quota.Refresh(false)
		return true
	})
	page.OnUnmount(p.Accounts.DismissProblem)
	page.Children(func() {
		p.quota(c)
		p.accounts(c)
	})
	switch ui.AlertDialog(c, &p.askSwitch, "切换到 "+p.switching.Title()+"？",
		"会先关掉这台设备上闲着的对话进程，对话内容不丢。之后这台上的新对话、额度和终端里的 claude 都用这个账号。",
		"取消", "切换") {
	case 1:
		p.Accounts.Switch(p.switching)
	}
	switch ui.AlertDialog(c, &p.askRemove, "移除 "+p.removing.Title()+"？",
		"只删这台设备上存着的这份登录，服务器上的登录还在。以后要用得重新登录。",
		"取消", "移除") {
	case 1:
		p.Accounts.Remove(p.removing)
	}
}

func (p *Page) quota(c *ui.Context) {
	q := p.Quota
	section(c, "订阅额度", func() {
		if s, ok := q.Snapshot(); ok && !s.Unavailable && len(s.Windows) > 0 {
			Windows(c, s, c.Now())
		}
		note(c, q.Footnote(c.Now()))
		label := "刷新"
		if q.Refreshing() {
			label = "正在刷新"
		}
		ui.Button(c.Key("refresh"), label).Disabled(q.Refreshing()).AlignSelf(ui.Start).OnClick(func() { q.Refresh(true) })
	})
}

func (p *Page) accounts(c *ui.Context) {
	svc := p.Accounts
	st, loaded := svc.State()
	section(c, "账号", func() {
		switch {
		case !loaded:
			ui.Text(c, "正在读取")
		case st.Current == nil && len(st.Accounts) == 0:
			ui.Text(c, "还没有登录")
		}
		for _, a := range st.Accounts {
			p.row(c, a, st.IsCurrent(a), c.Now())
		}
		if w := svc.Working(); w != "" {
			ui.Row(c).Gap(8).Children(func() {
				ui.Spinner(c).Label(w)
				ui.Text(c, w)
			})
		}
		note(c, "这台设备上两个 Zeta 和终端里的 claude 共用一份登录，有对话在进行或别处开着 Claude Code 时不能切换。"+
			"要加账号，在这台设备的终端里登录，再在这里记下。")
		if problem := svc.Problem(); problem != "" {
			ui.Text(c, problem).TextColor(c.Theme().Danger)
		}
	})
}

// row 是一个账号：在用的标「当前」（还没记下名字的给「记下」），别的给「切换」「移除」。
func (p *Page) row(c *ui.Context, a acct.Account, current bool, now time.Time) {
	idle := p.Accounts.Working() == ""
	ui.Row(c.Key("account:" + a.Name)).Gap(8).Children(func() {
		ui.Column(c).Grow(1).Gap(2).Children(func() {
			ui.Text(c, a.Title())
			if s := summary(a, now); s != "" {
				ui.Text(c, s).TextColor(c.Theme().TextMuted)
			}
		})
		if current {
			ui.Badge(c, "当前")
			if a.Name == "" {
				ui.Button(c, "记下").Disabled(!idle).OnClick(p.Accounts.Save)
			}
			return
		}
		ui.Button(c, "切换").Disabled(!idle).OnClick(func() { p.switching, p.askSwitch = a, true })
		ui.Button(c, "移除").Disabled(!idle).OnClick(func() { p.removing, p.askRemove = a, true })
	})
}

// summary 是账号一行的说明：名字（和终端里 claude-switch 列的一样）、组织、套餐、过期了没有。
func summary(a acct.Account, now time.Time) string {
	var parts []string
	for _, s := range []string{a.Name, a.Organization, a.Plan()} {
		if s != "" {
			parts = append(parts, s)
		}
	}
	if a.Expired(now) {
		parts = append(parts, "登录已过期")
	}
	if a.Name == "" {
		parts = append(parts, "还没记下名字")
	}
	return strings.Join(parts, " · ")
}

// section 是一组：小标题和内容。
func section(c *ui.Context, title string, fn func()) {
	ui.Column(c).Gap(10).Children(func() {
		ui.Text(c, title).Bold()
		fn()
	})
}

func note(c *ui.Context, text string) ui.Element {
	return ui.Text(c, text).FontSize(12).TextColor(c.Theme().TextMuted)
}

// Windows 画各个额度窗口：名字、已用比例、进度条、什么时候重置；被拒时先说一句，开了额外用量时最后一行。页面和小控件共用。
func Windows(c *ui.Context, s acct.Snapshot, now time.Time) {
	s = s.At(now)
	ui.Column(c).Gap(12).Children(func() {
		if s.Blocked {
			text := "已达上限，暂时发不出请求"
			if r := acct.ResetText(s.BlockedUntil, now); r != "" {
				text += "，" + r
			}
			ui.Text(c, text).FontSize(12).TextColor(c.Theme().Danger)
		}
		for _, w := range s.Windows {
			line(c, w.Label, percent(w.Percent()), &w.Used, w.Severity, acct.ResetText(w.ResetsAt, now))
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
				value = "已开启"
			}
			line(c, "额外用量（本月）", value, e.Used, acct.Normal, "")
		}
	})
}

func line(c *ui.Context, label, value string, used *float64, sev acct.Severity, detail string) {
	t := c.Theme()
	ui.Column(c).Gap(4).Children(func() {
		ui.Row(c).Children(func() {
			ui.Text(c, label).Grow(1)
			ui.Text(c, value).FontFeatures("tnum").TextColor(Color(t, sev, t.Text))
		})
		if used != nil {
			ui.Progress(c, min(max(*used, 0), 1)).TextColor(Color(t, sev, t.Accent)).Label(label)
		}
		if detail != "" {
			note(c, detail)
		}
	})
}

// Color 是紧张程度的颜色：紧张用 Danger，要注意用 Warning，平时用 normal。
func Color(t *ui.Theme, sev acct.Severity, normal ui.Color) ui.Color {
	switch sev {
	case acct.Critical:
		return t.Danger
	case acct.Warning:
		return t.Warning
	}
	return normal
}

func percent(n int) string { return strconv.Itoa(n) + "%" }
