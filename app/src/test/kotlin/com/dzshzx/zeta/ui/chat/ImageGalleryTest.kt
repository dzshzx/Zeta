package com.dzshzx.zeta.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.shots.awaitImages
import com.dzshzx.zeta.shots.testPicture
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import org.junit.Test

/** 流式输出时图片组里多了图：翻到的那一页留着，页码跟着变成新的总数。借截图测试的 Robolectric 环境，不出图。 */
class ImageGalleryTest : ShotTest() {
    @Test
    fun growingGroupKeepsCurrentPage() {
        Fixtures.readyHub(app)
        val first =
            "![](${testPicture(800, 600, 0xFF3D7BF7.toInt(), "1")})\n" +
                "![](${testPicture(800, 600, 0xFFF08A24.toInt(), "2")})\n"
        val third = "![](${testPicture(800, 600, 0xFF2EAD6B.toInt(), "3")})\n"
        var item by mutableStateOf(AnswerItem("a1", first, streaming = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                AnswerBlock(
                    item,
                    remember { StreamingMarkdownStateCache() },
                    imagesOf = { answerImages(it, "/workspace", null) },
                )
            }
        }
        compose.awaitImages()
        compose.onNodeWithText("1 / 2").assertExists()
        compose.onAllNodesWithContentDescription("图片").onFirst().performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1_000)
        awaitPage("2 / 2")

        compose.runOnUiThread { item = item.copy(text = first + third) }
        awaitPage("2 / 3")
        compose.onNodeWithText("3 张图片").assertExists()
    }

    private fun awaitPage(text: String) =
        awaitMain(5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
}
