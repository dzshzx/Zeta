package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.phone.tools.ScheduleTools
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每条消息按发的人的权限模式跑：后台运行的模式只管它发的那几条，用户自己发下一条时切回用户的；用户的设置不被后台运行改。
 * 这里不起进程（没配 Linux 环境），只看切到了哪个模式、起进程时带什么；Claude Code 那一侧由 UnattendedRulesContractTest 钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RunPermissionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val rules = ScheduleTools.unattendedRules

    private fun hub(mode: String?): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.permissionMode = mode
        hub.settings.env = null
        hub.reloadSettings()
        return hub
    }

    private fun conversation(hub: ConversationHub) = Conversation(hub, "/workspace/claude", sessionId = null, title = "任务", historyFile = null)

    private fun Conversation.modeArgument(): String? =
        launchArguments().let { args -> args.indexOf("--permission-mode").takeIf { it >= 0 }?.let { args[it + 1] } }

    private fun Conversation.receive(event: String) {
        Conversation::class.java.getDeclaredMethod("handle", JsonObject::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(this, Json.parseToJsonElement(event) as JsonObject, 0L)
    }

    @Test
    fun runModeLastsUntilTheUserSends() {
        val hub = hub("acceptEdits")
        val chat = conversation(hub)
        chat.sendUnattended("跑一次", "bypassPermissions", rules)
        assertEquals("bypassPermissions", chat.permissionMode)
        assertEquals("bypassPermissions", chat.modeArgument())
        // 接着又一次后台运行：用户的模式还记着
        chat.sendUnattended("再跑一次", "auto", rules)
        assertEquals("auto", chat.permissionMode)
        chat.send("我自己问")
        assertEquals("acceptEdits", chat.permissionMode)
        assertEquals("acceptEdits", chat.modeArgument())
        assertEquals("acceptEdits", hub.settings.permissionMode)
    }

    @Test
    fun choosingDuringARunKeepsTheChoice() {
        val hub = hub("acceptEdits")
        val chat = conversation(hub)
        chat.sendUnattended("跑一次", "bypassPermissions", rules)
        chat.choosePermissionMode("plan")
        chat.send("我自己问")
        assertEquals("plan", chat.permissionMode)
        assertEquals("plan", hub.settings.permissionMode)
    }

    @Test
    fun unknownUserModeFallsBackToAsking() {
        val hub = hub(null)
        val chat = conversation(hub)
        assertNull(chat.permissionMode)
        assertNull(chat.modeArgument())
        chat.sendUnattended("跑一次", "auto", rules)
        // 起进程时指定了模式，init 报的是指定的那个，不当成 Claude Code 自己设置里的默认
        chat.receive("""{"type":"system","subtype":"init","session_id":"s1","permissionMode":"auto"}""")
        assertNull(hub.defaultPermissionMode)
        chat.send("我自己问")
        assertEquals("default", chat.permissionMode)
        // 没指定时报的才是 Claude Code 自己的默认
        val fresh = conversation(hub)
        fresh.receive("""{"type":"system","subtype":"init","session_id":"s2","permissionMode":"acceptEdits"}""")
        assertEquals("acceptEdits", fresh.permissionMode)
        assertEquals("acceptEdits", hub.defaultPermissionMode)
    }

    @Test
    fun launchAllowsSwitchingIntoBypassLater() {
        val chat = conversation(hub("default"))
        assertTrue("--allow-dangerously-skip-permissions" in chat.launchArguments())
        assertFalse("--dangerously-skip-permissions" in chat.launchArguments())
    }
}
