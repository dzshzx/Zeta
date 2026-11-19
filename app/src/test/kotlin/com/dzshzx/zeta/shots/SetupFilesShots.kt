package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.ui.setup.LinuxFilesScreen
import org.junit.Test

/** Linux 文件浏览。列目录要 root，这里显示成列不出来的样子。 */
class SetupFilesShots : ShotTest() {
    @Test
    fun browser() {
        val hub = Fixtures.readyHub(app)
        shoot("linux-files") { LinuxFilesScreen(hub = hub, onBack = {}) }
    }

    /** 右上角“更多”菜单。 */
    @Test
    fun browserMenu() {
        val hub = Fixtures.readyHub(app)
        shoot("linux-files-menu", prepare = { onNodeWithContentDescription("更多").performClick() }) {
            LinuxFilesScreen(hub = hub, onBack = {})
        }
    }
}
