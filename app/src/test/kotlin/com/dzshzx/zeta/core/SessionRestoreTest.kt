package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 重新打开旧对话恢复它上次的模型和思考强度，分支接着用原对话的；都不动新对话的默认值。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val options = listOf(
        ModelOption("default", "Default (recommended)", "", "claude-opus-5-5"),
        ModelOption("opus", "Opus", "", "claude-opus-5-5"),
        ModelOption("fable", "Fable", "", "claude-fable-5-2"),
    )

    private fun hub(model: String? = null, effort: String? = null): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.model = model
        hub.settings.effort = effort
        hub.reloadSettings()
        hub.updateModels(options)
        return hub
    }

    /** 已有会话的对话对象；不给记录文件，就不去载入历史，由测试直接喂“载入完”的结果。 */
    private fun reopened(hub: ConversationHub) = Conversation(hub, "/workspace/claude", "s-old", "旧对话", historyFile = null)

    @Test
    fun reopenedConversationTakesLastAnswer() {
        val hub = hub(model = null, effort = "low")
        val conversation = reopened(hub)
        conversation.restoreChoice("claude-fable-5-2", "max")
        assertEquals("fable", conversation.model)
        assertEquals("max", conversation.effort)
        // 新对话的默认值不动
        assertNull(hub.settings.model)
        assertEquals("low", hub.settings.effort)
        assertNull(hub.defaultModel)
        assertTrue(conversation.items.none { it is NoticeItem })
    }

    @Test
    fun noRecordKeepsDefaults() {
        val hub = hub(model = "opus", effort = "high")
        val conversation = reopened(hub)
        conversation.restoreChoice(null, null)
        assertEquals("opus", conversation.model)
        assertEquals("high", conversation.effort)
    }

    /** 载入期间用户已经选了，或者定时任务、数字助理定了：历史不盖掉。 */
    @Test
    fun earlierChoicesWin() {
        val hub = hub(model = "opus", effort = "high")
        val chosen = reopened(hub)
        chosen.chooseModel("opus")
        chosen.chooseEffort("medium")
        chosen.restoreChoice("claude-fable-5-2", "max")
        assertEquals("opus", chosen.model)
        assertEquals("medium", chosen.effort)

        val preset = reopened(hub)
        preset.preset(model = "fable", effort = null, permissionMode = null)
        preset.restoreChoice("claude-opus-5-5", "low")
        assertEquals("fable", preset.model)
        // 定时任务只定了模型，思考强度照记录恢复
        assertEquals("low", preset.effort)
    }

    /** 记录里的模型现在选不到：临时用默认模型，对话末尾提示一句，不当成出错。 */
    @Test
    fun unavailableModelFallsBackWithNotice() {
        val hub = hub(model = "fable")
        val conversation = reopened(hub)
        conversation.restoreChoice("claude-opus-4-8", "high")
        assertEquals("fable", conversation.model)
        assertEquals("high", conversation.effort)
        val notice = conversation.items.filterIsInstance<NoticeItem>().single()
        assertEquals(false, notice.error)
        assertEquals("这个对话上次用的 claude-opus-4-8 现在选不到了，先改用Fable", notice.text)
    }

    /** 分支出的新对话接着用原对话当时的模型和思考强度，不是新对话的默认值。 */
    @Test
    fun branchInheritsSourceChoice() {
        val hub = hub(model = "opus", effort = "high")
        val source = reopened(hub)
        source.restoreChoice("claude-fable-5-2", "max")
        hub.force("current", source)
        hub.showChat("旧对话", listOf(UserItem("u1", "你好", uuid = "uuid-1"), AnswerItem("a1", "你好！", streaming = false)))
        hub.branch("a1")
        val branch = hub.current
        assertTrue(branch !== source)
        assertNull(branch.sessionId)
        assertEquals("fable", branch.model)
        assertEquals("max", branch.effort)
        assertEquals("opus", hub.settings.model)
        assertEquals("high", hub.settings.effort)
    }

    /** 模型选项存在设置里：应用重开、还没起进程时就能拿来恢复。 */
    @Test
    fun modelOptionsSurviveRestart() {
        hub()
        val reopened = ConversationHub(app)
        assertEquals(options, reopened.models)
    }
}
