package com.dzshzx.zeta.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SuggestionChip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 浮窗顶上那一条：这次带上了哪个应用的屏幕。 */
@Immutable
internal data class ScreenChip(
    val app: String,
    val thumbnail: ImageBitmap?,
    /** 还在等系统交来截图、应用内容。 */
    val waiting: Boolean,
    /** 用户把它去掉了：发出去的只有问题。 */
    val removed: Boolean,
    /** 没有截图（ASSIST intent 唤起，或者系统没给）。 */
    val textOnly: Boolean,
    /** 已经发出去了：只说带了什么，不能再改。 */
    val sent: Boolean = false,
)

/** 发出去以后浮窗上显示的回答。 */
@Immutable
internal data class ReplyView(
    /** 回答的正文（这一轮里 Claude 说的话，按顺序接起来）；还没有时为空。 */
    val text: String,
    /** 一句状态（“正在看屏幕”“等你确认”）；回答出来了、也不用操心时为 null。 */
    val status: String?,
    val streaming: Boolean,
    /** 要用户去 Zeta 里处理（确认工具调用）。 */
    val attention: Boolean,
    val error: String?,
)

/** 从对话的条目里取浮窗要显示的回答。纯函数，单元测试覆盖。 */
internal object AssistantReplies {
    fun view(items: List<ChatItem>, busy: Boolean, pendingTool: String?): ReplyView {
        val tail = items.takeLastWhile { it !is UserItem }
        val answers = tail.filterIsInstance<AnswerItem>().filter { it.text.isNotBlank() }
        val text = answers.joinToString("\n\n") { it.text.trim() }
        val error = tail.filterIsInstance<NoticeItem>().lastOrNull { it.error }?.text
        val working = tail.any { it is WorkItem }
        val status =
            when {
                pendingTool != null -> "等你确认：Claude 想用 $pendingTool，到 Zeta 里处理"
                busy && text.isEmpty() -> if (working) "Claude 正在处理…" else "Claude 正在看…"
                busy && tail.lastOrNull() is WorkItem -> "Claude 正在处理…"
                !busy && text.isEmpty() && error == null -> "这一轮没有回答"
                else -> null
            }
        return ReplyView(
            text = text,
            status = status,
            streaming = busy && answers.lastOrNull()?.streaming == true,
            attention = pendingTool != null,
            error = error,
        )
    }
}

/**
 * 数字助理的浮窗（照 Eta 的助理浮窗，外观按 COUI 2）：屏幕底部一张浮起的面板。 上面一条是带上的屏幕（截图缩略图和应用名，可以去掉），中间是回答（发出去以后），
 * 下面是建议问题和输入框。拖动把手往上推、或点「在 Zeta 中继续」打开 Zeta 接着这个对话，往下拖、点外面关掉。
 */
@Composable
internal fun AssistantScreen(
    screen: ScreenChip?,
    reply: ReplyView?,
    draft: TextFieldState,
    suggestions: List<String>,
    sending: Boolean,
    problem: String?,
    visible: Boolean,
    onSend: (String) -> Unit,
    onToggleScreen: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    /** 进场后把焦点给输入框、弹出键盘（只在主屏上：测试用的虚拟屏上弹键盘会弹到主屏去）。 */
    autoFocus: Boolean = false,
) {
    val colors = COUITheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // 进场：从底下滑上来；拖动时跟手，松手按距离决定打开 Zeta、关掉还是弹回去
    val drag = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        enter.animateTo(if (visible) 1f else 0f, tween(if (visible) 320 else 200))
    }
    val openDistance = with(density) { 56.dp.toPx() }
    val closeDistance = with(density) { 96.dp.toPx() }
    val dragState = rememberDraggableState { delta ->
        val next = dragTarget(drag.value, delta)
        scope.launch { drag.snapTo(next) }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelTravel = with(density) { maxHeight.toPx() } * 0.5f
        Scrim(enter, onDismiss)
        Column(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                    .widthIn(max = 560.dp)
                    .offset {
                        IntOffset(0, (drag.value + (1f - enter.value) * panelTravel).roundToInt())
                    }
                    .graphicsLayer { alpha = (enter.value * 1.4f).coerceAtMost(1f) },
        ) {
            val shape = remember { SquircleShape(COUIRoundCorner.XXXXL) }
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .dropShadow(
                            shape = shape,
                            shadow =
                                Shadow(
                                    radius = 24.dp,
                                    color = Color.Black,
                                    alpha = if (colors.isDark) 0.4f else 0.12f,
                                ),
                        )
                        .clip(shape)
                        .background(colors.surface)
                        // 面板上的空白处点了不算点外面
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = {
                                settleDrag(drag, openDistance, closeDistance, onOpen, onDismiss)
                            },
                        )
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            ) {
                PanelBody(
                    screen = screen,
                    reply = reply,
                    draft = draft,
                    suggestions = suggestions,
                    sending = sending,
                    problem = problem,
                    autoFocus = autoFocus && visible,
                    onSend = onSend,
                    onToggleScreen = onToggleScreen,
                    onOpen = onOpen,
                )
            }
        }
    }
}

