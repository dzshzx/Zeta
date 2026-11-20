package com.dzshzx.zeta.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 盖在下面那页上的一层整页：从右侧滑入，触摸不漏到下面那页。
 * [onBack] 不为空时，打开期间返回键交给它；它先于页面内容注册，页面里再往下的一层先收到返回键。
 * 应用里一层层盖上去的页面（聊天页上的页面栈、设置的子页、页面里再往下的一层）都用它。
 */
@Composable
internal fun SlideInPage(visible: Boolean, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(visible) }
    state.targetState = visible
    SlideInPage(state, onBack, content)
}

/**
 * 带值的 [SlideInPage]：[value] 不为空时打开，内容拿到这个值。关掉（[value] 变空）后退场动画期间仍用最后一个非空值画，
 * 页面不会先变空再滑走。[value] 从一个值直接换成另一个值时页面不重放动画，内容原地换成新值的。
 */
@Composable
internal fun <T : Any> SlideInPage(value: T?, onBack: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    val shown = rememberRetained(value)
    SlideInPage(visible = value != null, onBack = onBack) { shown?.let { content(it) } }
}

/** 由 [state] 管显示和动画的 [SlideInPage]：要知道退场动画什么时候放完时用。 */
@Composable
internal fun SlideInPage(state: MutableTransitionState<Boolean>, onBack: (() -> Unit)? = null, content: @Composable () -> Unit) {
    if (onBack != null) BackHandler(enabled = state.targetState, onBack = onBack)
    AnimatedVisibility(visibleState = state, enter = PageEnter, exit = PageExit) {
        // 底下那页还在；空的 pointerInput 让触摸不漏到它身上
        Box(Modifier.fillMaxSize().pointerInput(Unit) {}) { content() }
    }
}

private val PageEnter: EnterTransition = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))
private val PageExit: ExitTransition = slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(160))
