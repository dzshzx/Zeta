package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回答结束时尾部的字还在逐字显现：列表这时不能删掉它的流式状态，要等显现收尾、换成静态排版以后再删。 借截图测试的 Robolectric 环境，不出图；时钟一帧一帧地拨，显现的进度看得见。
 */
class AnswerRevealTest : ShotTest() {
    /** 拨一帧；Markdown 在 Dispatchers.Default 上解析，不算在 Compose 的空闲判断里，再等一点真实时间。 */
    private fun frame() {
        compose.waitForIdle()
        Thread.sleep(20)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test
    fun streamingStateOutlivesTheStreamUntilRevealDrains() {
        Fixtures.readyHub(app)
        val cache = StreamingMarkdownStateCache()
        var item by mutableStateOf(AnswerItem("a1", "先看一下", streaming = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                // 和聊天页一样：不在流式、也没有条目占着的状态随时删掉
                val streamingIds = setOfNotNull(item.id.takeIf { item.streaming })
                LaunchedEffect(streamingIds) { cache.discardWhenUnused(streamingIds) }
                AnswerBlock(item, cache)
            }
        }
        repeat(20) { frame() }

        // 最后一批字和结束一起到：尾部还没显现，回答已经不在流式了
        val tail = "改好了：构建命令换成了 tools/gradle，顺手把 CLAUDE.md 里过期的两句也删了。".repeat(6)
        compose.runOnUiThread { item = item.copy(text = item.text + tail, streaming = false) }
        repeat(3) { frame() }
        val state = cache.states["a1"]
        assertTrue("回答结束、显现收尾之前，流式状态要留着", state != null)
        assertFalse("这时尾部的字还在显现", state!!.revealCoordinator.drained.value)

        // 显现收尾后换成静态排版，流式状态随即删掉
        repeat(200) {
            if (!cache.states.containsKey("a1")) return@repeat
            frame()
        }
        assertFalse("显现收尾、换成静态排版后，流式状态删掉", cache.states.containsKey("a1"))
    }
}
