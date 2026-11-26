package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.DropdownEntry

// 长按手势照 COUI 2 的 OverlayContextMenu.kt（Apache-2.0，Copyright 2026 compose-coui-ui
// contributors，https://github.com/suqi8/coui）。
// Miuix 没有长按菜单；Zeta 的改动：菜单换成 AnchoredMenu（从内容的边上弹出，不从手指按下的地方）。

/**
 * 长按 [content] 弹出 [entries] 的菜单（鼠标右键也弹），点一项就收起。手势在 Initial 阶段看，不吃掉普通的点按：内容自己的点击照常，按住不动满长按时长才弹菜单，
 * 这时整串触摸都归菜单，内容不会再收到点击。[enabled] 为 false 时（多选模式）不拦长按。
 */
@Composable
fun LongPressMenu(
    entries: List<DropdownEntry>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 手势处理活得比重组久，经 State 读这些
    val haptic by rememberUpdatedState(LocalHapticFeedback.current)
    val nonEmpty = remember(entries) { entries.filter { it.items.isNotEmpty() } }
    Box(
        modifier.contextMenuGesture(enabled = enabled && nonEmpty.isNotEmpty()) { longPress ->
            if (longPress) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            expanded = true
        },
        propagateMinConstraints = true,
    ) {
        content()
        if (nonEmpty.isNotEmpty()) {
            AnchoredMenu(show = expanded, entries = nonEmpty, onDismiss = { expanded = false })
        }
    }
}

private fun Modifier.contextMenuGesture(
    enabled: Boolean,
    onOpen: (longPress: Boolean) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (currentEvent.buttons.isSecondaryPressed) {
                down.consume()
                onOpen(false)
                consumeUntilUp()
                return@awaitEachGesture
            }
            // 超时（返回 null）说明手指一直按着、没移出触摸容差，和 View 的长按条件一样
            val released =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ended = false
                    while (!ended) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        ended =
                            change == null ||
                                !change.pressed ||
                                change.isConsumed ||
                                (change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                    }
                    true
                }
            if (released == null) {
                onOpen(true)
                consumeUntilUp()
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) break
    }
}
