package com.dzshzx.zeta.shots

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.EarlierHistory
import com.dzshzx.zeta.core.EarlierState
import com.dzshzx.zeta.core.HistoryRead
import com.dzshzx.zeta.core.TranscriptReducer
import com.dzshzx.zeta.core.UsageTracker
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.chat.ChatScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长对话往上翻：列表顶上“更早的记录”那一行的几种样子，以及更早的一页接上来时正看着的那条不动。 */
class EarlierHistoryShots : ShotTest() {
    private val file = "/home/dzshzx/.claude/projects/-workspace-claude/s.jsonl"

    /** 内容不满一屏、翻不动时：顶上是一句可以点的“载入更早的 N 条记录”。 */
    @Test
    fun row() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("长对话", turns("h", 0 until 2))
        hub.current.force("earlier", EarlierHistory(file, 120, "row119"))
        shoot("chat-earlier") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    @Test
    fun loading() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("长对话", turns("h", 0 until 2))
        hub.current.force("earlier", EarlierHistory(file, 120, "row119"))
        hub.current.force("earlierState", EarlierState.Loading)
        shoot("chat-earlier-loading") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    /**
     * 翻到最顶上（“更早的记录”那行露出来）时接上一页：最前面那条提问停在原处，不被新内容往下推。
     * 再往下翻一点（那行不在屏上了）时接上一页：屏上那条也不动。页走 Conversation 自己接上的那条路（作废检查、接到列表前面）。
     */
    @Test
    fun readingPositionStaysWhenAPageLands() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        val shown = turns("h", 40 until 60)
        hub.showChat("长对话", shown)
        reducerOf(conversation).items.addAll(shown)
        conversation.force("earlier", EarlierHistory(file, 160, "row159"))
        // 标成正在载入：往上翻时不自己去读（单元测试里没有 Linux 环境），页由下面直接交给它
        conversation.force("earlierState", EarlierState.Loading)
        compose.mainClock.autoAdvance = false
        compose.setContent { ZetaTheme(darkTheme = false, windowBackdrop = false) { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) } }
        settle()
        val list = compose.onNode(hasScrollToIndexAction())
        // 用手指往上翻到顶（这样才会停掉贴底跟随）
        compose.mainClock.autoAdvance = true
        // 从中间往下拖：列表顶边盖在顶栏下面，从那里按到的是顶栏
        repeat(40) { list.performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f) } }
        compose.mainClock.autoAdvance = false
        // 顶上拉过头的回弹放完再量
        settle(3_000)
        compose.onNodeWithText("正在载入更早的记录").assertExistsCompat()
        val first = compose.onNodeWithText("第 40 轮的提问")
        val before = first.getBoundsInRoot().top
        deliver(conversation, EarlierHistory(file, 160, "row159"), turns("p1", 20 until 40), EarlierHistory(file, 80, "row79"))
        settle()
        assertEquals(before.value, first.getBoundsInRoot().top.value, 1f)
        compose.onNodeWithText("第 39 轮的回答", substring = true).assertExistsCompat()

        // 往下翻一点，再接一页：屏上那条照样不动
        conversation.force("earlierState", EarlierState.Loading)
        compose.mainClock.autoAdvance = true
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 200.dp.toPx()) }
        compose.mainClock.autoAdvance = false
        settle()
        val anchor = compose.onNodeWithText("第 42 轮的提问")
        val top = anchor.getBoundsInRoot().top
        deliver(conversation, EarlierHistory(file, 80, "row79"), turns("p2", 0 until 20), null)
        settle()
        assertEquals(top.value, anchor.getBoundsInRoot().top.value, 1f)
        assertEquals(null, conversation.earlier)
        assertEquals(60 * 2, conversation.items.size)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsCompat() {
        assertTrue(runCatching { fetchSemanticsNode() }.isSuccess)
    }

    /** 每轮一条提问、一段几行的回答。 */
    private fun turns(prefix: String, range: IntRange): List<ChatItem> = range.flatMap { t ->
        listOf(
            UserItem("$prefix-u$t", "第 $t 轮的提问", uuid = "u$t"),
            AnswerItem("$prefix-a$t", "第 $t 轮的回答。\n\n- 第一点\n- 第二点\n- 第三点", streaming = false),
        )
    }

    private fun reducerOf(conversation: Conversation): TranscriptReducer =
        Conversation::class.java.getDeclaredField("reducer").apply { isAccessible = true }.get(conversation) as TranscriptReducer

    /** 把读回来的一页交给对话（它自己读回来时走的就是这里）。 */
    private fun deliver(conversation: Conversation, window: EarlierHistory, items: List<ChatItem>, left: EarlierHistory?) {
        val apply = Conversation::class.java.getDeclaredMethod(
            "applyEarlier", Int::class.javaPrimitiveType, EarlierHistory::class.java, List::class.java, HistoryRead::class.java, String::class.java,
        ).apply { isAccessible = true }
        compose.runOnUiThread { apply.invoke(conversation, 0, window, items, HistoryRead(left, emptyList(), UsageTracker(), emptyMap(), stale = false), null) }
    }
}
