package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.WorkStep
import com.dzshzx.zeta.phone.tools.BrowserTools
import com.dzshzx.zeta.ui.browser.BrowserStepButton
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.CopyIconButton
import com.dzshzx.zeta.ui.components.coui2.FlatIconButton
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.coui2.menuItem
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import com.dzshzx.zeta.ui.markdown.FindableText
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import com.dzshzx.zeta.ui.markdown.StreamingMarkdown
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import com.dzshzx.zeta.ui.markdown.staticMarkdownDocumentCache
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Delete
import io.github.suqi8.coui.kmp.icon.extended.Edit
import io.github.suqi8.coui.kmp.icon.extended.Favorites
import io.github.suqi8.coui.kmp.icon.extended.Forward
import io.github.suqi8.coui.kmp.icon.extended.SelectAll
import io.github.suqi8.coui.kmp.menu.OverlayContextMenu
import io.github.suqi8.coui.kmp.theme.COUITheme

private val UserBubbleShape =
    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp)
private val WorkCardShape = RoundedCornerShape(16.dp)

/**
 * 长按菜单：复制，能改写时还有编辑和删除（照 Eta）。[editing] 时描一圈主题色边，表示正在编辑它。 菜单是 COUI 的长按菜单（在按下的位置弹出，自带长按震动），要在 COUI 2
 * 的 Scaffold 里才显示。
 */
@Composable
internal fun UserBubble(
    item: UserItem,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** 收藏为常用指令。 */
    onSave: (() -> Unit)? = null,
) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val text by rememberUpdatedState(item.text)
    val edit by rememberUpdatedState(onEdit)
    val delete by rememberUpdatedState(onDelete)
    val save by rememberUpdatedState(onSave)
    val menu =
        remember(onEdit != null, onDelete != null, onSave != null, clipboard) {
            userMenuEntries(
                onCopy = { clipboard.setText(AnnotatedString(text)) },
                onSave = if (onSave != null) ({ save?.invoke() }) else null,
                onEdit = if (onEdit != null) ({ edit?.invoke() }) else null,
                onDelete = if (onDelete != null) ({ delete?.invoke() }) else null,
            )
        }
    Column(
        modifier =
            modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (item.images.isNotEmpty()) UserImages(item)
        item.files.forEach { path ->
            FileChip(
                name = AttachedPaths.label(path),
                folder = AttachedPaths.isFolder(path),
                modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 280.dp),
            )
        }
        if (item.text.isNotEmpty()) {
            OverlayContextMenu(entries = menu) { UserTextBubble(item, editing) }
        }
    }
}

/** 用户消息带的图片：最多四张缩略图排一行，只有一张时画大些。 */
@Composable
private fun UserImages(item: UserItem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.padding(
                bottom = if (item.text.isEmpty() && item.files.isEmpty()) 0.dp else 6.dp,
            ),
    ) {
        item.images.take(4).forEach { image ->
            ChatThumbnail(image, size = if (item.images.size == 1) 148.dp else 76.dp)
        }
    }
}

/** 用户消息的文字气泡；[editing] 时描一圈主题色边。 */
@Composable
private fun UserTextBubble(item: UserItem, editing: Boolean) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    Column(
        modifier =
            Modifier.widthIn(max = 316.dp)
                .clip(UserBubbleShape)
                // 气泡是聊天页自己画的，COUI 没有对应的配色：浅淡的蓝灰，和回答的白底分开
                .background(
                    if (dark) Color(0xFF202634) else Color(0xFFEEF2FA),
                    UserBubbleShape,
                )
                .then(
                    if (editing) Modifier.border(1.dp, colors.primary, UserBubbleShape)
                    else Modifier,
                )
                .padding(horizontal = 16.dp, vertical = 10.5.dp),
    ) {
        FindableText(
            text = item.text,
            block = 0,
            itemId = item.id,
            style =
                COUITheme.textStyles.bodyXL.copy(
                    lineHeight = 23.sp,
                    lineHeightStyle = EditorialLineHeightStyle,
                ),
            color = colors.labelPrimary,
        )
    }
}

/** 用户消息的长按菜单：复制、收藏、编辑一组，删除单独一组、标成红色。 */
private fun userMenuEntries(
    onCopy: () -> Unit,
    onSave: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
): List<DropdownEntry> {
    val main = buildList {
        add(menuItem("复制", COUIIcons.Copy, onCopy))
        if (onSave != null) add(menuItem("收藏为常用指令", COUIIcons.Favorites, onSave))
        if (onEdit != null) add(menuItem("编辑", COUIIcons.Edit, onEdit))
    }
    val danger =
        if (onDelete != null) {
            listOf(menuItem("删除", COUIIcons.Delete, onDelete, alert = true))
        } else {
            emptyList()
        }
    return listOf(DropdownEntry(items = main), DropdownEntry(items = danger))
}

