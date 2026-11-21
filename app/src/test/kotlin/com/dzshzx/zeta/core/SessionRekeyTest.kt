package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 对话的会话号换了（新对话拿到会话号、/clear 换了新会话）：按会话登记的各处（手机工具、后台对话、输入栏草稿）都换到新号下， 之后按新号找得回同一份。新加按会话登记的东西时，在
 * [ConversationHub.sessionChanged] 里跟着换，并在这里加一句断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SessionRekeyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun assertFoundUnder(
        hub: ConversationHub,
        chat: Conversation,
        draft: ComposerDraft,
        id: String,
        old: String,
    ) {
        assertSame("后台对话", chat, hub.findConversation(id))
        assertNull("旧号下不再登记", hub.findConversation(old))
        assertSame("手机工具", chat.phone, hub.phoneFor(id))
        assertSame("输入栏草稿", draft, hub.drafts.of(chat))
    }

    @Test
    fun registriesFollowTheSessionId() {
        val hub = Fixtures.readyHub(app)
        val chat =
            Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        hub.park(chat)
        chat.send("先问一句")
        // 发出了、会话号还没来：草稿按对话的 key 放着
        val draft = hub.drafts.of(chat)
        assertSame(chat.phone, hub.phoneFor(chat.key))

        chat.receive(
            """{"type":"system","subtype":"init","session_id":"s-first","model":"claude-opus-5-5"}""",
        )
        assertFoundUnder(hub, chat, draft, "s-first", chat.key)

        // /clear：换了新会话
        chat.receive("""{"type":"conversation_reset","new_conversation_id":"s-second"}""")
        assertFoundUnder(hub, chat, draft, "s-second", "s-first")
    }
}
