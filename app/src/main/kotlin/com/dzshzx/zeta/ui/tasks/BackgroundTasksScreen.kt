package com.dzshzx.zeta.ui.tasks

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskOutput
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.formatElapsed
import com.dzshzx.zeta.core.plainTerminalText
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.ui.chat.SubagentScreen
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.rememberNow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.CardListGroupScope
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.ToolbarTextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ChevronForward
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.squircle.squircleBackground
import io.github.suqi8.coui.kmp.squircle.squircleBorder
import io.github.suqi8.coui.kmp.squircle.squircleClip
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.delay

/** 面板看全部对话时 scope 用的值。 */
internal const val AllConversations = "*"

/** 运行中的任务多久读一次输出。 */
private const val OutputPollMs = 2_000L

private const val ListFooter =
    "Claude 在对话里放到后台跑的命令、监视器和子代理。它们跑在对话的 Claude Code 进程里：" +
        "对话闲下来被 Zeta 收起、或进程结束时，没跑完的一起结束。结束了的留最近 20 个。"

private const val ConversationsFooter = "切到别的对话时还在回答、等确认或有后台任务的对话，以及定时任务在后台起的对话。点一个切过去。"

private fun Conversation.label(): String = title.ifBlank { "新对话" }

/** 正在看哪个对话的哪个任务。 */
private data class TaskRef(val conversation: String, val task: String)

/**
 * 后台任务面板。[scope] 是对话的 key 时只看这个对话的（聊天页的后台任务条点进来）； 是 [AllConversations] 时看全部：Zeta
 * 的后台对话，和每个还开着的对话的后台任务（侧栏入口）。 [onOpenConversation] 切到那个对话并回到聊天页。
 */
@Composable
internal fun BackgroundTasksScreen(
    hub: ConversationHub,
    scope: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val live = hub.liveConversations
    val all = scope == AllConversations
    val shown = if (all) live else live.filter { it.key == scope }
    var viewing by remember { mutableStateOf<TaskRef?>(null) }
    // 子代理的步骤：对话的 key 和发起它的那一步（tool_use id）
    var steps by remember { mutableStateOf<TaskRef?>(null) }
    val finished = shown.any { c -> c.tasks.list.any { !it.running } }

    CouiPage(
        title = "后台任务",
        onBack = onBack,
        actions = {
            if (finished)
                ToolbarTextButton(
                    text = "清除已结束",
                    onClick = { shown.forEach { it.tasks.clearFinished() } },
                )
        },
    ) {
        if (all) {
            item(key = "conversations") { ParkedConversationsGroup(hub, onOpenConversation) }
        }
        val withTasks = shown.filter { it.tasks.list.isNotEmpty() }
        if (withTasks.isEmpty()) {
            item(key = "empty") { NoTasksGroup(gone = !all && shown.isEmpty()) }
        }
        withTasks.forEachIndexed { index, c ->
            item(key = "tasks:" + c.key) {
                ConversationTasksGroup(
                    c,
                    title = taskGroupTitle(c, all, current = c === hub.current),
                    footer = if (index == withTasks.lastIndex) ListFooter else null,
                ) { task ->
                    viewing = TaskRef(c.key, task.id)
                }
            }
        }
    }

    SlideInPage(viewing, onBack = { viewing = null }) { ref ->
        TaskPage(
            live = live,
            ref = ref,
            onBack = { viewing = null },
            onOpenConversation = onOpenConversation,
            onOpenSteps = { conversation, use -> steps = TaskRef(conversation.key, use) },
        )
    }

    SlideInPage(steps, onBack = { steps = null }) { ref ->
        SubagentScreen(hub, ref.conversation, ref.task, onBack = { steps = null })
    }
}

/** 后台对话：切到别的对话时还在跑的，和定时任务在后台起的；点一个切过去。 */
@Composable
private fun ParkedConversationsGroup(hub: ConversationHub, onOpenConversation: (String) -> Unit) {
    val others = hub.parkedConversations
    PreferenceGroup(title = "后台对话 · ${others.size}", footer = ConversationsFooter) {
        if (others.isEmpty()) item { BasicComponent(title = "没有在后台运行的对话", enabled = false) }
        others.forEach { c ->
            item(key = c.key) {
                ArrowPreference(
                    title = c.label(),
                    summary =
                        TaskLabels.conversation(
                            c.busy,
                            c.pending != null,
                            c.backgroundTasks,
                            ScheduleRunner.taskName(c),
                        ),
                    onClick = { onOpenConversation(c.openKey) },
                )
            }
        }
    }
}