@Composable
internal fun AnswerBlock(
    item: AnswerItem,
    cache: StreamingMarkdownStateCache,
    modifier: Modifier = Modifier,
    /** 把回答正文里要直接显示的图片分出来。 */
    imagesOf: (String) -> AnswerImages = { AnswerImages(it, emptyList()) },
    /** 复制键后面的操作（这一轮的最后一段回答才有：重新生成、删除、分支）。 */
    actions: (@Composable () -> Unit)? = null,
    /** 左右留白：聊天页 20dp，数字助理的浮窗照 Eta 是 16dp。 */
    inset: Dp = 20.dp,
) {
    val parts = remember(item.text) { imagesOf(item.text) }
    // 流式状态跟着消息走：结束后等逐字显现收尾，再换成可选中文本的静态排版。
    val streamingState =
        if (item.streaming || cache.states.containsKey(item.id)) cache.getOrCreate(item.id)
        else null
    var revealComplete by remember(item.id) { mutableStateOf(false) }
    Column(
        modifier =
            modifier.fillMaxWidth().padding(start = inset, end = inset, top = 4.dp, bottom = 6.dp),
    ) {
        when {
            item.text.isBlank() && item.streaming -> TypingIndicator(Modifier.padding(top = 4.dp))
            streamingState != null && (item.streaming || !revealComplete) -> {
                // 显示流式排版期间占着这份状态：回答结束时列表不删它，换成静态排版或滚出列表时才放开
                DisposableEffect(streamingState) {
                    streamingState.attachContent()
                    onDispose { streamingState.detachContent() }
                }
                StreamingMarkdown(
                    state = streamingState,
                    content = parts.text,
                    isStreaming = item.streaming,
                    tone = MarkdownTone.Answer,
                    onRevealCompleteChange = { complete ->
                        // 先把终态排版交给静态缓存，换过去的第一帧就是最终排版，不经过原文占位
                        if (complete) streamingState.snapshot?.let(staticMarkdownDocumentCache::put)
                        revealComplete = complete
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else ->
                SelectionContainer {
                    StaticMarkdown(
                        content = parts.text,
                        tone = MarkdownTone.Answer,
                        modifier = Modifier.fillMaxWidth(),
                        findItem = item.id,
                    )
                }
        }
        parts.images.forEach { image -> ChatImage(image, modifier = Modifier.padding(top = 10.dp)) }
        if (!item.streaming && item.text.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CopyIconButton(
                    text = item.text,
                    size = 30.dp,
                    iconSize = 15.dp,
                    tint = COUITheme.colorScheme.labelSecondary,
                )
                actions?.invoke()
            }
        }
    }
}

@Composable
internal fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val dot = COUITheme.colorScheme.labelSecondary
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val alpha by
                transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec =
                        infiniteRepeatable(
                            animation = tween(600, delayMillis = index * 150),
                            repeatMode = RepeatMode.Reverse,
                        ),
                    label = "alpha",
                )
            Box(Modifier.size(6.dp).graphicsLayer(alpha = alpha).background(dot, CircleShape))
        }
    }
}

