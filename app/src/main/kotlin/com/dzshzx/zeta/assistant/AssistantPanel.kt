package com.dzshzx.zeta.assistant

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 拷自 Eta 的 agent/voice/ui/EtaVoicePanel.kt（GPL-3.0）：压暗与托底、进出场、唤起光效、半屏回答面板的外形
// （连续圆角顶角、沿顶边的环境投影、顶边高光）、把手。去掉了语音（声波、聆听态的托底、语音反馈）；
// 面板的高度、档位和拖动在 AssistantSheet.kt；回答里一问一答怎么排在 AssistantReply.kt。
// Zeta 自己加的：带上的屏幕那一行、发不出去时的说明、「在 Zeta 中继续」、还没发时整列上推打开 Zeta。

/** 唤起以后多久弹键盘（照 Eta 的录屏：面板先淡入、建议问题一个个出来，约 1.2 秒时键盘已经弹上来）。 */
private const val KeyboardDelayMs = 850

/** 往上推多远松手打开 Zeta、往下拉多远松手关掉（还没发的时候）。 */
private val OpenDistance = 56.dp
private val CloseDistance = 96.dp

/** 回答面板顶上离状态栏至少留出的高度：再高就在回答区里滚动（照 Eta 的 maxHeight - 88dp）。 */
private val SheetTopGap = 88.dp

private val SheetCornerRadius = 32.dp

/** 浮窗这一次的动画状态。 */
@Stable
private class PanelMotion {
    /** 内容整体出场（照 Eta 的 entryProgress、entryAlpha）和压暗（scrimProgress）。 */
    val entryProgress = Animatable(0f)
    val entryAlpha = Animatable(0f)
    val scrim = Animatable(0f)

    /** 拖动的距离：往上推为负，往下拉为正。 */
    val drag = Animatable(0f)

    /** 出场放完、到了弹键盘的时候。 */
    var keyboardReady by mutableStateOf(false)
}

@Composable
private fun rememberPanelMotion(): PanelMotion {
    val motion = remember { PanelMotion() }
    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                motion.scrim.animateTo(
                    1f,
                    tween(240, delayMillis = 50, easing = LinearOutSlowInEasing),
                )
            }
            launch {
                motion.entryAlpha.animateTo(
                    1f,
                    tween(180, delayMillis = 50, easing = FastOutSlowInEasing),
                )
            }
            launch {
                motion.entryProgress.animateTo(
                    1f,
                    tween(210, delayMillis = 50, easing = MiuixEnterEasing),
                )
            }
            // 焦点会主动唤起输入法：等显现和位移都结束、建议问题出齐以后再交出输入焦点（跟着系统的动画倍率，截图测试里不等）
            val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
            delay((KeyboardDelayMs * scale).toLong())
        }
        motion.keyboardReady = true
    }
    return motion
}

