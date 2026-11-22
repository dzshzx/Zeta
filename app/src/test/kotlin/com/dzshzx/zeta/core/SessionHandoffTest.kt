package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.chat.externalNoticeText
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 交接：核心 conv.occupant、conv.handoff 的 result 怎么读，「别处运行」卡片怎么跟着核心走（blocked 以核心为准，没人占着了请核心解开），
 * 在各种情况下说什么、能不能点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionHandoffTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun occupantResult() {
        assertNull(Occupant.of(json("""{"status":null,"waiting_for":null,"hands_over":true}""")))
        assertEquals(
            Occupant("waiting", "approve Bash"),
            Occupant.of(
                json("""{"status":"waiting","waiting_for":"approve Bash","hands_over":true}"""),
            ),
        )
        assertEquals(
            Occupant("busy", handsOver = false),
            Occupant.of(json("""{"status":"busy","waiting_for":null,"hands_over":false}""")),
        )
        // 看不懂的当作还占着、看不出忙闲：没人占着时卡片会去接管，不能因此把占着的那边当成没人
        assertEquals(Occupant(Occupant.UNKNOWN), Occupant.of(JsonPrimitive("x")))
        assertEquals(Occupant(Occupant.UNKNOWN), Occupant.of(json("""{"hands_over":true}""")))
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
            HandoffResult.Busy(Occupant("shell", handsOver = false)),
            HandoffResult.of(json("""{"handed":false,"status":"shell"}""")),
        )
        assertTrue(HandoffResult.of(null) is HandoffResult.Failed)
    }

    @Test
    fun cardFollowsTheCore() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val occupant =
            AtomicReference("""{"status":"busy","waiting_for":null,"hands_over":false}""")
        val handoff = AtomicReference("""{"handed":false,"status":"stuck"}""")
        core.respond = { call ->
            when (call.method) {
                "conv.open" -> {
                    // 打开时会话正被别处占着：核心在整份状态里就给 blocked
                    core.defaultReply(call)
                    core.state(call.conv, """"blocked":true""")
                    null
                }
                "conv.occupant" -> occupant.get()
                "conv.handoff" ->
                    handoff.get().also {
                        if (it.contains(""""handed":true"""))
                            core.state(call.conv, """"blocked":false""")
                    }
                else -> core.defaultReply(call)
            }
        }
        val chat = Conversation(hub, "/workspace/claude", "s-held", "", historyFile = null)

        // 卡片出来，接着就查那边的样子
        awaitCore { chat.blockedByExternal && chat.handoff.occupant != null }
        assertEquals(Occupant("busy", handsOver = false), chat.handoff.occupant)

        // 接管没成（那边没退）：说明原因，输入栏照旧不放开
        chat.handoff.handOver()
        core.next("conv.handoff")
        awaitMain { !chat.handoff.handing }
        assertTrue(chat.handoff.problem.orEmpty().contains("没有退出"))
        assertTrue(chat.blockedByExternal)

        // 那边自己退出了：查到没人占着，请核心解开（conv.handoff），卡片随核心的 blocked 收起
        occupant.set("""{"status":null,"waiting_for":null,"hands_over":true}""")
        handoff.set("""{"handed":true,"status":null}""")
        chat.handoff.refresh()
        // 查到的结果要回主线程才接着发 conv.handoff，所以这里不能用 core.next 干等
        awaitCore { !chat.blockedByExternal && !chat.handoff.handing }
        assertTrue(core.calls.any { it.method == "conv.handoff" })
        assertNull(chat.handoff.occupant)
        assertNull(chat.handoff.problem)
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
