package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.chat.externalNoticeText
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交接：核心 conv.state 的 occupant、conv.handoff 的 result 怎么读，「别处运行」卡片怎么跟着核心走（blocked、occupant 都以核心推来的为准，
 * 那边自己退出由核心解开，客户端不轮询也不代发 conv.handoff），在各种情况下说什么、能不能点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionHandoffTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun occupant(text: String) = Occupant.of(json(text).jsonObject)

    @Test
    fun occupantField() {
        assertNull(occupant("""{"status":null,"waiting_for":null,"hands_over":true}"""))
        assertEquals(
            Occupant("waiting", "approve Bash"),
            occupant("""{"status":"waiting","waiting_for":"approve Bash","hands_over":true}"""),
        )
        assertEquals(
            Occupant("busy", handsOver = false),
            occupant("""{"status":"busy","waiting_for":null,"hands_over":false}"""),
        )
        // 没有 status 的当作还占着、看不出忙闲
        assertEquals(Occupant(Occupant.UNKNOWN), occupant("""{"hands_over":true}"""))
    }

    @Test
    fun handoffResult() {
        assertEquals(
            HandoffResult.Handed,
            HandoffResult.of(json("""{"handed":true,"status":null}""")),
        )
        assertEquals(
            HandoffResult.Stuck,
            HandoffResult.of(json("""{"handed":false,"status":"stuck"}""")),
        )
        assertEquals(
            HandoffResult.Busy,
            HandoffResult.of(json("""{"handed":false,"status":"shell"}""")),
        )
        assertTrue(HandoffResult.of(null) is HandoffResult.Failed)
    }

    @Test
    fun cardFollowsTheCore() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val handoff = AtomicReference("""{"handed":false,"status":"stuck"}""")
        core.respond = { call ->
            when (call.method) {
                "conv.open" -> {
                    // 打开时会话正被别处占着：核心在整份状态里就给 blocked，查到那边的样子后推 occupant
                    core.defaultReply(call)
                    core.state(call.conv, """"blocked":true""")
                    core.state(
                        call.conv,
                        """"occupant":{"status":"idle","waiting_for":null,"hands_over":true}""",
                    )
                    null
                }
                "conv.handoff" -> handoff.get()
                else -> core.defaultReply(call)
            }
        }
        val chat = Conversation(hub, "/workspace/claude", "s-held", "", historyFile = null)
        awaitCore { chat.blockedByExternal && chat.occupant != null }
        assertEquals(Occupant(Occupant.IDLE), chat.occupant)
        val name = core.next("conv.open").conv

        // 接管没成（那边没退）：说明原因，输入栏照旧不放开
        chat.handoff.handOver()
        core.next("conv.handoff")
        awaitMain { !chat.handoff.handing }
        assertTrue(chat.handoff.problem.orEmpty().contains("没有退出"))
        assertTrue(chat.blockedByExternal)

        // 核心查到那边换了样子：卡片照新的说，上次没接成的原因不再作数
        core.state(name, """"occupant":{"status":"busy","waiting_for":null,"hands_over":false}""")
        awaitCore { chat.occupant?.status == "busy" }
        assertNull(chat.handoff.problem)
        assertFalse(
            externalNoticeText(chat.occupant, chat.handoff.handing, chat.handoff.problem).enabled,
        )

        // 那边自己退出了：核心解开 blocked、清掉 occupant，卡片随之收起，客户端不发 conv.handoff
        core.state(name, """"blocked":false,"occupant":null""")
        awaitCore { !chat.blockedByExternal }
        assertNull(chat.occupant)
        assertFalse(core.calls.any { it.method == "conv.handoff" || it.method == "conv.occupant" })
    }

    @Test
    fun noticeText() {
        // 还没查到：不能点
        assertFalse(externalNoticeText(null, handing = false, problem = null).enabled)
        externalNoticeText(Occupant(Occupant.IDLE), handing = false, problem = null).let {
            assertTrue(it.enabled)
            assertEquals("接管会话", it.button)
            assertTrue(it.summary.contains("/exit"))
        }
        externalNoticeText(
                Occupant(Occupant.WAITING, "approve Bash"),
                handing = false,
                problem = null,
            )
            .let {
                assertTrue(it.enabled)
                assertTrue(it.summary.contains("approve Bash") && it.summary.contains("作废"))
            }
        externalNoticeText(Occupant("busy", handsOver = false), handing = false, problem = null)
            .let {
                assertFalse(it.enabled)
                assertEquals("等待对方空闲", it.button)
            }
        assertFalse(
            externalNoticeText(Occupant(Occupant.IDLE), handing = true, problem = null).enabled,
        )
        // 没接过来的原因压过状态说明，按钮照状态还能再点
        externalNoticeText(Occupant(Occupant.IDLE), handing = false, problem = "那边没有退出").let {
            assertEquals("那边没有退出", it.summary)
            assertTrue(it.enabled)
        }
    }
}
