package com.dzshzx.zeta.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.markdown.MarkdownTone
import com.dzshzx.zeta.ui.markdown.StaticMarkdown
import io.github.suqi8.coui.kmp.basic.ChipDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SuggestionChip
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.Image
import io.github.suqi8.coui.kmp.squircle.LocalSquircleEnabled
import io.github.suqi8.coui.kmp.squircle.addSquircleRect
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.Colors
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
    /** 这一轮还在进行（正在看、正在处理、在出回答）：输入栏右边是停止键，输入框暂时不能输入。 */
    val busy: Boolean = false,
)

/** 从对话的条目里取浮窗要显示的回答。纯函数，单元测试覆盖。 */
internal object AssistantReplies {
    fun view(items: List<ChatItem>, busy: Boolean, waitingFor: String?): ReplyView {
        val tail = items.takeLastWhile { it !is UserItem }
        val answers = tail.filterIsInstance<AnswerItem>().filter { it.text.isNotBlank() }
        val text = answers.joinToString("\n\n") { it.text.trim() }
        val error = tail.filterIsInstance<NoticeItem>().lastOrNull { it.error }?.text
        val working = tail.any { it is WorkItem }
        val status =
            when {
                waitingFor != null -> "等你处理：$waitingFor，到 Zeta 里处理"
                busy && text.isEmpty() -> if (working) "Claude 正在处理…" else "Claude 正在看…"
                busy && tail.lastOrNull() is WorkItem -> "Claude 正在处理…"
                !busy && text.isEmpty() && error == null -> "这一轮没有回答"
                else -> null
            }
        return ReplyView(
            text = text,
            status = status,
            streaming = busy && answers.lastOrNull()?.streaming == true,
            attention = waitingFor != null,
            error = error,
            busy = busy,
        )
    }
}

/** COUI 的进场曲线：出场、建议问题依次出来都用它。 */
private val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 往上推多远松手打开 Zeta、往下拉多远松手关掉。 */
private val OpenDistance = 56.dp
private val CloseDistance = 96.dp

/** 回答面板顶上离状态栏至少留出的高度：再高就在回答区里滚动（照 Eta 的半屏面板）。 */
private val SheetTopGap = 88.dp

/** 没有回答时页面只轻轻压暗：完整遮罩的这个比例。 */
private const val IdleScrim = 0.3f

/** 底部托底的高度（从键盘或导航栏的上沿往上量）和最深处的透明度。 */
private val CushionBand = 136.dp
private const val CushionAlpha = 0.5f

/** 浮起胶囊的投影：输入栏重一些，带上的屏幕、建议问题轻一些（照 Eta 的助理胶囊）。 */
private enum class Lift {
    Composer,
    Chip,
}

/** 输入栏的外形：单行时是胶囊，长到多行时四角不再变大。 */
private val InputShape = RoundedCornerShape(25.dp)

/** 浮窗这一次的动画状态。 */
@Stable
private class PanelMotion {
    /** 压暗、托底和内容整体的透明度：唤起时 0→1，关掉时 1→0。 */
    val fade = Animatable(0f)

    /** 内容出场：唤起光效交棒以后 0→1。 */
    val reveal = Animatable(0f)

    /** 拖动的距离：往上推为负，往下拉为正。 */
    val drag = Animatable(0f)

    /** 唤起光效的流光落到了底边：内容从这时出场。 */
    var woken by mutableStateOf(false)

    /** 出场放完了：这以后才弹键盘。 */
    var settled by mutableStateOf(false)
}

@Composable
private fun rememberPanelMotion(visible: Boolean): PanelMotion {
    val motion = remember { PanelMotion() }
    LaunchedEffect(visible) {
        motion.fade.animateTo(if (visible) 1f else 0f, tween(if (visible) 240 else 200))
    }
    LaunchedEffect(motion.woken) {
        if (!motion.woken) return@LaunchedEffect
        motion.reveal.animateTo(1f, tween(250, easing = EnterEasing))
        motion.settled = true
    }
    // 光效没有交棒（没画出来、被跳过）时，放完的时间一到内容照样出来
    LaunchedEffect(Unit) {
        delay(((WakeGlowSeconds + 0.3f) * 1000).roundToInt().toLong())
        motion.woken = true
    }
    return motion
}