/** 没有后台任务时的说明；[gone] 是只看一个对话、而它已经收起了。 */
@Composable
private fun NoTasksGroup(gone: Boolean) {
    PreferenceGroup(footer = ListFooter) {
        item {
            BasicComponent(
                title = if (gone) "这个对话已经收起" else "没有后台任务",
                summary = "Claude 用 run_in_background 跑的命令、Monitor 和后台子代理会列在这里",
                enabled = false,
            )
        }
    }
}

/** 一组任务的标题：看全部时是对话名，当前对话注明；只看一个对话时就是它的名字。 */
private fun taskGroupTitle(c: Conversation, all: Boolean, current: Boolean): String =
    if (all && current) c.label() + "（当前对话）" else c.label()

/** 一个对话的后台任务，点一个看详情。 */
@Composable
private fun ConversationTasksGroup(
    c: Conversation,
    title: String,
    footer: String?,
    onOpen: (BackgroundTask) -> Unit,
) {
    PreferenceGroup(title = title, footer = footer) {
        c.tasks.list.forEach { task ->
            item(key = task.id) { TaskRow(task, onClick = { onOpen(task) }) }
        }
    }
}

/** 点进一个任务：找到了是它的详情，对话被收起、或任务被清掉了时说明一句。 */
@Composable
private fun TaskPage(
    live: List<Conversation>,
    ref: TaskRef,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenSteps: (Conversation, String) -> Unit,
) {
    val conversation = live.firstOrNull { it.key == ref.conversation }
    val task = conversation?.tasks?.list?.firstOrNull { it.id == ref.task }
    if (conversation == null || task == null) {
        // 对话被收起、或任务被清掉了
        CouiPage(title = "后台任务", onBack = onBack) {
            item(key = "gone") {
                PreferenceGroup { item { BasicComponent(title = "这个任务已经不在了", enabled = false) } }
            }
        }
        return
    }
    TaskDetail(
        conversation,
        task,
        onBack = onBack,
        onOpenConversation = { onOpenConversation(conversation.openKey) },
        onOpenSteps = { use -> onOpenSteps(conversation, use) },
    )
}

/** 算“已运行”用的现在：没结束的任务每秒走一次，结束了的用不到（用时按结束时刻算）。 只在显示用时的那一行里调用，免得整页每秒重组。 */
@Composable private fun elapsedNow(task: BackgroundTask): Long = task.endedAt ?: rememberNow(1_000)

/** 列表里的一个任务。 */
@Composable
private fun TaskRow(task: BackgroundTask, onClick: () -> Unit) {
    val color =
        when {
            task.state == TaskState.Failed -> COUITheme.colorScheme.error
            task.running -> COUITheme.colorScheme.primary
            else -> null
        }
    ArrowPreference(
        title = TaskLabels.title(task),
        summary = TaskLabels.summary(task, elapsedNow(task)),
        summaryColor =
            if (color != null) BasicComponentDefaults.summaryColor(color = color)
            else BasicComponentDefaults.summaryColor(),
        onClick = onClick,
    )
}

/** 详情里的“已运行”或“用时”。 */
@Composable
private fun ElapsedRow(task: BackgroundTask) {
    BasicComponent(
        title = if (task.running) "已运行" else "用时",
        endActions = { PreferenceValue(formatElapsed(task.elapsedMs(elapsedNow(task)))) },
    )
}

/** 一个任务：说明、状态、命令、输出（或子代理的进展和报告，点进去看它的步骤），运行中可以停掉。 */
@Composable
private fun TaskDetail(
    conversation: Conversation,
    task: BackgroundTask,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
    /** 打开子代理查看页，参数是发起它的那一步（tool_use id）。 */
    onOpenSteps: (String) -> Unit,
) {
    var confirmStop by remember { mutableStateOf(false) }
    // 运行中每两秒读一次输出；结束时（running 变了，重新启动）再读最后一次
    LaunchedEffect(conversation, task.id, task.running) { followTaskOutput(conversation, task) }
    val output = conversation.tasks.outputs[task.id]
    CouiPage(
        title = TaskLabels.page(task),
        onBack = onBack,
        dialogs = {
            ConfirmDialog(
                show = confirmStop,
                title = "停止这个任务？",
                summary = stopSummary(task),
                confirmText = "停止",
                destructive = true,
                onConfirm = {
                    confirmStop = false
                    conversation.stopTask(task.id)
                },
                onDismissRequest = { confirmStop = false },
            )
        },
    ) {
        item(key = "info") {
            TaskInfoGroup(conversation, task, onOpenConversation, onOpenSteps) {
                confirmStop = true
            }
        }
        taskTextItems(task)
        if (task.readable) {
            item(key = "output") { TaskOutputBlock(output, conversation.isProcessAlive) }
        }
    }
}

