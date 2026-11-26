package com.dzshzx.zeta.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.overlay.CapsuleControls
import com.dzshzx.zeta.ui.overlay.CapsuleState
import com.dzshzx.zeta.ui.overlay.StatusCapsule
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

class CapsuleShots : ShotTest() {
    /**
     * 顶部状态胶囊：上面是收起的（运行中，带思考尾句），下面是展开的（暂停，补充 / 继续 / 停止）。 没有后台屏幕，所以没有右侧的微缩画面和展开后的画面（那些见
     * [agentDisplayStage]）。
     */
    @Test
    fun statusCapsule() {
        Fixtures.readyHub(app)
        val collapsed =
            CapsuleState().apply {
                focus =
                    FakeFocus(OverlayPhase.RUNNING, step = "在后台操作相册", thought = "先找到截图文件夹，再按月份归档")
                shown = true
            }
        val expanded =
            CapsuleState().apply {
                focus = FakeFocus(OverlayPhase.PAUSED, step = "已暂停", thought = "")
                shown = true
                this.expanded = true
            }
        shootCapsules("status-capsule", collapsed, expanded)
    }

    /**
     * 后台屏幕在用时：收起的胶囊右侧镶微缩画面（运行中），展开的是画廊式的画面加下面那一行（接管中：紫色描边、“继续”高亮）。 后台屏幕要 root
     * 和虚拟屏，这里用一块不接进程的假屏，画面是手画的一帧。
     */
    @Test
    fun agentDisplayStage() {
        agentDisplayStage("status-capsule-stage")
    }

    /** 英文界面的后台屏幕胶囊：接管、全屏等按钮的译文放不放得下（每个用例新建 Application，语言随之回到简体中文）。 */
    @Test
    fun agentDisplayStageEnglish() {
        UiLanguage.current = UiLanguage.ENGLISH
        agentDisplayStage("status-capsule-stage-en")
    }

    private fun agentDisplayStage(name: String) {
        Fixtures.readyHub(app)
        val screen = fakeScreen()
        val collapsed =
            CapsuleState().apply {
                focus =
                    FakeFocus(
                        OverlayPhase.RUNNING,
                        step = "在后台操作相册",
                        thought = "先找到截图文件夹",
                        screen = screen,
                    )
                shown = true
            }
        val expanded =
            CapsuleState().apply {
                focus =
                    FakeFocus(
                        OverlayPhase.CONTROLLING,
                        step = "你在接管",
                        thought = "",
                        screen = screen,
                    )
                shown = true
                this.expanded = true
            }
        shootCapsules(name, collapsed, expanded)
    }

    /**
     * 工作站上的 Claude Code 经外部入口操作手机（BridgeFocus）：收起的第二行写谁在操作、在哪个应用；展开后只有暂停、停止，
     * 没有“补充”，右上角也没有可点开的对话标题（外部客户端不在 Zeta 里）。
     */
    @Test
    fun bridgeClient() {
        Fixtures.readyHub(app)
        val screen = fakeScreen()
        fun bridge() =
            FakeFocus(
                OverlayPhase.RUNNING,
                step = "点按「截图」",
                thought = "电脑在操作 · 相册",
                screen = screen,
                title = "电脑在操作",
                inZeta = false,
            )
        val collapsed =
            CapsuleState().apply {
                focus = bridge()
                shown = true
            }
        val expanded =
            CapsuleState().apply {
                focus = bridge()
                shown = true
                this.expanded = true
            }
        shootCapsules("status-capsule-bridge", collapsed, expanded)
        compose.onNodeWithContentDescription("补充").assertDoesNotExist()
        compose.onNodeWithText("电脑在操作").assertDoesNotExist()
        compose.onNodeWithContentDescription("暂停").assertExists()
        compose.onNodeWithContentDescription("停止").assertExists()
    }

    /** Zeta 里的对话展开后有“补充”和可点开的对话标题（和 [bridgeClient] 对照）。 */
    @Test
    fun conversationHasSupplementAndTitle() {
        Fixtures.readyHub(app)
        val expanded =
            CapsuleState().apply {
                focus = FakeFocus(OverlayPhase.RUNNING, step = "在后台操作相册", thought = "")
                shown = true
                this.expanded = true
            }
        compose.setContent {
            ZetaTheme(windowBackdrop = false) { StatusCapsule(expanded, NoControls) }
        }
        compose.onNodeWithContentDescription("补充").assertExists()
        compose.onNodeWithText("整理相册里的截图").assertExists()
    }

