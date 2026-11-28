package com.dzshzx.zeta.ui.tasks

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
import androidx.compose.runtime.DisposableEffect
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
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.SubagentScreen
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroupScope
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TopBarTextButton
import com.dzshzx.zeta.ui.components.pingPong
import com.dzshzx.zeta.ui.components.rememberIndicatorClock
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.components.success
import com.dzshzx.zeta.ui.settings.DangerRow
import com.dzshzx.zeta.ui.settings.ValueRow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 面板看全部对话时 scope 用的值。 */
const val AllConversations = "*"

private fun listFooter(): String = Strings.tasksListFooter()

private fun conversationsFooter(): String = Strings.tasksConversationsFooter()

private fun androidx.compose.foundation.lazy.LazyListScope.swarmBoardItems(
    shown: List<Conversation>,
) {
    shown.forEach { c ->
        val swarm = c.swarm
        if (swarm != null && swarm.members.isNotEmpty()) {
            item(key = "swarm:" + c.key) { SwarmBoardGroup(swarm) }
        }
    }
}

@Composable
private fun SwarmBoardGroup(swarm: com.dzshzx.zeta.core.conv.SwarmState) {
    val title =
        if (swarm.title.isNotBlank()) "${Strings.tasksSwarmTitle()} · ${swarm.title}"
        else Strings.tasksSwarmTitle()
    PreferenceGroup(title = title) {
        swarm.members.forEach { m ->
            item(key = m.name) {
                val summary = listOf(m.model, m.role).filter { it.isNotBlank() }.joinToString(" · ")
                BasicComponent(
                    title = m.name,
                    summary = summary.ifBlank { null },
                    endActions = { PreferenceValue(m.status, clickable = false) },
                )
            }
        }
    }
}

internal fun Conversation.label(): String = title.ifBlank { Strings.tasksUntitled() }

/** 正在看哪个对话的哪个任务。 */
private data class TaskRef(val conversation: String, val task: String)

/** 面板上盖着的页：[viewing] 是任务详情，[steps] 是子代理查看页（对话的 key 和发起它的那一步 tool_use id）。 */
private class OpenedTask {
    var viewing by mutableStateOf<TaskRef?>(null)
    var steps by mutableStateOf<TaskRef?>(null)

    /** 点了对话 [conversation] 的 [task]：子代理直接进它的查看页；还不知道是哪一步发起的（task_started 没到）才看任务详情。 */
    fun open(conversation: String, task: BackgroundTask) {
        val use = task.toolUseId
        if (task.kind == TaskKind.Agent && use != null) steps = TaskRef(conversation, use)
        else viewing = TaskRef(conversation, task.id)
    }
}

/**
 * 后台任务面板。[scope] 是对话的 key 时只看这个对话的（聊天页的后台任务条点进来）； 是 [AllConversations] 时看全部：Zeta
 * 的后台对话，和每个还开着的对话的后台任务（侧栏入口）。 [onOpenConversation] 切到那个对话并回到聊天页。
 */
@Composable
fun BackgroundTasksScreen(
    hub: ConversationHub,
    scope: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val live = hub.liveConversations
    val all = scope == AllConversations
    val shown = if (all) live else live.filter { it.key == scope }
    val opened = remember { OpenedTask() }
    val finished = shown.any { c -> c.tasks.list.any { !it.running } }

    MiuixPage(
        title = Strings.tasksTitle(),
        onBack = onBack,
        actions = {
            if (finished)
                TopBarTextButton(
                    text = Strings.tasksClearFinished(),
                    onClick = { shown.forEach { it.tasks.clearFinished() } },
                )
        },
    ) {
        if (all) {
            item(key = "conversations") { ParkedConversationsGroup(hub, onOpenConversation) }
        }
        swarmBoardItems(shown)
        val withTasks = shown.filter { it.tasks.list.isNotEmpty() }
        if (withTasks.isEmpty()) {
            item(key = "empty") { NoTasksGroup(gone = !all && shown.isEmpty()) }
        }
        withTasks.forEachIndexed { index, c ->
            item(key = "tasks:" + c.key) {
                ConversationTasksGroup(
                    c,
                    title = taskGroupTitle(c, all, current = c === hub.current),
                    footer = if (index == withTasks.lastIndex) listFooter() else null,
                ) { task ->
                    opened.open(c.key, task)
                }
            }
        }
    }

    SlideInPage(opened.viewing, onBack = { opened.viewing = null }) { ref ->
        TaskPage(
            live = live,
            ref = ref,
            onBack = { opened.viewing = null },
            onOpenConversation = onOpenConversation,
        )
    }

    SlideInPage(opened.steps, onBack = { opened.steps = null }) { ref ->
        SubagentScreen(
            hub,
            ref.conversation,
            ref.task,
            onBack = { opened.steps = null },
            onOpenConversation = onOpenConversation,
        )
    }
}

