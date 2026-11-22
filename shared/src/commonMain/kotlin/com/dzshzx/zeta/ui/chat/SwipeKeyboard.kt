package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
 * 在聊天列表上用手指调起、收起输入法：列表已到最底端（或内容不满一屏）时接着往上拖满 [openPx]，调 [onOpen]； 输入法开着（[imeVisible]）时往下拖满
 * [closePx]，调 [onClose]。列表照常滚动，这里只看不吃位移。 只算手指拖动（惯性滑动不算），一次拖动最多触发一回，松手后重新计。
 */
class SwipeKeyboard(
    private val openPx: Float,
    private val closePx: Float,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit,
) : NestedScrollConnection {
    var imeVisible = false

    /** 这次拖动朝触发方向走了多远：输入法开着时算往下，没开时算往上。触发前输入法开关不会变，一个就够。 */
    private var travel = 0f
    private var fired = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || fired || !imeVisible) return Offset.Zero
        // 手指往下拖时 y 为正；中途往回拖要抵掉
        travel = (travel + available.y).coerceAtLeast(0f)
        if (travel >= closePx) fire(onClose)
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source != NestedScrollSource.UserInput || fired || imeVisible) return Offset.Zero
        // 列表滚不动了还剩下的往上位移（y 为负）才算；列表还在往下滚时不计
        travel = if (consumed.y < 0f) 0f else (travel - available.y).coerceAtLeast(0f)
        if (travel >= openPx) fire(onOpen)
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        travel = 0f
        fired = false
        return Velocity.Zero
    }

    private fun fire(action: () -> Unit) {
        fired = true
        action()
    }
}

/** 往上拖多远调起输入法。 */
val OpenDistance = 72.dp

/** 往下拖多远收起输入法。 */
val CloseDistance = 24.dp

/** 聊天页的 [SwipeKeyboard]：调起时把焦点给输入框 [field]，拿到了才弹输入法（输入框不在屏上或不能输入时拿不到）；收起时连焦点一起清掉。 */
@Composable
fun rememberSwipeKeyboard(field: FocusRequester): NestedScrollConnection {
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val connection =
        remember(density, keyboard, focusManager, field) {
            SwipeKeyboard(
                openPx = with(density) { OpenDistance.toPx() },
                closePx = with(density) { CloseDistance.toPx() },
                onOpen = { if (field.requestFocus()) keyboard?.show() },
                onClose = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                },
            )
        }
    TrackIme(connection)
    return connection
}

/** 输入法开关时只有这里重组，不带着调用方（整个聊天页）一起。 */
@Composable
private fun TrackIme(connection: SwipeKeyboard) {
    val visible = imeVisible()
    SideEffect { connection.imeVisible = visible }
}

/** 屏幕上的输入法开着（手机）；桌面没有，总是 false。 */
@Composable expect fun imeVisible(): Boolean