/**
 * 数字助理的浮窗（照 Eta 的助理浮窗，外观按 COUI 2）。
 *
 * 唤起时页面轻轻压暗、底部铺一层托底，屏幕边缘的唤起光效流到底边后，三样东西从下面浮上来：带上的屏幕（截图缩略图和应用名，可以去掉）、一行建议问题、输入栏，都是浮起的胶囊。
 *
 * 有回答以后压暗加深，同一列内容铺成贴底的半屏面板（照 Eta
 * 的回答面板）：顶上是把手，下面是带上的屏幕、回答、输入栏。面板直接升到基础高度，回答变长时只长不缩，到最高后在回答区里滚动；把手和回答区可以拖着改高度，松手停到最近的一档（[SheetState]）。这一轮还在进行时输入栏右边是停止键。
 *
 * 还没发时整列往上推、有回答后在最高档从把手再往上拉、点把手或「在 Zeta 中继续」，打开 Zeta 接着这个对话；往下拖过最低档、点外面关掉。
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
    /** 屏幕下角的物理圆角半径（像素），唤起光效沿它绕过来；0 时由光效自己取默认值。 */
    cornerRadius: Float = 0f,
    /** 回答期间点停止键：打断这一轮。 */
    onStop: () -> Unit = {},
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val motion = rememberPanelMotion(visible)
    val sheetMix by
        animateFloatAsState(
            if (reply != null) 1f else 0f,
            tween(240),
            label = "assistant-sheet",
        )
    val sheet = rememberSheetState(shown = reply != null)
    // 还没发时：整列往上推够了打开 Zeta，往下拉够了关掉，其余弹回原位
    val openDistance = with(density) { OpenDistance.toPx() }
    val closeDistance = with(density) { CloseDistance.toPx() }
    val handle by
        remember(openDistance) { derivedStateOf { handleState(sheet, motion, openDistance) } }
    HandoffHaptic(handle.ready)
    val dragState = rememberDraggableState { delta ->
        val next = dragTarget(motion.drag.value, delta)
        scope.launch { motion.drag.snapTo(next) }
    }
    val onRelease: (SheetRelease) -> Unit = {
        when (it) {
            SheetRelease.Dismiss -> onDismiss()
            SheetRelease.Handoff -> onOpen()
            is SheetRelease.Settle -> Unit
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Backdrop(motion, sheetMix, onDismiss)
        AssistantWakeGlow(
            bottomCornerRadius = cornerRadius,
            onHandoff = { motion.woken = true },
            modifier = Modifier.graphicsLayer { alpha = motion.fade.value },
        )
        if (motion.woken) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                        .padding(top = SheetTopGap)
                        .onSizeChanged { sheet.room = it.height },
            ) {
                Column(
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .panelMotion(motion, sheet)
                            .sheetSurface(shown = reply != null, mix = sheetMix)
                            .panelDrag(dragState, enabled = reply == null) {
                                settleDrag(
                                    motion.drag,
                                    openDistance,
                                    closeDistance,
                                    onOpen,
                                    onDismiss,
                                )
                            }
                            .onSizeChanged { sheet.panel = it.height }
                            .panelInsets(),
                ) {
                    PanelBody(
                        screen = screen,
                        reply = reply,
                        draft = draft,
                        suggestions = suggestions,
                        sending = sending,
                        problem = problem,
                        autoFocus = autoFocus && visible && motion.settled,
                        sheet = sheet,
                        handle = handle,
                        actions = PanelActions(onSend, onToggleScreen, onOpen, onStop, onRelease),
                    )
                }
            }
        }
    }
}

/** 浮窗上各处点了、拖完以后要做的事。 */
@Immutable
private class PanelActions(
    val onSend: (String) -> Unit,
    val onToggleScreen: () -> Unit,
    val onOpen: () -> Unit,
    val onStop: () -> Unit,
    val onRelease: (SheetRelease) -> Unit,
)

