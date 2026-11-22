package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.conv.ConvMode
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.settings.effortRows
import com.dzshzx.zeta.ui.settings.effortSummary
import com.dzshzx.zeta.ui.settings.hostDefaultsSummary
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
 * 新对话的默认值是各台主机上 Claude Code 自己的设置（[ConversationHub.claudeDefaults]）：新对话打开时不带模型、思考强度和权限模式；
 * 对话里选的（conv.model、conv.effort、conv.mode）只管那个对话，不改别的对话，也不改默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ClaudeDefaultsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun conversation(hub: ConversationHub) =
        Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)

    @Test
    fun newConversationLeavesDefaultsToClaudeCode() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults("opus", "auto", mapOf("claude-opus-5-5" to "xhigh"), null),
        )
        val chat = conversation(hub)
        // 还没选过：输入栏显示 Claude Code 的默认权限模式
        assertNull(chat.permissionMode)
        assertEquals("auto", chat.shownPermissionMode)
        chat.send("你好")
        val open = core.next("conv.open")
        assertNull(open.params["model"])
        assertNull(open.params["effort"])
        assertNull(open.params["mode"])
        assertEquals("你好", core.next("conv.send").str("text"))
    }

    @Test
    fun choicesStayInTheirConversation() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val defaults = ClaudeDefaults("opus", "auto", emptyMap(), null)
        hub.updateClaudeDefaults(Backends.LOCAL, defaults)
        val chat = conversation(hub)
        chat.chooseModel("fable")
        chat.chooseEffort("low")
        chat.choosePermissionMode("plan")
        val opened = core.next("conv.open").conv
        val model = core.next("conv.model")
        assertEquals(opened, model.conv)
        assertEquals("fable", model.str("value"))
        assertEquals("low", core.next("conv.effort").str("value"))
        assertEquals("plan", core.next("conv.mode").str("value"))
        chat.core { it.copy(mode = ConvMode(user = "plan")) }
        assertEquals("plan", chat.shownPermissionMode)
        // 别的对话、默认值都不动
        val other = conversation(hub)
        assertNull(other.model)
        assertNull(other.effort)
        assertEquals("auto", other.shownPermissionMode)
        assertEquals(defaults, hub.claudeDefaults(Backends.LOCAL))
        assertEquals("auto", hub.defaultPermissionMode(Backends.LOCAL))
        // 在终端里删掉了默认模式：回到逐项确认
        hub.updateClaudeDefaults(Backends.LOCAL, defaults.copy(permissionMode = null))
        assertEquals("default", other.shownPermissionMode)
    }

    /** 每台主机的默认值各是各的（存在各自的 settings.json）：远程主机上的对话显示那台的默认权限模式，不受手机的影响； 删掉这台主机时它的默认值一起丢掉。 */
    @Test
    fun defaultsArePerHost() {
        val hub = Fixtures.readyHub(app)
        val remote = Fixtures.workstation
        hub.updateRemoteHosts(listOf(remote))
        hub.updateClaudeDefaults(Backends.LOCAL, ClaudeDefaults("opus", "auto", emptyMap(), null))
        hub.updateClaudeDefaults(remote.id, ClaudeDefaults("sonnet", "plan", emptyMap(), null))
        val local = conversation(hub)
        val there =
            Conversation(
                hub,
                remote.defaultCwd,
                sessionId = null,
                title = "",
                historyFile = null,
                backend = remote.id,
            )
        assertEquals("auto", local.shownPermissionMode)
        assertEquals("plan", there.shownPermissionMode)
        assertEquals("sonnet", hub.claudeDefaults(remote.id)?.model)
        hub.updateRemoteHosts(emptyList())
        assertNull(hub.claudeDefaults(remote.id))
        assertEquals("opus", hub.claudeDefaults(Backends.LOCAL)?.model)
    }

    /** 设置首页一台主机右边的值：默认模型（别名按这台的清单换成完整 ID）和它的思考强度，没单独设时用共用的那一档。 */
    @Test
    fun hostSummaryShowsModelAndItsEffort() {
        val models = Fixtures.models
        val efforts = mapOf("claude-opus-5-5" to "xhigh")
        assertEquals(
            "Opus 5.5 · 超高",
            hostDefaultsSummary(ClaudeDefaults("opus", null, efforts, null), models),
        )
        assertEquals(
            "Opus 5.5 · 超高",
            hostDefaultsSummary(ClaudeDefaults(null, null, efforts, null), models),
        )
        assertEquals(
            "Sonnet 5.5 · 中",
            hostDefaultsSummary(ClaudeDefaults("sonnet", null, efforts, "medium"), models),
        )
        assertEquals(
            "Sonnet 5.5",
            hostDefaultsSummary(ClaudeDefaults("sonnet", null, efforts, null), models),
        )
        // 还没拿到这台的清单：别名找不到完整 ID，只写模型
        assertEquals(
            "Opus",
            hostDefaultsSummary(ClaudeDefaults("opus", null, efforts, null), FallbackModels),
        )
        assertEquals(
            "默认模型",
            hostDefaultsSummary(ClaudeDefaults(null, null, efforts, null), FallbackModels),
        )
        assertEquals(
            "Opus 5.5 · 超高",
            hostDefaultsSummary(
                ClaudeDefaults("claude-opus-5-5", null, efforts, null),
                FallbackModels,
            ),
        )
    }

    @Test
    fun effortSummaryReadsPerModel() {
        assertEquals(
            "没有设，按 Claude Code 的默认",
            effortSummary(ClaudeDefaults(null, null, emptyMap(), null)),
        )
        assertEquals("高", effortSummary(ClaudeDefaults(null, null, emptyMap(), "high")))
        assertEquals(
            "Opus 5.5 超高，Fable 5.2 高",
            effortSummary(
                ClaudeDefaults(
                    null,
                    null,
                    mapOf("claude-opus-5-5" to "xhigh", "claude-fable-5-2" to "high"),
                    null,
                ),
            ),
        )
        assertEquals(
            "Opus 5.5 超高，其他模型 中",
            effortSummary(
                ClaudeDefaults(null, null, mapOf("claude-opus-5-5" to "xhigh"), "medium"),
            ),
        )
    }

    /**
     * 默认思考强度页：清单里的模型按完整 ID 各一行（「默认」和 Opus 是同一个，只列一次），档位只列支持的，不支持也没设过的不列； settings.json
     * 里有、清单里没有的旧模型接在后面；顶层设过时最后是「其他模型」。
     */
    @Test
    fun effortRowsFollowTheModelList() {
        val all = EffortLevels.map { it.first }
        val models =
            listOf(
                ModelOption("default", "Default", "", "claude-opus-5-5", all),
                ModelOption("opus", "Opus", "", "claude-opus-5-5", all),
                ModelOption("fable", "Fable", "", "claude-fable-5-2", listOf("low", "high")),
                ModelOption("lite", "Lite", "", "claude-lite-1", emptyList()),
                ModelOption("custom", "Custom", "", null, all),
            )
        val defaults =
            ClaudeDefaults(
                null,
                null,
                mapOf("claude-fable-5-1" to "high", "claude-opus-5-5" to "xhigh"),
                "medium",
            )
        val rows = effortRows(models, defaults)
        assertEquals(
            listOf("claude-opus-5-5", "claude-fable-5-2", "claude-fable-5-1", null),
            rows.map { it.model },
        )
        assertEquals(listOf("Opus 5.5", "Fable 5.2", "Fable 5.1", "其他模型"), rows.map { it.title })
        assertEquals(listOf("xhigh", null, "high", "medium"), rows.map { it.value })
        assertEquals(listOf("low" to "低", "high" to "高"), rows[1].choices)
        // 还没拿到清单：只列设过的
        assertEquals(
            listOf("claude-fable-5-1", "claude-opus-5-5"),
            effortRows(emptyList(), defaults.copy(effort = null)).map { it.model },
        )
    }

    /** initialize 应答里每个模型的完整 ID 和能选的思考强度；不支持思考强度的能选的是空的。 */
    @Test
    fun initializeModelsCarryIdAndEffortLevels() {
        fun parse(text: String) = modelOption(Json.parseToJsonElement(text) as JsonObject)
        assertEquals(
            ModelOption("opus", "Opus", "Opus 5.5", "claude-opus-5-5", listOf("low", "high")),
            parse(
                """{"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus","description":"Opus 5.5","supportsEffort":true,"supportedEffortLevels":["low","high"]}""",
            ),
        )
        assertEquals(
            emptyList<String>(),
            parse("""{"value":"lite","resolvedModel":"claude-lite-1","supportsEffort":false}""")
                ?.effortLevels,
        )
        assertEquals(ModelOption("x", "", ""), parse("""{"value":"x"}"""))
        assertNull(parse("""{"displayName":"没有 value"}"""))
    }

    /** 导入备份（[Settings.restore]）后各项按导入的值重读：盖在设置首页上的备份页关掉后，首页显示的是导入的值。 */
    @Test
    fun restoreRefreshesObservedSettings() {
        val hub = Fixtures.readyHub(app)
        assertTrue(
            hub.settings.restore(
                mapOf(
                    "autoTitle" to false,
                    "autoCompact" to false,
                    "autoCompactPercent" to 70,
                    "cwd" to "/workspace/other",
                ),
            ),
        )
        assertFalse(hub.settings.autoTitle)
        assertFalse(hub.settings.autoCompact)
        assertEquals(70, hub.settings.autoCompactPercent)
        assertEquals("/workspace/other", hub.settings.env?.defaultCwd)
        // 空着的新对话换到导入的默认工作目录
        hub.renewBlank()
        assertEquals("/workspace/other", hub.current.cwd)
    }
}
