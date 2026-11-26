package com.dzshzx.zeta.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.PageBlurHost
import com.dzshzx.zeta.ui.components.miuix.pageBlurSource
import com.dzshzx.zeta.ui.components.miuix.rememberPageBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object ConversationPaneMetrics {
    val PaneMaxWidth = 340.dp
    val PaneWidthFraction = 0.84f
    const val ChatScrimAlpha = 0.16f
    const val SettleDampingRatio = 1f
    const val SettleStiffness = 146f
    const val SettleVisibilityThresholdPx = 0.5f
    const val SettlePositionThresholdFraction = 0.5f
}

/**
 * 聊天页和左侧会话栏的推移容器：两边并排，由同一个位移推着走。
 *
 * 外面包一层 Miuix 的 [Scaffold]，当整个窗口的弹窗宿主：侧栏里的对话框、长按菜单（Miuix 的 Overlay 组件）要在 Scaffold
 * 里才显示，而且要盖住整个屏幕，不能只在侧栏那一条里。聊天页里的 Miuix 页面若再套 Scaffold，会把弹窗交给这一层。
 * 侧栏和聊天页一起录进一张背景图，交给侧栏里弹出的对话框、菜单做毛玻璃（弹窗宿主在录制范围之外）。
 */
@Composable
fun ConversationSidePaneScaffold(
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable (width: Dp, isRevealed: Boolean, modifier: Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val pageBackdrop = rememberPageBackdrop()
    // 两个槽位各自处理系统栏留白，这一层不给内容加留白
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val paneWidth =
                minOf(
                    maxWidth * ConversationPaneMetrics.PaneWidthFraction,
                    ConversationPaneMetrics.PaneMaxWidth,
                )
            val paneWidthPx = with(density) { paneWidth.toPx() }
            val pushed =
                rememberPushedPane(
                    paneWidthPx = paneWidthPx,
                    visible = visible,
                    onOpen = onOpen,
                    onDismiss = onDismiss,
                )
            val isRevealed = pushed.isRevealed
            val resumed = sceneLifecycleState == Lifecycle.State.RESUMED

            // 只在聊天页已稳定显示（RESUMED）、上面没盖着别的页面（backHandlerEnabled）时用返回键收起侧栏，
            // 免得抢在盖着的页面之前消费掉第一次返回。
            NavigationBackHandler(
                state = navigationEventState,
                isBackEnabled = isRevealed && backHandlerEnabled && resumed,
                onBackCompleted = pushed.close,
            )

            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clipToBounds()
                        .background(conversationPaneContainerColor())
                        // 手势由共同容器拥有，侧栏和聊天舞台均可拖动；子级先处理滚动与选择。
                        .anchoredDraggable(
                            state = pushed.drag,
                            reverseDirection = false,
                            orientation = Orientation.Horizontal,
                            enabled = backHandlerEnabled && resumed,
                            flingBehavior = pushed.fling,
                            interactionSource = pushed.interactions,
                        ),
                contentAlignment = AbsoluteAlignment.TopLeft,
            ) {
                // 两个槽位并排测量和放置，由同一位移推移；不在聊天内容下面叠放侧栏。
                // 侧栏保留组合，避免每次起手重新创建列表和搜索框。
                val measurePolicy =
                    remember(pushed.drag, paneWidthPx) { sidePaneMeasure(pushed.drag, paneWidthPx) }
                Layout(
                    modifier = Modifier.fillMaxSize().pageBlurSource(pageBackdrop),
                    content = {
                        // 只给侧栏：聊天页有自己的 Scaffold 和背景图，外面提供了它的弹窗宿主就不给毛玻璃了
                        PageBlurHost(pageBackdrop) {
                            pane(paneWidth, isRevealed, Modifier.pushedSlot { !pushed.isRevealed })
                        }
                        ChatSlot(pushed, paneWidthPx, content)
                    },
                    measurePolicy = measurePolicy,
                )
            }
        }
    }
}

/** 推移容器里随手势和 [visible] 变的那些：拖动状态、甩动、拖动的交互、露没露出侧栏、怎么收起。 */
private class PushedPane(
    val drag: AnchoredDraggableState<ConversationPaneAnchor>,
    val fling: FlingBehavior,
    val interactions: MutableInteractionSource,
    revealed: State<Boolean>,
    val close: () -> Unit,
) {
    val isRevealed by revealed
}

/**
 * 侧栏的拖动状态和它跟 [visible] 的同步：外面改了 [visible] 就推过去；手势停在另一头时报给 [onOpen]、[onDismiss]。
 * 侧栏一露出来就收起键盘、清掉焦点，拖动结束时震一下。
 */
