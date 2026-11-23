package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.conv.ConvSubagent
import com.dzshzx.zeta.core.conv.SubagentState
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.CardListSegment
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.whileStarted
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import com.dzshzx.zeta.ui.tasks.TaskLabels
import com.dzshzx.zeta.ui.tasks.taskStateColor
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.preference.PreferenceCategory
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 子代理查看页：聊天里点开发起子代理的那一步、后台任务面板里点子代理进来。[conversationKey] 是所在对话的 key， [toolUseId]
 * 是发起子代理的那一步。页面开着时核心增量读它的记录、推过来（conv.subagent）；它的步骤里又派了子代理时，可以接着点进去。
 */
@Composable
fun SubagentScreen(
    hub: ConversationHub,
    conversationKey: String,
    toolUseId: String,
    onBack: () -> Unit,
) {
    val conversation = hub.liveConversations.firstOrNull { it.key == conversationKey }
    if (conversation == null) {
        CouiPage(title = Strings.chatSubagent(), onBack = onBack) {
            item(key = "gone") {
                PreferenceGroup {
                    item { BasicComponent(title = Strings.chatChatClosed(), enabled = false) }
                }
            }
        }
        return
    }
    DisposableEffect(conversation, toolUseId) {
        conversation.watchSubagent(toolUseId, true)
        onDispose { conversation.watchSubagent(toolUseId, false) }
    }
    var nested by remember { mutableStateOf<String?>(null) }
    val home = hub.home(conversation.backend)
    val imagesOf =
        remember(conversation.cwd, home) {
            { text: String -> answerImages(text, conversation.cwd, home) }
        }
    CompositionLocalProvider(LocalBackend provides conversation.backend) {
        SubagentPage(
            conversation.subagent(toolUseId) ?: ConvSubagent(toolUseId),
            onBack,
            imagesOf,
            onOpenSubagent = { nested = it },
        )
    }
    // 它自己又派的子代理：记录在同一个会话的目录里，照样看
    SlideInPage(nested, onBack = { nested = null }) {
        SubagentScreen(hub, conversationKey, it, onBack = { nested = null })
    }
}

/**
 * 页面本身：概要、交给它的指令、它的步骤（和聊天里一样的条目），运行中最后一行在动。记录还没读到时，说明、类型和指令由核心先用发起它的 Agent 调用的参数填上（conv.subagent 的
 * meta、prompt）。
 */
@Composable
fun SubagentPage(
    log: ConvSubagent,
    onBack: () -> Unit,
    imagesOf: (String) -> AnswerImages = { AnswerImages(it, emptyList()) },
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    val prompt = log.prompt.ifEmpty { null }
    val listState = rememberLazyListState()
    val markdownCache = remember { StreamingMarkdownStateCache() }
    // 一开始停在概要上；拉到底以后跟着新步骤走，往上翻就不跟了
    rememberFollowBottom(listState, following = false)
    // 子代理还在跑时步骤随时变；界面看不见时这一页不跟着重组、测量
    val items = whileStarted { log.items }
    CouiPage(title = Strings.chatSubagent(), onBack = onBack, listState = listState) {
        item(key = "info") { SubagentSummary(log) }
        prompt?.let { text -> item(key = "prompt") { PromptBlock(text) } }
        item(key = "steps-title") { PreferenceCategory(title = Strings.chatSteps()) }
        if (log.skipped > 0)
            item(key = "skipped") {
                NoticeRow(
                    NoticeItem("skipped", Strings.chatEarlierNotLoaded(log.skipped), error = false),
                )
            }
        val tail = log.state == SubagentState.Running
        val count = items.size + if (tail) 1 else 0
        if (count == 0) {
            item(key = "empty") { PagerFooterPreference(summary = noStepsText(log)) }
        }
        itemsIndexed(
            items,
            key = { _, item -> item.id },
            contentType = { _, item -> item::class },
        ) { index, item ->
            CardListSegment(index, count, dividerStart = null) {
                SubagentStep(index, count, item, markdownCache, imagesOf, onOpenSubagent)
            }
        }
        if (tail) {
            item(key = "working") {
                CardListSegment(items.size, count, dividerStart = null) { WorkingRow() }
            }
        }
    }
}

