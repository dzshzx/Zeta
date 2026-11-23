package com.dzshzx.zeta.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.ui.chat.AnswerBlock
import com.dzshzx.zeta.ui.chat.Chevron
import com.dzshzx.zeta.ui.chat.NoticeRow
import com.dzshzx.zeta.ui.chat.PulsingText
import com.dzshzx.zeta.ui.chat.ThoughtBlock
import com.dzshzx.zeta.ui.chat.WorkStepRow
import com.dzshzx.zeta.ui.chat.presentation
import com.dzshzx.zeta.ui.chat.rememberWorkInk
import com.dzshzx.zeta.ui.chat.workIcon
import com.dzshzx.zeta.ui.components.FoldEnter
import com.dzshzx.zeta.ui.components.FoldExit
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.theme.COUITheme

// 回答面板里的几样照 Eta 浮窗里的对话（GPL-3.0）：用户气泡的外形和配色拷自 ui/components/ChatMessageItem.kt
// 的 UserMessageBubble（assistantOverlay），工作过程那一行拷自 ui/components/AgentWorkProcess.kt 的
// AgentWorkHeader；回答正文和复制键用 Zeta 聊天页的 AnswerBlock（它本来就照 Eta 的 AgentMessageBlock）。

/** 浮窗上带上的屏幕：哪个应用、截图缩略图。 */
@Immutable
internal data class ScreenChip(
    val app: String,
    val thumbnail: ImageBitmap?,
    /** 用户把它去掉了：发出去的只有问题。 */
    val removed: Boolean,
)

/** 回答面板里的一行（照 Eta 浮窗里的对话列表）。 */
@Immutable
internal sealed interface ReplyEntry {
    val key: String

    /** 用户的问题（右对齐的灰色气泡），第一问上面带着这次带上的屏幕。 */
    data class Question(override val key: String, val text: String, val screen: ScreenChip?) :
        ReplyEntry

    /** 一组思考和工具调用：折叠成一行「思考 N 次」，点开是逐步的时间线。 */
    data class Work(val item: WorkItem) : ReplyEntry {
        override val key: String
            get() = item.id
    }

    data class Answer(val item: AnswerItem) : ReplyEntry {
        override val key: String
            get() = item.id
    }

    /** 中间说明：同回答一样排正文，不算进回答。 */
    data class Thought(val item: ThoughtItem) : ReplyEntry {
        override val key: String
            get() = item.id
    }

    data class Problem(val item: NoticeItem) : ReplyEntry {
        override val key: String
            get() = item.id
    }

    /** 这一轮的状态：「正在分析任务」、等用户处理、没有回答。[active] 时图标用主色、文字呼吸。 */
    data class Status(
        override val key: String,
        val text: String,
        val active: Boolean,
        val attention: Boolean = false,
    ) : ReplyEntry
}

/** 发出去以后浮窗上显示的对话。 */
@Immutable
internal data class ReplyView(
    val entries: List<ReplyEntry>,
    /** 这一轮还在进行（正在分析、正在处理、在出回答）：输入栏右边是停止键，输入框暂时不能输入。 */
    val busy: Boolean,
    /** 最后一问之后 Claude 说了多少字（各段回答去掉首尾空白后相加）：0 是还没开口；流式输出时跟着长，回答区靠它跟到底。 */
    val answerLength: Int,
)

/** 工作过程那一行（照 Eta 的 AgentWorkHeader）的文字。 */
internal object WorkSummary {
    const val ANALYZING = "正在分析任务"

    fun text(item: WorkItem): String {
        val running = item.steps.lastOrNull { it.running }
        val tools = item.steps.filterIsInstance<ToolStep>()
        if (running is ToolStep) {
            return "正在处理 · 第 ${tools.size} 步 · " + running.presentation().title
        }
        if (running != null) return ANALYZING
        val parts = buildList {
            val thinking = item.steps.count { it is ThinkingStep }
            if (thinking > 0) add("思考 $thinking 次")
            if (tools.isNotEmpty()) add("调用 ${tools.size} 个工具")
            val failed = tools.count { it.status == ToolStatus.Failed }
            if (failed > 0) add("$failed 个失败")
        }
        return parts.joinToString(" · ").ifEmpty { "已完成分析" }
    }
}

