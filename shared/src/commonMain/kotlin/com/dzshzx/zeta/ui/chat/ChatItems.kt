package com.dzshzx.zeta.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.key
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
import com.dzshzx.zeta.core.ImageRef
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.ThoughtItem
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.core.WorkStep
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.FoldEnter
import com.dzshzx.zeta.ui.components.FoldExit
import com.dzshzx.zeta.ui.components.FoldResize
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.isDark
import com.dzshzx.zeta.ui.components.miuix.CopyIconButton
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.components.miuix.LongPressMenu
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.components.miuix.menuItem
import com.dzshzx.zeta.ui.components.pingPong
import com.dzshzx.zeta.ui.components.rememberIndicatorClock
import com.dzshzx.zeta.ui.components.success
import com.dzshzx.zeta.ui.markdown.EditorialLineHeightStyle
import com.dzshzx.zeta.ui.markdown.FindableText
import com.dzshzx.zeta.ui.markdown.LocalTextFinder
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import com.dzshzx.zeta.ui.markdown.StreamingMarkdown
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import com.dzshzx.zeta.ui.markdown.findOccurrences
import com.dzshzx.zeta.ui.markdown.staticMarkdownDocumentCache
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.MindMap
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val UserBubbleShape =
    RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 4.dp, bottomStart = 22.dp)
/** 折叠卡片（思考与工具调用的一组、回答里的图片组）的外形。 */
val WorkCardShape = RoundedCornerShape(16.dp)

/**
 * 长按菜单：复制，能改写时还有编辑和删除（照 Eta）。[editing] 时描一圈主题色边，表示正在编辑它。 菜单是 [LongPressMenu]（从气泡边上弹出，带长按震动），要在
 * Miuix 的 Scaffold 里才显示。另一个会话发来的消息（[UserItem.peer]）画成会话消息卡片（[PeerMessage]），编辑、删除、收藏都不挂。
 */
@Composable
fun UserBubble(
    item: UserItem,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** 收藏为常用指令。 */
    onSave: (() -> Unit)? = null,
) {
    val peer = item.peer
    if (peer != null) PeerMessage(item, peer, modifier)
    else OwnUserBubble(item, modifier, editing, onEdit, onDelete, onSave)
}

/** 会话消息收着时露出正文的前几行。 */
private const val PeerCollapsedLines = 4

/**
 * 另一个 Claude Code 会话发来的消息（会话互通）：和用户消息一样是 Claude 要回应的输入，所以靠右；但不是用户亲手写的，不用用户的气泡，用工作组那样的细边框卡片
 * （[WorkCardShape]）：顶上一行图标和「来自会话 名字」，下面是正文。正文排出来超过 [PeerCollapsedLines] 行时默认收着，点顶上那行展开；对话内查找命中它时展开。
 * 长按复制正文。
 */
@Composable
private fun PeerMessage(item: UserItem, peer: String, modifier: Modifier) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val text by rememberUpdatedState(item.text)
    val menu =
        remember(clipboard) {
            userMenuEntries(
                onCopy = { clipboard.setText(AnnotatedString(text)) },
                onSave = null,
                onEdit = null,
                onDelete = null,
            )
        }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    // 收着的那版排出来超过几行才算长、才能展开；展开后不再排收着的那版，所以记下来
    var long by remember(item.id, item.text) { mutableStateOf(false) }
    val query = LocalTextFinder.current?.query.orEmpty()
    val found = remember(item.text, query) { findOccurrences(item.text, query).isNotEmpty() }
    val open = expanded || found
    val ink = rememberWorkInk()
    Column(
        modifier =
            modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        LongPressMenu(entries = menu) {
            Column(
                modifier =
                    Modifier.widthIn(max = 316.dp)
                        .width(IntrinsicSize.Max)
                        .clip(WorkCardShape)
                        .border(0.5.dp, ink.border, WorkCardShape)
                        .animateContentSize(FoldResize),
            ) {
                PeerHeader(peer, ink, foldable = long, open = open) { expanded = !expanded }
                PeerBody(item, open, onOverflow = { long = true })
            }
        }
    }
}

/** 会话消息卡片的标题行：图标和「来自会话 名字」；正文长时末尾带展开箭头，点整行展开或收起。 */
@Composable
private fun PeerHeader(
    peer: String,
    ink: WorkInk,
    foldable: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 38.dp)
                .clickable(
                    enabled = foldable,
                    onClickLabel = if (open) Strings.chatCollapse() else Strings.chatExpand(),
                    onClick = onToggle,
                )
                .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.MindMap,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = ink.icon,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = Strings.chatFromSession(peer),
            style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp),
            color = ink.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (foldable) Chevron(open)
    }
}