/** 把手现在的样子：有回答时看面板的过顶拉取（照 Eta），还没发时看整列往上推了多远（这时没有把手，只用来振动）。 */
private fun handleState(sheet: SheetState, motion: PanelMotion, openDistance: Float): HandleState =
    if (sheet.open) {
        HandleState(sheet.handoffProgress > HandoffHintProgress, sheet.handoffReady)
    } else {
        HandleState(
            motion.drag.value < -openDistance * HandoffHintProgress,
            motion.drag.value < -openDistance,
        )
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

/** 往上推过了打开 Zeta 的位置时振动一次；退回去再推过来才再振。 */
@Composable
private fun HandoffHaptic(ready: Boolean) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(ready) {
        if (ready) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
}

/** 内容跟着拖动走（面板拖出档位时有阻尼地跟手）；出场时从下面 18dp 处淡入上来（照 Eta 的助理浮窗），关掉时淡出。 */
private fun Modifier.panelMotion(motion: PanelMotion, sheet: SheetState): Modifier = offset {
    IntOffset(0, (motion.drag.value + sheet.rubber).roundToInt())
}
    .graphicsLayer {
        alpha = motion.reveal.value * motion.fade.value
        translationY = (1f - motion.reveal.value) * 18.dp.toPx()
    }

/** 还没发时整列都能拖：往上推打开 Zeta，往下拉关掉。有回答以后改由面板的把手和回答区拖动。 */
private fun Modifier.panelDrag(
    state: DraggableState,
    enabled: Boolean,
    onStopped: suspend () -> Unit,
): Modifier =
    draggable(
        state = state,
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStopped = { onStopped() },
    )

/** 面板的底色先铺到导航栏下面，内容再让开键盘、导航栏和挖孔。 */
@Composable
private fun Modifier.panelInsets(): Modifier =
    windowInsetsPadding(
            WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom),
        )
        .windowInsetsPadding(
            WindowInsets.navigationBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal),
        )

/**
 * 发出去以后的半屏面板外观（照 Eta 的回答面板）：顶上两角 32dp 连续圆角、下面直角，COUI 面板底色和一圈柔和投影，都随 [mix]
 * 淡入。没有面板时什么都不加，免得面板外形把浮起胶囊的投影裁掉。面板上的空白处点了不算点外面。
 */
@Composable
private fun Modifier.sheetSurface(shown: Boolean, mix: Float): Modifier {
    val colors = COUITheme.colorScheme
    val shape = TopRoundedShape(COUIRoundCorner.XXXXL, LocalSquircleEnabled.current)
    if (!shown) return this
    val surface = colors.surface
    val shadowAlpha = if (colors.isDark) 0.30f else 0.10f
    return dropShadow(
            shape = shape,
            shadow = Shadow(radius = 24.dp, color = Color.Black, alpha = shadowAlpha * mix),
        )
        .clip(shape)
        .drawBehind { drawRect(surface.copy(alpha = surface.alpha * mix)) }
        .clickable(interactionSource = null, indication = null) {}
}

/** 顶上两个角是圆角、下面两个角是直角的面板外形，用 COUI 的分角连续圆角；截图测试里关掉连续圆角时画成普通圆角。 */
@Immutable
private data class TopRoundedShape(val radius: Dp, val squircle: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }
        val path =
            Path().apply {
                addSquircleRect(
                    rect = Rect(Offset.Zero, size),
                    topLeft = r,
                    topRight = r,
                    bottomRight = 0f,
                    bottomLeft = 0f,
                    squircleEnabled = squircle,
                )
            }
        return Outline.Generic(path)
    }
}

/** 浮起的胶囊（照 Eta 的助理胶囊，配色按 COUI）：投影、最上层面板的底色和一圈细描边。[fill] 为 false 时只加投影，给自己画底色的 COUI 组件用。 */
private fun Modifier.floatingSurface(
    colors: Colors,
    shape: Shape,
    lift: Lift,
    fill: Boolean = true,
): Modifier {
    val dark = colors.isDark
    val shadow =
        when (lift) {
            Lift.Composer ->
                Shadow(
                    radius = if (dark) 12.dp else 20.dp,
                    color = Color.Black,
                    alpha = if (dark) 0.36f else 0.13f,
                )
            Lift.Chip ->
                Shadow(
                    radius = if (dark) 8.dp else 14.dp,
                    color = Color.Black,
                    alpha = if (dark) 0.28f else 0.10f,
                )
        }
    val lifted = dropShadow(shape = shape, shadow = shadow)
    if (!fill) return lifted
    val stroke = colors.divider.copy(alpha = colors.divider.alpha * 0.6f)
    return lifted.clip(shape).background(colors.surfaceTop).border(0.75.dp, stroke, shape)
}

