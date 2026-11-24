package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 套餐的显示名：订阅类型只分 pro、max、team、enterprise，Max 20x 改名 Ultra 以后 Ultra 的号仍是 max，只有 Claude Code 照服务器写的
 * 展示名（claude-switch 的 `plan`）分得开。核心给了展示名就用它，没有时才由订阅类型写成。
 */
class PlanNameTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun accountShowsThePlanNameTheCoreGives() {
        val state =
            AccountsCore.parse(
                Json.parseToJsonElement(
                    """{"current":{"name":"claude","email":"a@example.com","subscription":"max","plan":"Ultra","current":true},
                    "accounts":[{"name":"claude","email":"a@example.com","subscription":"max","plan":"Ultra","current":true},
                    {"name":"claude-b","email":"b@example.com","subscription":"max","plan":"Max"},
                    {"name":"claude-c","email":"c@example.com","subscription":"pro"},
                    {"name":"claude-d","email":"d@example.com","subscription":"enterprise"},
                    {"name":"claude-e","email":"e@example.com"}]}""",
                ),
            )
        assertEquals("Ultra", state.current!!.plan)
        // 订阅类型照旧：给仍按它判断的地方用
        assertEquals("max", state.current!!.subscription)
        assertEquals(
            listOf("Ultra", "Max", "Pro", "Enterprise", ""),
            state.all.map { it.plan },
        )
    }

    @Test
    fun quotaShowsThePlanNameTheCoreGives() {
        fun snapshot(extra: String) =
            Quota.fromCore(obj("""{"windows":[],$extra"updatedAt":"2026-11-18T07:00:00Z"}"""))!!
        assertEquals("Ultra", snapshot(""""subscription":"max","plan":"Ultra",""").planLabel)
        // 旧快照没有展示名：由订阅类型写成
        assertEquals("Max", snapshot(""""subscription":"max",""").planLabel)
        assertEquals("Pro", snapshot(""""subscription":"pro","plan":"",""").planLabel)
        // 都没有（API key 登录）：不显示套餐
        assertNull(snapshot("").planLabel)
    }

    @Test
    fun loginStatusUsesThePlanNameOfTheListedAccount() {
        val status = obj("""{"loggedIn":true,"email":"a@example.com","subscriptionType":"max"}""")
        val ultra =
            ClaudeAccount("claude", "a@example.com", "", "", "max", null, planName = "Ultra")
        assertEquals("Ultra", AccountStatus.fromAuthStatus(status, ultra).plan)
        // 核心没列出账号（或它没有展示名）：把 auth status 的订阅类型写成显示名，不再露出小写的 max
        assertEquals("Max", AccountStatus.fromAuthStatus(status, null).plan)
        val bare = ClaudeAccount("claude", "a@example.com", "", "", "max", null)
        assertEquals("Max", AccountStatus.fromAuthStatus(status, bare).plan)
        // 没有订阅类型（API key 登录）：没有套餐
        val apiKey = obj("""{"loggedIn":true,"email":null}""")
        assertNull(AccountStatus.fromAuthStatus(apiKey, null).plan)
        // 没登录：不用别人的套餐
        val out = obj("""{"loggedIn":false}""")
        assertNull(AccountStatus.fromAuthStatus(out, ultra).plan)
    }
}