/** 会话消息的正文：展开时是能被查找高亮的全文；收着时最多 [PeerCollapsedLines] 行，排不下时报 [onOverflow]。 */
@Composable
private fun PeerBody(item: UserItem, open: Boolean, onOverflow: () -> Unit) {
    val style =
        MiuixTheme.textStyles.body1.copy(
            lineHeight = 23.sp,
            lineHeightStyle = EditorialLineHeightStyle,
        )
    val color = MiuixTheme.colorScheme.onBackground
    val modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
    if (open) {
        FindableText(
            text = item.text,
            block = 0,
            itemId = item.id,
            style = style,
            color = color,
            modifier = modifier,
        )
    } else {
        Text(
            text = item.text,
            style = style,
            color = color,
            maxLines = PeerCollapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (it.hasVisualOverflow) onOverflow() },
            modifier = modifier,
        )
    }
}

/** 用户自己发的消息：图片、附件和文字气泡，长按菜单见 [UserBubble]。 */
@Composable
private fun OwnUserBubble(
    item: UserItem,
    modifier: Modifier,
    editing: Boolean,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onSave: (() -> Unit)?,
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
        // 图片文件和它的缩小图画成一张：画出了缩小图的文件不另画小标签（缩略图最多四张，其余的照样画小标签）
        val files = item.files.filter { f -> item.images.take(4).none { it.original == f } }
        if (item.images.isNotEmpty())
            UserImages(item, last = item.text.isEmpty() && files.isEmpty())
        files.forEach { path ->
            FileChip(
                name = AttachedPaths.label(path),
                folder = AttachedPaths.isFolder(path),
                modifier = Modifier.padding(bottom = 6.dp).widthIn(max = 280.dp),
            )
        }
        if (item.text.isNotEmpty()) {
            LongPressMenu(entries = menu) { UserTextBubble(item, editing) }
        }
    }
}

/** 用户消息带的图片：最多四张缩略图排一行，只有一张时画大些。 */
@Composable
private fun UserImages(item: UserItem, last: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.padding(
                bottom = if (last) 0.dp else 6.dp,
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
    val colors = MiuixTheme.colorScheme
    val dark = colors.isDark
    Column(
        modifier =
            Modifier.widthIn(max = 316.dp)
                .clip(UserBubbleShape)
                // 气泡是聊天页自己画的，Miuix 没有对应的配色：浅淡的蓝灰，和回答的白底分开
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
                MiuixTheme.textStyles.body1.copy(
                    lineHeight = 23.sp,
                    lineHeightStyle = EditorialLineHeightStyle,
                ),
            color = colors.onBackground,
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
        add(menuItem(Strings.chatCopy(), MiuixIcons.Copy, onCopy))
        if (onSave != null) add(menuItem(Strings.chatSaveAsPrompt(), MiuixIcons.Favorites, onSave))
        if (onEdit != null) add(menuItem(Strings.chatEdit(), MiuixIcons.Edit, onEdit))
    }
    val danger =
        if (onDelete != null) {
            listOf(menuItem(Strings.chatDelete(), MiuixIcons.Delete, onDelete, alert = true))
        } else {
            emptyList()
        }
    return listOf(DropdownEntry(items = main), DropdownEntry(items = danger))
}

@Composable
fun AnswerBlock(
    item: AnswerItem,
    cache: StreamingMarkdownStateCache,
    modifier: Modifier = Modifier,
    /** 把回答正文里要直接显示的图片分出来。 */
    imagesOf: (String) -> AnswerImages = { AnswerImages(it, emptyList()) },
    /** 复制键后面的操作（这一轮的最后一段回答才有：重新生成、删除、分支）。 */
    actions: (@Composable () -> Unit)? = null,
    /** 左右留白：聊天页 20dp，数字助理的浮窗照 Eta 是 16dp。 */
    inset: Dp = 20.dp,
    /** 写完后下面带复制键（和 [actions]）；中间说明不带。 */
    copyable: Boolean = true,
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
        AnswerImageGroups(parts.groups)
        if (copyable && !item.streaming && item.text.isNotBlank()) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CopyIconButton(
                    text = item.text,
                    size = 30.dp,
                    iconSize = 15.dp,
                    tint = MiuixTheme.colorScheme.onBackgroundVariant,
                )
                actions?.invoke()
            }
        }
    }
}

