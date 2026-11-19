package com.dzshzx.zeta.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.overlay.CapsuleControls
import com.dzshzx.zeta.ui.overlay.CapsuleState
import com.dzshzx.zeta.ui.overlay.StatusCapsule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.junit.Test
import top.yukonga.miuix.kmp.theme.MiuixTheme

class CapsuleShots : ShotTest() {
    /**
     * 顶部状态胶囊：上面是收起的（运行中，带思考尾句），下面是展开的（暂停，补充 / 继续 / 停止）。
     * 后台屏幕要 root 和虚拟屏，这里没有，所以不画右侧的微缩画面和展开后的画面。
     */
    @Test
    fun statusCapsule() {
        Fixtures.readyHub(app)
        val collapsed = CapsuleState().apply {
            focus = FakeFocus(OverlayPhase.RUNNING, step = "在后台操作相册", thought = "先找到截图文件夹，再按月份归档")
            shown = true
        }
        val expanded = CapsuleState().apply {
            focus = FakeFocus(OverlayPhase.PAUSED, step = "已暂停", thought = "")
            shown = true
            this.expanded = true
        }
        shoot("status-capsule", target = hasTestTag("capsules")) {
            Column(
                modifier = Modifier.testTag("capsules").fillMaxWidth().background(MiuixTheme.colorScheme.background).padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusCapsule(collapsed, NoControls)
                StatusCapsule(expanded, NoControls)
            }
        }
    }

    private class FakeFocus(override val phase: OverlayPhase, override val step: String, override val thought: String) : OverlayFocus {
        override val title = "整理相册里的截图"
        override val screen: VirtualScreen? = null
        override val appName = "相册"
        override val actingOnMain = false
        override val userControlling = false
        override val touches: SharedFlow<TouchMark> = MutableSharedFlow()
        override fun takeOver() = Unit
        override fun resumeAuto() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun supplement(text: String) = Unit
        override fun userTap(x: Float, y: Float, longPress: Boolean) = Unit
        override fun userSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Int) = Unit
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