/** 概要：说明、状态、子代理类型、模型和工具调用次数（知道的才列）。 */
@Composable
private fun SubagentSummary(log: ConvSubagent) {
    val meta = log.meta
    PreferenceGroup {
        item(key = "title") {
            BasicComponent(
                title = meta?.description?.takeIf { it.isNotBlank() } ?: Strings.chatSubagent(),
            )
        }
        item(key = "state") {
            BasicComponent(
                title = Strings.chatStatus(),
                endActions = {
                    val (text, color) = stateLabel(log.state)
                    PreferenceValue(text, color = color)
                },
            )
        }
        meta?.agentType?.let { value ->
            item(key = "type") {
                BasicComponent(
                    title = Strings.chatSubagentType(),
                    endActions = { PreferenceValue(value) },
                )
            }
        }
        meta?.model?.let { value ->
            item(key = "model") {
                BasicComponent(title = Strings.chatModel(), endActions = { PreferenceValue(value) })
            }
        }
        if (log.toolUses > 0)
            item(key = "tools") {
                BasicComponent(
                    title = Strings.chatToolCalls(),
                    endActions = { PreferenceValue(Strings.chatTimes(log.toolUses)) },
                )
            }
    }
}

/**
 * 状态的说法和颜色：核心给的结论（conv.subagent 的 state）换成文案。在跑和结局照后台任务的说法和颜色（[TaskLabels.state]、
 * [taskStateColor]），别的不另上色；和 Windows 版一样。
 */
@Composable
private fun stateLabel(state: SubagentState): Pair<String, Color> =
    when (state) {
        SubagentState.Running -> taskLabel(TaskState.Running)
        SubagentState.Completed -> taskLabel(TaskState.Completed)
        SubagentState.Failed -> taskLabel(TaskState.Failed)
        SubagentState.Stopped -> taskLabel(TaskState.Stopped)
        SubagentState.Loading -> Strings.chatReading() to Color.Unspecified
        SubagentState.ReadFailed -> Strings.chatReadFailed() to Color.Unspecified
        SubagentState.Missing -> Strings.chatNoRecord() to Color.Unspecified
        SubagentState.Unfinished -> Strings.chatUnfinished() to Color.Unspecified
    }

@Composable
private fun taskLabel(state: TaskState) = TaskLabels.state(state) to taskStateColor(state)

/** 一步都还没有、又不在跑时的说明：读失败时是原因。 */
private fun noStepsText(log: ConvSubagent): String =
    when (log.state) {
        SubagentState.Missing -> Strings.chatSubagentMissing()
        SubagentState.ReadFailed -> log.failure ?: Strings.chatReadFailed()
        SubagentState.Loading -> Strings.chatReadingEllipsis()
        else -> Strings.chatNoStepsYet()
    }

/** 步骤卡片里的一条（和聊天里的条目一样）；卡片的头尾和里面的条目留开一点。 */
@Composable
private fun SubagentStep(
    index: Int,
    count: Int,
    item: ChatItem,
    markdownCache: StreamingMarkdownStateCache,
    imagesOf: (String) -> AnswerImages,
    onOpenSubagent: ((String) -> Unit)?,
) {
    if (index == 0) Spacer(Modifier.height(10.dp))
    when (item) {
        is AnswerItem -> AnswerBlock(item, markdownCache, imagesOf = imagesOf)
        is ThoughtItem -> ThoughtBlock(item, markdownCache, imagesOf = imagesOf)
        is WorkItem ->
            WorkGroup(
                item,
                modifier = Modifier.padding(vertical = 4.dp),
                expandedByDefault = true,
                onOpenSubagent = onOpenSubagent,
            )
        // 之后续派给它的消息
        is UserItem -> UserBubble(item)
        is NoticeItem -> NoticeRow(item)
    }
    if (index == count - 1) Spacer(Modifier.height(10.dp))
}

/** 运行中步骤卡片的最后一行。 */
@Composable
private fun WorkingRow() {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingIndicator()
        Spacer(Modifier.width(10.dp))
        Text(
            text = Strings.chatWorking(),
            style = COUITheme.textStyles.bodyS,
            color = COUITheme.colorScheme.labelSecondary,
        )
    }
}

/** 交给它的指令：长的先只露几行，点了展开。 */
@Composable
private fun PromptBlock(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val long = text.length > 240 || text.count { it == '\n' } > 4
    PreferenceGroup(title = Strings.chatInstructions()) {
        item { SelectionContainer { PromptText(text, long, expanded) { expanded = !expanded } } }
    }
}

/** 指令正文；[long] 时收起只露 5 行，点了展开、收起。 */
@Composable
private fun PromptText(text: String, long: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyM.copy(fontSize = 14.sp, lineHeight = 21.sp),
        color = COUITheme.colorScheme.labelPrimary,
        maxLines = if (expanded || !long) Int.MAX_VALUE else 5,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier.fillMaxWidth()
                .then(
                    if (long)
                        Modifier.clickable(
                            onClickLabel =
                                if (expanded) Strings.chatCollapse() else Strings.chatExpand(),
                            onClick = onToggle,
                        )
                    else Modifier,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