/** 中间说明：同回答一样排正文，不带复制键。 */
@Composable
fun ThoughtBlock(
    item: ThoughtItem,
    cache: StreamingMarkdownStateCache,
    modifier: Modifier = Modifier,
    imagesOf: (String) -> AnswerImages = { AnswerImages(it, emptyList()) },
    inset: Dp = 20.dp,
) {
    AnswerBlock(
        AnswerItem(item.id, item.text, item.streaming),
        cache,
        modifier,
        imagesOf = imagesOf,
        inset = inset,
        copyable = false,
    )
}

/** 回答正文下面的图片：单张照原样铺开，连着的几张是一个图片组。 */
@Composable
private fun AnswerImageGroups(groups: List<List<ImageRef>>) {
    // 按组的第一张图认组：流式输出时组里多了图，翻到的页和展开与否都留着
    groups.forEach { group ->
        key(group.first().key) {
            if (group.size == 1) ChatImage(group.first(), Modifier.padding(top = 10.dp))
            else ImageGallery(group, Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val clock = rememberIndicatorClock()
    val dot = MiuixTheme.colorScheme.onBackgroundVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                Modifier.size(6.dp)
                    .graphicsLayer {
                        alpha = pingPong(clock.value, 600, 0.3f, 1f, delayMs = index * 150)
                    }
                    .background(dot, CircleShape),
            )
        }
    }
}

@Composable
fun NoticeRow(item: NoticeItem, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    if (item.error || item.code == "noticeHookContext") {
        // 出错提示和 hook 原文都是又长又分行的块：靠左、可选中；出错的另加红色底
        val shape = RoundedCornerShape(14.dp)
        val boxed =
            if (item.error)
                Modifier.clip(shape)
                    .background(colors.error.copy(alpha = 0.08f), shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            else Modifier
        SelectionContainer(
            modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Text(
                text = item.said(),
                style =
                    if (item.error)
                        MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 19.sp)
                    else MiuixTheme.textStyles.footnote1,
                color = if (item.error) colors.error else colors.onBackgroundVariant,
                modifier = Modifier.fillMaxWidth().then(boxed),
            )
        }
    } else {
        Box(
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.said(),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onBackgroundVariant,
            )
        }
    }
}

/** 时间线里弱化的文字和图标的颜色：主文字色调淡。数字助理浮窗的工作过程那一行也用。 */
class WorkInk(val border: Color, val muted: Color, val icon: Color)

@Composable
fun rememberWorkInk(): WorkInk {
    val colors = MiuixTheme.colorScheme
    val dark = colors.isDark
    val ink = colors.onBackground
    return remember(ink, dark) {
        WorkInk(
            border = ink.copy(alpha = if (dark) 0.10f else 0.065f),
            muted = ink.copy(alpha = if (dark) 0.56f else 0.52f),
            icon = ink.copy(alpha = if (dark) 0.50f else 0.45f),
        )
    }
}

/**
 * 思考与工具调用一组（[WorkGroup]）的卡片：圆角细边框，最小 42dp 高的标题行（图标、标题、展开箭头），点标题行收起或展开，高度变化有动画。[content] 是标题行下面的部分，按
 * [expanded] 自己决定画什么。图片组（[ImageGallery]）也是这张卡片。
 */
@Composable
fun WorkCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    active: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ink = rememberWorkInk()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(WorkCardShape)
                .border(0.5.dp, ink.border, WorkCardShape)
                .animateContentSize(FoldResize),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 42.dp)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = iconTint,
            )
            Spacer(Modifier.width(10.dp))
            PulsingText(
                text = title,
                active = active,
                color = ink.muted,
                modifier = Modifier.weight(1f),
            )
            Chevron(expanded)
        }
        content()
    }
}

/**
 * 一组连续的思考与工具调用：默认折叠成一行摘要，点开是逐步的时间线（[expandedByDefault] 时一开始就展开）。 给了 [onOpenSubagent]
 * 时，发起子代理的那一步点了进子代理查看页（参数是那一步的 tool_use id）。
 */
