package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.Occupant
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.SquircleShape
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 「别处运行」卡片上的字：标题（在哪）、说明和按钮。 */
data class ExternalNoticeText(
    val title: String,
    val summary: String,
    val button: String,
    val enabled: Boolean,
)

/**
 * 卡片按那边的样子（[occupant]，核心推来的，还没查到是
 * null）说什么：标题说在哪（[occupantPlace]），说明只说那边的状态和接管后会怎样：闲着时能接过来；在等确认时也能接， 那个确认作废；正跑着一轮时等它跑完。 [handing]
 * 是正在接，[problem] 是上次没接过来的原因。同桌面版 `windows/internal/ui/handoff.go` 的 handoffText。
 */
fun externalNoticeText(
    occupant: Occupant?,
    handing: Boolean,
    problem: String?,
): ExternalNoticeText {
    val title =
        spaceMixed(
            Strings.chatSessionRunningAt(
                occupant?.let { occupantPlace(it.app, it.device, it.local) }
                    ?: Strings.chatPlaceElsewhere(),
            ),
        )
    val summary =
        when {
            problem != null -> problem
            occupant == null -> Strings.chatOccupantChecking()
            occupant.status == Occupant.IDLE ->
                if (occupant.app == Occupant.ZETA || occupant.app == Occupant.ZETA_DEV)
                    Strings.chatOccupantIdleZeta()
                else Strings.chatOccupantIdleOther()
            occupant.status == Occupant.WAITING ->
                occupant.waitingFor?.let { Strings.chatOccupantWaitingFor(it) }
                    ?: Strings.chatOccupantWaiting()
            occupant.status == Occupant.UNKNOWN -> Strings.chatOccupantUnknown()
            occupant.status == Occupant.SHELL -> Strings.chatOccupantShell()
            else -> Strings.chatOccupantAnswering()
        }
    return when {
        handing -> ExternalNoticeText(title, summary, Strings.chatTakingOver(), enabled = false)
        occupant == null || occupant.handsOver ->
            ExternalNoticeText(title, summary, Strings.chatTakeOver(), enabled = occupant != null)
        else -> ExternalNoticeText(title, summary, Strings.chatWaitIdle(), enabled = false)
    }
}

/**
 * 占着会话的进程在哪，接在「会话正在」「它正在」后面（同桌面版的 occupantPlace，用例相同）：[app]、[device]、[local] 是核心推来的 occupant 的
 * app、device、 local。本机的是「本机终端里」「本机的 Zeta 测试版里」，有名字的是「work-pc 的终端里」，在哪不知道时只说应用（「终端里」）；VS Code、Claude
 * 桌面版同样。 说不出是什么应用时为 null，调用方说「别处」（[Strings.chatPlaceElsewhere]）。按界面语言出字。
 */
fun occupantPlace(app: String?, device: String?, local: Boolean): String? {
    val what =
        when (app) {
            Occupant.TERMINAL -> Strings.chatAppTerminal()
            Occupant.ZETA -> "Zeta"
            Occupant.ZETA_DEV -> Strings.chatAppZetaDev()
            Occupant.AGY -> "Antigravity CLI"
            "claude-vscode" -> "VS Code"
            "claude-desktop" -> Strings.chatAppClaudeDesktop()
            else -> return null
        }
    return spaceMixed(
        when {
            local && app == Occupant.TERMINAL -> Strings.chatPlaceLocalTerminal()
            local -> Strings.chatPlaceLocalApp(what)
            !device.isNullOrEmpty() -> Strings.chatPlaceDeviceApp(device, what)
            else -> Strings.chatPlaceApp(what)
        },
    )
}

/**
 * 汉字和英文字母、数字相接处加一个空格（「work-pc的Zeta里」→「work-pc 的 Zeta 里」）：中文的句子把设备名、应用名直接嵌在字中间， 拼好后用它补空格。已有空格的不重复加。
 */
fun spaceMixed(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        if (out.isNotEmpty()) {
            val last = out.codePointBefore(out.length)
            if (asciiWord(last) && wide(c) || wide(last) && asciiWord(c)) out.append(' ')
        }
        out.appendCodePoint(c)
        i += Character.charCount(c)
    }
    return out.toString()
}

private fun asciiWord(c: Int) =
    c < 0x80 && (Character.isLetterOrDigit(c) || c == ')'.code || c == '.'.code)

/** 汉字这类全角的字（CJK 各区起于 U+2E80）。 */
private fun wide(c: Int) = c >= 0x2E80

/**
 * 会话正在别处（终端里、另一个 Zeta 里）运行时，输入栏的位置换成这张说明卡：那边闲不闲照核心推来的 [Conversation.occupant] 说（核心自己盯着，那边退出了核心解开
 * blocked，卡片就收起），闲着时能一键接过来（[SessionHandoff.handOver]）。 要强行停掉那边，用右上角菜单里的「结束对话」。
 */
@Composable
fun ExternalSessionNotice(conversation: Conversation, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val handoff = conversation.handoff
    val text = externalNoticeText(conversation.occupant, handoff.handing, handoff.problem)
    Column(
        modifier =
            modifier.fillMaxWidth().chatPanel(shape).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = text.title,
            style = MiuixTheme.textStyles.headline2,
            color = colors.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = text.summary,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onBackgroundVariant,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = text.button,
            onClick = conversation.handoff::handOver,
            enabled = text.enabled,
        )
    }
}
