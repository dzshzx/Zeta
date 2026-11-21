package com.dzshzx.zeta.ui.chat

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ComposerDrafts
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DraftRecords
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天页的输入栏接上草稿：打了一半的字切到别的对话再切回来还在；停手后、离开时存盘； 编辑旧消息时不存，离开时取消编辑，存下的是编辑前的草稿。借截图测试的 Robolectric 环境，不出图。
 *
 * 测试时钟一次拨很久时，输入框的改动要到这一段末尾的那一帧才被看到，停手的计时从那时才开始：一小段一小段地拨。
 */
class ComposerDraftScreenTest : ShotTest() {
    private fun ConversationHub.openSession(id: String, items: List<ChatItem> = emptyList()) {
        val conversation =
            Conversation(this, Fixtures.env.defaultCwd, id, "会话 $id", historyFile = null)
        if (items.isNotEmpty()) conversation.force("items", items)
        park(conversation)
        openConversation(id)
    }

    private fun storedText(key: String): String? =
        DraftRecords.decode(
                app.getSharedPreferences(ComposerDrafts.PREFS, Context.MODE_PRIVATE)
                    .getString(ComposerDrafts.KEY, null),
            )
            .firstOrNull { it.key == key }
            ?.text

    private fun show(hub: ConversationHub) = compose.setContent {
        ZetaTheme(windowBackdrop = false) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 把测试时钟往前拨 [ms] 毫秒，每次拨 50 毫秒。 */
    private fun pause(ms: Long) {
        repeat((ms / 50).toInt()) {
            compose.mainClock.advanceTimeBy(50)
            compose.waitForIdle()
        }
    }

    /** 等 [key] 下存的文字变成 [expected]，测试时钟最多拨 3 秒。 */
    private fun awaitStored(key: String, expected: String) {
        repeat(60) {
            if (storedText(key) == expected) return
            pause(50)
        }
        assertEquals(expected, storedText(key))
    }

    @Test
    fun draftComesBackAfterSwitching() {
        val hub = Fixtures.readyHub(app)
        compose.runOnUiThread { hub.openSession("s-a") }
        show(hub)
        compose.onNode(hasSetTextAction()).performTextInput("写到一半")

        compose.runOnUiThread { hub.startNew() }
        compose.waitForIdle()
        compose.onAllNodesWithText("写到一半").assertCountEquals(0)
        assertEquals("离开时马上存盘", "写到一半", storedText("s-a"))

        // 对话对象已经收掉了，重新打开是新建的
        compose.runOnUiThread { hub.openSession("s-a") }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("写到一半")
    }

    @Test
    fun draftsDoNotLeakBetweenConversations() {
        val hub = Fixtures.readyHub(app)
        compose.runOnUiThread { hub.openSession("s-a") }
        show(hub)
        compose.onNode(hasSetTextAction()).performTextInput("甲的草稿")

        // 直接换到另一个会话：输入栏是它自己的，不带着上一个对话的字
        compose.runOnUiThread { hub.openSession("s-b") }
        compose.waitForIdle()
        compose.onAllNodesWithText("甲的草稿").assertCountEquals(0)
        compose.onNode(hasSetTextAction()).performTextInput("乙的草稿")

        compose.runOnUiThread { hub.openSession("s-a") }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("甲的草稿")
        assertEquals("甲的草稿", storedText("s-a"))
        assertEquals("乙的草稿", storedText("s-b"))
    }

    @Test
    fun typingIsSavedAfterAPause() {
        val hub = Fixtures.readyHub(app)
        compose.runOnUiThread { hub.openSession("s-a") }
        show(hub)
        compose.onNode(hasSetTextAction()).performTextInput("停手就存")
        awaitStored("s-a", "停手就存")
    }

    @Test
    fun editingKeepsTheRealDraft() {
        val hub = Fixtures.readyHub(app)
        val chat =
            listOf(
                UserItem("u1", "原来的问题", uuid = "uuid-1"),
                AnswerItem("a1", "原来的回答", streaming = false),
            )
        compose.runOnUiThread { hub.openSession("s-a", chat) }
        show(hub)
        compose.onNode(hasSetTextAction()).performTextInput("我的草稿")
        awaitStored("s-a", "我的草稿")

        compose.onNodeWithText("原来的问题").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("编辑").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("原来的问题")
        pause(1_000)
        assertEquals("编辑中不存", "我的草稿", storedText("s-a"))

        // 编辑到一半切走：放回编辑前的草稿
        compose.runOnUiThread { hub.startNew() }
        compose.waitForIdle()
        assertEquals("我的草稿", storedText("s-a"))
        compose.runOnUiThread { hub.openSession("s-a", chat) }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEquals("我的草稿")
        compose.onAllNodesWithText("编辑消息").assertCountEquals(0)
    }
}
