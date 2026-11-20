package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
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
 * 接着已有会话时模型交给 Claude Code：不带 --model，它照会话记录恢复（行为由 ClaudeResumeContractTest 钉住），
 * 界面显示它报的模型；用户在这个对话里亲手选了才指定。Claude Code 不恢复思考强度，Zeta 照会话记录恢复。都不动新对话的默认值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun hub(model: String? = null, effort: String? = null): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.model = model
        hub.settings.effort = effort
        hub.reloadSettings()
        return hub
    }

    /** 已有会话的对话对象；不给记录文件，就不去载入历史。 */
    private fun reopened(hub: ConversationHub) = Conversation(hub, "/workspace/claude", "s-old", "旧对话", historyFile = null)

    private fun Conversation.modelArgument(): String? = launchArguments().let { args -> args.indexOf("--model").takeIf { it >= 0 }?.let { args[it + 1] } }

    /** 喂一条 Claude Code 的输出事件（和进程送来的一样走 handle）。 */
    private fun Conversation.receive(event: String) {
        Conversation::class.java.getDeclaredMethod("handle", JsonObject::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(this, Json.parseToJsonElement(event) as JsonObject, 0L)
    }

    @Test
    fun newConversationUsesTheDefaultForNewConversations() {
        val hub = hub(model = "opus")
        val conversation = hub.current
        assertEquals("opus", conversation.model)
        assertEquals("opus", conversation.modelArgument())
        assertFalse(conversation.modelFromSession)
        // 新对话选默认：不指定，Claude Code 照它自己的设置
        conversation.receive("""{"type":"system","subtype":"init","session_id":"s-new","model":"claude-opus-5-5"}""")
        conversation.chooseModel("default")
        assertNull(conversation.model)
        assertNull(conversation.modelArgument())
        assertNull(hub.settings.model)
        // 顶栏写「默认」，不留上一轮 init 报的模型
        assertEquals("默认", modelLabel(conversation.shownModel, hub.modelOptions))
    }

    @Test
    fun reopenedConversationLeavesTheModelToClaudeCode() {
        val hub = hub(model = "opus", effort = "low")
        val conversation = reopened(hub)
        assertNull(conversation.model)
        assertTrue(conversation.modelFromSession)
        assertTrue("--resume" in conversation.launchArguments())
        assertNull(conversation.modelArgument())
        // Claude Code 起来后报的是它照会话记录恢复的那个
        conversation.receive("""{"type":"system","subtype":"init","session_id":"s-old","model":"claude-sonnet-5-5"}""")
        assertEquals("claude-sonnet-5-5", conversation.shownModel)
        assertEquals("Sonnet 5.5", modelLabel(conversation.shownModel, emptyList()))
        // 思考强度 Claude Code 不恢复：照会话记录
        conversation.restoreEffort("max")
        assertEquals("max", conversation.effort)
        assertEquals("max", conversation.launchArguments().let { it[it.indexOf("--effort") + 1] })
        conversation.restoreEffort("ultra")
        assertEquals("max", conversation.effort)
        // 新对话的默认值不动
        assertEquals("opus", hub.settings.model)
        assertEquals("low", hub.settings.effort)
    }

    /** 用户在这个对话里亲手选了（或定时任务、数字助理定了）才指定；选默认也要明说，不然 Claude Code 会恢复原来的。 */
    @Test
    fun aChoiceInThisConversationIsPassedOn() {
        val hub = hub(model = "opus")
        val chosen = reopened(hub)
        chosen.chooseModel("fable")
        assertEquals("fable", chosen.modelArgument())
        assertEquals("fable", chosen.shownModel)
        assertFalse(chosen.modelFromSession)
        assertEquals("fable", hub.settings.model)
        chosen.chooseModel("default")
        assertEquals("default", chosen.modelArgument())
        assertEquals("默认", modelLabel(chosen.shownModel, emptyList()))
        assertNull(hub.settings.model)

        val preset = reopened(hub)
        preset.preset(model = "haiku", effort = null)
        assertEquals("haiku", preset.modelArgument())
        val follow = reopened(hub)
        follow.preset(model = null, effort = "high")
        assertNull(follow.modelArgument())
    }

    /** 分支：--fork-session 复制出的会话同样由 Claude Code 接着用原来的模型；先显示原对话显示的，思考强度接着用原对话的。 */
    @Test
    fun branchLeavesTheModelToClaudeCodeAndKeepsEffort() {
        val hub = hub(model = "opus", effort = "high")
        val source = reopened(hub)
        // 原对话显示着 Claude Code 报来的模型（真报来时它在回答，不能分支，这里直接摆上）
        source.force("reportedModel", "claude-fable-5-2")
        source.restoreEffort("max")
        hub.force("current", source)
        hub.showChat("旧对话", listOf(UserItem("u1", "你好", uuid = "uuid-1"), AnswerItem("a1", "你好！", streaming = false)))
        hub.branch("a1")
        val branch = hub.current
        assertTrue(branch !== source)
        assertNull(branch.sessionId)
        assertNull(branch.model)
        assertTrue(branch.modelFromSession)
        assertNull(branch.modelArgument())
        assertTrue("--fork-session" in branch.launchArguments())
        assertEquals("claude-fable-5-2", branch.shownModel)
        assertEquals("max", branch.effort)
        assertEquals("opus", hub.settings.model)
        assertEquals("high", hub.settings.effort)
    }

    @Test
    fun modelIdsReadLikeTheirNames() {
        assertEquals("Opus 5.5", readableModelId("claude-opus-5-5"))
        assertEquals("Haiku 4.5", readableModelId("claude-haiku-4-5-20251001"))
        assertEquals("Opus 5.5 (1M)", readableModelId("claude-opus-5-5[1m]"))
        assertEquals("gpt-6-astra", readableModelId("gpt-6-astra"))
        val options = listOf(ModelOption("default", "Default (recommended)", ""), ModelOption("fable", "Fable", ""))
        assertEquals("Fable", modelLabel("fable", options))
        assertEquals("默认", modelLabel(null, options))
        assertEquals("默认", modelLabel("default", options))
        assertEquals("sonnet", modelLabel("sonnet", options))
    }
}