/** 内容后面的一层：压暗（没有回答时轻，发出去以后加深到 COUI 的遮罩）和底部托底（照 Eta 的输入栏托底，只在没有回答时），点一下关掉。 */
@Composable
private fun Backdrop(motion: PanelMotion, sheetMix: Float, onDismiss: () -> Unit) {
    val colors = COUITheme.colorScheme
    val mask = colors.mask
    val cushion = colors.background
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    Box(
        modifier =
            Modifier.fillMaxSize()
                .drawBehind {
                    val fade = motion.fade.value
                    val depth = IdleScrim + (1f - IdleScrim) * sheetMix
                    drawRect(mask.copy(alpha = mask.alpha * depth * fade))
                    val bottom = maxOf(ime.getBottom(this), navigation.getBottom(this))
                    drawCushion(cushion, (1f - sheetMix) * fade * motion.reveal.value, bottom)
                }
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )
}

/** 底部托底：从键盘或导航栏上方 [CushionBand] 处到屏幕底边，由透明渐变到半透明的页面底色。 */
private fun DrawScope.drawCushion(color: Color, strength: Float, bottomInset: Int) {
    if (strength <= 0.005f) return
    val top = (size.height - bottomInset - CushionBand.toPx()).coerceAtLeast(0f)
    val deepest = color.alpha * CushionAlpha * strength
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.58f to color.copy(alpha = deepest * 0.5f),
                1f to color.copy(alpha = deepest),
                startY = top,
                endY = size.height,
            ),
        topLeft = Offset(0f, top),
        size = Size(size.width, size.height - top),
    )
}

/** 从上到下：面板上半截（把手、带上的屏幕、回答，只在有回答时）或带上的屏幕、出错说明、建议问题（只在发出去以前）、输入栏。 */
@Composable
private fun ColumnScope.PanelBody(
    screen: ScreenChip?,
    reply: ReplyView?,
    draft: TextFieldState,
    suggestions: List<String>,
    sending: Boolean,
    problem: String?,
    autoFocus: Boolean,
    sheet: SheetState,
    handle: HandleState,
    actions: PanelActions,
) {
    if (reply != null) {
        // 上半截最后量：键盘、输入栏这些先排好，剩下的高度才归它，它的最大高度也照这个算
        Column(modifier = Modifier.weight(1f, fill = false).sheetHeight(sheet).clipToBounds()) {
            DragHandle(
                handle,
                actions.onOpen,
                Modifier.sheetDrag(sheet, scroll = null, onRelease = actions.onRelease),
            )
            if (screen != null) ScreenRow(screen, actions.onToggleScreen)
            ReplyArea(reply, sheet, actions)
        }
    } else if (screen != null) {
        ScreenRow(screen, actions.onToggleScreen)
    }
    problem?.let { ProblemNote(it) }
    Suggestions(
        visible = reply == null && suggestions.isNotEmpty(),
        suggestions = suggestions,
        sending = sending,
        onSend = actions.onSend,
    )
    InputRow(
        draft = draft,
        sending = sending,
        busy = reply?.busy == true,
        followUp = reply != null,
        autoFocus = autoFocus,
        actions = actions,
    )
}

/** 往上拉时把手的样子：[hint] 拉取进度过了 18%，[ready] 拉够了、这时松手打开 Zeta。 */
@Immutable private data class HandleState(val hint: Boolean, val ready: Boolean)

/**
 * 面板顶上的把手（照 Eta 的把手）。拖它改面板高度；在最高档再往上拉，下面淡入「上拉打开 Zeta」、把手往上让，拉够了把手变宽、染成主色，提示换成「松手打开 Zeta」。点一下打开
 * Zeta（读屏用户用得上）。
 */