@Composable
fun WorkGroup(
    item: WorkItem,
    modifier: Modifier = Modifier,
    expandedByDefault: Boolean = false,
    onOpenSubagent: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(expandedByDefault) }
    val colors = MiuixTheme.colorScheme
    val ink = rememberWorkInk()
    val running = item.steps.lastOrNull { it.running }
    val toolCount = item.steps.count { it is ToolStep }
    val runningTool = (running as? ToolStep)?.presentation()
    val headerText =
        when {
            runningTool != null -> Strings.chatWorkRunningStep(toolCount, runningTool.title)
            running is ThinkingStep -> Strings.chatThinking()
            else ->
                if (toolCount > 0) Strings.chatStepsDone(toolCount) else Strings.chatThinkingDone()
        }
    WorkCard(
        icon = workIcon(running),
        iconTint = if (running != null) colors.primary else ink.icon,
        title = headerText,
        active = running != null,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        // 折叠着也直接露出改了哪些文件
        if (!expanded) CollapsedFileChanges(item.steps, onShowAll = { expanded = true })
        AnimatedVisibility(visible = expanded, enter = FoldEnter, exit = FoldExit) {
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
fun workIcon(running: WorkStep?): ImageVector =
    when (running) {
        is ToolStep -> running.presentation().icon
        null -> MiuixIcons.SelectAll
        else -> ZetaIcons.Lightbulb
    }

/** 发起子代理的工具。Task 是 Agent 的旧名。 */
private val SubagentTools = setOf("Agent", "Task")

@Composable
fun WorkStepRow(step: WorkStep, onOpenSubagent: ((String) -> Unit)?) {
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
        AnimatedVisibility(expanded && detail.isNotBlank(), enter = FoldEnter, exit = FoldExit) {
            SelectionContainer { WorkStepDetail(step, detail) }
        }
        AnimatedVisibility(expanded && images.isNotEmpty(), enter = FoldEnter, exit = FoldExit) {
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
                toolDetail(presentation.detail, step.resultSaid()),
                step.status,
            )
        }
    }

private fun thinkingTitle(step: ThinkingStep): String =
    when {
        step.running -> Strings.chatThinking()
        step.seconds != null -> Strings.chatThinkingDoneIn(step.seconds)
        else -> Strings.chatThinkingDone()
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
    val colors = MiuixTheme.colorScheme
    val ink = rememberWorkInk()
    val expandable = summary.detail.isNotBlank() || hasImages
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 34.dp).clip(RoundedCornerShape(8.dp)).clickable(
                enabled = subagent != null || expandable,
                onClickLabel = if (subagent != null) Strings.chatViewSubagentSteps() else null,
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
            ToolStatus.Success -> StatusDot(MiuixTheme.colorScheme.success, Strings.chatDone())
            ToolStatus.Failed -> StatusDot(colors.error, Strings.chatFailed())
            else -> Unit
        }
        if (step is ToolStep) ChatPlatform.current.ToolStepAction(step)
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
                icon = MiuixIcons.Forward,
                contentDescription = Strings.chatViewSubagentSteps(),
                size = 26.dp,
                iconSize = 16.dp,
                modifier = Modifier.padding(start = 6.dp),
                tint = MiuixTheme.colorScheme.primary,
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
        val colors = MiuixTheme.colorScheme
        val dark = colors.isDark
        val shape = RoundedCornerShape(10.dp)
        Text(
            text = detail,
            style =
                MiuixTheme.textStyles.footnote1.copy(
                    fontFamily = MonoFont,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                ),
            color = colors.onBackground.copy(alpha = 0.78f),
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 25.dp, bottom = 8.dp)
                    .clip(shape)
                    .background(
                        colors.onBackground.copy(alpha = if (dark) 0.06f else 0.035f),
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
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

/** 展开箭头：收着朝右，展开时转到朝下（图标朝下，收着时转 -90 度）。默认是聊天页的大小和颜色，[modifier] 默认在左边留 6dp。 */
@Composable
fun Chevron(
    expanded: Boolean,
    modifier: Modifier = Modifier.padding(start = 6.dp),
    size: Dp = 17.dp,
    tint: Color = MiuixTheme.colorScheme.onBackgroundVariant.copy(alpha = 0.7f),
) {
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "chevron")
    Icon(
        imageVector = ZetaIcons.ChevronDown,
        contentDescription = if (expanded) Strings.chatCollapse() else Strings.chatExpand(),
        modifier = modifier.size(size).rotate(rotation),
        tint = tint,
    )
}

/** 进行中的那一行轻微呼吸，提示还在跑。 */
@Composable
fun PulsingText(
    text: String,
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val clock = if (active) rememberIndicatorClock() else null
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier.graphicsLayer {
                alpha = clock?.let { pingPong(it.value, 800, 0.45f, 1f) } ?: 1f
            },
    )
}
