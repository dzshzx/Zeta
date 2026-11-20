package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对话发出的控制请求，各自的应答交给各自的接收方：initialize 给模型、命令清单和起进程时的模式，get_context_usage、get_settings
 * 给用量，get_usage 给额度。只认请求号：形状一样、号对不上的应答什么也不改。
 * 进程是真起的，只是单元测试的 su 总是失败（同 QueuedMessagesTest），应答由测试扮 Claude Code 送进来。
 * Claude Code 那一侧（每种请求都按请求号答）由 FakeClaudeContractTest 钉住，应答里的各个字段由各自的契约测试钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ControlResponsesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun started(): Pair<ConversationHub, Conversation> {
        val hub = Fixtures.readyHub(app)
        val chat = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        chat.prepare()
        return hub to chat
    }

    @Test
    fun initializeAnswerFillsModelsCommandsAndMode() {
        val (hub, chat) = started()
        // 形状一样、号不对的不算
        chat.receive(
            """{"type":"control_response","response":{"subtype":"success","request_id":"zeta-1","response":""" +
                """{"models":[{"value":"bogus","displayName":"x"}],"current_permission_mode":"plan"}}}""",
        )
        assertEquals(emptyList<ModelOption>(), hub.models)
        assertNull(chat.permissionMode)

        chat.answer(
            "initialize",
            """{"models":[{"value":"opus","displayName":"Opus","description":"d"}],""" +
                """"commands":[{"name":"compact","description":"c","argumentHint":""}],"current_permission_mode":"acceptEdits"}""",
        )
        assertEquals(listOf(ModelOption("opus", "Opus", "d")), hub.models)
        assertEquals(listOf("compact"), hub.commands.map { it.name })
        // 起进程时没指定模式：报来的就是 Claude Code 照设置定的，这个对话从此用它
        assertEquals("acceptEdits", chat.permissionMode)
        assertEquals("acceptEdits", chat.processMode)
    }

    @Test
    fun usageAnswersReachTheMeter() {
        val (_, chat) = started()
        chat.answer("get_context_usage", """{"totalTokens":12410,"maxTokens":400000,"model":"claude-opus-5-5"}""")
        assertEquals(400_000L, chat.usage.contextWindow)
        assertEquals(12410L, chat.usage.contextTokens)
        chat.answer("get_settings", """{"effective":{},"applied":{"model":"claude-opus-5-5","effort":"xhigh"}}""")
        assertEquals("xhigh", chat.usage.appliedEffort)
    }

    @Test
    fun quotaAnswerReachesTheQuotaService() {
        val (hub, chat) = started()
        hub.quota.forget()
        // 新起的进程顺便问一次额度
        chat.answer(
            "get_usage",
            """{"subscription_type":"max","rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":42.0,"resets_at":null}}}""",
        )
        assertEquals("max", hub.quota.snapshot?.subscription)
    }
}
