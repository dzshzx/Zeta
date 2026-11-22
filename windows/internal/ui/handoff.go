package ui

import (
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// handoffText 是「别处运行」卡片上的说明和按钮（同手机版 ExternalSessionNotice.kt 的 externalNoticeText）：闲着时能接过来；
// 在等确认时也能接，那个确认作废；正跑着一轮时等它跑完。o 是那边的样子（还没查到是 nil），handing 是正在接，problem 是上次没接过来的原因。
func handoffText(o *sessionproto.Occupancy, handing bool, problem string) (summary, button string, enabled bool) {
	switch {
	case problem != "":
		summary = problem
	case o == nil:
		summary = "另一个 Claude Code 进程（例如终端里的）正在使用此会话，正在查询它的状态。"
	default:
		summary = occupantSummary(deref(o.Status), deref(o.WaitingFor))
	}
	switch {
	case handing:
		return summary, "正在接管…", false
	case o == nil || o.HandsOver:
		return summary, "接管会话", o != nil
	}
	return summary, "等待对方空闲", false
}

// occupantSummary 是占着会话的进程在状态 status（sessionproto 的 Status*）时卡片上的说明；waitingFor 是在等确认时等的是什么。
func occupantSummary(status, waitingFor string) string {
	switch status {
	case sessionproto.StatusIdle:
		return "终端里的 Claude Code 正在使用此会话，当前空闲。接管后，终端一侧会像执行 /exit 一样退出并留下 claude --resume 提示，会话转到 Zeta 继续。"
	case sessionproto.StatusWaiting:
		summary := "终端里的 Claude Code 正在等待确认"
		if waitingFor != "" {
			summary += "（" + waitingFor + "）"
		}
		return summary + "。接管后该确认作废，需要让 Claude 重新执行这一步。"
	case sessionproto.StatusUnknown:
		return "另一个 Claude Code 进程正在使用此会话，无法判断它是否空闲。接管会使它退出，进行中的操作将被中断。"
	case sessionproto.StatusShell:
		return "终端里的 Claude Code 正在执行 ! 命令，执行完毕后可以接管。"
	}
	return "终端里的 Claude Code 正在回答，本轮结束后可以接管。"
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
	summary, button, enabled := handoffText(p.state.Occupant, ho.Handing, ho.Problem)
	k := coui.Of(c)
	panel(c, func() {
		ui.Column(c).Padding(8, 16, 8, 16).Gap(4).Children(func() {
			ui.Text(c, "会话正在别处运行").FontSize(coui.SizeHeadlineXS).FontWeight(coui.Medium).TextColor(k.Text)
			ui.Text(c, summary).FontSize(coui.SizeBodyS).LineHeight(1.45).TextColor(k.Secondary).Selectable()
			ui.Row(c).Margin(8, 0, 0, 0).Children(func() {
				coui.SmallPrimaryButton(c, button).Disabled(!enabled).OnClick(a.hub.HandOver)
			})
		})
	})
}