/**
 * 数字助理的浮窗（照 Eta 的助理浮窗）。
 *
 * 唤起时页面轻轻压暗、底部铺一层托底，屏幕边缘播一次唤起光效，同时带上的屏幕、一行建议问题、输入栏几个浮起的胶囊淡入上来，建议问题一个个错开出现，约 1 秒后弹键盘。
 *
 * 发出去以后键盘收起，压暗加深，同一列内容铺成贴底的半屏面板（照 Eta 的回答面板）：顶上是把手，下面一问一答（用户问题的气泡、「正在分析任务」或「思考 N 次」、回答、复制键），
 * 输入栏在最下面。面板从基础高度升起，随回答长高到接近全屏，再在回答区里滚动；把手和回答区可以拖着改高度（[SheetState]）。这一轮还在进行时输入栏右边是停止键。
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
    /** 回答期间点停止键：打断这一轮。 */
    onStop: () -> Unit = {},
    /** 屏幕下角的物理圆角半径（像素），唤起光效贴着它转弯；取不到时是 0。 */
    bottomCornerRadius: Float = 0f,
    /** 输入框空着时的占位提示。 */
    inputHint: String = Strings.assistantInputHint("Claude"),
) {
    val colors = rememberAssistantColors()
    val motion = rememberPanelMotion()
    val exitProgress =
        animateFloatAsState(
            targetValue = if (visible) 0f else 1f,
            animationSpec = tween(210, easing = MiuixExitEasing),
            label = "assistant_exit",
        )
    // 发出去以后压暗加深、底部托底退掉、面板底色淡入，都跟这一个进度
    val sheetProgress =
        animateFloatAsState(
            targetValue = if (reply != null) 1f else 0f,
            animationSpec = tween(220, easing = LinearOutSlowInEasing),
            label = "assistant_sheet",
        )
    val backdrop =
        remember(colors) { Backdrop(colors, { sheetProgress.value }, { 1f - exitProgress.value }) }
    val entryOffsetPx = with(LocalDensity.current) { 14.dp.toPx() }
    val exitOffsetPx = with(LocalDensity.current) { 22.dp.toPx() }
    // 回答只在回答区里读：流式输出时每出一个字只重组回答区，外面这一列都跳过
    val latestReply = rememberUpdatedState(reply)
    val replyOf = remember { { latestReply.value } }
    val actions = rememberPanelActions(onSend, onToggleScreen, onOpen, onStop, onDismiss)
    Box(modifier = Modifier.fillMaxSize().assistantBackdrop(backdrop, motion)) {
        Box(
            modifier =
                Modifier.fillMaxSize()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = actions.onDismiss,
                    ),
        )
        AssistantEdgeGlow(exiting = !visible, bottomCornerRadius = bottomCornerRadius)
        Box(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = motion.entryAlpha.value * (1f - exitProgress.value)
                    translationY =
                        (1f - motion.entryProgress.value) * entryOffsetPx +
                            exitProgress.value * exitOffsetPx
                },
        ) {
            PanelColumn(
                state =
                    PanelState(
                        screen = screen,
                        draft = draft,
                        suggestions = suggestions,
                        sending = sending,
                        problem = problem,
                        inputHint = inputHint,
                    ),
                reply = replyOf,
                sheetAlpha = { sheetProgress.value },
                colors = colors,
                motion = motion,
                focusInput = autoFocus && visible && motion.keyboardReady && reply == null,
                actions = actions,
            )
        }
    }
}

/** 浮窗这一刻要显示的内容（回答另外传，只在回答区里读）。内容没变时整列跳过重组。 */
@Immutable
private data class PanelState(
    val screen: ScreenChip?,
    val draft: TextFieldState,
    val suggestions: List<String>,
    val sending: Boolean,
    val problem: String?,
    val inputHint: String,
)

/** 浮窗上各处点了以后要做的事。 */
@Immutable
private class PanelActions(
    val onSend: (String) -> Unit,
    val onToggleScreen: () -> Unit,
    val onOpen: () -> Unit,
    val onStop: () -> Unit,
    val onDismiss: () -> Unit,
)

/** 一直是同一份 [PanelActions]，点了调最新传进来的回调；发送时先收起键盘。 */
@Composable
private fun rememberPanelActions(
    onSend: (String) -> Unit,
    onToggleScreen: () -> Unit,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
): PanelActions {
    val keyboard = LocalSoftwareKeyboardController.current
    val latest =
        rememberUpdatedState(PanelActions(onSend, onToggleScreen, onOpen, onStop, onDismiss))
    return remember(keyboard) {
        PanelActions(
            onSend = { text ->
                keyboard?.hide()
                latest.value.onSend(text)
            },
            onToggleScreen = { latest.value.onToggleScreen() },
            onOpen = { latest.value.onOpen() },
            onStop = { latest.value.onStop() },
            onDismiss = { latest.value.onDismiss() },
        )
    }
}

/** 内容后面那一层要用的颜色和进度（在绘制阶段读）：[sheet] 是铺成面板的进度，底部托底跟它反着走；[fade] 是退场的淡出。 */
private class Backdrop(val colors: AssistantColors, val sheet: () -> Float, val fade: () -> Float)