@Composable
private fun rememberPushedPane(
    paneWidthPx: Float,
    visible: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
): PushedPane {
    val anchors = remember(paneWidthPx) { paneAnchors(paneWidthPx) }
    val paneDragState = remember {
        AnchoredDraggableState(initialValue = paneAnchor(visible), anchors = anchors)
    }
    val settleAnimation = remember {
        spring<Float>(
            dampingRatio = ConversationPaneMetrics.SettleDampingRatio,
            stiffness = ConversationPaneMetrics.SettleStiffness,
            visibilityThreshold = ConversationPaneMetrics.SettleVisibilityThresholdPx,
        )
    }
    val flingBehavior =
        AnchoredDraggableDefaults.flingBehavior(
            state = paneDragState,
            positionalThreshold = { distance ->
                distance * ConversationPaneMetrics.SettlePositionThresholdFraction
            },
            animationSpec = settleAnimation,
        )
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val hapticFeedback = LocalHapticFeedback.current
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val dragInteractions = remember { MutableInteractionSource() }
    val isDragging by dragInteractions.collectIsDraggedAsState()
    LaunchedEffect(dragInteractions) {
        dragInteractions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Stop) {
                currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            }
        }
    }
    val revealed = remember(paneDragState) { derivedStateOf { isRevealedAt(paneDragState.offset) } }
    val isRevealed by revealed
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val hideInput: () -> Unit = {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val closePane: () -> Unit = {
        currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
        hideInput()
        if (currentVisible) {
            currentOnDismiss()
        } else {
            scope.launch { paneDragState.animateTo(ConversationPaneAnchor.Closed, settleAnimation) }
        }
    }
    LaunchedEffect(isRevealed) { if (isRevealed) hideInput() }

    SideEffect { paneDragState.updateAnchors(anchors) }

    LaunchedEffect(visible, paneWidthPx) {
        if (!visible && isRevealed) hideInput()
        settleTo(paneDragState, paneAnchor(visible), settleAnimation)
    }

    LaunchedEffect(paneDragState) {
        conversationPaneSettledValues(paneDragState) { isDragging }
            .collectLatest { settledValue ->
                reportSettled(
                    settledOpen = settledValue == ConversationPaneAnchor.Open,
                    visible = currentVisible,
                    onOpen = currentOnOpen,
                    onDismiss = {
                        hideInput()
                        currentOnDismiss()
                    },
                )
            }
    }
    return PushedPane(paneDragState, flingBehavior, dragInteractions, revealed, closePane)
}

private fun paneAnchors(paneWidthPx: Float): DraggableAnchors<ConversationPaneAnchor> =
    DraggableAnchors {
        ConversationPaneAnchor.Closed at 0f
        ConversationPaneAnchor.Open at paneWidthPx
    }

private fun paneAnchor(visible: Boolean): ConversationPaneAnchor =
    if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed

/** 侧栏露出来了没有：位移有效且超过半个像素。 */
private fun isRevealedAt(offset: Float): Boolean = !offset.isNaN() && offset > 0.5f

/** 没在 [target] 上（或正往别处去）时推过去。 */
private suspend fun settleTo(
    state: AnchoredDraggableState<ConversationPaneAnchor>,
    target: ConversationPaneAnchor,
    animation: AnimationSpec<Float>,
) {
    if (state.targetValue != target || state.settledValue != target) {
        state.animateTo(target, animation)
    }
}

/** 手势停下的那一头和外面的 [visible] 不一致时，报给外面：停在打开处是 [onOpen]，停在收起处是 [onDismiss]。 */
private fun reportSettled(
    settledOpen: Boolean,
    visible: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (settledOpen == visible) return
    if (settledOpen) onOpen() else onDismiss()
}

/** 推到一边的那个槽位（[hidden] 为真时）：焦点进不去，读屏也跳过它。 */
private fun Modifier.pushedSlot(hidden: () -> Boolean): Modifier = focusProperties {
    onEnter = { if (hidden()) cancelFocusChange() }
}
    .focusGroup()
    .then(if (hidden()) Modifier.clearAndSetSemantics {} else Modifier)

/** 聊天页那个槽位：随侧栏露出蒙一层暗色；侧栏露着时点聊天页收起侧栏。 */
@Composable
private fun ChatSlot(pushed: PushedPane, paneWidthPx: Float, content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.fillMaxSize()
                .drawWithContent {
                    drawContent()
                    val progress = conversationPaneMotion(pushed.drag.offset, paneWidthPx).progress
                    drawRect(Color.Black, alpha = ConversationPaneMetrics.ChatScrimAlpha * progress)
                }
                .background(MiuixTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.pushedSlot { pushed.isRevealed }) { content() }
        if (pushed.isRevealed) {
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClickLabel = Strings.commonClose(),
                            onClick = pushed.close,
                        ),
            )
        }
    }
}

/** 两个槽位并排：侧栏固定宽度，聊天页占满；物理左侧是会话区，不随 RTL 改变两个槽位的关系。 */
private fun sidePaneMeasure(
    state: AnchoredDraggableState<ConversationPaneAnchor>,
    paneWidthPx: Float,
): MeasurePolicy = MeasurePolicy { measurables, constraints ->
    val panel =
        measurables[0].measure(Constraints.fixed(paneWidthPx.roundToInt(), constraints.maxHeight))
    val page =
        measurables[1].measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight))
    layout(constraints.maxWidth, constraints.maxHeight) {
        val motion = conversationPaneMotion(state.offset, paneWidthPx)
        val offset = motion.foregroundOffset.roundToInt()
        panel.placeWithLayer(offset - panel.width, 0)
        page.placeWithLayer(offset, 0)
    }
}

/** 侧栏与外层推移容器共用的背景色：Miuix 的浮层底色，深色下和纯黑的聊天页分得开。 */
@Composable fun conversationPaneContainerColor(): Color = MiuixTheme.colorScheme.surface