@Composable
private fun DragHandle(state: HandleState, onOpen: () -> Unit, modifier: Modifier) {
    val colors = COUITheme.colorScheme
    val accent = if (state.ready) colors.primary else colors.dragHandle
    val hintColor = if (state.ready) colors.primary else colors.labelSecondary
    val hintText = if (state.ready) "松手打开 Zeta" else "上拉打开 Zeta"
    val width by
        animateDpAsState(
            if (state.ready) 52.dp else 36.dp,
            spring(dampingRatio = 0.88f, stiffness = 580f),
            label = "assistant-handle-width",
        )
    val color by animateColorAsState(accent, tween(160), label = "assistant-handle-color")
    val hint by
        animateFloatAsState(
            if (state.hint) 1f else 0f,
            tween(140),
            label = "assistant-handle-hint",
        )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(38.dp)
                .semantics { contentDescription = "在 Zeta 中继续" }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = onOpen,
                ),
    ) {
        Box(
            modifier =
                Modifier.align(Alignment.TopCenter)
                    .padding(top = 15.dp)
                    .graphicsLayer { translationY = -7.dp.toPx() * hint }
                    .size(width = width, height = 4.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        if (hint > 0.01f) {
            Text(
                text = hintText,
                style = COUITheme.textStyles.buttonS.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = hintColor,
                maxLines = 1,
                modifier =
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).graphicsLayer {
                        alpha = hint
                    },
            )
        }
    }
}

/** 带上的屏幕：一个靠左的浮起胶囊，左边缩略图，中间应用名和带了什么，右边去掉或带上。 */
@Composable
private fun ScreenRow(screen: ScreenChip, onToggle: () -> Unit) {
    val colors = COUITheme.colorScheme
    Row(
        modifier =
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                .floatingSurface(colors, CircleShape, Lift.Chip)
                .padding(start = 11.dp, end = if (screen.sent) 8.dp else 6.dp)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenThumbnail(screen)
        Column(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp)) {
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
    val thumbShape = RoundedCornerShape(COUIRoundCorner.XS)
    Box(
        modifier =
            Modifier.size(width = 22.dp, height = 32.dp)
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
                modifier = Modifier.size(14.dp),
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

/** 回答区：占面板上半截剩下的高度，回答短时下面留白，放不下时自己滚动；出来时从下面 14dp 处淡入（照 Eta）。回答已经滚到顶时往下拖、面板还没到最高时往上拖，改的是面板高度。 */
@Composable
private fun ColumnScope.ReplyArea(reply: ReplyView, sheet: SheetState, actions: PanelActions) {
    val colors = COUITheme.colorScheme
    val scroll = rememberScrollState()
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(210, delayMillis = 45, easing = EnterEasing))
    }
    // 流式回答跟到底；面板长高时也跟着
    LaunchedEffect(reply.text.length, scroll.maxValue) {
        if (reply.streaming) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier =
            Modifier.weight(1f)
                .fillMaxWidth()
                .sheetDrag(sheet, scroll, actions.onRelease)
                .graphicsLayer {
                    alpha = reveal.value
                    translationY = (1f - reveal.value) * 14.dp.toPx()
                }
                .padding(top = 4.dp),
    ) {
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { sheet.viewport = it.height }
                    .verticalScroll(scroll)
                    // 滚动区把最小高度传给内容：先放开，量到的才是回答本身的高度
                    .wrapContentHeight(Alignment.Top)
                    .onSizeChanged { sheet.content = it.height }
                    .padding(horizontal = 22.dp),
        ) {
            ReplyContent(reply)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "在 Zeta 中继续",
                style = COUITheme.textStyles.buttonS,
                color = colors.primary,
                modifier =
                    Modifier.clip(CircleShape)
                        .clickable(role = Role.Button, onClick = actions.onOpen)
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

/** 发不出去时的说明（比如还没配置 Linux 环境），放在一块浮起的小面板上，压暗的页面上也看得清。 */
@Composable
private fun ProblemNote(text: String) {
    val colors = COUITheme.colorScheme
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS,
        color = colors.error,
        modifier =
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                .floatingSurface(colors, RoundedCornerShape(COUIRoundCorner.L), Lift.Chip)
                .padding(horizontal = 14.dp, vertical = 10.dp),
    )
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
        exit = shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom) + fadeOut(tween(120)),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    // 投影留在横向滚动的裁剪框里面，上下不被截平
                    .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                SuggestionCapsule(index, suggestion, sending, onSend)
            }
        }
    }
}