/** 压暗（没有回答时轻，发出去以后加深）和底部托底（只在没有回答时：输入胶囊与键盘交界处一层柔和羽化）。 */
@Composable
private fun Modifier.assistantBackdrop(backdrop: Backdrop, motion: PanelMotion): Modifier {
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    return drawBehind {
        val colors = backdrop.colors
        val exitFade = backdrop.fade()
        val scrimProgress = motion.scrim.value
        val sheet = backdrop.sheet()
        val baseScrim = lerp(colors.idleScrim, colors.sheetScrim, sheet)
        val scrimAlpha = baseScrim.alpha * scrimProgress * exitFade
        if (scrimAlpha > 0.002f) drawRect(baseScrim.copy(alpha = scrimAlpha))
        val bottomInsetPx =
            maxOf(imeInsets.getBottom(this), navigationInsets.getBottom(this)).toFloat()
        val textCushion = scrimProgress * (1f - sheet) * exitFade
        if (textCushion > 0.005f)
            drawTextCushion(colors.textBottomCushion, textCushion, bottomInsetPx)
    }
}

/** 仅在输入胶囊与软键盘交界处铺一层柔和羽化托底，避免大面积灰雾截断壁纸。 */
private fun DrawScope.drawTextCushion(color: Color, strength: Float, bottomInsetPx: Float) {
    val bandPx = 136.dp.toPx()
    val startY = (size.height - bottomInsetPx - bandPx).coerceAtLeast(0f)
    drawRect(
        brush =
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.58f to color.copy(alpha = color.alpha * 0.50f * strength),
                1f to color.copy(alpha = color.alpha * strength),
                startY = startY,
                endY = size.height,
            ),
        topLeft = Offset(0f, startY),
        size = Size(size.width, size.height - startY),
    )
}

/** 贴底的一列：回答面板（有回答时）或带上的屏幕、发不出去时的说明、建议问题、输入栏。 */
@Composable
private fun PanelColumn(
    state: PanelState,
    reply: () -> ReplyView?,
    sheetAlpha: () -> Float,
    colors: AssistantColors,
    motion: PanelMotion,
    focusInput: Boolean,
    actions: PanelActions,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val shown by remember(reply) { derivedStateOf { reply() != null } }
    val sheet = rememberSheetState(shown = shown)
    // 收回去的淡出放完以前面板外形还在
    val surfaceVisible by
        remember(reply, sheetAlpha) { derivedStateOf { reply() != null || sheetAlpha() > 0.001f } }
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
                    .offset { IntOffset(0, (motion.drag.value + sheet.rubber).roundToInt()) }
                    // 还没发时整列都能拖：往上推打开 Zeta，往下拉关掉。有回答以后改由面板的把手和回答区拖动
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        enabled = !shown,
                        onDragStopped = {
                            settleDrag(motion.drag, openDistance, closeDistance, actions)
                        },
                    )
                    .sheetSurface(colors, sheetAlpha, visible = surfaceVisible)
                    .onSizeChanged { sheet.panel = it.height }
                    .panelInsets(),
        ) {
            PanelBody(state, reply, shown, colors, sheet, handle, focusInput, actions)
        }
    }
}

/** 键盘弹出来了（比导航栏高）。 */
@Composable
private fun rememberKeyboardVisible(): Boolean {
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val visible by
        remember(density, imeInsets, navigationInsets) {
            derivedStateOf { imeInsets.getBottom(density) > navigationInsets.getBottom(density) }
        }
    return visible
}

