package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
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
 * 界面显示它报的模型；用户在这个对话里亲手选了才指定。Claude Code 不恢复思考强度，Zeta 照会话记录恢复。 新对话的默认值是 Claude Code
 * 自己的设置，这里都不动（[ClaudeDefaultsTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun hub(): ConversationHub = Fixtures.readyHub(app)

    /** 已有会话的对话对象；不给记录文件，就不去载入历史。 */
    private fun reopened(hub: ConversationHub) =
        Conversation(hub, "/workspace/claude", "s-old", "旧对话", historyFile = null)

    private fun Conversation.modelArgument(): String? = launchArgument("--model")

    @Test
    fun newConversationLeavesTheModelToClaudeCodeSettings() {
        val hub = hub()
        val conversation = hub.current
        assertNull(conversation.model)
        assertNull(conversation.modelArgument())
        assertFalse(conversation.modelFromSession)
        conversation.chooseModel("opus")
        assertEquals("opus", conversation.modelArgument())
        // 新对话选默认：不指定，Claude Code 照它自己的设置
        conversation.receive(
            """{"type":"system","subtype":"init","session_id":"s-new","model":"claude-opus-5-5"}""",
        )
        conversation.chooseModel("default")
        assertNull(conversation.model)
        assertNull(conversation.modelArgument())
        // 不留上一轮 init 报的模型。顶栏写具体的模型：清单还没到时说不出默认是哪个，才写「默认」
        assertNull(conversation.shownModel)
        assertEquals("默认", conversation.shownModelName(hub.modelOptions(conversation.backend)))
        val options =
            listOf(
                ModelOption("default", "Default (recommended)", "", "claude-opus-5-5"),
                ModelOption("fable", "Fable 5.2", "", "claude-fable-5-2"),
            )
        assertEquals("Opus 5.5", conversation.shownModelName(options))
        // 这台主机的 Claude Code 设置里另设了默认模型：新对话用的是它
        hub.updateClaudeDefaults(
            conversation.backend,
            ClaudeDefaults(
                model = "fable",
                permissionMode = null,
                efforts = emptyMap(),
                effort = null,
            ),
        )
        assertEquals("Fable 5.2", conversation.shownModelName(options))
    }

    /**
     * 终端里 /model 换了模型、之后还没有回答的会话（比如刚从终端接管过来）：Claude Code 自己不认这次切换，Zeta 起进程时指定它，
     * 顶栏先写它；显示名认不出是哪个模型的不指定。用户在这个对话里另选了就照用户选的。
     */
    @Test
    fun aModelSwitchedInTheTerminalIsCarriedOver() {
        val hub = hub()
        val taken = reopened(hub)
        taken.restoreModel(answered = "claude-opus-5-5", switched = "Fable 5.2")
        assertEquals("claude-fable-5-2", taken.modelArgument())
        assertEquals("Fable 5.2", taken.shownModelName(emptyList()))
        // 没指定过模型：菜单不勾任何一项
        assertNull(taken.model)
        taken.chooseModel("opus")
        assertEquals("opus", taken.modelArgument())

        // 一个回答都没有、也没换过：Claude Code 用默认模型，不指定
        val bare = reopened(hub)
        bare.restoreModel(answered = null, switched = null)
        assertNull(bare.modelArgument())
        // 认不出的显示名：不指定，照 Claude Code 自己恢复的
        val foreign = reopened(hub)
        foreign.restoreModel(answered = "claude-opus-5-5", switched = "GPT-6-Astra · Codex")
        assertNull(foreign.modelArgument())
        assertEquals("Opus 5.5", foreign.shownModelName(emptyList()))
    }

    @Test
    fun reopenedConversationLeavesTheModelToClaudeCode() {
        val hub = hub()
        val conversation = reopened(hub)
        assertNull(conversation.model)
        assertTrue(conversation.modelFromSession)
        assertTrue("--resume" in conversation.launchArguments())
        assertNull(conversation.modelArgument())
        // Claude Code 起来后报的是它照会话记录恢复的那个
        conversation.receive(
            """{"type":"system","subtype":"init","session_id":"s-old","model":"claude-sonnet-5-5"}""",
        )
        assertEquals("claude-sonnet-5-5", conversation.shownModel)
        assertEquals("Sonnet 5.5", conversation.shownModelName(emptyList()))
        // 思考强度 Claude Code 不恢复：照会话记录
        conversation.restoreEffort("max")
        assertEquals("max", conversation.effort)
        assertEquals("max", conversation.launchArgument("--effort"))
        conversation.restoreEffort("ultra")
        assertEquals("max", conversation.effort)
    }

    /** 用户在这个对话里亲手选了（或定时任务、数字助理定了）才指定；选默认也要明说，不然 Claude Code 会恢复原来的。 */
    @Test
    fun aChoiceInThisConversationIsPassedOn() {
        val hub = hub()
        val chosen = reopened(hub)
        chosen.chooseModel("fable")
        assertEquals("fable", chosen.modelArgument())
        assertEquals("fable", chosen.shownModel)
        assertFalse(chosen.modelFromSession)
        chosen.chooseModel("default")
        assertEquals("default", chosen.modelArgument())
        assertEquals("默认", chosen.shownModelName(emptyList()))

        val preset = reopened(hub)
        preset.preset(model = "haiku", effort = null)
        assertEquals("haiku", preset.modelArgument())
        val follow = reopened(hub)
        follow.preset(model = null, effort = "high")
        assertNull(follow.modelArgument())
    }

    /** 分支：--fork-session 复制出的会话同样由 Claude Code 接着用原来的模型；先显示原对话显示的，思考强度、权限模式接着用原对话的。 */
    @Test
    fun branchLeavesTheModelToClaudeCodeAndKeepsEffort() {
        val hub = hub()
        val source = reopened(hub)
        // 原对话显示着 Claude Code 报来的模型（真报来时它在回答，不能分支，这里直接摆上）
        source.force("reportedModel", "claude-fable-5-2")
        source.restoreEffort("max")
        source.choosePermissionMode("plan")
        hub.force("currentState", source)
        hub.showChat(
            "旧对话",
            listOf(
                UserItem("u1", "你好", uuid = "uuid-1"),
                AnswerItem("a1", "你好！", streaming = false),
            ),
        )
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
        // 权限模式和思考强度一样接着用原对话的
        assertEquals("plan", branch.permissionMode)
        assertEquals("plan", branch.launchArgument("--permission-mode"))
    }

    @Test
    fun modelIdsReadLikeTheirNames() {
        assertEquals("Opus 5.5", readableModelId("claude-opus-5-5"))
        assertEquals("Haiku 4.5", readableModelId("claude-haiku-4-5-20251001"))
        assertEquals("Opus 5.5 (1M)", readableModelId("claude-opus-5-5[1m]"))
        assertEquals("gpt-6-astra", readableModelId("gpt-6-astra"))
        val options =
            listOf(
                ModelOption("default", "Default (recommended)", ""),
                ModelOption("fable", "Fable", ""),
            )
        assertEquals("Fable", modelLabel("fable", options))
        assertEquals("默认", modelLabel(null, options))
        assertEquals("默认", modelLabel("default", options))
        assertEquals("sonnet", modelLabel("sonnet", options))
        // 清单报了默认指向哪个：菜单里写明
        val resolved =
            listOf(ModelOption("default", "Default (recommended)", "", "claude-opus-5-5"))
        assertEquals("默认（Opus 5.5）", modelLabel(null, resolved))
        assertEquals("Opus 5.5", modelName("default", resolved))
        assertNull(modelName("default", options))
        assertEquals("Fable", modelName("fable", options))
        // 终端里 /model 打出来的显示名换回模型 ID
        assertEquals("claude-fable-5-2", modelIdOfName("Fable 5.2"))
        assertEquals("claude-opus-5", modelIdOfName("Opus 5"))
        assertNull(modelIdOfName("GPT-6-Astra · Codex"))
        assertNull(modelIdOfName("Opus 5.5 (1M)"))
    }
}
