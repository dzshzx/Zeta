package com.dzshzx.zeta.ui.overlay

import android.os.SystemClock
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶部状态胶囊（照 Eta 的 COUI 样式）：收起时一句当前步骤 + 思考尾句，后台屏幕在用时右侧镶一块 18×32dp 的实时微缩画面；
 * 点一下原地展开：后台屏幕的画廊式展开画面（可以直接点、滑来接管）、“全屏”，以及补充 / 暂停·继续 / 停止。
 * 位置固定在状态栏下方居中，不跟着 Claude 的点按位置走，免得和目标控件重叠。
 */
@Composable
internal fun StatusCapsule(state: CapsuleState, controls: CapsuleControls) {
    val focus = state.focus ?: return
    val phase = focus.phase
    val screen = focus.screen
    val expanded = state.expanded
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var supplementText by remember(focus) { mutableStateOf("") }

    // 先收输入法再改窗口焦点，免得输入法在窗口失焦的一瞬间闪一下或留在屏上
    fun leaveInput(then: () -> Unit = {}) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            controls.setInputMode(false)
            supplementText = ""
            then()
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded && state.inputMode) leaveInput()
    }

    // 只关心后台屏幕上的触摸（主屏上的由边缘流光表现）；刚出现时不补放旧的
    var touch by remember(focus) { mutableStateOf<TouchMark?>(null) }
    LaunchedEffect(focus) {
        focus.touches.collect { mark ->
            if (mark.onBackground && SystemClock.uptimeMillis() - mark.at < StaleTouchMs) touch = mark
        }
    }

    // 进场、退场；退场放完告诉窗口（焦点没了就移除窗口）
    val visibility = remember { MutableTransitionState(false) }
    val target = state.shown && !state.materializing
    LaunchedEffect(target) { visibility.targetState = target }
    LaunchedEffect(visibility) {
        snapshotFlow { visibility.isIdle && !visibility.currentState }.collect { hidden -> if (hidden) controls.onExitSettled() }
    }

    // 为 Claude 的手势让路：快速上移、收小、淡出，回来时弹簧落回；只改绘制层，不重新布局
    val clearance by animateFloatAsState(
        targetValue = if (state.yielded) 1f else 0f,
        animationSpec = if (state.yielded) tween(180, easing = FastOutLinearInEasing) else spring(0.82f, Spring.StiffnessMediumLow),
        label = "capsule_clearance",
    )
    val accent by animateColorAsState(phaseAccent(phase), tween(240), label = "capsule_accent")
    // 展开进度只驱动绘制层的透明度和位移，不参与测量；下面的控制区用 AnimatedVisibility，只在两头各测一次
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "capsule_expansion",
        finishedListener = { value -> if (value == 0f) controls.onCollapsedSettled() },
    )
    val capsuleWidth by animateDpAsState(
        targetValue = when {
            expanded -> StageCapsuleWidth
            screen != null -> LensCapsuleWidth
            else -> CapsuleWidth
        },
        animationSpec = spring(0.86f, Spring.StiffnessMediumLow),
        label = "capsule_width",
    )
    val capsuleCorner by animateDpAsState(
        targetValue = if (expanded && screen != null) StageOuterCorner else CapsuleCorner,
        animationSpec = spring(0.86f, Spring.StiffnessMediumLow),
        label = "capsule_corner",
    )
    val step = focus.step.ifBlank { phaseLabel(phase) }
    // 后台屏幕没了时微缩画面还要淡出一下，留着上一块
    var lensScreen by remember(focus) { mutableStateOf(screen) }
    if (screen != null) lensScreen = screen

    AnimatedVisibility(
        visibleState = visibility,
        enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { -it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(140)),
    ) {
        val dark = CouiTheme.isDark
        val colors = MiuixTheme.colorScheme
        val shape = CouiShape(capsuleCorner, 2.5f)
        val headerPress = rememberCouiPressMotion()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 量内容本来的高度（不受窗口高度限制），窗口档位放不下时由窗口那边撑大
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { controls.reportContentHeight(it.height) },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .width(capsuleWidth)
                    .graphicsLayer {
                        // 点按时轻轻回弹、展开后保持原尺寸；让路时上移收小淡出
                        val press = (1f - 0.03f * (1f - expansion) * expansion * 4f) * if (!expanded) headerPress.scale.value else 1f
                        val shrink = 1f - 0.08f * clearance
                        scaleX = press * shrink
                        scaleY = press * shrink
                        translationY = -12.dp.toPx() * clearance
                        alpha = 1f - clearance
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .shadow(
                        elevation = 12.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = if (dark) 0.45f else 0.18f),
                        spotColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.22f),
                    )
                    .clip(shape)
                    .background(if (dark) Color(0xF520232B) else Color(0xFCFFFFFF), shape)
                    .border(0.6.dp, if (dark) Color(0x22FFFFFF) else Color(0x12000000), shape),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!expanded) Modifier.couiPressMotion(headerPress) else Modifier)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = controls::toggle)
                        .semantics { contentDescription = if (expanded) "$step，点按收起" else "$step，点按展开控制" }
                        .padding(start = 13.dp, end = if (screen != null && !expanded) 10.dp else 16.dp, top = 9.5.dp, bottom = 9.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseIndicator(phase, accent)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = step,
                            transitionSpec = {
                                (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                    (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                    SizeTransform(clip = true) { _, _ -> snap() }
                            },
                            label = "capsule_step",
                        ) { text ->
                            Text(
                                text = text,
                                color = colors.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ThoughtLine(focus.thought.takeIf { phase == OverlayPhase.RUNNING }.orEmpty())
                    }
                    // 右侧同一个位置：收起时是微缩画面，展开时换成对话标题
                    Box(contentAlignment = Alignment.CenterEnd) {
                        // 在 Row 里面的 Box 中，要用不带作用域的那个版本
                        androidx.compose.animation.AnimatedVisibility(visible = screen != null && !expanded, enter = fadeIn(tween(180)), exit = fadeOut(tween(120))) {
                            lensScreen?.let { lens ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(8.dp))
                                    MicroLens(lens, touch, accent)
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(visible = expanded, enter = fadeIn(tween(180, delayMillis = 60)), exit = fadeOut(tween(90))) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(8.dp))
                                TitlePill(focus.title, onClick = controls::openInZeta)
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow), clip = true) +
                        fadeIn(tween(180, delayMillis = 40)),
                    exit = shrinkVertically(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium), clip = true) + fadeOut(tween(90)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .graphicsLayer { translationY = (1f - expansion) * -6.dp.toPx() },
                    ) {
                        if (screen != null && !state.inputMode) {
                            BackgroundStage(
                                focus = focus,
                                screen = screen,
                                touch = touch,
                                accent = accent,
                                onTakeOver = controls::takeOver,
                                onFullscreen = controls::fullscreen,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (state.inputMode) {
                            SupplementInput(
                                value = supplementText,
                                onValueChange = { supplementText = it },
                                onCancel = { leaveInput() },
                                onSend = {
                                    val text = supplementText.trim()
                                    if (text.isNotEmpty()) leaveInput { focus.supplement(text) }
                                },
                            )
                        } else {
                            ActionRow(focus, phase, onSupplement = { controls.setInputMode(true) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(focus: OverlayFocus, phase: OverlayPhase, onSupplement: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        CapsuleAction(Icons.Rounded.Edit, "补充", colors.onSurface, onSupplement)
        when (phase) {
            OverlayPhase.RUNNING -> CapsuleAction(Icons.Rounded.Pause, "暂停", colors.onSurface) { focus.pause() }
            // 接管造成的暂停交还给 Claude；点了暂停的就继续
            OverlayPhase.PAUSED -> CapsuleAction(Icons.Rounded.PlayArrow, "继续", colors.primary) {
                if (focus.userControlling) focus.resumeAuto() else focus.resume()
            }
            OverlayPhase.FINISHED -> Unit
        }
        CapsuleAction(Icons.Rounded.Stop, "停止", colors.error) { focus.stop() }
    }
}

/** 运行中是转动的弧形光点，暂停是静止的空心环，结束是实心点；只在运行时持有帧动画。 */
@Composable
private fun PhaseIndicator(phase: OverlayPhase, accent: Color) {
    val size = 14.dp
    if (phase != OverlayPhase.RUNNING) {
        Box(
            Modifier
                .size(size)
                .drawBehind {
                    val radius = this.size.minDimension / 2f
                    if (phase == OverlayPhase.PAUSED) {
                        drawCircle(accent, radius * 0.78f, style = Stroke(radius * 0.32f))
                    } else {
                        drawCircle(accent, radius * 0.6f)
                    }
                },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "capsule_indicator")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "capsule_indicator_rotation")
    val breathe by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "capsule_indicator_breathe")
    Box(
        Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(radius, radius)
                drawCircle(accent.copy(alpha = 0.18f), radius)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(radius * 0.34f, cap = StrokeCap.Round),
                )
                drawCircle(accent.copy(alpha = breathe), radius * 0.26f, center)
            },
    )
}

/**
 * 思考尾句：单行，从左边裁掉旧内容，最新的字始终看得见。
 * 流式增量只换文字不做逐字动画；这一行出现、消失只淡入淡出，高度不变，胶囊不会上下跳。
 */
@Composable
private fun ThoughtLine(thought: String) {
    val shown = thought.isNotBlank()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "thought_alpha")
    var last by remember { mutableStateOf("") }
    if (shown) last = thought
    Text(
        text = last,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .padding(top = 1.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** 展开时右上角的对话标题：Zeta 可以同时开好几个对话，点它回到 Zeta 的这个对话。 */
@Composable
private fun TitlePill(title: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .height(24.dp)
            .widthIn(max = TitlePillMaxWidth)
            .clip(CouiCapsuleShape)
            .background(colors.surfaceContainerHigh)
            .couiButtonFeedback(dark, source, enabled = true)
            .clickable(source, indication = null, role = Role.Button, onClickLabel = "在 Zeta 中打开", onClick = onClick)
            .padding(start = 9.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.ifBlank { "新对话" },
            color = colors.onSurfaceVariantSummary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(13.dp), tint = colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    val press = rememberCouiPressMotion()
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 34.dp)
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .couiPressMotion(press)
            .clip(CouiCapsuleShape)
            .background(if (dark) Color(0x1FFFFFFF) else Color(0x0F11141A), CouiCapsuleShape)
            .couiButtonFeedback(dark, source, enabled = true)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(17.dp), tint = tint)
    }
}

/**
 * 补充输入：窗口这时已可获焦点。系统返回键（targetSdk 37 走 OnBackInvokedCallback）和窗口失去焦点（点到了别的应用）都算取消。
 */
@Composable
private fun SupplementInput(value: String, onValueChange: (String) -> Unit, onCancel: () -> Unit, onSend: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val colors = MiuixTheme.colorScheme
    val cancel by rememberUpdatedState(onCancel)

    LaunchedEffect(Unit) {
        // 等窗口变成可获焦点再要焦点、弹输入法
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val view = LocalView.current
    DisposableEffect(view) {
        val dispatcher = view.findOnBackInvokedDispatcher()
        val callback = OnBackInvokedCallback { cancel() }
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        var focused = false
        snapshotFlow { windowInfo.isWindowFocused }.collect { now ->
            if (now) focused = true else if (focused) cancel()
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 112.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = "补充指令，Claude 会继续当前任务",
                    color = colors.onSurface.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp, lineHeight = 18.sp),
                keyboardOptions = KeyboardOptions.Default,
                cursorBrush = SolidColor(colors.primary),
                maxLines = 4,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            CouiButton(
                text = "取消",
                onClick = onCancel,
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            CouiButton(
                text = "发送",
                onClick = onSend,
                enabled = value.isNotBlank(),
                role = CouiButtonRole.Primary,
                minHeight = 32.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun phaseAccent(phase: OverlayPhase): Color = when (phase) {
    OverlayPhase.RUNNING -> MiuixTheme.colorScheme.primary
    OverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    OverlayPhase.FINISHED -> Color(0xFF34C759)
}

private fun phaseLabel(phase: OverlayPhase) = when (phase) {
    OverlayPhase.RUNNING -> "运行中"
    OverlayPhase.PAUSED -> "已暂停"
    OverlayPhase.FINISHED -> "已完成"
}

/** 订阅前就发生的触摸不补放。 */
private const val StaleTouchMs = 1_500L

private val CapsuleCorner = 22.dp
private val StageOuterCorner = 28.dp
private val CapsuleWidth = 248.dp
private val LensCapsuleWidth = 264.dp
private val StageCapsuleWidth = 296.dp
private val TitlePillMaxWidth = 112.dp