/** 从对话的条目里取浮窗要显示的对话。纯函数，单元测试覆盖。 */
internal object AssistantReplies {
    /** [screen] 是这次带上的屏幕，画在第一问上面；[waitingFor] 是在等用户处理的请求（确认工具调用）；[asking] 是刚发出去、还没进对话的问题，排在最后。 */
    fun view(
        items: List<ChatItem>,
        busy: Boolean,
        waitingFor: String?,
        screen: ScreenChip? = null,
        asking: String? = null,
    ): ReplyView {
        val entries = ArrayList<ReplyEntry>()
        var unsentScreen = screen
        for (item in items) {
            val entry = entryOf(item, unsentScreen) ?: continue
            if (entry is ReplyEntry.Question) unsentScreen = null
            entries += entry
        }
        if (asking != null) entries += ReplyEntry.Question(ASKING, asking, unsentScreen)
        val tail = entries.takeLastWhile { it !is ReplyEntry.Question }
        val answerLength = tail.sumOf {
            (it as? ReplyEntry.Answer)?.item?.text?.trim()?.length ?: 0
        }
        status(tail, busy, waitingFor)?.let(entries::add)
        return ReplyView(entries, busy, answerLength)
    }

    /** 还没进对话的那一问的 key。 */
    private const val ASKING = "asking"

    private fun entryOf(item: ChatItem, screen: ScreenChip?): ReplyEntry? =
        when (item) {
            is UserItem -> ReplyEntry.Question(item.id, ScreenDigest.question(item.text), screen)
            is WorkItem -> item.takeIf { it.steps.isNotEmpty() }?.let(ReplyEntry::Work)
            is AnswerItem ->
                item.takeIf { it.streaming || it.text.isNotBlank() }?.let(ReplyEntry::Answer)
            is ThoughtItem -> ReplyEntry.Thought(item)
            is NoticeItem -> item.takeIf { it.error }?.let(ReplyEntry::Problem)
            else -> null
        }

    /** 最后一问之后（[tail]）要不要再加一行状态。 */
    private fun status(tail: List<ReplyEntry>, busy: Boolean, waitingFor: String?): ReplyEntry? {
        val last = tail.lastOrNull()
        return when {
            waitingFor != null ->
                ReplyEntry.Status(
                    "status",
                    "等你处理：$waitingFor，到 Zeta 里处理",
                    active = false,
                    attention = true,
                )
            busy &&
                last !is ReplyEntry.Answer &&
                !(last is ReplyEntry.Thought && last.item.streaming) &&
                !(last is ReplyEntry.Work && last.item.running) ->
                ReplyEntry.Status("status", WorkSummary.ANALYZING, active = true)
            !busy && tail.none { it is ReplyEntry.Answer || it is ReplyEntry.Problem } ->
                ReplyEntry.Status("status", "这一轮没有回答", active = false)
            else -> null
        }
    }
}

/** 回答面板里滚动的那部分：一问一答依次排下来。 */
@Composable
internal fun ReplyEntries(reply: ReplyView, cache: StreamingMarkdownStateCache) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp)) {
        reply.entries.forEach { entry ->
            when (entry) {
                is ReplyEntry.Question -> QuestionBubble(entry)
                is ReplyEntry.Work -> WorkRow(entry.item)
                is ReplyEntry.Answer -> AnswerBlock(entry.item, cache, inset = 16.dp)
                is ReplyEntry.Thought -> ThoughtBlock(entry.item, cache, inset = 16.dp)
                is ReplyEntry.Problem -> NoticeRow(entry.item)
                is ReplyEntry.Status -> StatusRow(entry)
            }
        }
    }
}

/** 单行时左侧呈完整半胶囊弧度，右下角保留利落的 4dp 对话收角（照 Eta 浮窗里的用户气泡）。 */
private val QuestionBubbleShape =
    RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomEnd = 4.dp, bottomStart = 14.dp)

