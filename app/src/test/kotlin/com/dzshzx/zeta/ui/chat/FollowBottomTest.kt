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

/** 贴底跟随（[FollowBottom]）不带锚点（子代理查看页）：一开始跟或拉到底才跟；往上拖就停，恢复后接着跟。借截图测试的 Robolectric 环境，不出图。 */
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

/** 带锚点（聊天页最新一条用户消息）：发消息后只跟到它顶到上沿；打开时落到底、之后不跟；点「回到底部」才一直跟。 */
class FollowBottomAnchorTest : ShotTest() {
    private lateinit var list: LazyListState
    private lateinit var follow: FollowBottom
    private val rows = mutableStateListOf<Int>()
    private var anchorAt: Int? = null

    private fun show() = compose.setContent {
        val state = rememberLazyListState()
        list = state
        follow = rememberFollowBottom(state) { anchorAt }
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) {
            items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
        }
    }

    private fun add(count: Int) {
        compose.runOnUiThread { repeat(count) { rows += rows.size } }
        compose.waitForIdle()
    }

    /** 发了一条消息（锚点是新加的这一行），回答一截截往下长。 */
    private fun send() {
        // 和聊天页一样先调 followTurn，消息再进列表
        compose.runOnUiThread { follow.followTurn() }
        compose.runOnUiThread {
            anchorAt = rows.size
            rows += rows.size
        }
        compose.waitForIdle()
    }

    private fun anchorTop(): Int? =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchorAt }?.offset

    @Test
    fun followsAnswerUntilQuestionReachesTop() {
        show()
        add(3)
        send()
        repeat(30) { add(1) }
        assertEquals("问题顶到上沿就停", 0, anchorTop())
        assertFalse("停下了", follow.following)
        assertTrue("回答还在下面接着长", list.canScrollForward)

        compose.runOnUiThread { follow.resume() }
        compose.waitForIdle()
        assertFalse("点回到底部就滚到底", list.canScrollForward)
        repeat(5) { add(1) }
        assertFalse("之后一直跟到底", list.canScrollForward)
    }

    @Test
    fun openLandsAtEndThenStaysPut() {
        anchorAt = 20
        rows += 0 until 30
        show()
        compose.waitForIdle()
        assertFalse("打开时落到最底下", list.canScrollForward)
        add(5)
        assertTrue("问题已在屏幕上面：之后不跟", list.canScrollForward)
        assertFalse(follow.following)
    }
}

/** 发消息后，消息要过一会儿才进列表：这之前锚点还是上一条，不能当成「已经到顶」停下。 */
class FollowBottomLateMessageTest : ShotTest() {
    private lateinit var list: LazyListState
    private lateinit var follow: FollowBottom
    private val rows = mutableStateListOf<Int>()
    private var anchorAt: Int? = null

    private fun add(count: Int) {
        compose.runOnUiThread { repeat(count) { rows += rows.size } }
        compose.waitForIdle()
    }

    @Test
    fun followsWhenTheSentMessageShowsUpLate() {
        anchorAt = 5
        rows += 0 until 30
        compose.setContent {
            val state = rememberLazyListState()
            list = state
            follow = rememberFollowBottom(state) { anchorAt }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
        compose.waitForIdle()
        add(3)
        assertTrue("打开后不跟", list.canScrollForward)

        compose.runOnUiThread { follow.followTurn() }
        compose.waitForIdle()
        assertFalse("发出去先滚到底", list.canScrollForward)
        // 消息这时才进列表，回答跟着往下长
        compose.runOnUiThread {
            anchorAt = rows.size
            rows += rows.size
        }
        compose.waitForIdle()
        assertFalse("新消息露出来", list.canScrollForward)
        repeat(30) { add(1) }
        assertEquals(
            "跟到新消息顶到上沿",
            0,
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == anchorAt }?.offset,
        )
    }
}