/** 一列里从上到下的内容：回答面板的上半截（把手、回答区）或带上的屏幕，发不出去时的说明，建议问题，输入栏。 */
@Composable
private fun ColumnScope.PanelBody(
    state: PanelState,
    reply: () -> ReplyView?,
    shown: Boolean,
    colors: AssistantColors,
    sheet: SheetState,
    handle: HandleState,
    focusInput: Boolean,
    actions: PanelActions,
) {
    val busy by remember(reply) { derivedStateOf { reply()?.busy == true } }
    val onRelease: (SheetRelease) -> Unit = {
        when (it) {
            SheetRelease.Dismiss -> actions.onDismiss()
            SheetRelease.Handoff -> actions.onOpen()
            is SheetRelease.Settle -> Unit
        }
    }
    if (shown) {
        // 上半截最后量：键盘、输入栏这些先排好，剩下的高度才归它，它的最大高度也照这个算
        Column(
            modifier = Modifier.weight(1f, fill = false).sheetHeight(sheet).clipToBounds(),
        ) {
            DragHandle(
                colors,
                handle,
                actions.onOpen,
                Modifier.sheetDrag(sheet, scroll = null, onRelease = onRelease),
            )
            ReplyArea(reply, sheet, onRelease, actions.onOpen)
        }
    } else {
        state.screen?.let { ScreenRow(it, colors, actions.onToggleScreen) }
    }
    state.problem?.let { ProblemNote(it, colors) }
    AssistantSuggestionRow(
        suggestions = state.suggestions,
        onSuggestionClick = { if (!state.sending) actions.onSend(it) },
        colors = colors,
        visible = !shown && state.suggestions.isNotEmpty(),
    )
    AssistantInputBar(
        input = state.draft,
        busy = busy,
        sending = state.sending,
        focus = focusInput,
        keyboardVisible = rememberKeyboardVisible(),
        colors = colors,
        onSubmit = {
            val text = state.draft.text.toString()
            state.draft.clearText()
            actions.onSend(text)
        },
        onStop = actions.onStop,
        hint = state.inputHint,
        modifier =
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp),
    )
}

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

/** 拖动整列时这一下之后的位置：往上拖有阻尼，往下跟手。 */
private fun dragTarget(current: Float, delta: Float): Float =
    current + if (current + delta < 0) delta * 0.45f else delta

/** 松手时：往上推够了打开 Zeta，往下拉够了关掉；都弹回原位。 */
private suspend fun settleDrag(
    drag: Animatable<Float, AnimationVector1D>,
    openDistance: Float,
    closeDistance: Float,
    actions: PanelActions,
) {
    when {
        drag.value < -openDistance -> actions.onOpen()
        drag.value > closeDistance -> actions.onDismiss()
    }
    drag.animateTo(0f, tween(220))
}

