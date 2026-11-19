package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.ui.SettingsScreen
import org.junit.Test

class SettingsShots : ShotTest() {
    @Test
    fun settingsHome() {
        val hub = Fixtures.readyHub(app)
        shoot("settings") { SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onOpenPhone = {}) }
    }

    /** 设置首页慢慢往下滑一段（不带惯性）：顶栏折叠，能力、账号、上下文与扩展。 */
    @Test
    fun settingsMiddle() {
        val hub = Fixtures.readyHub(app)
        // 两个列表存在进程级的单例里，别的截图测试先跑会留下规则：清空，入口的说明才每次一样
        ScheduleStore.update(app) { emptyList() }
        NotifyRuleStore.update(app) { emptyList() }
        shoot("settings-middle", prepare = { onRoot().performTouchInput { swipeUp(durationMillis = 1500) } }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onOpenPhone = {})
        }
    }

    /** 设置首页最下面：环境、备份与恢复、诊断、关于。 */
    @Test
    fun settingsBottom() {
        val hub = Fixtures.readyHub(app)
        shoot("settings-bottom", prepare = { repeat(6) { onRoot().performTouchInput { swipeUp() } } }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onOpenPhone = {})
        }
    }
}
