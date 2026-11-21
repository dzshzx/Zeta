package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * 在聊天列表上用手指调起、收起输入法：列表已到最底端（或内容不满一屏）时接着往上拖满 [openPx]，调 [onOpen]；
 * 输入法开着时往下拖满 [closePx]，调 [onClose]。列表照常滚动，这里只看不吃位移。
 * 只算手指拖动（惯性滑动不算），一次拖动最多触发一回，松手后重新计。
 */
internal class SwipeKeyboard(
    private val openPx: Float,
    private val closePx: Float,
    private val imeVisible: () -> Boolean,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
) : NestedScrollConnection {
    private var up = 0f
    private var down = 0f
    private var fired = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || fired || !imeVisible()) return Offset.Zero
        // 手指往下拖时 y 为正；中途往回拖要抵掉
        down = (down + available.y).coerceAtLeast(0f)
        if (down >= closePx) {
            fired = true
            onClose()
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || fired || imeVisible()) return Offset.Zero
        // 列表滚不动了还剩下的往上位移（y 为负）才算；列表还在往下滚时不计
        up = if (consumed.y < 0f) 0f else (up - available.y).coerceAtLeast(0f)
        if (up >= openPx) {
            fired = true
            onOpen()
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        up = 0f
        down = 0f
        fired = false
        return Velocity.Zero
    }
}

/** 往上拖多远调起输入法。 */
private val OpenDistance = 72.dp

/** 往下拖多远收起输入法。 */
private val CloseDistance = 24.dp

/**
 * 聊天页的 [SwipeKeyboard]：调起时把焦点给输入框 [field]（[canOpen] 为假时输入框不在屏上或不能输入，不调起），收起时连焦点一起清掉。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberSwipeKeyboard(field: FocusRequester, canOpen: Boolean): NestedScrollConnection {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    val open = rememberUpdatedState(canOpen)
    return remember(density, keyboard, focusManager, field) {
        SwipeKeyboard(
            openPx = with(density) { OpenDistance.toPx() },
            closePx = with(density) { CloseDistance.toPx() },
            imeVisible = { imeVisible.value },
            onOpen = {
                if (open.value) {
                    field.requestFocus()
                    keyboard?.show()
                }
            },
            onClose = {
                keyboard?.hide()
                focusManager.clearFocus()
            },
        )
    }
}
