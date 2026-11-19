package com.dzshzx.zeta.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.formatElapsed
import com.dzshzx.zeta.core.plainTerminalText
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.ui.components.EtaMonospace
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusSuccess
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiTopAppBarTextButton
import com.dzshzx.zeta.ui.setup.ConfigSubPage
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.components.rememberRetained
import com.itos.coui.layout.Group
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 面板看全部对话时 scope 用的值。 */
internal const val AllConversations = "*"

/** 运行中的任务多久读一次输出。 */
private const val OutputPollMs = 2_000L

private const val ListFooter = "Claude 在对话里放到后台跑的命令、监视器和子代理。它们跑在对话的 Claude Code 进程里：" +
    "对话闲下来被 Zeta 收起、或进程结束时，没跑完的一起结束。结束了的留最近 20 个。"

private const val ConversationsFooter = "切到别的对话时还在回答、等确认或有后台任务的对话，以及定时任务在后台起的对话。点一个切过去。"

private fun Conversation.label(): String = title.ifBlank { "新对话" }

/** 打开这个对话用的键（和 ConversationHub.openConversation 一致）。 */
private fun Conversation.openKey(): String = sessionId ?: key

/** 正在看哪个对话的哪个任务。 */
private data class TaskRef(val conversation: String, val task: String)

/**
 * 后台任务面板。[scope] 是对话的 key 时只看这个对话的（聊天页的后台任务条点进来）；
 * 是 [AllConversations] 时看全部：Zeta 的后台对话，和每个还开着的对话的后台任务（侧栏入口）。
 * [onOpenConversation] 切到那个对话并回到聊天页。
 */
@Composable
internal fun BackgroundTasksScreen(hub: ConversationHub, scope: String, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val live = hub.liveConversations
    val all = scope == AllConversations
    val shown = if (all) live else live.filter { it.key == scope }
    var viewing by remember { mutableStateOf<TaskRef?>(null) }
    val finished = shown.any { c -> c.tasks.list.any { !it.running } }

    CouiScaffoldPage(
        title = "后台任务",
        onBack = onBack,
        actions = {
            if (finished) CouiTopAppBarTextButton(text = "清除已结束", onClick = { shown.forEach { it.tasks.clearFinished() } })
        },
    ) {
        if (all) {
            val others = hub.backgroundConversations
            Group(title = "后台对话 · ${others.size}", footer = ConversationsFooter) {
                if (others.isEmpty()) CouiPreference(title = "没有在后台运行的对话", enabled = false, showChevron = false)
                others.forEach { c ->
                    CouiPreference(
                        title = c.label(),
                        summary = TaskLabels.conversation(c.busy, c.pending != null, c.backgroundTasks, ScheduleRunner.taskName(c)),
                        onClick = { onOpenConversation(c.openKey()) },
                    )
                }
            }
        }
        val withTasks = shown.filter { it.tasks.list.isNotEmpty() }
        if (withTasks.isEmpty()) {
            Group(footer = ListFooter) {
                CouiPreference(
                    title = if (all || shown.isNotEmpty()) "没有后台任务" else "这个对话已经收起",
                    summary = "Claude 用 run_in_background 跑的命令、Monitor 和后台子代理会列在这里",
                    enabled = false,
                    showChevron = false,
                )
            }
        }
        withTasks.forEachIndexed { index, c ->
            val current = c === hub.current
            Group(
                title = if (all) c.label() + if (current) "（当前对话）" else "" else c.label(),
                footer = if (index == withTasks.lastIndex) ListFooter else null,
            ) {
                c.tasks.list.forEach { task -> TaskRow(task, onClick = { viewing = TaskRef(c.key, task.id) }) }
            }
        }
    }

    val retained = rememberRetained(viewing)
    ConfigSubPage(visible = viewing != null, onBack = { viewing = null }) {
        val ref = retained ?: return@ConfigSubPage
        val conversation = live.firstOrNull { it.key == ref.conversation }
        val task = conversation?.tasks?.list?.firstOrNull { it.id == ref.task }
        if (conversation == null || task == null) {
            // 对话被收起、或任务被清掉了
            CouiScaffoldPage(title = "后台任务", onBack = { viewing = null }) {
                Group { CouiPreference(title = "这个任务已经不在了", enabled = false, showChevron = false) }
            }
        } else {
            TaskDetail(conversation, task, onBack = { viewing = null }, onOpenConversation = { onOpenConversation(conversation.openKey()) })
        }
    }
}