/** 用户的问题：右对齐的灰色气泡；第一问上面是这次带上的屏幕（小缩略图和应用名）。 */
@Composable
private fun QuestionBubble(entry: ReplyEntry.Question) {
    val colors = rememberAssistantColors()
    Column(
        modifier =
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        entry.screen?.let { ScreenAttachment(it, colors) }
        if (entry.text.isNotEmpty()) {
            SelectionContainer {
                Text(
                    text = entry.text,
                    style =
                        COUITheme.textStyles.bodyL.copy(
                            lineHeight = 23.sp,
                            lineHeightStyle = EditorialLineHeightStyle,
                        ),
                    color = COUITheme.colorScheme.labelPrimary,
                    modifier =
                        Modifier.widthIn(max = 316.dp)
                            .clip(QuestionBubbleShape)
                            .background(colors.questionBubble, QuestionBubbleShape)
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** 问题上面的小附件：带上的屏幕截图缩略图和应用名。 */
@Composable
private fun ScreenAttachment(screen: ScreenChip, colors: AssistantColors) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
            Modifier.widthIn(max = 260.dp)
                .clip(shape)
                .background(colors.questionBubble.copy(alpha = 0.6f), shape)
                .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenThumbnail(screen.thumbnail, colors)
        Text(
            text = screen.app,
            style = COUITheme.textStyles.bodyXS,
            color = colors.inputSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** 截图缩略图；没有截图时是一个图片图标。 */
@Composable
internal fun ScreenThumbnail(thumbnail: ImageBitmap?, colors: AssistantColors) {
    Box(
        modifier =
            Modifier.size(width = 22.dp, height = 32.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.controlSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "屏幕截图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                COUIIcons.Image,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.inputTertiary,
            )
        }
    }
}

/** 时间线标题行与节点槽位共用的几何尺寸（照 Eta），保证图标中心与首行文字中线对齐。 */
private val WorkRowHeight = 30.dp
private val WorkGutterWidth = 18.dp
private val WorkGutterGap = 8.dp

/** 一组思考和工具调用：折叠成一行（照 Eta 的 AgentWorkHeader，浮窗里默认不展开），点开是逐步的时间线。 */
@Composable
private fun WorkRow(item: WorkItem) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        WorkHeader(
            text = WorkSummary.text(item),
            working = item.running,
            icon = workIcon(item.steps.lastOrNull { it.running }),
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        AnimatedVisibility(visible = expanded, enter = FoldEnter, exit = FoldExit) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                item.steps.forEach { step -> WorkStepRow(step, onOpenSubagent = null) }
            }
        }
    }
}

/** 这一轮的状态：「正在分析任务」（图标主色、文字呼吸）、等用户处理、没有回答。 */
@Composable
private fun StatusRow(entry: ReplyEntry.Status) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        WorkHeader(
            text = entry.text,
            working = entry.active || entry.attention,
            icon = if (entry.attention) ZetaIcons.ShieldAlert else ZetaIcons.Lightbulb,
            expanded = null,
            onClick = null,
        )
    }
}

/** 工作过程那一行：左边图标槽，中间一句话，右边展开箭头（[expanded] 为 null 时没有箭头）。 */
@Composable
private fun WorkHeader(
    text: String,
    working: Boolean,
    icon: ImageVector,
    expanded: Boolean?,
    onClick: (() -> Unit)?,
) {
    val ink = rememberWorkInk()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = WorkRowHeight)
                .clip(RoundedCornerShape(8.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(WorkGutterWidth), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.5.dp),
                tint = if (working) COUITheme.colorScheme.primary else ink.icon,
            )
        }
        Spacer(modifier = Modifier.width(WorkGutterGap))
        PulsingText(
            text = text,
            active = working,
            color = ink.muted,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (expanded != null) {
            Chevron(
                expanded,
                modifier = Modifier.padding(horizontal = 4.dp),
                size = 13.dp,
                tint = COUITheme.colorScheme.labelSecondary.copy(alpha = 0.5f),
            )
        }
    }
}