@Composable
internal fun NoticeRow(item: NoticeItem, modifier: Modifier = Modifier) {
    val colors = COUITheme.colorScheme
    if (item.error) {
        val shape = RoundedCornerShape(14.dp)
        SelectionContainer(
            modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Text(
                text = item.text,
                style = COUITheme.textStyles.bodyM.copy(fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.error,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(colors.error.copy(alpha = 0.08f), shape)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.text,
                style = COUITheme.textStyles.bodyS,
                color = colors.labelSecondary,
            )
        }
    }
}

/** 时间线里弱化的文字和图标的颜色：主文字色调淡。数字助理浮窗的工作过程那一行也用。 */
internal class WorkInk(val border: Color, val muted: Color, val icon: Color)

@Composable
internal fun rememberWorkInk(): WorkInk {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    val ink = colors.labelPrimary
    return remember(ink, dark) {
        WorkInk(
            border = ink.copy(alpha = if (dark) 0.10f else 0.065f),
            muted = ink.copy(alpha = if (dark) 0.56f else 0.52f),
            icon = ink.copy(alpha = if (dark) 0.50f else 0.45f),
        )
    }
}

/**
 * 一组连续的思考与工具调用：默认折叠成一行摘要，点开是逐步的时间线（[expandedByDefault] 时一开始就展开）。 给了 [onOpenSubagent]
 * 时，发起子代理的那一步点了进子代理查看页（参数是那一步的 tool_use id）。
 */
@Composable
internal fun WorkGroup(
    item: WorkItem,
    modifier: Modifier = Modifier,
    expandedByDefault: Boolean = false,
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(expandedByDefault) }
    val colors = COUITheme.colorScheme
    val ink = rememberWorkInk()
    val running = item.steps.lastOrNull { it.running }
    val toolCount = item.steps.count { it is ToolStep }
    val runningTool = (running as? ToolStep)?.presentation()
    val headerText =
        when {
            runningTool != null -> "正在处理第 $toolCount 步 · " + runningTool.title
            running is ThinkingStep -> "正在思考"
            else -> if (toolCount > 0) "已完成 $toolCount 个步骤" else "思考已完成"
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clip(WorkCardShape)
                .border(0.5.dp, ink.border, WorkCardShape)
                .animateContentSize(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = workIcon(running),
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (running != null) colors.primary else ink.icon,
            )
            Spacer(Modifier.width(10.dp))
            PulsingText(
                text = headerText,
                active = running != null,
                color = ink.muted,
                modifier = Modifier.weight(1f),
            )
            Chevron(expanded)
        }
        // 折叠着也直接露出改了哪些文件
        if (!expanded) CollapsedFileChanges(item.steps, onShowAll = { expanded = true })
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier =
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .height(0.5.dp)
                        .background(ink.border),
                )
                item.steps.forEach { step -> WorkStepRow(step, onOpenSubagent) }
            }
        }
    }
}

/** 工作过程表头的图标：[running] 是在跑的那一步，工具用它自己的图标，思考是灯泡；都做完了（null）是对勾。数字助理浮窗也用。 */
internal fun workIcon(running: WorkStep?): ImageVector =
    when (running) {
        is ToolStep -> running.presentation().icon
        null -> COUIIcons.SelectAll
        else -> ZetaIcons.Lightbulb
    }

/** 发起子代理的工具。Task 是 Agent 的旧名。 */
private val SubagentTools = setOf("Agent", "Task")

@Composable
internal fun WorkStepRow(step: WorkStep, onOpenSubagent: ((String) -> Unit)?) {
    // 改文件的一步直接画成红绿对比
    val change = (step as? ToolStep)?.let { rememberFileChange(it) }
    if (change != null) return FileEditStepRow(step, change)
    // 发起子代理的一步：点了看它在做什么，不在这里展开
    val subagent = if (step is ToolStep && step.name in SubagentTools) onOpenSubagent else null
    var expanded by rememberSaveable(step.id) { mutableStateOf(false) }
    val summary = stepSummary(step)
    val detail = summary.detail
    val images = (step as? ToolStep)?.images.orEmpty()
    Column(modifier = Modifier.fillMaxWidth()) {
        WorkStepHeader(step, summary, images.isNotEmpty(), expanded, subagent) {
            expanded = !expanded
        }
        AnimatedVisibility(visible = expanded && detail.isNotBlank()) {
            SelectionContainer { WorkStepDetail(step, detail) }
        }
        AnimatedVisibility(visible = expanded && images.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 25.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.forEach { image -> ChatImage(image) }
            }
        }
    }
}

/** 一步在时间线里的样子：图标、标题、展开后的详情，工具调用还有结果状态（思考没有）。 */
private class StepSummary(
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val status: ToolStatus?,
)

private fun stepSummary(step: WorkStep): StepSummary =
    when (step) {
        is ThinkingStep -> StepSummary(ZetaIcons.Lightbulb, thinkingTitle(step), step.text, null)
        is ToolStep -> {
            val presentation = step.presentation()
            StepSummary(
                presentation.icon,
                presentation.title,
                toolDetail(presentation.detail, step.result),
                step.status,
            )
        }
    }

private fun thinkingTitle(step: ThinkingStep): String =
    when {
        step.running -> "正在思考"
        step.seconds != null -> "思考已完成 · 用时 ${step.seconds} 秒"
        else -> "思考已完成"
    }

/** 工具调用展开后的详情：调用的说明，空一行接结果（太长的截到 3000 字）。 */
private fun toolDetail(presented: String, result: String?): String = buildString {
    append(presented.trim())
    result
        ?.takeIf { it.isNotBlank() }
        ?.let {
            if (isNotEmpty()) append("\n\n")
            append(
                it.trim().let { text ->
                    if (text.length > 3000) text.take(3000) + "\n…" else text
                },
            )
        }
}

