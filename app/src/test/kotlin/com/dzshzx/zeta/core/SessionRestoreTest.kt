package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 顶栏的模型：指定了的、Claude Code 报的、所在主机的默认模型，按这个先后显示。接着已有会话时恢复哪个模型、思考强度由核心定 （helper/conv，行为由
 * ClaudeResumeContractTest 钉住）；这里只看这边怎么显示、分支怎么交给核心。新对话的默认值见 [ClaudeDefaultsTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionRestoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun hub(): ConversationHub = Fixtures.readyHub(app)

    @Test
    fun shownModelFollowsChoiceReportAndHostDefault() {
        val hub = hub()
        val conversation = hub.current
        assertNull(conversation.model)
        assertFalse(conversation.modelFromSession)
        // 什么都不知道：清单还没到时说不出默认是哪个，才写「默认」
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
        // Claude Code 报了在用哪个
        conversation.core { it.copy(reportedModel = "claude-sonnet-5-5") }
        assertEquals("claude-sonnet-5-5", conversation.shownModel)
        assertEquals("Sonnet 5.5", conversation.shownModelName(emptyList()))
        // 这个对话指定了的优先
        conversation.core { it.copy(model = "fable") }
        assertEquals("fable", conversation.shownModel)
        assertEquals("Fable 5.2", conversation.shownModelName(options))
    }

    /** 分支：在原对话的请求队列里发 conv.fork（从哪一条之后），新对话由核心那边建好，这边还没有会话号。 */
    @Test
    fun branchForksInTheCore() {
        val hub = hub()
        val core = FakeCore().install(hub)
        val source = Conversation(hub, "/workspace/claude", "s-old", "旧对话", historyFile = null)
        val opened = core.next("conv.open").conv
        hub.force("currentState", source)
        core.push(
            """{"type":"conv.items","conv":"$opened","full":true,"items":[""" +
                """{"kind":"user","id":"u1","text":"你好","uuid":"uuid-1"},{"kind":"answer","id":"a1","text":"你好！"}]}""",
        )
        core.state(opened, """"can_rewind":true""")
        awaitCore { source.canRewind && source.items.size == 2 }
        hub.branch("a1")
        val branch = hub.current
        assertTrue(branch !== source)
        assertNull(branch.sessionId)
        val fork = core.next("conv.fork")
        assertEquals(opened, fork.conv)
        assertEquals("a1", fork.str("last"))
        assertEquals("conv.close", core.next("conv.close").method)
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
    }
}
