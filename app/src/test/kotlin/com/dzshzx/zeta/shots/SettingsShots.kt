package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.ui.settings.SettingsScreen
import com.dzshzx.zeta.ui.settings.SettingsBackupScreen
import com.dzshzx.zeta.ui.settings.DefaultEffortScreen
import org.junit.Test

class SettingsShots : ShotTest() {
    @Test
    fun settingsHome() {
        val hub = Fixtures.readyHub(app)
        hub.updateClaudeDefaults(ClaudeDefaults(model = null, permissionMode = "auto", efforts = mapOf("claude-opus-5-5" to "xhigh"), effort = null))
        shoot("settings") { SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}) }
    }

    /** 设置首页慢慢往下滑一段（不带惯性）：顶栏折叠，上下文与扩展、手机与工具。 */
    @Test
    fun settingsMiddle() {
        val hub = Fixtures.readyHub(app)
        // 两个列表存在进程级的单例里，别的截图测试先跑会留下规则：清空，入口的说明才每次一样
        ScheduleStore.update(app) { emptyList() }
        NotifyRuleStore.update(app) { emptyList() }
        shoot("settings-middle", prepare = { onRoot().performTouchInput { swipeUp(durationMillis = 1500) } }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {})
        }
    }

    /** 设置首页最下面：通用、权限、关于。 */
    @Test
    fun settingsBottom() {
        val hub = Fixtures.readyHub(app)
        shoot("settings-bottom", prepare = { repeat(6) { onRoot().performTouchInput { swipeUp() } } }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {})
        }
    }

    /** 设置 › 默认思考强度：Claude Code 清单里的模型各一行，Opus 设过、Fable 没设（跟随），还有一个清单里没有的旧模型。 */
    @Test
    fun defaultEffort() {
        val hub = Fixtures.readyHub(app)
        val all = EffortLevels.map { it.first }
        hub.models = mapOf(Backends.LOCAL to listOf(
            ModelOption("default", "Default (recommended)", "", "claude-opus-5-5", all),
            ModelOption("opus", "Opus", "", "claude-opus-5-5", all),
            ModelOption("fable", "Fable", "", "claude-fable-5-2", all),
            ModelOption("sonnet", "Sonnet", "", "claude-sonnet-5-5", all),
            ModelOption("haiku", "Haiku", "", "claude-haiku-5-5", all),
        ))
        hub.updateClaudeDefaults(ClaudeDefaults(model = "opus", permissionMode = null, efforts = mapOf("claude-opus-5-5" to "xhigh", "claude-fable-5-1" to "high"), effort = null))
        shoot("settings-default-effort") { DefaultEffortScreen(hub = hub, onBack = {}) }
    }

    /** 设置 › 备份与恢复。 */
    @Test
    fun backup() {
        val hub = Fixtures.readyHub(app)
        shoot("settings-backup") { SettingsBackupScreen(hub = hub, onBack = {}) }
    }
}