    /** 展开后点了“补充”：输入框和取消 / 发送。 */
    @Test
    fun supplementInput() {
        Fixtures.readyHub(app)
        val input =
            CapsuleState().apply {
                focus = FakeFocus(OverlayPhase.RUNNING, step = "在后台操作相册", thought = "先找到截图文件夹")
                shown = true
                expanded = true
                inputMode = true
            }
        shootCapsules("status-capsule-input", input)
    }

    private fun shootCapsules(name: String, vararg states: CapsuleState) {
        shoot(name, target = hasTestTag("capsules")) {
            Column(
                modifier =
                    Modifier.testTag("capsules")
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surface)
                        .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (state in states) StatusCapsule(state, NoControls)
            }
        }
    }

    /**
     * 不接 root 辅助进程的后台屏幕。画面放在“休眠前那一帧”里：没人看时屏幕会清掉 [VirtualScreen.frames]，
     * 每次有人登记观看（换配色重新组合时）它都会把这一帧再推出来。
     */
    private fun fakeScreen(): VirtualScreen {
        val width = 1220
        val height = 2656
        val constructor =
            VirtualScreen::class.java.declaredConstructors.single { it.parameterCount == 7 }
        constructor.isAccessible = true
        val worker = HandlerThread("fake-screen").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val screen =
            constructor.newInstance(
                0,
                width,
                height,
                reader,
                worker,
                Handler(worker.looper),
                IdleProcess(),
            ) as VirtualScreen
        val frame = VirtualScreen.Frame(fakeApp(width, height), width, height, 1)
        VirtualScreen::class
            .java
            .getDeclaredField("snapshot")
            .apply { isAccessible = true }
            .set(screen, frame)
        val frames =
            VirtualScreen::class.java.getDeclaredField("_frames").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (frames.get(screen) as MutableStateFlow<VirtualScreen.Frame?>).value = frame
        return screen
    }

    /** 一帧像相册应用的画面：顶栏和几行缩略图。 */
    private fun fakeApp(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFF2F3F5.toInt())
        paint.color = 0xFF2F6FED.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), 420f, paint)
        val tiles =
            intArrayOf(
                0xFFFFB74D.toInt(),
                0xFF81C784.toInt(),
                0xFF64B5F6.toInt(),
                0xFFE57373.toInt(),
                0xFFBA68C8.toInt(),
                0xFF4DB6AC.toInt(),
            )
        val size = (width - 80f) / 3f
        for (i in 0 until 12) {
            val col = i % 3
            val row = i / 3
            paint.color = tiles[i % tiles.size]
            val left = 20f + col * (size + 20f)
            val top = 480f + row * (size + 20f)
            canvas.drawRoundRect(RectF(left, top, left + size, top + size), 36f, 36f, paint)
        }
        return bitmap
    }

    private class IdleProcess : Process() {
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun exitValue(): Int = 0

        override fun destroy() = Unit
    }

    private class FakeFocus(
        override val phase: OverlayPhase,
        override val step: String,
        override val thought: String,
        override val screen: VirtualScreen? = null,
        override val title: String = "整理相册里的截图",
        override val inZeta: Boolean = true,
    ) : OverlayFocus {
        override val appName = "相册"
        override val actingOnMain = false
        override val userControlling = phase == OverlayPhase.CONTROLLING
        override val touches: SharedFlow<TouchMark> = MutableSharedFlow()

        override fun takeOver() = Unit

        override fun resumeAuto() = Unit

        override fun pause() = Unit

        override fun resume() = Unit

        override fun stop() = Unit

        override fun supplement(text: String) = Unit

        override fun userTap(x: Float, y: Float, longPress: Boolean) = Unit

        override fun userSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Int,
        ) = Unit

        override fun handoffToMain() = Unit

        override fun openInZeta() = Unit
    }

    private object NoControls : CapsuleControls {
        override fun toggle() = Unit

        override fun collapse() = Unit

        override fun onCollapsedSettled() = Unit

        override fun onExitSettled() = Unit

        override fun setInputMode(enabled: Boolean) = Unit

        override fun reportContentHeight(px: Int) = Unit

        override fun onScreenChanged() = Unit

        override fun takeOver() = Unit

        override fun fullscreen(stage: Rect, frame: VirtualScreen.Frame?) = Unit

        override fun openInZeta() = Unit
    }
}