/** 后台对话：切到别的对话时还在跑的，和定时任务在后台起的；点一个切过去。 */
@Composable
private fun ParkedConversationsGroup(hub: ConversationHub, onOpenConversation: (String) -> Unit) {
    val others = hub.parkedConversations
    PreferenceGroup(title = Strings.tasksParkedGroup(others.size), footer = conversationsFooter()) {
        if (others.isEmpty())
            item { BasicComponent(title = Strings.tasksNoParked(), enabled = false) }
        others.forEach { c ->
            item(key = c.key) {
                val baseSummary =
                    TaskLabels.conversation(
                        c.busy,
                        c.pending != null,
                        c.backgroundTasks,
                        hub.platform.unattendedName(c),
                    )
                val summary =
                    if (
                        com.dzshzx.zeta.core.Harnesses.normalize(c.harness) ==
                            com.dzshzx.zeta.core.Harnesses.AGY
                    )
                        "Antigravity · $baseSummary"
                    else baseSummary
                ArrowPreference(
                    title = c.label(),
                    summary = summary,
                    onClick = { onOpenConversation(c.openKey) },
                )
            }
        }
    }
}

/** 没有后台任务时的说明；[gone] 是只看一个对话、而它已经收起了。 */
@Composable
private fun NoTasksGroup(gone: Boolean) {
    PreferenceGroup(footer = listFooter()) {
        item {
            BasicComponent(
                title = if (gone) Strings.tasksConversationGone() else Strings.tasksNone(),
                summary = Strings.tasksNoneSummary(),
                enabled = false,
            )
        }
    }
}

/** 一组任务的标题：看全部时是对话名，当前对话注明；只看一个对话时就是它的名字。 */
private fun taskGroupTitle(c: Conversation, all: Boolean, current: Boolean): String =
    if (all && current) Strings.tasksCurrentConversation(c.label()) else c.label()

/** 一个对话的后台任务，点一个看详情（子代理是它的查看页）。 */
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
) {
    val conversation = live.firstOrNull { it.key == ref.conversation }
    val task = conversation?.tasks?.list?.firstOrNull { it.id == ref.task }
    if (conversation == null || task == null) {
        // 对话被收起、或任务被清掉了
        MiuixPage(title = Strings.tasksTitle(), onBack = onBack) {
            item(key = "gone") {
                PreferenceGroup {
                    item { BasicComponent(title = Strings.tasksGone(), enabled = false) }
                }
            }
        }
        return
    }
    TaskDetail(
        conversation,
        task,
        onBack = onBack,
        onOpenConversation = { onOpenConversation(conversation.openKey) },
    )
}

/** 算“已运行”用的现在：没结束的任务每秒走一次，结束了的用不到（用时按结束时刻算）。 只在显示用时的那一行里调用，免得整页每秒重组。 */
@Composable private fun elapsedNow(task: BackgroundTask): Long = task.endedAt ?: rememberNow(1_000)

