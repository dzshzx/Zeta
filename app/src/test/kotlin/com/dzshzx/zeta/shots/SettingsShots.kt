package com.dzshzx.zeta.shots

import com.dzshzx.zeta.ui.SettingsScreen
import org.junit.Test

class SettingsShots : ShotTest() {
    @Test
    fun settingsHome() {
        val hub = Fixtures.readyHub(app)
        shoot("settings") { SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onOpenPhone = {}) }
    }
}
