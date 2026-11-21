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
    val take = "在 Zeta 接着"
    val summary =
        when {
            problem != null -> problem
            occupant == null -> "另一个 Claude Code 进程（比如终端里的）开着它，正在看那边闲不闲。"
            occupant.status == Occupant.IDLE ->
                "终端里的 Claude Code 开着它，现在闲着。接过来会让那边像 /exit 一样退出，终端里留下 claude --resume 的提示，再在这里接着。"
            occupant.status == Occupant.WAITING ->
                "终端里的 Claude Code 在等你确认" +
                    (occupant.waitingFor?.let { "（$it）" } ?: "") +
                    "。接过来那个确认就作废，接上后让 Claude 重新来一遍。"
            occupant.status == Occupant.UNKNOWN ->
                "另一个 Claude Code 进程开着它，看不出它闲不闲。接过来会让它退出，要是它正在做事，做到一半的会停下。"
            occupant.status == Occupant.SHELL -> "终端里的 Claude Code 正在执行 ! 命令，跑完就能接过来。"
            else -> "终端里的 Claude Code 正在回答，这一轮结束就能接过来。"
        }
    return when {
        handing -> ExternalNoticeText(summary, "正在接过来…", enabled = false)
        occupant == null || occupant.handsOver ->
            ExternalNoticeText(summary, take, enabled = occupant != null)
        else -> ExternalNoticeText(summary, "等那边这一轮结束", enabled = false)
    }
}

/**
 * 会话正在别处（比如终端里）运行时，输入栏的位置换成这张说明卡：显示着时每隔几秒查一次那边闲不闲（[Conversation.refreshOccupant]，那边自己退出了卡片就收起），
 * 闲着时能一键接过来（[Conversation.handOver]）。 要强行停掉那边，用右上角菜单里的「结束对话」。
 */
@Composable
internal fun ExternalSessionNotice(conversation: Conversation, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatPanelCorner) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conversation, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                conversation.refreshOccupant()
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
            text = "这个会话正在别处运行",
            style = COUITheme.textStyles.headlineXS,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(text = text.summary, style = COUITheme.textStyles.bodyS, color = colors.labelSecondary)
        Spacer(Modifier.height(12.dp))
        TextButton(text = text.button, onClick = conversation::handOver, enabled = text.enabled)
    }
}
