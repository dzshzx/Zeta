package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.shots.testPicture
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全屏看图的双指缩放：张开放大，捏拢缩小。借截图测试的 Robolectric 环境，不出图。 */
class ImageZoomTest : ShotTest() {
    private fun zoom(): String =
        compose
            .onNodeWithContentDescription("图片", useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.StateDescription)
            .orEmpty()

    private fun scale(): Float =
        zoom().let { if (it == "原大") 1f else it.substringAfter(" ").removeSuffix(" 倍").toFloat() }

    /** 以图的中心为中点，两指从相距 [from] 像素挪到相距 [to] 像素。 */
    private fun pinch(from: Float, to: Float) {
        compose.onNodeWithContentDescription("图片", useUnmergedTree = true).performTouchInput {
            val c = center
            pinch(
                c - Offset(from / 2, 0f),
                c - Offset(to / 2, 0f),
                c + Offset(from / 2, 0f),
                c + Offset(to / 2, 0f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
    }

    private fun open() {
        Fixtures.readyHub(app)
        val ref =
            answerImages(
                    "![](${testPicture(800, 600, 0xFF3D7BF7.toInt(), "1")})",
                    "/workspace",
                    null,
                )
                .groups
                .single()
        compose.setContent { ZetaTheme(windowBackdrop = false) { ImageViewer(ref, 0) {} } }
        awaitMain(5_000) {
            compose.waitForIdle()
            runCatching { zoom() }.getOrDefault("").isNotEmpty()
        }
        assertEquals("原大", zoom())
    }

    @Test
    fun pinchZoomsInAndBackOut() {
        open()

        pinch(200f, 600f)
        val big = scale()
        assertTrue("张开该放大：$big", big > 2f)

        pinch(600f, 300f)
        val smaller = scale()
        assertTrue("放大后捏拢该缩小：$big → $smaller", smaller < big * 0.7f)
    }

    /** 刚打开（铺满屏幕）时捏拢能缩得更小，最小到一半；双击回到原大。 */
    @Test
    fun pinchShrinksBelowFitAndDoubleTapRestores() {
        open()
        pinch(600f, 300f)
        val small = scale()
        assertTrue("原大时捏拢该缩小：$small", small < 0.8f)
        pinch(600f, 100f)
        assertEquals("最小缩到一半", "缩小到 0.5 倍", zoom())

        compose.onNodeWithContentDescription("图片", useUnmergedTree = true).performTouchInput {
            doubleClick()
        }
        compose.waitForIdle()
        assertEquals("双击回到原大", "原大", zoom())
    }

    /** 捏回到离原大很近的地方松手，落回正好原大。 */
    @Test
    fun releasingNearFitSnapsToFit() {
        open()
        pinch(600f, 300f)
        val small = scale()
        pinch(300f, 300f / small * 0.98f)
        assertEquals("松手时离原大不到 5% 该落回原大（缩放前 $small）", "原大", zoom())
    }
}