/** 列表里的一个任务。 */
@Composable
private fun TaskRow(task: BackgroundTask, onClick: () -> Unit) {
    val color =
        when {
            task.state == TaskState.Failed -> MiuixTheme.colorScheme.error
            task.running -> MiuixTheme.colorScheme.primary
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

/** 详情里的“已运行”或“用时”（子代理查看页也用）。 */
@Composable
internal fun ElapsedRow(task: BackgroundTask) {
    ValueRow(
        if (task.running) Strings.tasksElapsedRunning() else Strings.tasksElapsedTook(),
        formatElapsed(task.elapsedMs(elapsedNow(task))),
    )
}

/** 一个任务：说明、状态、命令、输出，运行中可以停掉。子代理平常不进这页（面板里点了直接是它的查看页），进来时是进展和报告。 */
@Composable
private fun TaskDetail(
    conversation: Conversation,
    task: BackgroundTask,
    onBack: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    var confirmStop by remember { mutableStateOf(false) }
    // 详情开着时请核心推输出（变了才推，任务结束后核心读完最后一次就不再问），关了就停；子代理读不了输出，不看
    if (task.readable) {
        DisposableEffect(conversation, task.id) {
            conversation.watchTaskOutput(task.id, true)
            onDispose { conversation.watchTaskOutput(task.id, false) }
        }
    }
    val output = conversation.tasks.outputs[task.id]
    MiuixPage(
        title = TaskLabels.page(task),
        onBack = onBack,
        dialogs = {
            ConfirmDialog(
                show = confirmStop,
                title = Strings.tasksStopConfirm(),
                summary = stopSummary(task),
                confirmText = Strings.tasksStop(),
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
            TaskInfoGroup(conversation, task, onOpenConversation) {
                confirmStop = true
            }
        }
        taskTextItems(task)
        if (task.readable) {
            item(key = "output") { TaskOutputBlock(output, conversation.isProcessAlive) }
        }
    }
}

/** 停止确认框里的说明：是哪个任务；子代理停下后 Claude 会接着处理。 */
internal fun stopSummary(task: BackgroundTask): String =
    TaskLabels.title(task) +
        if (task.kind == TaskKind.Agent) "\n" + Strings.tasksStopAgentNote() else ""

/** 任务的概要：说明、状态、用时，子代理还有类型、工具调用、在做什么；所属对话。 运行中最后是停止：输出在下面一直变长，按钮放在最后会被挤着走，点不准，所以放在这组里。 */
@Composable
private fun TaskInfoGroup(
    conversation: Conversation,
    task: BackgroundTask,
    onOpenConversation: () -> Unit,
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
            ValueRow(
                Strings.tasksState(),
                TaskLabels.state(task),
                color = taskStateColor(task.state),
            )
        }
        item(key = "elapsed") { ElapsedRow(task) }
        if (task.kind == TaskKind.Agent) agentItems(task)
        item(key = "conversation") {
            ArrowPreference(
                title = Strings.tasksConversation(),
                endActions = { PreferenceValue(conversation.label()) },
                onClick = onOpenConversation,
            )
        }
        if (task.running) {
            item(key = "stop") {
                DangerRow(
                    title =
                        if (task.stopping) Strings.tasksStoppingEllipsis()
                        else Strings.tasksStopThis(),
                    enabled = !task.stopping && conversation.isProcessAlive,
                    onClick = onStop,
                )
            }
        }
    }
}

/** 状态的颜色：在跑是主题色，完成绿色，失败红色，别的不另上色；子代理查看页在跑和结局也照它上色（SubagentScreen）。 */
@Composable
internal fun taskStateColor(state: TaskState): Color =
    when (state) {
        TaskState.Running -> MiuixTheme.colorScheme.primary
        TaskState.Completed -> MiuixTheme.colorScheme.success
        TaskState.Failed -> MiuixTheme.colorScheme.error
        else -> Color.Unspecified
    }

/** 子代理才有的几行：类型、工具调用次数、（运行中）在做什么。 */
private fun PreferenceGroupScope.agentItems(task: BackgroundTask) {
    task.agentType?.let { type ->
        item(key = "agent") { ValueRow(Strings.tasksAgentType(), type) }
    }
    task.toolUses?.let { uses ->
        item(key = "tools") { ValueRow(Strings.tasksToolUses(), Strings.tasksToolUsesCount(uses)) }
    }
    if (task.running)
        task.progress?.let { progress ->
            item(key = "progress") {
                BasicComponent(title = Strings.tasksProgress(), summary = progress)
            }
        }
}

/** 出错的原因、命令（子代理是交给它的指令）和结果（子代理是报告），有的才列。 */
private fun LazyListScope.taskTextItems(task: BackgroundTask) {
    val agent = task.kind == TaskKind.Agent
    task.error?.let { error ->
        item(key = "error") { TextBlock(title = Strings.tasksError(), text = error) }
    }
    task.command?.let { command ->
        item(key = "command") {
            TextBlock(
                title = if (agent) Strings.tasksAgentPrompt() else Strings.tasksCommand(),
                text = command,
                mono = !agent,
            )
        }
    }
    task.summary?.let { summary ->
        item(key = "summary") {
            TextBlock(
                title = if (agent) Strings.tasksReport() else Strings.tasksResult(),
                text = summary,
            )
        }
    }
}

/** 后台命令、监视器的输出（最多 8 KiB）；还没读到、读不到或还没有输出时说明。 */
@Composable
private fun TaskOutputBlock(output: TaskOutput?, processAlive: Boolean) {
    // 核心推来的已经是去掉控制符的纯文本
    val text = output?.text?.trimEnd()
    TextBlock(
        title =
            output
                ?.takeIf { it.error == null }
                ?.let { TaskLabels.outputHeader(it.totalBytes, it.truncated) }
                ?: Strings.tasksOutput(),
        text = outputText(output, text, processAlive),
        mono = output?.error == null && !text.isNullOrEmpty(),
    )
}

private fun outputText(output: TaskOutput?, text: String?, processAlive: Boolean): String =
    when {
        output == null ->
            if (processAlive) Strings.tasksOutputLoading() else Strings.tasksOutputProcessGone()
        output.error != null -> Strings.tasksOutputFailed(output.errorSaid().orEmpty())
        text.isNullOrEmpty() -> Strings.tasksOutputEmpty()
        else -> text
    }

/** 一段可选中的文字：命令、输出、报告。 */
@Composable
internal fun TextBlock(title: String, text: String, mono: Boolean = false) {
    PreferenceGroup(title = title) {
        item {
            SelectionContainer {
                Text(
                    text = text,
                    style =
                        if (mono) {
                            MiuixTheme.textStyles.footnote1.copy(
                                fontFamily = MonoFont,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                            )
                        } else {
                            MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 21.sp)
                        },
                    color = MiuixTheme.colorScheme.onBackground,
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** 输入卡片上方的“后台任务”条：这个对话有后台任务在跑时出现，点进去看本对话的任务。 第二行是最新的那个在做什么。 */
@Composable
fun BackgroundTaskStrip(
    conversation: Conversation,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = conversation.tasks.list.filter { it.running }
    if (running.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    val clock = rememberIndicatorClock()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .entryPanel(18.dp, MiuixTheme.colorScheme.secondaryContainer)
                .clickable(onClickLabel = Strings.tasksOpen(), onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp)
                        .graphicsLayer { alpha = pingPong(clock.value, 900, 0.35f, 1f) }
                        .clip(CircleShape)
                        .background(colors.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = Strings.tasksStripRunning(running.size),
                    style =
                        MiuixTheme.textStyles.footnote1.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                    color = colors.primary,
                )
            }
            Text(
                text = TaskLabels.title(running.first()),
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariantActions,
        )
    }
}

/** 侧栏里的“后台运行”入口：有后台对话或后台任务时出现，点进去看全部对话的。 [count] 是在跑的后台对话和后台任务的个数，为 0 时显示有几个结束了的任务。 */
@Composable
fun BackgroundTasksEntry(
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
                conversations.takeIf { it > 0 }?.let { Strings.tasksEntryConversations(it) },
                runningTasks.takeIf { it > 0 }?.let { Strings.tasksEntryRunning(it) },
                finishedTasks
                    .takeIf { it > 0 && runningTasks == 0 }
                    ?.let { Strings.tasksEntryFinished(it) },
            )
            .joinToString(" · ")
    val colors = MiuixTheme.colorScheme
    val active = conversations > 0 || runningTasks > 0
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .entryPanel(14.dp, MiuixTheme.colorScheme.surfaceContainer)
                .clickable(onClickLabel = Strings.tasksEntryOpen(), onClick = onOpen)
                .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp)
                .clip(CircleShape)
                .background(if (active) colors.primary else colors.onSurfaceVariantActions),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = Strings.tasksEntry(),
            style =
                MiuixTheme.textStyles.body2.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
            color = colors.onBackground,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = colors.onSurfaceVariantActions,
        )
    }
}

/** 任务条和侧栏入口的底：一块填充色加细描边，平滑圆角。任务条在白底的聊天页上，用淡的填充色； 侧栏入口在灰底的侧栏里，用卡片色。 */
@Composable
private fun Modifier.entryPanel(radius: Dp, color: Color): Modifier =
    this.squircleClip(radius)
        .squircleBackground(color, radius)
        .squircleBorder(0.5.dp, MiuixTheme.colorScheme.dividerLine, radius)
