package com.dzshzx.zeta.shots

import com.dzshzx.zeta.ui.settings.PermissionScreen
import org.junit.Test

/** 权限健康页：JVM 上没有 root、无障碍和通知使用权，各项显示成没给的样子。 */
class PermissionShots : ShotTest() {
    @Test
    fun page() {
        val hub = Fixtures.readyHub(app)
        shoot("permissions") { PermissionScreen(hub = hub, onBack = {}, onOpenLinux = {}) }
    }
}