/** 一个建议问题：浮起的 COUI 建议胶囊，照 Eta 依次错开 42ms 浮上来。 */
@Composable
private fun SuggestionCapsule(
    index: Int,
    suggestion: String,
    sending: Boolean,
    onSend: (String) -> Unit,
) {
    val colors = COUITheme.colorScheme
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        enter.animateTo(1f, tween(250, delayMillis = index * 42, easing = EnterEasing))
    }
    SuggestionChip(
        onClick = { if (!sending) onSend(suggestion) },
        label = suggestion,
        modifier =
            Modifier.graphicsLayer {
                    alpha = enter.value
                    translationY = (1f - enter.value) * 10.dp.toPx()
                }
                .floatingSurface(colors, CircleShape, Lift.Chip, fill = false),
        enabled = !sending,
        cornerRadius = 18.dp,
        minHeight = 36.dp,
        colors =
            ChipDefaults.chipColors(
                containerColor = colors.surfaceTop,
                disabledContainerColor = colors.surfaceTop,
            ),
        insideMargin = PaddingValues(horizontal = 15.dp),
    )
}

/** 输入栏：一个浮起的胶囊，左边输入框，右边圆形发送按钮；这一轮还在进行时（[busy]）按钮换成停止键，输入框暂时不能输入（照 Eta 的输入栏）。 */
@Composable
private fun InputRow(
    draft: TextFieldState,
    sending: Boolean,
    busy: Boolean,
    followUp: Boolean,
    autoFocus: Boolean,
    actions: PanelActions,
) {
    val colors = COUITheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(autoFocus) {
        // autoFocus 在出场放完以后才为真：内容浮上来以后再弹键盘，两段动画不挤在一起
        if (!autoFocus) return@LaunchedEffect
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val canSend = !sending && !busy && draft.text.isNotBlank()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp)
                .heightIn(min = 50.dp)
                .floatingSurface(colors, InputShape, Lift.Composer),
        verticalAlignment = Alignment.Bottom,
    ) {
        DraftField(draft, enabled = !sending && !busy, followUp, focus)
        Box(modifier = Modifier.padding(6.dp)) {
            SendButton(canSend, busy, actions.onStop) {
                val text = draft.text.toString()
                draft.clearText()
                actions.onSend(text)
            }
        }
    }
}

/** 输入框：空着时显示提示（发出去以后是「接着问」），最多长到 4 行。 */
@Composable
private fun RowScope.DraftField(
    draft: TextFieldState,
    enabled: Boolean,
    followUp: Boolean,
    focus: FocusRequester,
) {
    val colors = COUITheme.colorScheme
    val style = COUITheme.textStyles.bodyL.copy(color = colors.labelPrimary)
    Box(
        modifier =
            Modifier.weight(1f)
                .heightIn(min = 50.dp)
                .padding(start = 18.dp, end = 2.dp, top = 9.dp, bottom = 9.dp),
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
            enabled = enabled,
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 4),
        )
    }
}

/** 发送按钮：能发时是主色，不能发时变灰；这一轮还在进行时是停止键（深色底、面板底色的实心方块，和聊天页的停止键同一个图标）。 */
@Composable
private fun SendButton(canSend: Boolean, busy: Boolean, onStop: () -> Unit, onSend: () -> Unit) {
    val colors = COUITheme.colorScheme
    val buttonColor by
        animateColorAsState(
            when {
                busy -> colors.labelPrimary
                canSend -> colors.primary
                else -> colors.container8
            },
            tween(180),
            label = "assistant-send",
        )
    if (busy) {
        RoundButton(description = "停止", color = buttonColor, onClick = onStop) {
            Icon(
                ZetaIcons.Stop,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = colors.surface,
            )
        }
        return
    }
    RoundButton(description = "发送", color = buttonColor, enabled = canSend, onClick = onSend) {
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
            Modifier.size(38.dp)
                .clip(CircleShape)
                .background(color)
                .semantics { contentDescription = description }
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
