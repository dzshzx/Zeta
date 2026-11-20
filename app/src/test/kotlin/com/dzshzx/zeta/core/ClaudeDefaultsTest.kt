package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.settings.effortSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新对话的默认值是 Claude Code 自己的设置（[ConversationHub.claudeDefaults]）：新对话起进程时不带模型、思考强度和权限模式；
 * 对话里选的只管那个对话，不改别的对话，也不改默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ClaudeDefaultsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun conversation(hub: ConversationHub) = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)

    @Test
    fun newConversationLeavesDefaultsToClaudeCode() {
        val hub = Fixtures.readyHub(app)
        hub.updateClaudeDefaults(ClaudeDefaults("opus", "auto", mapOf("claude-opus-5-5" to "xhigh"), null))
        val chat = conversation(hub)
        assertNull(chat.launchArgument("--model"))
        assertNull(chat.launchArgument("--effort"))
        assertNull(chat.launchArgument("--permission-mode"))
        // 还没选过：输入栏显示 Claude Code 的默认权限模式
        assertNull(chat.permissionMode)
        assertEquals("auto", chat.shownPermissionMode)
    }

    @Test
    fun choicesStayInTheirConversation() {
        val hub = Fixtures.readyHub(app)
        val defaults = ClaudeDefaults("opus", "auto", emptyMap(), null)
        hub.updateClaudeDefaults(defaults)
        val chat = conversation(hub)
        chat.chooseModel("fable")
        chat.chooseEffort("low")
        chat.choosePermissionMode("plan")
        assertEquals("fable", chat.launchArgument("--model"))
        assertEquals("low", chat.launchArgument("--effort"))
        assertEquals("plan", chat.launchArgument("--permission-mode"))
        assertEquals("plan", chat.shownPermissionMode)
        // 别的对话、默认值都不动
        val other = conversation(hub)
        assertNull(other.launchArgument("--model"))
        assertNull(other.launchArgument("--effort"))
        assertEquals("auto", other.shownPermissionMode)
        assertEquals(defaults, hub.claudeDefaults)
        assertEquals("auto", hub.defaultPermissionMode)
        // 在终端里删掉了默认模式：回到逐项确认
        hub.updateClaudeDefaults(defaults.copy(permissionMode = null))
        assertEquals("default", other.shownPermissionMode)
    }

    @Test
    fun effortSummaryReadsPerModel() {
        assertEquals("没有设，按 Claude Code 的默认", effortSummary(ClaudeDefaults(null, null, emptyMap(), null)))
        assertEquals("高", effortSummary(ClaudeDefaults(null, null, emptyMap(), "high")))
        assertEquals("Opus 5.5 超高，Fable 5.2 高", effortSummary(ClaudeDefaults(null, null, mapOf("claude-opus-5-5" to "xhigh", "claude-fable-5-2" to "high"), null)))
        assertEquals("Opus 5.5 超高，其他模型 中", effortSummary(ClaudeDefaults(null, null, mapOf("claude-opus-5-5" to "xhigh"), "medium")))
    }

    /** 导入备份（[Settings.restore]）后各项按导入的值重读：盖在设置首页上的备份页关掉后，首页显示的是导入的值。 */
    @Test
    fun restoreRefreshesObservedSettings() {
        val hub = Fixtures.readyHub(app)
        assertTrue(hub.settings.restore(mapOf("autoTitle" to false, "autoCompact" to false, "autoCompactPercent" to 70, "cwd" to "/workspace/other")))
        assertFalse(hub.settings.autoTitle)
        assertFalse(hub.settings.autoCompact)
        assertEquals(70, hub.settings.autoCompactPercent)
        assertEquals("/workspace/other", hub.env?.defaultCwd)
        // 空着的新对话换到导入的默认工作目录
        hub.settingsRestored()
        assertEquals("/workspace/other", hub.current.cwd)
    }
}
