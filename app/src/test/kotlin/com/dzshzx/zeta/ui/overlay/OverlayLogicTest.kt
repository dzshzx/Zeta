package com.dzshzx.zeta.ui.overlay

import com.dzshzx.zeta.phone.OverlayPhase
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLogicTest {
    @Test
    fun phaseSeparatesPauseFromTakeover() {
        assertEquals(
            OverlayPhase.RUNNING,
            OverlayPhase.of(finishing = false, controlling = false, paused = false),
        )
        assertEquals(
            OverlayPhase.PAUSED,
            OverlayPhase.of(finishing = false, controlling = false, paused = true),
        )
        assertEquals(
            OverlayPhase.CONTROLLING,
            OverlayPhase.of(finishing = false, controlling = true, paused = false),
        )
        // 用户在动手时说“接管”，不说“暂停”
        assertEquals(
            OverlayPhase.CONTROLLING,
            OverlayPhase.of(finishing = false, controlling = true, paused = true),
        )
        assertEquals(
            OverlayPhase.FINISHED,
            OverlayPhase.of(finishing = true, controlling = true, paused = true),
        )
        assertTrue(OverlayPhase.PAUSED.held && OverlayPhase.CONTROLLING.held)
        assertFalse(OverlayPhase.RUNNING.held || OverlayPhase.FINISHED.held)
    }

    @Test
    fun pauseAndTakeoverReadDifferently() {
        assertEquals("已暂停", phaseLabel(OverlayPhase.PAUSED))
        assertEquals("你在接管", phaseLabel(OverlayPhase.CONTROLLING))
        assertEquals("已暂停，等你继续", stageModeLabel(OverlayPhase.PAUSED))
        assertEquals("手动接管中", stageModeLabel(OverlayPhase.CONTROLLING))
        assertEquals("后台画布", stageModeLabel(OverlayPhase.RUNNING))
        for (colors in listOf(lightColorScheme(), darkColorScheme())) {
            val accents = OverlayPhase.entries.map { phaseAccent(it, colors) }
            assertEquals("四个阶段的颜色各不相同", accents.size, accents.toSet().size)
        }
    }

    @Test
    fun portraitStageKeepsEtaSize() {
        // 1220×2656 @520dpi：375×817dp，状态栏下沿约 40dp
        val canvas = CapsuleLayout.stageCanvas(1220f / 2656f, 817f, 40f)
        assertEquals(336f, canvas.height, 0.01f)
        assertEquals(336f * 1220f / 2656f, canvas.width, 0.01f)
        assertEquals(524f, CapsuleLayout.stageWindowHeight(canvas), 0.01f)
    }

    @Test
    fun landscapeStageFitsOnScreen() {
        // 横屏只有 375dp 高：画布收小，窗口下沿不出屏幕，比例不变
        val ratio = 1220f / 2656f
        val canvas = CapsuleLayout.stageCanvas(ratio, 375f, 30f)
        assertTrue(canvas.height < 336f)
        assertTrue(30f + CapsuleLayout.stageWindowHeight(canvas) <= 375f)
        assertEquals(ratio, canvas.width / canvas.height, 0.001f)
    }

    @Test
    fun landscapeAgentDisplayIsWidthBound() {
        // 后台屏幕是横的：按内宽算高，不再被压成竖的
        val ratio = 2656f / 1220f
        val canvas = CapsuleLayout.stageCanvas(ratio, 817f, 40f)
        assertEquals(CapsuleLayout.STAGE_CANVAS_MAX_WIDTH_DP, canvas.width, 0.01f)
        assertEquals(ratio, canvas.width / canvas.height, 0.001f)
    }

    @Test
    fun tinyScreenKeepsMinimumCanvas() {
        val canvas = CapsuleLayout.stageCanvas(0.5f, 200f, 30f)
        assertEquals(CapsuleLayout.STAGE_CANVAS_MIN_HEIGHT_DP, canvas.height, 0.01f)
        // 比例读不到时按常见手机屏
        val fallback = CapsuleLayout.stageCanvas(Float.NaN, 817f, 40f)
        assertEquals(CapsuleLayout.DEFAULT_RATIO, fallback.width / fallback.height, 0.001f)
    }

    @Test
    fun lockHidesOnlyTheRealMainScreen() {
        assertTrue(screenLocked(interactive = false, keyguardLocked = false))
        assertTrue(screenLocked(interactive = true, keyguardLocked = true))
        assertFalse(screenLocked(interactive = true, keyguardLocked = false))
        assertTrue(overlayHiddenByLock(displayId = 0, locked = true))
        // 调试时悬浮层在测试屏上，和手机锁没锁无关
        assertFalse(overlayHiddenByLock(displayId = 7, locked = true))
        assertFalse(overlayHiddenByLock(displayId = 0, locked = false))
    }
}