/** 一步的标题行：点了展开详情，发起子代理的一步点了进子代理查看页（[subagent]）。 */
@Composable
private fun WorkStepHeader(
    step: WorkStep,
    summary: StepSummary,
    hasImages: Boolean,
    expanded: Boolean,
    subagent: ((String) -> Unit)?,
    onToggle: () -> Unit,
) {
    val colors = COUITheme.colorScheme
    val ink = rememberWorkInk()
    val expandable = summary.detail.isNotBlank() || hasImages
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(8.dp)).clickable(
                enabled = subagent != null || expandable,
                onClickLabel = if (subagent != null) "查看子代理的步骤" else null,
            ) {
                if (subagent != null) subagent(step.id) else onToggle()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = summary.icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (step.running) colors.primary else ink.icon,
        )
        Spacer(Modifier.width(10.dp))
        PulsingText(
            text = summary.title,
            active = step.running,
            color = ink.muted,
            modifier = Modifier.weight(1f),
        )
        when (summary.status) {
            ToolStatus.Success -> StatusDot(COUITheme.colorScheme.success, "已完成")
            ToolStatus.Failed -> StatusDot(colors.error, "失败")
            else -> Unit
        }
        if (step is ToolStep && step.name == "mcp__phone__" + BrowserTools.NAME) BrowserStepButton()
        WorkStepOpener(step, subagent, expandable, expanded)
    }
}

/** 标题行末尾：进子代理查看页的钮（和“在浏览器里查看”一个样子），或原地展开的折角。 */
@Composable
private fun WorkStepOpener(
    step: WorkStep,
    subagent: ((String) -> Unit)?,
    expandable: Boolean,
    expanded: Boolean,
) {
    when {
        subagent != null ->
            FlatIconButton(
                icon = COUIIcons.Forward,
                contentDescription = "查看子代理的步骤",
                size = 26.dp,
                iconSize = 16.dp,
                modifier = Modifier.padding(start = 6.dp),
                tint = COUITheme.colorScheme.primary,
            ) {
                subagent(step.id)
            }
        expandable -> Chevron(expanded)
    }
}

/** 一步展开后的详情：思考按 Markdown 排，工具调用是等宽字的灰底块，太宽时横向滚动。 */
@Composable
private fun WorkStepDetail(step: WorkStep, detail: String) {
    if (step is ThinkingStep) {
        StaticMarkdown(
            content = detail,
            tone = MarkdownTone.Thinking,
            modifier = Modifier.fillMaxWidth().padding(start = 25.dp, bottom = 8.dp),
        )
    } else {
        val colors = COUITheme.colorScheme
        val dark = colors.isDark
        val shape = RoundedCornerShape(10.dp)
        Text(
            text = detail,
            style =
                COUITheme.textStyles.bodyS.copy(
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
            color = colors.labelPrimary.copy(alpha = 0.78f),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 25.dp, bottom = 8.dp)
                    .clip(shape)
                    .background(
                        colors.labelPrimary.copy(alpha = if (dark) 0.06f else 0.035f),
                        shape,
                    )
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = COUITheme.textStyles.bodyS.copy(fontSize = 12.sp),
            color = COUITheme.colorScheme.labelPrimary.copy(alpha = 0.7f),
        )
    }
}

/** 展开箭头：收着朝右，展开时转到朝下（图标朝下，收着时转 -90 度）。默认是聊天页的大小和颜色，[modifier] 默认在左边留 6dp。 */
@Composable
internal fun Chevron(
    expanded: Boolean,
    modifier: Modifier = Modifier.padding(start = 6.dp),
    size: Dp = 17.dp,
    tint: Color = COUITheme.colorScheme.labelSecondary.copy(alpha = 0.7f),
) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Icon(
        imageVector = ZetaIcons.ChevronDown,
        contentDescription = if (expanded) "收起" else "展开",
        modifier = modifier.size(size).rotate(rotation),
        tint = tint,
    )
}

/** 进行中的那一行轻微呼吸，提示还在跑。 */
@Composable
internal fun PulsingText(
    text: String,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha =
        if (active) {
            val transition = rememberInfiniteTransition(label = "pulse")
            transition
                .animateFloat(
                    0.45f,
                    1f,
                    infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "pulse-alpha",
                )
                .value
        } else {
            1f
        }
    Text(
        text = text,
        style = COUITheme.textStyles.bodyM.copy(fontSize = 13.5.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
    )
}
