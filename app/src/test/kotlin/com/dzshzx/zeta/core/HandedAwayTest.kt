package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 会话到了别处时对话怎么收场（Conversation.handedAway）：要起进程时会话正被终端里的 Claude Code 占着（托管脚本以 96 退出），刚发的消息放回输入框；
 * 电脑那边接回去了（zeta_exit 的 reason 是 released），换成「别处运行」卡片、不报进程出错。进程是假的，退出回调由测试直接调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HandedAwayTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val processField =
        Conversation::class.java.getDeclaredField("process").apply { isAccessible = true }

    private val onProcessExit =
        Conversation::class
            .java
            .getDeclaredMethod("onProcessExit", ClaudeProcess::class.java, ProcessExit::class.java)
            .apply { isAccessible = true }

    private fun Conversation.exit(exit: ProcessExit) {
        onProcessExit.invoke(this, processField.get(this), exit)
    }

    private fun chat(hub: ConversationHub): Conversation =
        Conversation(hub, "/workspace/claude", SESSION, "旧对话", historyFile = null).also {
            it.attachLiveProcess(hub)
        }

    @Test
    fun messageGoesBackWhenTheSessionIsHeldElsewhere() {
        val chat = chat(Fixtures.readyHub(app))
        chat.send("接着做")
        chat.exit(ProcessExit(0, "", reason = ProcessExit.HELD_ELSEWHERE))
        assertTrue(chat.blockedByExternal)
        assertFalse(chat.busy)
        assertEquals("接着做", chat.takeRecalled()?.text)
        assertTrue("没送到的消息不留在聊天记录里", chat.items.none { it is UserItem })
        assertTrue("不报进程出错", chat.items.none { it is NoticeItem && it.error })
    }

    @Test
    fun releasedToTheComputerShowsTheCardWithoutAnError() {
        val chat = chat(Fixtures.readyHub(app))
        chat.send("你好")
        chat.receive("""{"type":"system","subtype":"init","session_id":"$SESSION"}""")
        chat.receive(
            """{"type":"result","subtype":"success","is_error":false,"result":"好","session_id":"$SESSION"}""",
        )
        chat.exit(ProcessExit(0, "", reason = ProcessExit.RELEASED))
        assertTrue(chat.blockedByExternal)
        assertNull("送到了的消息不放回去", chat.takeRecalled())
        assertTrue(chat.items.any { it is UserItem })
        assertTrue(chat.items.none { it is NoticeItem && it.error })
        assertTrue(chat.items.any { it is NoticeItem && it.text.contains("转到电脑终端") })
    }

    @Test
    fun otherExitsAreStillErrors() {
        val chat = chat(Fixtures.readyHub(app))
        chat.send("你好")
        chat.exit(ProcessExit(96, "别的原因"))
        assertFalse(chat.blockedByExternal)
        assertTrue(chat.items.any { it is NoticeItem && it.error })
    }

    private companion object {
        const val SESSION = "aaaaaaaa-1111-4222-8333-444444444444"
    }
}