/** 读任务的输出：先读一次，运行中每隔一会儿再读；读不了的（子代理）不读。 */
private suspend fun followTaskOutput(conversation: Conversation, task: BackgroundTask) {
    if (!task.readable) return
    conversation.readTaskOutput(task.id)
    while (task.running) {
        delay(OutputPollMs)
        conversation.readTaskOutput(task.id)
    }
}

/** 停止确认框里的说明：是哪个任务；子代理停下后 Claude 会接着处理。 */
private fun stopSummary(task: BackgroundTask): String =
    TaskLabels.title(task) + if (task.kind == TaskKind.Agent) "\n子代理停下后，Claude 会收到通知并接着处理。" else ""

/** 任务的概要：说明、状态、用时，子代理还有类型、工具调用、在做什么和它的步骤；所属对话。 运行中最后是停止：输出在下面一直变长，按钮放在最后会被挤着走，点不准，所以放在这组里。 */
@Composable
private fun TaskInfoGroup(
    conversation: Conversation,
    task: BackgroundTask,
    onOpenConversation: () -> Unit,
    onOpenSteps: (String) -> Unit,
    onStop: () -> Unit,
) {
    PreferenceGroup {
        item(key = "title") {
            BasicComponent(
                title = TaskLabels.title(task),
                summary =
                    task.description.takeIf { it.isNotBlank() && it != TaskLabels.title(task) },
            )
        }
        item(key = "state") {
            BasicComponent(
                title = "状态",
                endActions = {
                    PreferenceValue(TaskLabels.state(task), color = taskStateColor(task.state))
                },
            )
        }
        item(key = "elapsed") { ElapsedRow(task) }
        if (task.kind == TaskKind.Agent) agentItems(task, onOpenSteps)
        item(key = "conversation") {
            ArrowPreference(
                title = "所属对话",
                endActions = { PreferenceValue(conversation.label()) },
                onClick = onOpenConversation,
            )
        }
        if (task.running) {
            item(key = "stop") {
                BasicComponent(
                    title = if (task.stopping) "正在停止…" else "停止这个任务",
                    titleColor =
                        BasicComponentDefaults.titleColor(color = COUITheme.colorScheme.error),
                    enabled = !task.stopping && conversation.isProcessAlive,
                    onClick = onStop,
                )
            }
        }
    }
}

/** 状态的颜色：在跑是主题色，完成绿色，失败红色。 */
@Composable
private fun taskStateColor(state: TaskState): Color =
    when (state) {
        TaskState.Running -> COUITheme.colorScheme.primary
        TaskState.Completed -> COUITheme.colorScheme.success
        TaskState.Failed -> COUITheme.colorScheme.error
        else -> Color.Unspecified
    }

/** 子代理才有的几行：类型、工具调用次数、（运行中）在做什么，和进它的步骤。 */
private fun CardListGroupScope.agentItems(task: BackgroundTask, onOpenSteps: (String) -> Unit) {
    task.agentType?.let { type ->
        item(key = "agent") {
            BasicComponent(title = "子代理类型", endActions = { PreferenceValue(type) })
        }
    }
    task.toolUses?.let { uses ->
        item(key = "tools") {
            BasicComponent(title = "工具调用", endActions = { PreferenceValue("$uses 次") })
        }
    }
    if (task.running)
        task.progress?.let { progress ->
            item(key = "progress") { BasicComponent(title = "正在做", summary = progress) }
        }
    task.toolUseId?.let { use ->
        item(key = "steps") {
            ArrowPreference(title = "查看它的步骤", onClick = { onOpenSteps(use) })
        }
    }
}

/** 出错的原因、命令（子代理是交给它的指令）和结果（子代理是报告），有的才列。 */
private fun LazyListScope.taskTextItems(task: BackgroundTask) {
    val agent = task.kind == TaskKind.Agent
    task.error?.let { error -> item(key = "error") { TextBlock(title = "出错", text = error) } }
    task.command?.let { command ->
        item(key = "command") {
            TextBlock(title = if (agent) "交给它的指令" else "命令", text = command, mono = !agent)
        }
    }
    task.summary?.let { summary ->
        item(key = "summary") { TextBlock(title = if (agent) "报告" else "结果", text = summary) }
    }
}