/** 拖动面板时这一下之后的位置：往上拖有阻尼，往下跟手。 */
private fun dragTarget(current: Float, delta: Float): Float =
    current + if (current + delta < 0) delta * 0.45f else delta

/** 松手时：往上推够了打开 Zeta，往下拉够了关掉；都弹回原位。 */
private suspend fun settleDrag(
    drag: Animatable<Float, AnimationVector1D>,
    openDistance: Float,
    closeDistance: Float,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        drag.value < -openDistance -> onOpen()
        drag.value > closeDistance -> onDismiss()
    }
    drag.animateTo(0f, tween(220))
}

/** 面板后面的遮罩：跟着进场淡入，点一下关掉。 */
@Composable
private fun Scrim(enter: Animatable<Float, AnimationVector1D>, onDismiss: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = enter.value }
                .background(COUITheme.colorScheme.mask)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
    )
}

/** 面板里从上到下的内容：把手、带上的屏幕、出错说明、回答、建议问题、输入栏。 */
@Composable
private fun ColumnScope.PanelBody(
    screen: ScreenChip?,
    reply: ReplyView?,
    draft: TextFieldState,
    suggestions: List<String>,
    sending: Boolean,
    problem: String?,
    autoFocus: Boolean,
    onSend: (String) -> Unit,
    onToggleScreen: () -> Unit,
    onOpen: () -> Unit,
) {
    DragHandle(onOpen)
    if (screen != null) ScreenRow(screen, onToggleScreen)
    problem?.let {
        Text(
            text = it,
            style = COUITheme.textStyles.bodyS,
            color = COUITheme.colorScheme.error,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    if (reply != null) ReplyArea(reply, onOpen)
    Suggestions(
        visible = reply == null && suggestions.isNotEmpty(),
        suggestions = suggestions,
        sending = sending,
        onSend = onSend,
    )
    InputRow(draft, sending, followUp = reply != null, autoFocus = autoFocus, onSend = onSend)
}

/** 还没发出去时的建议问题，一行横着滑。 */
@Composable
private fun ColumnScope.Suggestions(
    visible: Boolean,
    suggestions: List<String>,
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + expandVertically(),
        exit = fadeOut(tween(120)) + shrinkVertically(),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                SuggestionChip(
                    onClick = { if (!sending) onSend(suggestion) },
                    label = suggestion,
                    enabled = !sending,
                )
            }
        }
    }
}

/** COUI 面板的拖动把手（36×4dp）。点一下等于往上推：打开 Zeta。 */
@Composable
private fun DragHandle(onOpen: () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(20.dp)
                .semantics { contentDescription = "在 Zeta 中继续" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onOpen,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(COUITheme.colorScheme.dragHandle),
        )
    }
}

@Composable
private fun ScreenRow(screen: ScreenChip, onToggle: () -> Unit) {
    val colors = COUITheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenThumbnail(screen)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = screen.app,
                style = COUITheme.textStyles.buttonM,
                color = if (screen.removed) colors.labelTertiary else colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = screenCaption(screen),
                style = COUITheme.textStyles.bodyXS,
                color = colors.labelSecondary,
                maxLines = 1,
            )
        }
        ScreenToggle(screen, onToggle)
    }
}

/** 带上的屏幕那一行下面的小字：带了（或会带）什么。 */
private fun screenCaption(screen: ScreenChip): String =
    when {
        screen.sent && screen.removed -> "没带屏幕内容"
        screen.sent && screen.textOnly -> "已带上应用信息"
        screen.sent -> "已带上截屏和屏幕上的文字"
        screen.removed -> "不带屏幕内容，只发问题"
        screen.waiting -> "正在读取屏幕…"
        screen.textOnly -> "会带上应用信息"
        else -> "会带上截屏和屏幕上的文字"
    }

