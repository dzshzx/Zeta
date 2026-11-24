package com.dzshzx.zeta.core

import com.dzshzx.zeta.i18n.CoreStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中转的 accounts.* 请求和结果（docs/core.md「账号与额度」）：读 relays、发出去的参数，以及编辑页草稿在客户端先做的那遍校验。 */
class AccountsCoreRelayTest {
    /** 记下发出去的请求，按方法答 [answers] 里的结果。 */
    private class Recorder(private val answers: Map<String, String> = emptyMap()) {
        val sent = mutableListOf<Pair<String, JsonObject>>()

        val core =
            AccountsCore(
                HelperProgram({ ByteArray(0) }, "zeta", HelperProgram.CLAUDE_SWITCH),
                call = { method, params, _ ->
                    sent += method to params
                    answers[method]?.let { Json.parseToJsonElement(it) }
                },
                shell = { error("不该装 claude-switch") },
            )
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test
    fun listReadsRelaysAndTheCurrentOne() {
        val state =
            AccountsCore.parse(
                Json.parseToJsonElement(
                    // 旧核心还带着的 auth 照样读得过，不用它
                    """{"current":null,"accounts":[{"name":"claude","email":"a@example.com"}],
                    "relays":[{"name":"gw","baseUrl":"https://relay.example.com","model":"claude-opus-5-5","auth":"bearer",
                    "hasKey":true,"keyEditable":true,"current":true},
                    {"name":"own","baseUrl":"http://127.0.0.1:8317","model":"","auth":"","hasKey":true,"keyEditable":false,"current":false},
                    {"name":""}]}""",
                ),
            )
        assertFalse(state.loggedIn)
        assertEquals(listOf("gw", "own"), state.relays.map { it.name })
        val gw = state.relay!!
        assertEquals("gw", gw.name)
        assertEquals("claude-opus-5-5", gw.model)
        assertFalse(state.relays[1].keyEditable)
        assertEquals(setOf("claude", "gw", "own"), state.names)
        // 旧核心没有 relays：照样读，没有中转
        val old = AccountsCore.parse(Json.parseToJsonElement("""{"current":null,"accounts":[]}"""))
        assertNull(old.relay)
    }

    @Test
    fun relayRequestsCarryTheDraftAndProgram() {
        val rec =
            Recorder(
                mapOf(
                    "accounts.relay_models" to
                        """{"models":[{"id":"claude-opus-5-5","displayName":"Claude Opus 5.5"},{"id":"glm-5"},{"id":""}]}""",
                ),
            )
        val draft =
            RelayDraft(
                name = "gw",
                baseUrl = " https://relay.example.com ",
                key = "sk-test-relay-0001",
                model = "claude-opus-5-5",
            )
        rec.core.saveRelay(draft, create = true)
        val models = rec.core.relayModels(draft.copy(key = ""))
        rec.core.removeRelay("gw")
        rec.core.switch("gw")

        assertEquals(
            listOf(
                "accounts.relay_save",
                "accounts.relay_models",
                "accounts.relay_remove",
                "accounts.switch",
            ),
            rec.sent.map { it.first },
        )
        val save = rec.sent[0].second
        assertEquals("gw", save.text("name"))
        assertEquals("https://relay.example.com", save.text("baseUrl"))
        assertEquals("sk-test-relay-0001", save.text("key"))
        assertEquals("claude-opus-5-5", save.text("model"))
        // 不发鉴权方式：核心拉模型列表时两个鉴权头一起带
        assertFalse(rec.sent.any { "auth" in it.second })
        assertEquals(JsonPrimitive(true), save["create"])
        // 每个 accounts.* 都带应用里那份 claude-switch 的路径
        assertTrue(
            rec.sent.all {
                it.second.text("program")?.startsWith(".cache/zeta/bin/claude-switch-") == true
            },
        )
        // 拉模型列表：密钥留空（核心用存着的那份），不带 model、create
        val list = rec.sent[1].second
        assertEquals("", list.text("key"))
        assertNull(list["model"])
        assertNull(list["create"])
        assertEquals(listOf("claude-opus-5-5", "glm-5"), models.map { it.id })
        assertEquals(listOf("Claude Opus 5.5", "glm-5"), models.map { it.title })
        assertEquals("gw", rec.sent[2].second.text("name"))
        assertEquals("gw", rec.sent[3].second.text("name"))
    }

    @Test
    fun draftChecksBeforeAskingTheCore() {
        val taken = setOf("claude", "gw")
        val ok = RelayDraft("new-relay", "https://relay.example.com/api", "sk-test-0002")
        assertNull(ok.problem(create = true, taken = taken))
        assertEquals(CoreStrings.errRelayName(), ok.copy(name = "Bad Name").problem(true, taken))
        assertEquals(CoreStrings.errRelayName(), ok.copy(name = "claude").problem(true, emptySet()))
        assertEquals(CoreStrings.errRelayNameTaken(), ok.copy(name = "gw").problem(true, taken))
        assertEquals(
            CoreStrings.errRelayURL(),
            ok.copy(baseUrl = "relay.example.com").problem(true, taken),
        )
        assertEquals(CoreStrings.errRelayURL(), ok.copy(baseUrl = "ftp://x").problem(true, taken))
        assertEquals(
            CoreStrings.errRelayURL(),
            ok.copy(baseUrl = "https://u:p@relay.example.com").problem(true, taken),
        )
        assertEquals(CoreStrings.errRelayKeyRequired(), ok.copy(key = "").problem(true, taken))
        assertEquals(CoreStrings.errRelayKey(), ok.copy(key = "sk test").problem(true, taken))
        assertEquals(CoreStrings.errRelayModel(), ok.copy(model = "a b").problem(true, taken))
        // 修改：名字已在列表里、密钥留空都可以
        val edit =
            RelayDraft.of(ClaudeRelay("gw", "https://relay.example.com", "", true, true, false))
        assertEquals("", edit.key)
        assertNull(edit.problem(create = false, taken = taken))
        // 密钥框禁用时不看密钥
        assertNull(
            edit.copy(key = "x y").problem(create = false, taken = taken, keyEditable = false),
        )
    }
}