/**
 * 算“已运行”用的现在：没结束的任务每秒走一次，结束了的用不到（用时按结束时刻算）。
 * 只在显示用时的那一行里调用，免得整页每秒重组。
 */
@Composable
private fun elapsedNow(task: BackgroundTask): Long = task.endedAt ?: rememberNow(1_000)

/** 列表里的一个任务。 */
@Composable
private fun TaskRow(task: BackgroundTask, onClick: () -> Unit) {
    CouiPreference(
        title = TaskLabels.title(task),
        summary = TaskLabels.summary(task, elapsedNow(task)),
        summaryColor = when {
            task.state == TaskState.Failed -> StatusError
            task.running -> MiuixTheme.colorScheme.primary
            else -> Color.Unspecified
        },
        onClick = onClick,
    )
}

/** 详情里的“已运行”或“用时”。 */
@Composable
private fun ElapsedRow(task: BackgroundTask) {
    CouiPreference(title = if (task.running) "已运行" else "用时", value = formatElapsed(task.elapsedMs(elapsedNow(task))), showChevron = false)
}

/** 一个任务：说明、状态、命令、输出（或子代理的进展和报告），运行中可以停掉。 */
@Composable
private fun TaskDetail(conversation: Conversation, task: BackgroundTask, onBack: () -> Unit, onOpenConversation: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    // 运行中每两秒读一次输出；结束时（running 变了，重新启动）再读最后一次
    LaunchedEffect(conversation, task.id, task.running) {
        if (!task.readable) return@LaunchedEffect
        conversation.readTaskOutput(task.id)
        while (task.running) {
            delay(OutputPollMs)
            conversation.readTaskOutput(task.id)
        }
    }
    val output = conversation.tasks.outputs[task.id]
    CouiScaffoldPage(title = TaskLabels.page(task), onBack = onBack) {
        Group {
            CouiPreference(title = TaskLabels.title(task), summary = task.description.takeIf { it.isNotBlank() && it != TaskLabels.title(task) }, showChevron = false)
            CouiPreference(
                title = "状态",
                value = TaskLabels.state(task),
                valueColor = when (task.state) {
                    TaskState.Running -> MiuixTheme.colorScheme.primary
                    TaskState.Completed -> StatusSuccess
                    TaskState.Failed -> StatusError
                    else -> Color.Unspecified
                },
                showChevron = false,
            )
            ElapsedRow(task)
            if (task.kind == TaskKind.Agent) {
                task.agentType?.let { CouiPreference(title = "子代理类型", value = it, showChevron = false) }
                task.toolUses?.let { CouiPreference(title = "工具调用", value = "$it 次", showChevron = false) }
                if (task.running) task.progress?.let { CouiPreference(title = "正在做", summary = it, showChevron = false) }
            }
            CouiPreference(title = "所属对话", value = conversation.label(), onClick = onOpenConversation)
            // 停止放在上面这组里：输出在下面一直变长，按钮放在最后会被挤着走，点不准
            if (task.running) {
                CouiPreference(
                    title = if (task.stopping) "正在停止…" else "停止这个任务",
                    titleColor = StatusError,
                    enabled = !task.stopping && conversation.isProcessAlive,
                    onClick = { confirmStop = true },
                    showChevron = false,
                )
            }
        }
        task.error?.let { TextBlock(title = "出错", text = it) }
        task.command?.let { TextBlock(title = if (task.kind == TaskKind.Agent) "交给它的指令" else "命令", text = it, mono = task.kind != TaskKind.Agent) }
        task.summary?.let { TextBlock(title = if (task.kind == TaskKind.Agent) "报告" else "结果", text = it) }
        if (task.readable) {
            // 输出最多 8 KiB，去控制符只在它变了时做，不随别的重组重做
            val text = remember(output?.text) { output?.let { plainTerminalText(it.text).trimEnd() } }
            TextBlock(
                title = output?.takeIf { it.error == null }?.let { TaskLabels.outputHeader(it.totalBytes, it.truncated) } ?: "输出",
                text = when {
                    output == null -> if (conversation.isProcessAlive) "正在读取…" else "进程已经结束，读不到输出了"
                    output.error != null -> "没读到：" + output.error
                    text.isNullOrEmpty() -> "（还没有输出）"
                    else -> text
                },
                mono = output?.error == null && !text.isNullOrEmpty(),
            )
        }
    }
    CouiConfirmDialog(
        visible = confirmStop,
        title = "停止这个任务？",
        message = TaskLabels.title(task) + if (task.kind == TaskKind.Agent) "\n子代理停下后，Claude 会收到通知并接着处理。" else "",
        confirmText = "停止",
        destructive = true,
        onConfirm = {
            confirmStop = false
            conversation.stopTask(task.id)
        },
        onDismissRequest = { confirmStop = false },
    )
}

