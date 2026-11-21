package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.ui.settings.AppearanceScreen
import org.junit.Test

class AppearanceShots : ShotTest() {
    @Test
    fun appearance() {
        val hub = Fixtures.readyHub(app)
        shoot("appearance") { AppearanceScreen(hub = hub, onBack = {}) }
    }

    @Test
    fun scaleDialog() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "appearance-scale-dialog",
            prepare = { onAllNodesWithText("界面缩放").onFirst().performClick() },
        ) {
            AppearanceScreen(hub = hub, onBack = {})
        }
    }
}