/** 拉过打开 Zeta 的位置时振动一次；退回去再拉过来才再振（照 Eta）。 */
@Composable
private fun HandoffHaptic(ready: Boolean) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(ready) {
        if (ready) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/** 背景先覆盖导航区域，再让内容避开系统栏；IME 与导航栏取并集，布局阶段消费一次，跟随系统键盘动画。 */
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
 * 回答面板的外观（照 Eta）：连续圆角顶角、底边直角，面板底色、沿顶部圆角外侧渐隐的环境投影和顶边一道高光，都随 [alpha] 淡入。
 * 未展开回复面板时不启用轮廓裁剪，避免把空状态下的悬浮胶囊顶部与阴影裁掉。路径、描边和渐变按尺寸缓存，绘制时只换透明度。
 */
private fun Modifier.sheetSurface(
    colors: AssistantColors,
    alpha: () -> Float,
    visible: Boolean,
): Modifier {
    val shape = TopMiuixSheetShape(topRadius = SheetCornerRadius)
    return drawWithCache { sheetShadow(colors, alpha) }
        .then(
            if (visible)
                Modifier.clip(shape).clickable(interactionSource = null, indication = null) {}
            else Modifier,
        )
        .drawWithCache { sheetFill(colors, alpha) }
}

/** 环境投影叠几层描边。 */
private const val ShadowLayers = 8

/** 沿着连续圆角的顶部外侧绘制渐隐环境投影，切出面板内部后贴合左右切点收束至 0，避免矩形阴影在圆角外侧露馅。 */
private fun CacheDrawScope.sheetShadow(colors: AssistantColors, alpha: () -> Float): DrawResult {
    val shadowRisePx = 28.dp.toPx()
    val cornerPx = SheetCornerRadius.toPx().coerceIn(0f, minOf(size.width * 0.5f, size.height))
    val sheetPath = buildTopMiuixSheetPath(size, cornerPx)
    val strokes =
        List(ShadowLayers) { i ->
            Stroke(width = shadowRisePx * (i + 1f) / ShadowLayers * 2f, cap = StrokeCap.Butt)
        }
    return onDrawBehind {
        val sheetAlpha = alpha()
        if (sheetAlpha <= 0.01f || size.height <= 8f) return@onDrawBehind
        val maxAlpha = (if (colors.dark) 0.30f else 0.10f) * sheetAlpha
        clipRect(left = 0f, top = -shadowRisePx, right = size.width, bottom = cornerPx) {
            clipPath(sheetPath, clipOp = ClipOp.Difference) {
                strokes.forEachIndexed { i, stroke ->
                    val falloff = 1f - i.toFloat() / ShadowLayers
                    val layerAlpha = maxAlpha * falloff * falloff * (2.2f / ShadowLayers)
                    if (layerAlpha > 0.001f) {
                        drawPath(sheetPath, Color.Black.copy(alpha = layerAlpha), style = stroke)
                    }
                }
            }
        }
    }
}

/** 面板底色，和顶边的一道高光（往下渐隐）。 */
private fun CacheDrawScope.sheetFill(colors: AssistantColors, alpha: () -> Float): DrawResult {
    val rimPath = buildTopMiuixSheetPath(size, SheetCornerRadius.toPx())
    val rimBrush =
        Brush.verticalGradient(
            0f to colors.panelSpecular,
            1f to Color.Transparent,
            startY = 0f,
            endY = minOf(84.dp.toPx(), size.height * 0.55f).coerceAtLeast(1f),
        )
    val rimStroke = Stroke(width = 1.15.dp.toPx())
    return onDrawWithContent {
        val sheetAlpha = alpha()
        if (sheetAlpha > 0f)
            drawRect(colors.content.copy(alpha = colors.content.alpha * sheetAlpha))
        drawContent()
        if (sheetAlpha > 0.01f && size.height > 8f)
            drawPath(rimPath, rimBrush, alpha = sheetAlpha, style = rimStroke)
    }
}

/** 往上拉时把手的样子：[hint] 拉取进度过了 18%，[ready] 拉够了、这时松手打开 Zeta。 */
@Immutable private data class HandleState(val hint: Boolean, val ready: Boolean)

/**
 * 面板顶上的把手（照 Eta）：拖它改面板高度；在最高档再往上拉，下面淡入「继续上拉进入 Zeta」、把手往上让，拉够了把手变宽、染成主色，提示换成「松手进入 Zeta」。 点一下打开
 * Zeta（读屏用户用得上）。
 */
@Composable
private fun DragHandle(
    colors: AssistantColors,
    state: HandleState,
    onOpen: () -> Unit,
    modifier: Modifier,
) {
    val handleWidth by
        animateDpAsState(
            targetValue = if (state.ready) 52.dp else 36.dp,
            animationSpec = folmeSpring(damping = 0.88f, response = 0.26f),
            label = "assistant_handle_width",
        )
    val handleColor by
        animateColorAsState(
            targetValue = if (state.ready) colors.accent else colors.tertiary,
            animationSpec = tween(160),
            label = "assistant_handle_color",
        )
    val hintAlpha by
        animateFloatAsState(
            targetValue = if (state.hint) 1f else 0f,
            animationSpec = tween(140),
            label = "assistant_handoff_hint_alpha",
        )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(38.dp)
                .semantics { contentDescription = Strings.assistantContinueInZeta() }
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
                    .graphicsLayer { translationY = -7.dp.toPx() * hintAlpha }
                    .size(width = handleWidth, height = 4.dp)
                    .clip(CircleShape)
                    .background(handleColor),
        )
        if (hintAlpha > 0.01f) HandoffHint(state.ready, colors) { hintAlpha }
    }
}

