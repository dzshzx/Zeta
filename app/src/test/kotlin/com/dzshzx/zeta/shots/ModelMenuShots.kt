package com.dzshzx.zeta.shots

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.settings.DefaultEffortScreen
import com.dzshzx.zeta.ui.settings.SettingsScreen
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型菜单只直接列各系列最新的，旧版本在「更多模型」二级菜单里；清单是 Claude Code 2.1.329 实际报的那 14 项。 */
class ModelMenuShots : ShotTest() {
    private fun hub(): ConversationHub = Fixtures.readyHub(app).also { hub ->
        val all = EffortLevels.map { it.first }
        fun option(value: String, name: String, resolved: String = value) = ModelOption(value, name, "", resolved, all)
        hub.models = listOf(
            option("default", "Default (recommended)", "claude-opus-5-5"),
            option("opus", "Opus 5.5", "claude-opus-5-5"),
            option("fable", "Fable 5.2", "claude-fable-5-2"),
            option("sonnet", "Sonnet 5.5", "claude-sonnet-5-5"),
            option("haiku", "Haiku 5.5", "claude-haiku-5-5"),
            option("claude-fable-5-1", "Fable 5.1"),
            option("claude-haiku-4-5-20251001", "Haiku 4.5"),
            option("claude-sonnet-5", "Sonnet 5"),
            option("claude-opus-5", "Opus 5"),
            option("claude-fable-5", "Fable 5"),
            option("claude-opus-4-8", "Opus 4.8"),
            option("claude-opus-4-7", "Opus 4.7"),
            option("claude-opus-4-6", "Opus 4.6"),
            option("claude-sonnet-4-6", "Sonnet 4.6"),
        )
    }

    private fun AndroidComposeTestRule<*, *>.tap(text: String) {
        onNodeWithText(text).performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    /** 顶栏的模型菜单：默认和四个系列最新的，最后是「更多模型」。 */
    @Test
    fun chatMenu() {
        val hub = hub()
        shoot("model-menu", prepare = { tap("Claude · 默认") }) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    /** 点开「更多模型」：旧版本按系列、从新到旧；这个对话选的是 Opus 4.8，「更多模型」下面写着它。 */
    @Test
    fun chatMenuOlder() {
        val hub = hub()
        hub.current.chooseModel("claude-opus-4-8")
        shoot("model-menu-older", prepare = { tap("Claude · Opus 4.8"); tap("更多模型") }) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    /** 在「更多模型」里点一个旧版本：这个对话换成它，菜单收起。 */
    @Test
    fun choosingAnOlderModel() {
        val hub = hub()
        compose.setContent { ZetaTheme(windowBackdrop = false) { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) } }
        compose.tap("Claude · 默认")
        compose.tap("更多模型")
        compose.tap("Opus 4.7")
        assertEquals("claude-opus-4-7", hub.current.model)
        assertTrue("顶栏换成 Opus 4.7", runCatching { compose.onNodeWithText("Claude · Opus 4.7").fetchSemanticsNode() }.isSuccess)
        assertTrue("菜单收起了", compose.onAllNodesWithText("Opus 4.6").fetchSemanticsNodes().isEmpty())
    }

    /** 设置首页的默认模型：同样两层。 */
    @Test
    fun settingsDefaultModel() {
        val hub = hub()
        hub.updateClaudeDefaults(ClaudeDefaults(model = null, permissionMode = null, efforts = emptyMap(), effort = null))
        shoot("model-settings-older", prepare = { tap("默认模型"); tap("更多模型") }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {})
        }
    }

    /** 默认思考强度：各系列最新的和设过的旧版本（Opus 4.6）直接列出，其余旧版本收在「更多模型」一行后面。 */
    @Test
    fun defaultEffortFolded() {
        val hub = hub()
        hub.updateClaudeDefaults(ClaudeDefaults(model = null, permissionMode = null, efforts = mapOf("claude-opus-4-6" to "high"), effort = null))
        shoot("model-default-effort") { DefaultEffortScreen(hub = hub, onBack = {}) }
    }
}
