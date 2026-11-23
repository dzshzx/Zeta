package accounts

import (
	acct "github.com/dzshzx/Zeta/windows/internal/accounts"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// login 是添加账号进行中的一组：打开授权网页（或复制网址到别处打开），把网页给的授权码贴回来。
func (p *Page) login(c *ui.Context, l acct.Login) {
	svc := p.Accounts
	idle := svc.Working() == ""
	coui.Group(c, "登录新账号", "在浏览器里打开授权网页，登录要添加的 Claude 账号，再把网页给的授权码贴到这里。", func(list *coui.List) {
		list.Custom(func() {
			ui.Column(c).Grow(1).Gap(10).Padding(4, 0).Children(func() {
				ui.Row(c).Gap(8).Children(func() {
					coui.SmallPrimaryButton(c, "打开授权网页").OnClick(func() { c.OpenURL(l.URL) })
					coui.SmallButton(c, "复制网址").OnClick(func() { c.Services().WriteClipboard(l.URL) })
				})
				in := coui.LineInput(c, &p.code, 1).Placeholder("授权码").Label("授权码").Disabled(!idle)
				if in.Submitted() {
					svc.SubmitCode(p.code)
				}
				if l.Notice != "" {
					coui.Notice(c, l.Notice, coui.Failure)
				}
				ui.Row(c).Gap(8).Justify(ui.End).Children(func() {
					coui.SmallButton(c, "取消").Disabled(!idle).OnClick(svc.CancelLogin)
					coui.SmallPrimaryButton(c, "登录").Disabled(!idle || p.code == "").OnClick(func() { svc.SubmitCode(p.code) })
				})
			})
		})
	})
}
