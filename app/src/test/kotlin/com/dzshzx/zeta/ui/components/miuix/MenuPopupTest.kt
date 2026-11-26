package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults

/**
 * 挂在元素上的玻璃菜单放在哪：元素比菜单窄时和它水平居中，宽时和它右边对齐。借截图测试的 Robolectric 环境，不出图。弹出动画这里验不了： Robolectric
 * 截下的玻璃弹层打开、收起都是一帧之内整个变过去，要在真机上看。
 */
class MenuPopupTest : ShotTest() {
    /** 打开挂在 [width] 宽、屏幕中间的元素上的菜单，返回元素和菜单项的位置。 */
    private fun openMenu(width: Dp, alignEnd: Boolean = false): Pair<DpRect, DpRect> {
        var show by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                Scaffold {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 200.dp)) {
                            Box(Modifier.size(width = width, height = 36.dp).testTag("anchor"))
                            AnchoredMenu(
                                show = show,
                                entries = listOf(MenuEntry(listOf(MenuItem("复制", onClick = {})))),
                                onDismiss = { show = false },
                                alignEnd = alignEnd,
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        show = true
        settle(1000)
        return compose.onNodeWithTag("anchor").getBoundsInRoot() to
            compose.onNodeWithText("复制").getBoundsInRoot()
    }

    @Test
    fun centeredOnNarrowAnchor() {
        val (anchor, menu) = openMenu(120.dp)
        assertEquals((anchor.left + anchor.right).value / 2, (menu.left + menu.right).value / 2, 1f)
    }

    @Test
    fun rightAlignedOnWideAnchor() {
        val (anchor, menu) = openMenu(320.dp)
        // 菜单项的边界比面板每边窄一个按下底色的内缩
        assertEquals((anchor.right - GlassPopupDefaults.ItemPressInset).value, menu.right.value, 1f)
    }

    /** 行尾的图标按钮（alignEnd）：再窄也和它右边对齐，向左展开。 */
    @Test
    fun rightAlignedWhenAskedOnNarrowAnchor() {
        val (anchor, menu) = openMenu(36.dp, alignEnd = true)
        // 右边贴着按钮（差不过一个按下底色的内缩），整个菜单在按钮左边展开
        assertEquals(
            anchor.right.value,
            menu.right.value,
            GlassPopupDefaults.ItemPressInset.value + 1f,
        )
        assertTrue(menu.left < anchor.left - 100.dp)
    }
}