/** 后台命令、监视器的输出（最多 8 KiB）；还没读到、读不到或还没有输出时说明。 */
@Composable
private fun TaskOutputBlock(output: TaskOutput?, processAlive: Boolean) {
    // 去控制符只在输出变了时做，不随别的重组重做
    val text = remember(output?.text) { output?.let { plainTerminalText(it.text).trimEnd() } }
    TextBlock(
        title =
            output
                ?.takeIf { it.error == null }
                ?.let { TaskLabels.outputHeader(it.totalBytes, it.truncated) } ?: "输出",
        text = outputText(output, text, processAlive),
        mono = output?.error == null && !text.isNullOrEmpty(),
    )
}

private fun outputText(output: TaskOutput?, text: String?, processAlive: Boolean): String =
    when {
        output == null -> if (processAlive) "正在读取…" else "进程已经结束，读不到输出了"
        output.error != null -> "没读到：" + output.error
        text.isNullOrEmpty() -> "（还没有输出）"
        else -> text
    }

/** 一段可选中的文字：命令、输出、报告。 */
@Composable
private fun TextBlock(title: String, text: String, mono: Boolean = false) {
    PreferenceGroup(title = title) {
        item {
            SelectionContainer {
                Text(
                    text = text,
                    style =
                        if (mono) {
                            COUITheme.textStyles.bodyS.copy(
                                fontFamily = MonoFont,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            )
                        } else {
                            COUITheme.textStyles.bodyM.copy(fontSize = 14.sp, lineHeight = 21.sp)
                        },
                    color = COUITheme.colorScheme.labelPrimary,
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 输入卡片上方的“后台任务”条：这个对话有后台任务在跑时出现，点进去看本对话的任务。 第二行是最新的那个在做什么。 */
@Composable
internal fun BackgroundTaskStrip(
    conversation: Conversation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = conversation.tasks.list.filter { it.running }
    if (running.isEmpty()) return
    val colors = COUITheme.colorScheme
    val pulse =
        rememberInfiniteTransition(label = "tasks")
            .animateFloat(
                0.35f,
                1f,
                infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "tasks-dot",
            )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .entryPanel(18.dp, COUITheme.colorScheme.fillThin)
                .clickable(onClickLabel = "查看后台任务", onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp)
                        .graphicsLayer { alpha = pulse.value }
                        .clip(CircleShape)
                        .background(colors.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "后台任务 · ${running.size} 个在运行",
                    style =
                        COUITheme.textStyles.bodyS.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    color = colors.primary,
                )
            }
            Text(
                text = TaskLabels.title(running.first()),
                style = COUITheme.textStyles.bodyM.copy(fontSize = 14.sp),
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = COUIIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.labelTertiary,
        )
    }
}

/** 侧栏里的“后台运行”入口：有后台对话或后台任务时出现，点进去看全部对话的。 [count] 是在跑的后台对话和后台任务的个数，为 0 时显示有几个结束了的任务。 */
@Composable
internal fun BackgroundTasksEntry(
    hub: ConversationHub,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversations = hub.parkedConversations.size
    val live = hub.liveConversations
    val runningTasks = live.sumOf { c -> c.tasks.list.count { it.running } }
    val finishedTasks = live.sumOf { c -> c.tasks.list.count { !it.running } }
    if (conversations == 0 && runningTasks == 0 && finishedTasks == 0) return
    val text =
        listOfNotNull(
                conversations.takeIf { it > 0 }?.let { "$it 个对话" },
                runningTasks.takeIf { it > 0 }?.let { "$it 个任务在运行" },
                finishedTasks.takeIf { it > 0 && runningTasks == 0 }?.let { "$it 个任务已结束" },
            )
            .joinToString(" · ")
    val colors = COUITheme.colorScheme
    val active = conversations > 0 || runningTasks > 0
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .entryPanel(14.dp, COUITheme.colorScheme.cardBackground)
                .clickable(onClickLabel = "查看后台运行的对话和任务", onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp)
                .clip(CircleShape)
                .background(if (active) colors.primary else colors.labelTertiary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "后台运行",
            style =
                COUITheme.textStyles.bodyM.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Medium),
            color = colors.labelPrimary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = COUIIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.labelTertiary,
        )
    }
}

/** 任务条和侧栏入口的底：一块填充色加细描边，平滑圆角。任务条在白底的聊天页上，用淡的填充色； 侧栏入口在灰底的侧栏里，用卡片色。 */
@Composable
private fun Modifier.entryPanel(radius: Dp, color: Color): Modifier =
    this.squircleClip(radius)
        .squircleBackground(color, radius)
        .squircleBorder(0.5.dp, COUITheme.colorScheme.divider, radius)
