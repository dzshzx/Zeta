package accounts

import (
	acct "github.com/dzshzx/Zeta/hub/accounts"
	clientlocales "github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// login 是添加账号进行中的一组：打开授权网页（或复制网址到别处打开），把网页给的授权码贴回来。
func (p *Page) login(c *ui.Context, l acct.Login) {
	svc := p.Accounts
	idle := svc.Working() == ""
	widget.Group(c, locales.AccountsLoginTitle(), locales.AccountsLoginFooter(), func(list *widget.List) {
		list.Custom(func() {
			ui.Column(c).Grow(1).Gap(10).Padding(4, 0).Children(func() {
				ui.Row(c).Gap(8).Children(func() {
					widget.SmallPrimaryButton(c, locales.AccountsOpenAuthPage()).OnClick(func() { c.OpenURL(l.URL) })
					widget.SmallButton(c, locales.AccountsCopyUrl()).OnClick(func() { c.Services().WriteClipboard(l.URL) })
				})
				in := widget.LineInput(c, &p.code, 1).Placeholder(locales.AccountsCode()).Label(locales.AccountsCode()).Disabled(!idle)
				if in.Submitted() {
					svc.SubmitCode(p.code)
				}
				if l.Notice != nil {
					widget.Notice(c, clientlocales.ErrorText(l.Notice), widget.Failure)
				}
				ui.Row(c).Gap(8).Justify(ui.End).Children(func() {
					widget.SmallButton(c, locales.CommonCancel()).Disabled(!idle).OnClick(svc.CancelLogin)
					widget.SmallPrimaryButton(c, locales.AccountsSignIn()).Disabled(!idle || p.code == "").OnClick(func() { svc.SubmitCode(p.code) })
				})
			})
		})
	})
}
