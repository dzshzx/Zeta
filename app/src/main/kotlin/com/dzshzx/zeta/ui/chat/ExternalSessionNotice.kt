package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.Occupant
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay

/** 卡片显示着、Zeta 在前台时多久查一次那边闲不闲。 */
private const val OCCUPANT_POLL_MS = 3_000L

/** 「别处运行」卡片上的字：说明和按钮。 */
internal data class ExternalNoticeText(
    val summary: String,
    val button: String,
    val enabled: Boolean,
)

/**
 * 卡片按那边的样子（[occupant]，还没查到是 null）说什么：闲着时能接过来；在等确认时也能接，那个确认作废；正跑着一轮时等它跑完。 [handing] 是正在接，[problem]
 * 是上次没接过来的原因。
 */
internal fun externalNoticeText(
    occupant: Occupant?,
    handing: Boolean,
    problem: String?,
): ExternalNoticeText {
    val take = "接管会话"
    val summary =
        when {
            problem != null -> problem
            occupant == null -> "另一个 Claude Code 进程（例如终端里的）正在使用此会话，正在查询它的状态。"
            occupant.status == Occupant.IDLE ->
                "终端里的 Claude Code 正在使用此会话，当前空闲。接管后，终端一侧会像执行 /exit 一样退出并留下 claude --resume 提示，会话转到 Zeta 继续。"
            occupant.status == Occupant.WAITING ->
                "终端里的 Claude Code 正在等待确认" +
                    (occupant.waitingFor?.let { "（$it）" } ?: "") +
                    "。接管后该确认作废，需要让 Claude 重新执行这一步。"
            occupant.status == Occupant.UNKNOWN ->
                "另一个 Claude Code 进程正在使用此会话，无法判断它是否空闲。接管会使它退出，进行中的操作将被中断。"
            occupant.status == Occupant.SHELL -> "终端里的 Claude Code 正在执行 ! 命令，执行完毕后可以接管。"
            else -> "终端里的 Claude Code 正在回答，本轮结束后可以接管。"
        }
    return when {
        handing -> ExternalNoticeText(summary, "正在接管…", enabled = false)
        occupant == null || occupant.handsOver ->
            ExternalNoticeText(summary, take, enabled = occupant != null)
        else -> ExternalNoticeText(summary, "等待对方空闲", enabled = false)
    }
}

/**
 * 会话正在别处（比如终端里）运行时，输入栏的位置换成这张说明卡：显示着时每隔几秒查一次那边闲不闲（[SessionHandoff.refresh]，那边自己退出了卡片就收起），
 * 闲着时能一键接过来（[SessionHandoff.handOver]）。 要强行停掉那边，用右上角菜单里的「结束对话」。
 */
@Composable
internal fun ExternalSessionNotice(conversation: Conversation, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conversation, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                conversation.handoff.refresh()
                delay(OCCUPANT_POLL_MS)
            }
        }
    }
    val handoff = conversation.handoff
    val text = externalNoticeText(handoff.occupant, handoff.handing, handoff.problem)
    Column(
        modifier =
            modifier.fillMaxWidth().chatPanel(shape).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = "会话正在别处运行",
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
