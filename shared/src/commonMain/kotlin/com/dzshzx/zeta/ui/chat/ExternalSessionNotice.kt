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
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme

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
        occupant?.let { occupantPlace(it.app, it.device) }?.let { spaced("会话正在", it, "运行") }
            ?: "会话正在别处运行"
    val summary =
        when {
            problem != null -> problem
            occupant == null -> "正在查询那边的状态。"
            occupant.status == Occupant.IDLE -> "当前空闲。" + takeoverEffect(occupant.app)
            occupant.status == Occupant.WAITING ->
                "正在等待确认" +
                    (occupant.waitingFor?.let { "：$it" } ?: "") +
                    "。接管后这个确认作废，要让 Claude 重做这一步。"
            occupant.status == Occupant.UNKNOWN -> "看不出是否空闲。接管会让它退出，进行中的操作会中断。"
            occupant.status == Occupant.SHELL -> "正在执行 ! 命令，执行完后可以接管。"
            else -> "正在回答，本轮结束后可以接管。"
        }
    return when {
        handing -> ExternalNoticeText(title, summary, "正在接管…", enabled = false)
        occupant == null || occupant.handsOver ->
            ExternalNoticeText(title, summary, "接管会话", enabled = occupant != null)
        else -> ExternalNoticeText(title, summary, "等待对方空闲", enabled = false)
    }
}

/**
 * 占着会话的进程在哪，接在「会话正在」「它正在」后面（同桌面版的 occupantPlace，用例相同）：[app]、[device] 是核心推来的 occupant 的 app、device。
 * 终端里的说是哪台的终端，没有设备的是本机终端；Zeta 的说是哪台设备上的哪个包；VS Code、Claude 桌面版说应用。说不出是什么应用时为 null，调用方说「别处」。
 */
fun occupantPlace(app: String?, device: String?): String? {
    val what =
        when (app) {
            Occupant.TERMINAL -> if (device.isNullOrEmpty()) return "本机终端里" else "终端"
            Occupant.ZETA -> "Zeta"
            Occupant.ZETA_DEV -> "Zeta 测试版"
            "claude-vscode" -> "VS Code"
            "claude-desktop" -> "Claude 桌面版"
            else -> return null
        }
    return if (device.isNullOrEmpty()) spaced(what, "里") else spaced(device, "的", what, "里")
}

/** 闲着时接管的后果：Zeta 托管的那边结束它的托管进程；别的（终端里的等）收到中断像 /exit 那样退出。 */
private fun takeoverEffect(app: String?): String =
    if (app == Occupant.ZETA || app == Occupant.ZETA_DEV) "接管后那边的对话会结束，会话转到这里继续。"
    else "接管后那边会退出，会话转到 Zeta 继续。"

/** 把几段字连起来，汉字和英文字母、数字相接处加一个空格（「work-pc 的 Zeta 里」）；空的段跳过。 */
fun spaced(vararg parts: String): String {
    val out = StringBuilder()
    for (p in parts) {
        if (p.isEmpty()) continue
        if (out.isNotEmpty()) {
            val last = out.codePointBefore(out.length)
            val first = p.codePointAt(0)
            if (asciiWord(last) && wide(first) || wide(last) && asciiWord(first)) out.append(' ')
        }
        out.append(p)
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
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val handoff = conversation.handoff
    val text = externalNoticeText(conversation.occupant, handoff.handing, handoff.problem)
    Column(
        modifier =
            modifier.fillMaxWidth().chatPanel(shape).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = text.title,
            style = COUITheme.textStyles.headlineXS,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = text.summary, style = COUITheme.textStyles.bodyS, color = colors.labelSecondary)
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = text.button,
            onClick = conversation.handoff::handOver,
            enabled = text.enabled,
        )
    }
}
