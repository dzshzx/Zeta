package com.dzshzx.zeta.core

import com.dzshzx.zeta.ui.chat.externalNoticeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交接：zeta-helper occupant、handoff 的输出怎么读，「别处运行」卡片在各种情况下说什么、能不能点。 */
class SessionHandoffTest {
    @Test
    fun occupantOutput() {
        assertNull(HandoffOutput.occupant("""{"status":null,"waiting_for":null}"""))
        assertEquals(
            Occupant("waiting", "approve Bash"),
            HandoffOutput.occupant(
                "登录 shell 打的字\n{\"status\":\"waiting\",\"waiting_for\":\"approve Bash\"}\n",
            ),
        )
        assertEquals(
            Occupant("busy"),
            HandoffOutput.occupant("""{"status":"busy","waiting_for":null}"""),
        )
        // 看不懂的当作还占着、看不出忙闲：不能因此放开输入栏
        assertEquals(Occupant(Occupant.UNKNOWN), HandoffOutput.occupant("not json"))
    }

    @Test
    fun handoffOutput() {
        assertEquals(
            HandoffResult.Handed,
            HandoffOutput.handoff("""{"handed":true,"status":null}"""),
        )
        assertEquals(
            HandoffResult.Stuck,
            HandoffOutput.handoff("""{"handed":false,"status":"stuck"}"""),
        )
        assertEquals(
            HandoffResult.Busy(Occupant("shell")),
            HandoffOutput.handoff("""{"handed":false,"status":"shell"}"""),
        )
        assertTrue(HandoffOutput.handoff("") is HandoffResult.Failed)
    }

    @Test
    fun onlyBetweenTurnsCanBeHandedOver() {
        assertTrue(Occupant(Occupant.IDLE).handsOver)
        assertTrue(Occupant(Occupant.WAITING).handsOver)
        assertTrue(Occupant(Occupant.UNKNOWN).handsOver)
        assertFalse(Occupant("busy").handsOver)
        assertFalse(Occupant(Occupant.SHELL).handsOver)
    }

    @Test
    fun noticeText() {
        // 还没查到：不能点
        assertFalse(externalNoticeText(null, handing = false, problem = null).enabled)
        externalNoticeText(Occupant(Occupant.IDLE), handing = false, problem = null).let {
            assertTrue(it.enabled)
            assertEquals("在 Zeta 接着", it.button)
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
        externalNoticeText(Occupant("busy"), handing = false, problem = null).let {
            assertFalse(it.enabled)
            assertEquals("等那边这一轮结束", it.button)
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
