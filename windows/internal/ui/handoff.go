package ui

import (
	"unicode"

	clientlocales "github.com/dzshzx/Zeta/hub/locales"

	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// handoffText 是「别处运行」卡片上的标题、说明和按钮（同手机版 ExternalSessionNotice.kt 的 externalNoticeText）：标题说在哪（occupantPlace），
// 说明只说那边的状态和接管后会怎样：闲着时能接过来；在等确认时也能接，那个确认作废；正跑着一轮时等它跑完。
// o 是那边的样子（还没查到是 nil），handing 是正在接，problem 是上次没接过来的原因。
func handoffText(o *sessionproto.Occupancy, handing bool, problem string) (title, summary, button string, enabled bool) {
	title = locales.HandoffTitle()
	switch {
	case o == nil:
		summary = locales.HandoffQuerying()
	default:
		if place := occupantPlace(o.App, o.Device, o.Local); place != "" {
			title = spaceWide(locales.HandoffTitleAt(place))
		}
		summary = occupantSummary(o.App, deref(o.Status), deref(o.WaitingFor))
	}
	if problem != "" {
		summary = problem
	}
	switch {
	case handing:
		return title, summary, locales.HandoffTaking(), false
	case o == nil || o.HandsOver:
		return title, summary, locales.HandoffTakeOver(), o != nil
	}
	return title, summary, locales.HandoffWaitIdle(), false
}

// occupantPlace 是占着会话的进程在哪，接在「会话正在」「它正在」后面（同手机版的 occupantPlace，用例相同）：
// app、device、local 是 conv.state 里 occupant 的 app、device、local。本机的是「本机终端里」「本机的 Zeta 测试版里」，
// 有名字的是「work-pc 的终端里」，在哪不知道时只说应用（「终端里」）；VS Code、Claude 桌面版同样。说不出是什么应用时为空，调用方说「别处」。
func occupantPlace(app, device string, local bool) string {
	var what string
	switch app {
	case sessionproto.AppTerminal:
		what = locales.HandoffAppTerminal()
	case sessionproto.AppZeta:
		what = "Zeta"
	case sessionproto.AppZetaDev:
		what = locales.HandoffAppZetaDev()
	case sessionproto.AppAgy:
		what = "Antigravity CLI"
	case "claude-vscode":
		what = "VS Code"
	case "claude-desktop":
		what = locales.HandoffAppClaudeDesktop()
	default:
		return ""
	}
	switch {
	case local && app == sessionproto.AppTerminal:
		return locales.HandoffPlaceLocalTerminal()
	case local:
		return spaceWide(locales.HandoffPlaceLocal(what))
	case device != "":
		return spaceWide(locales.HandoffPlaceDevice(device, what))
	}
	return spaceWide(locales.HandoffPlaceApp(what))
}

// spaced 把几段字连起来，汉字和英文字母、数字相接处加一个空格（「work-pc 的 Zeta 里」）；空的段跳过。
func spaced(parts ...string) string {
	var out []rune
	for _, p := range parts {
		r := []rune(p)
		if len(r) == 0 {
			continue
		}
		if n := len(out); n > 0 && (asciiWord(out[n-1]) && wide(r[0]) || wide(out[n-1]) && asciiWord(r[0])) {
			out = append(out, ' ')
		}
		out = append(out, r...)
	}
	return string(out)
}

// spaceWide 在一句话里汉字和英文字母、数字相接处补一个空格（文字表里的「{device}的{app}里」填上 work-pc 后成「work-pc 的…」）；
// 英文的句子里没有汉字，原样返回。
func spaceWide(s string) string {
	parts := make([]string, 0, len(s))
	for _, r := range s {
		parts = append(parts, string(r))
	}
	return spaced(parts...)
}

func asciiWord(r rune) bool {
	return r < 0x80 && (unicode.IsLetter(r) || unicode.IsDigit(r) || r == ')' || r == '.')
}

// wide：汉字这类全角的字（CJK 各区起于 U+2E80）。
func wide(r rune) bool { return r >= 0x2E80 }

// occupantSummary 是占着会话的进程（应用 app）在状态 status（sessionproto 的 Status*）时卡片上的说明；waitingFor 是在等确认时等的是什么。
// 主语在标题里，这里不再说是谁。
func occupantSummary(app, status, waitingFor string) string {
	switch status {
	case sessionproto.StatusIdle:
		return locales.HandoffIdle(takeoverEffect(app))
	case sessionproto.StatusWaiting:
		if waitingFor != "" {
			return locales.HandoffWaitingFor(waitingFor)
		}
		return locales.HandoffWaiting()
	case sessionproto.StatusUnknown:
		return locales.HandoffUnknown()
	case sessionproto.StatusShell:
		return locales.HandoffShell()
	}
	return locales.HandoffBusy()
}

// takeoverEffect 是闲着时接管的后果：Zeta 托管的那边结束它的托管进程；别的（终端里的等）收到中断像 /exit 那样退出。
func takeoverEffect(app string) string {
	if app == sessionproto.AppZeta || app == sessionproto.AppZetaDev {
		return locales.HandoffEffectZeta()
	}
	return locales.HandoffEffectOther()
}

// deref 是 p 指着的字符串；p 是 nil（JSON 里的 null）时是空。
func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// handoffCard 是会话正在别处运行时换掉输入栏的说明卡：照核心推来的那边的样子（conv.state 的 occupant）说话，
// 闲着时一键接过来。接过来、或那边自己退出以后核心解开 blocked，卡片收起。
func (a *App) handoffCard(c *ui.Context, p *chatPane) {
	ho := p.src.Handoff
	title, summary, button, enabled := handoffText(p.state.Occupant, ho.Handing, clientlocales.ErrorText(ho.Problem))
	k := widget.Of(c)
	panel(c, func() {
		ui.Column(c).Padding(8, 16, 8, 16).Gap(4).Children(func() {
			ui.Text(c, title).FontSize(widget.SizeHeadlineXS).FontWeight(widget.Medium).TextColor(k.Text)
			ui.Text(c, summary).FontSize(widget.SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).Selectable()
			ui.Row(c).Margin(8, 0, 0, 0).Children(func() {
				widget.SmallPrimaryButton(c, button).Disabled(!enabled).OnClick(a.hub.HandOver)
			})
		})
	})
}