/** 截图缩略图；没有截图或去掉了时是一个图片图标。 */
@Composable
private fun ScreenThumbnail(screen: ScreenChip) {
    val colors = COUITheme.colorScheme
    val thumbShape = RoundedCornerShape(COUIRoundCorner.S)
    Box(
        modifier =
            Modifier.size(width = 30.dp, height = 44.dp)
                .clip(thumbShape)
                .background(colors.fillThin),
        contentAlignment = Alignment.Center,
    ) {
        if (screen.thumbnail != null && !screen.removed) {
            Image(
                bitmap = screen.thumbnail,
                contentDescription = "屏幕截图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                COUIIcons.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = colors.labelTertiary,
            )
        }
    }
}

/** 那一行最右边：去掉了是「带上」，带着是一个叉；发出去以后什么都没有。 */
@Composable
private fun ScreenToggle(screen: ScreenChip, onToggle: () -> Unit) {
    val colors = COUITheme.colorScheme
    if (screen.sent) {
        Spacer(Modifier.width(0.dp))
    } else if (screen.removed) {
        Text(
            text = "带上",
            style = COUITheme.textStyles.buttonS,
            color = colors.primary,
            modifier =
                Modifier.clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    } else {
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "不带屏幕内容" }
                    .clickable(role = Role.Button, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(colors.fillThin),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    COUIIcons.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun ReplyArea(reply: ReplyView, onOpen: () -> Unit) {
    val colors = COUITheme.colorScheme
    val scroll = rememberScrollState()
    // 流式回答跟到底
    LaunchedEffect(reply.text.length) { if (reply.streaming) scroll.scrollTo(scroll.maxValue) }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(scroll)) {
            ReplyContent(reply)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "在 Zeta 中继续",
                style = COUITheme.textStyles.buttonS,
                color = colors.primary,
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpen)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** 回答区里滚动的那部分：状态、回答正文、出错说明。 */
@Composable
private fun ReplyContent(reply: ReplyView) {
    val colors = COUITheme.colorScheme
    reply.status?.let {
        Text(
            text = it,
            style = COUITheme.textStyles.bodyS,
            color = if (reply.attention) colors.primary else colors.labelSecondary,
            modifier = Modifier.padding(bottom = if (reply.text.isEmpty()) 0.dp else 8.dp),
        )
    }
    if (reply.text.isNotEmpty()) {
        // 流式时直接显示文字（每个片段都重新排版 Markdown 会闪），完了再按 Markdown 排
        if (reply.streaming) {
            Text(text = reply.text, style = COUITheme.textStyles.bodyL, color = colors.labelPrimary)
        } else {
            StaticMarkdown(content = reply.text, tone = MarkdownTone.Answer)
        }
    }
    reply.error?.let {
        Text(
            text = it,
            style = COUITheme.textStyles.bodyS,
            color = colors.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun InputRow(
    draft: TextFieldState,
    sending: Boolean,
    followUp: Boolean,
    autoFocus: Boolean,
    onSend: (String) -> Unit,
) {
    val colors = COUITheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        // 等面板滑上来再弹键盘，两段动画不挤在一起
        delay(320)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val canSend = !sending && draft.text.isNotBlank()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DraftField(draft, sending, followUp, focus)
        Spacer(Modifier.width(8.dp))
        SendButton(canSend) {
            val text = draft.text.toString()
            draft.clearText()
            onSend(text)
        }
    }
}

/** 输入框：空着时显示提示（发出去以后是「接着问」），最多长到 4 行。 */
@Composable
private fun RowScope.DraftField(
    draft: TextFieldState,
    sending: Boolean,
    followUp: Boolean,
    focus: FocusRequester,
) {
    val colors = COUITheme.colorScheme
    val style = COUITheme.textStyles.bodyL.copy(color = colors.labelPrimary)
    Box(
        modifier =
            Modifier.weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(colors.fillThin)
                .defaultMinSize(minHeight = 44.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (draft.text.isEmpty()) {
            Text(
                text = if (followUp) "接着问" else "问问 Claude",
                style = style,
                color = colors.hintNeutral,
                maxLines = 1,
            )
        }
        BasicTextField(
            state = draft,
            modifier =
                Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    contentDescription = "输入问题"
                },
            enabled = !sending,
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
        )
    }
}

/** 发送按钮：能发时是主色，不能发时变灰。 */
@Composable
private fun SendButton(canSend: Boolean, onClick: () -> Unit) {
    val colors = COUITheme.colorScheme
    val sendColor by
        animateColorAsState(
            if (canSend) colors.primary else colors.container8,
            tween(160),
            label = "assistant-send",
        )
    RoundButton(description = "发送", color = sendColor, enabled = canSend, onClick = onClick) {
        Icon(
            ZetaIcons.ArrowUp,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (canSend) colors.onPrimary else colors.labelTertiary,
        )
    }
}

@Composable
private fun RoundButton(
    description: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(44.dp)
                .clip(CircleShape)
                .background(color)
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
