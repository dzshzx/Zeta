package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import com.dzshzx.zeta.ui.settings.PermissionHealthScreen
import org.junit.Test

/** 权限健康页：JVM 上没有 root、无障碍和通知使用权，各项显示成没给的样子。一屏放不下，另画一张滚到底的。 */
class PermissionShots : ShotTest() {
    @Test
    fun page() {
        val hub = Fixtures.readyHub(app)
        shoot("permissions") {
            PermissionHealthScreen(hub = hub, onBack = {}, onOpenLinux = {})
        }
    }

    @Test
    fun bottom() {
        val hub = Fixtures.readyHub(app)
        // 列表里是权限一组、一键授予一组、页尾留白；跳到页尾，不用滚动动画
        shoot(
            "permissions-bottom",
            prepare = { onNode(hasScrollToIndexAction()).performScrollToIndex(2) },
        ) {
            PermissionHealthScreen(hub = hub, onBack = {}, onOpenLinux = {})
        }
    }
}
