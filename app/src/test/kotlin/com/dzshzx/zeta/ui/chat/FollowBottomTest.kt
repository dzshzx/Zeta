package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.shots.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 贴底跟随（[FollowBottom]）：聊天页一开始就跟，子代理查看页拉到底才跟；往上拖就停，恢复后接着跟。借截图测试的 Robolectric 环境，不出图。 */
class FollowBottomTest : ShotTest() {
    private lateinit var list: LazyListState
    private lateinit var follow: FollowBottom
    private val rows = mutableStateListOf<Int>()

    private fun show(following: Boolean) = compose.setContent {
        val state = rememberLazyListState()
        list = state
        follow = rememberFollowBottom(state, following)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) {
            items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
        }
    }

    private fun add(count: Int) {
        compose.runOnUiThread { repeat(count) { rows += rows.size } }
        compose.waitForIdle()
    }

    @Test
    fun followsGrowthUntilDraggedUp() {
        show(following = true)
        add(3)
        add(30)
        assertFalse("内容长出屏幕就滚到底", list.canScrollForward)

        compose.onNodeWithTag("list").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertFalse("往上拖就不跟了", follow.following)
        add(5)
        assertTrue(list.canScrollForward)

        compose.runOnUiThread { follow.resume() }
        compose.waitForIdle()
        assertFalse("恢复后马上滚到底", list.canScrollForward)
        add(5)
        assertFalse(list.canScrollForward)
    }

    @Test
    fun startsStillUntilScrolledToTheEnd() {
        show(following = false)
        add(3)
        // 内容还不满一屏时不算停在底部
        assertFalse(follow.following)
        add(30)
        assertEquals("一开始停在顶上", 0, list.firstVisibleItemIndex)

        compose.onNodeWithTag("list").performScrollToIndex(rows.size - 1)
        compose.waitForIdle()
        assertTrue("滚到底以后跟上", follow.following)
        add(5)
        assertFalse(list.canScrollForward)
    }
}
