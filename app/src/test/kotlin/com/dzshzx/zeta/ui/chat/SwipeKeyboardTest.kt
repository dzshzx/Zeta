package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.shots.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 在聊天列表上拖动调起、收起输入法（[SwipeKeyboard]）。输入法开没开由测试给，借截图测试的 Robolectric 环境，不出图。 */
class SwipeKeyboardTest : ShotTest() {
    private lateinit var list: LazyListState
    private var imeVisible = false
    private var opened = 0
    private var closed = 0

    private fun show(rows: Int) = compose.setContent {
        val state = rememberLazyListState()
        list = state
        val density = LocalDensity.current
        val connection = remember {
            SwipeKeyboard(
                openPx = with(density) { 72.dp.toPx() },
                closePx = with(density) { 24.dp.toPx() },
                imeVisible = { imeVisible },
                onOpen = { opened++ },
                onClose = { closed++ },
            )
        }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().nestedScroll(connection).testTag("list")) {
            items((0 until rows).toList(), key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
        }
    }

    /** 从屏幕中间竖着拖 [dy] dp（负值往上），慢慢拖、不带惯性。 */
    private fun drag(dy: Float) {
        compose.onNodeWithTag("list").performTouchInput {
            val px = dy * density
            val start = center
            down(start)
            repeat(20) { moveBy(Offset(0f, px / 20), delayMillis = 16) }
            up()
        }
        compose.waitForIdle()
    }

    @Test
    fun upAtTheEndOpens() {
        show(rows = 30)
        compose.onNodeWithTag("list").performScrollToIndex(29)
        drag(-40f)
        assertEquals("拖得不够远不调起", 0, opened)
        drag(-150f)
        assertEquals("到底后接着往上拖就调起", 1, opened)
    }

    @Test
    fun shortListOpensToo() {
        show(rows = 3)
        drag(-150f)
        assertEquals("内容不满一屏也能调起", 1, opened)
    }

    @Test
    fun scrollingUpThroughTheListDoesNotOpen() {
        show(rows = 60)
        drag(-300f)
        assertEquals("列表还在滚时不调起", 0, opened)
        assertEquals(true, list.firstVisibleItemIndex > 0)
    }

    @Test
    fun downWhileKeyboardShownCloses() {
        show(rows = 30)
        compose.onNodeWithTag("list").performScrollToIndex(29)
        imeVisible = true
        drag(-150f)
        assertEquals("输入法开着时往上拖不再调起", 0, opened)
        drag(60f)
        assertEquals("往下拖就收起，一次拖动只收一回", 1, closed)
        imeVisible = false
        drag(60f)
        assertEquals("输入法没开时往下拖不管", 1, closed)
    }
}
