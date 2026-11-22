package com.dzshzx.zeta.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.ui.chat.AnswerBlock
import com.dzshzx.zeta.ui.chat.answerImages
import com.dzshzx.zeta.ui.markdown.StreamingMarkdownStateCache
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.Test

/** 一张纯色、中间写着 [label] 的 PNG，写成回答里能直接引用的 data: 地址（截图测试里没有 Linux 环境可取文件）。 */
internal fun testPicture(width: Int, height: Int, color: Int, label: String): String {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val paint =
        Paint().apply {
            this.color = android.graphics.Color.WHITE
            textSize = minOf(width, height) / 3f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    Canvas(bitmap).apply {
        drawColor(color)
        drawText(label, width / 2f, height / 2f + paint.textSize / 3, paint)
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())
}

/** 三张比例不同的图（横、竖、方）连在一起的一段回答。 */
internal fun galleryAnswer(): String =
    "截了三张图：\n\n" +
        "![首页](${testPicture(1600, 900, 0xFF3D7BF7.toInt(), "1")})\n" +
        "![设置](${testPicture(600, 1400, 0xFFF08A24.toInt(), "2")})\n" +
        "![关于](${testPicture(1000, 1000, 0xFF2EAD6B.toInt(), "3")})\n\n" +
        "第一张是首页，第二张是设置页。"

/** 等回答里的图取出来（在后台线程上解码）：每轮时钟走一帧，5 秒内等不到就失败。 */
internal fun AndroidComposeTestRule<*, *>.awaitImages() =
    awaitMain(5_000) {
        mainClock.advanceTimeByFrame()
        waitForIdle()
        onAllNodesWithContentDescription("图片").fetchSemanticsNodes().isNotEmpty()
    }

/** 回答里连着的几张图合成的图片组：展开（默认）、收起、翻到第二张、点开全屏看。 */
class ImageGalleryShots : ShotTest() {
    @Composable
    private fun Answer(text: String) {
        Scaffold(containerColor = COUITheme.colorScheme.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                AnswerBlock(
                    AnswerItem("a1", text, streaming = false),
                    cache = remember { StreamingMarkdownStateCache() },
                    imagesOf = { answerImages(it, "/workspace", "/home/zeta") },
                )
            }
        }
    }

    @Test
    fun expanded() {
        Fixtures.readyHub(app)
        val text = galleryAnswer()
        shoot("chat-image-gallery", prepare = { awaitImages() }) { Answer(text) }
    }

    @Test
    fun collapsed() {
        Fixtures.readyHub(app)
        val text = galleryAnswer()
        shoot(
            "chat-image-gallery-collapsed",
            prepare = {
                awaitImages()
                tap("3 张图片")
            },
        ) {
            Answer(text)
        }
    }

    @Test
    fun secondPage() {
        Fixtures.readyHub(app)
        val text = galleryAnswer()
        shoot(
            "chat-image-gallery-second",
            prepare = {
                awaitImages()
                onAllNodesWithContentDescription("图片").onFirst().performTouchInput { swipeLeft() }
                mainClock.advanceTimeBy(1_000)
                awaitMain(5_000) {
                    mainClock.advanceTimeByFrame()
                    onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
                }
            },
        ) {
            Answer(text)
        }
    }

    /** 翻到第二张再点开：全屏看图带着顶栏（返回、2 / 3）、底部的圆点页码和保存到相册、分享两个按钮。 */
    @Test
    fun viewer() {
        Fixtures.readyHub(app)
        val text = galleryAnswer()
        shoot("chat-image-viewer", prepare = { openSecondInViewer() }) { Answer(text) }
    }

    /** 全屏看图时点一下图：顶栏、页码和保存、分享一起收起，只剩图。 */
    @Test
    fun viewerImmersive() {
        Fixtures.readyHub(app)
        val text = galleryAnswer()
        shoot(
            "chat-image-viewer-immersive",
            prepare = {
                openSecondInViewer()
                onAllNodesWithContentDescription("图片").onLast().performClick()
                awaitMain(5_000) {
                    mainClock.advanceTimeByFrame()
                    onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithText("分享").fetchSemanticsNodes().isEmpty()
                }
            },
        ) {
            Answer(text)
        }
    }

    /** 在图片组里翻到第二张，点开全屏看，等顶栏的返回钮出来、保存和分享拿到这张图的字节可以点。 */
    private fun AndroidComposeTestRule<*, *>.openSecondInViewer() {
        awaitImages()
        onAllNodesWithContentDescription("图片").onFirst().performTouchInput { swipeLeft() }
        mainClock.advanceTimeBy(1_000)
        awaitMain(5_000) {
            mainClock.advanceTimeByFrame()
            onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithContentDescription("图片").onLast().performClick()
        awaitMain(5_000) {
            mainClock.advanceTimeByFrame()
            onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty() &&
                listOf("保存到相册", "分享").all {
                    onAllNodes(hasText(it) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
        }
    }
}