/** 把手下面的提示：「继续上拉进入 Zeta」，拉够了换成主色的「松手进入 Zeta」。 */
@Composable
private fun BoxScope.HandoffHint(ready: Boolean, colors: AssistantColors, alpha: () -> Float) {
    Text(
        text = if (ready) Strings.assistantReleaseToOpen() else Strings.assistantPullToOpen(),
        color = if (ready) colors.accent else colors.inputSecondary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier =
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).graphicsLayer {
                this.alpha = alpha()
            },
    )
}

/** 回答区：占面板上半截剩下的高度，回答短时下面留白，放不下时自己滚动；出来时从下面 14dp 处淡入（照 Eta）。 回答已经滚到顶时往下拖、面板还没到最高时往上拖，改的是面板高度。 */
@Composable
private fun ColumnScope.ReplyArea(
    reply: () -> ReplyView?,
    sheet: SheetState,
    onRelease: (SheetRelease) -> Unit,
    onOpen: () -> Unit,
) {
    val view = reply() ?: return
    val scroll = rememberScrollState()
    val cache = remember { StreamingMarkdownStateCache() }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(210, delayMillis = 45, easing = MiuixEnterEasing))
    }
    // 一直跟到底：流式回答、面板长高、新的一问都跟着
    LaunchedEffect(view.entries.size, view.answerLength, scroll.maxValue) {
        if (view.busy) scroll.scrollTo(scroll.maxValue)
    }
    Column(
        modifier =
            Modifier.weight(1f).fillMaxWidth().sheetDrag(sheet, scroll, onRelease).graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 14.dp.toPx()
            },
    ) {
        Column(
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { sheet.viewport = it.height }
                    .verticalScroll(scroll)
                    // 滚动区把最小高度传给内容：先放开，量到的才是回答本身的高度
                    .wrapContentHeight(Alignment.Top)
                    .onSizeChanged { sheet.content = it.height },
        ) {
            ReplyEntries(view, cache)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextAction(Strings.assistantContinueInZeta(), MiuixTheme.colorScheme.primary, onOpen)
        }
    }
}

/** 还没发时带上的屏幕：一个靠左的浮起胶囊，左边缩略图，中间应用名，右边去掉或带上。 */
@Composable
private fun ScreenRow(screen: ScreenChip, colors: AssistantColors, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 4.dp)) {
        Row(
            modifier =
                Modifier.weight(1f, fill = false)
                    .miuixAssistantCapsule(colors, AssistantCapsuleElevation.Chip)
                    .padding(start = 11.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenThumbnail(screen.thumbnail.takeIf { !screen.removed }, colors)
            Text(
                text = screen.app,
                color = if (screen.removed) colors.inputTertiary else colors.inputPrimary,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp),
            )
            ScreenToggle(screen.removed, colors, onToggle)
        }
    }
}

/** 那一行最右边：去掉了是「带上」，带着是一个叉（32dp 的点按区里一个 22dp 的小圆底）。 */
@Composable
private fun ScreenToggle(removed: Boolean, colors: AssistantColors, onToggle: () -> Unit) {
    if (removed) {
        TextAction(Strings.assistantIncludeScreen(), colors.accent, onToggle)
        return
    }
    FlatIconButton(
        icon = MiuixIcons.Close,
        contentDescription = Strings.assistantExcludeScreen(),
        size = 32.dp,
        iconSize = 12.dp,
        modifier = Modifier.drawBehind { drawCircle(colors.controlSurface, radius = 11.dp.toPx()) },
        tint = colors.inputSecondary,
        onClick = onToggle,
    )
}

/** 只有一行主色小字的按钮：「带上」「在 Zeta 中继续」。 */
@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = color,
        modifier =
            Modifier.clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 发不出去时的说明（比如还没配置 Linux 环境），放在一块浮起的小面板上，压暗的页面上也看得清。 */
@Composable
private fun ProblemNote(text: String, colors: AssistantColors) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 6.dp)) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.error,
            modifier =
                Modifier.miuixAssistantCapsule(
                        colors = colors,
                        elevation = AssistantCapsuleElevation.Chip,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