/** 一段可选中的文字：命令、输出、报告。 */
@Composable
private fun TextBlock(title: String, text: String, mono: Boolean = false) {
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    Group(title = title) {
        SelectionContainer {
            Text(
                text = text,
                style = if (mono) {
                    MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace, fontSize = 11.5.sp, lineHeight = 16.sp)
                } else {
                    MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 21.sp)
                },
                color = colors.onSurface.copy(alpha = if (dark) 0.86f else 0.82f),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * 输入卡片上方的“后台任务”条：这个对话有后台任务在跑时出现，点进去看本对话的任务。
 * 第二行是最新的那个在做什么。
 */
@Composable
internal fun BackgroundTaskStrip(conversation: Conversation, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val running = conversation.tasks.list.filter { it.running }
    if (running.isEmpty()) return
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(18.dp, 2.3f) }
    val pulse = rememberInfiniteTransition(label = "tasks").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "tasks-dot")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
            .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
            .clickable(onClickLabel = "查看后台任务", onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).graphicsLayer { alpha = pulse.value }.clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "后台任务 · ${running.size} 个在运行",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = colors.primary,
                )
            }
            Text(
                text = TaskLabels.title(running.first()),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariantSummary,
        )
    }
}

/**
 * 侧栏里的“后台运行”入口：有后台对话或后台任务时出现，点进去看全部对话的。
 * [count] 是在跑的后台对话和后台任务的个数，为 0 时显示有几个结束了的任务。
 */
@Composable
internal fun BackgroundEntry(hub: ConversationHub, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val conversations = hub.backgroundConversations.size
    val live = hub.liveConversations
    val runningTasks = live.sumOf { c -> c.tasks.list.count { it.running } }
    val finishedTasks = live.sumOf { c -> c.tasks.list.count { !it.running } }
    if (conversations == 0 && runningTasks == 0 && finishedTasks == 0) return
    val text = listOfNotNull(
        conversations.takeIf { it > 0 }?.let { "$it 个对话" },
        runningTasks.takeIf { it > 0 }?.let { "$it 个任务在运行" },
        finishedTasks.takeIf { it > 0 && runningTasks == 0 }?.let { "$it 个任务已结束" },
    ).joinToString(" · ")
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val active = conversations > 0 || runningTasks > 0
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF252830) else Color.White, shape)
            .border(0.5.dp, if (dark) Color(0x1EFFFFFF) else Color(0x12000000), shape)
            .clickable(onClickLabel = "查看后台运行的对话和任务", onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (active) colors.primary else colors.onSurfaceVariantSummary))
        Spacer(Modifier.width(10.dp))
        Text(
            text = "后台运行",
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
            color = colors.onSurface,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp),
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariantSummary,
        )
    }
}
