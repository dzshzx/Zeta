package com.dzshzx.zeta.shots

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** 长对话往上翻：列表顶上“更早的记录”那一行的几种样子，更早的一页接上来时正看着的那条不动，查找时把剩下的载完。 */
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

    /** 查找栏打开时把剩下的一次载完：下面那行说还剩几条在载。 */
    @Test
    fun findLoadingTheRest() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("长对话", turns("h", 0 until 2))
        hub.current.force("earlier", EarlierHistory(file, 120, "row119"))
        // 标成正在载入：不真去读（单元测试里没有 Linux 环境）
        hub.current.force("earlierState", EarlierState.Loading)
        shoot("chat-find-earlier", prepare = { openFind() }) { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    /** 打开查找栏就去载更早的记录；读不成时停下，查找栏里说明没查到的部分。 */
    @Test
    fun openingFindLoadsTheRest() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        hub.showChat("长对话", turns("h", 0 until 2))
        conversation.force("earlier", EarlierHistory(file, 120, "row119"))
        compose.setContent { ZetaTheme(darkTheme = false, windowBackdrop = false) { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) } }
        settle()
        assertEquals(EarlierState.Idle, conversation.earlierState)
        compose.openFind()
        // 单元测试里的 su 总是失败：读不成，状态从 Idle 变成可以重试的失败
        awaitMain { conversation.earlierState is EarlierState.Failed }
        assertTrue((conversation.earlierState as EarlierState.Failed).retry)
        settle()
        compose.onNodeWithText("更早的 120 条没查", substring = true).assertExistsCompat()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.openFind() {
        // 截图时时钟不自己走：菜单弹出、收起的动画要推一把
        onNodeWithContentDescription("更多").performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
        onNodeWithText("在对话中查找").performClick()
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    /**
     * 翻到最顶上（“更早的记录”那行露出来）时接上几百项：最前面那条提问停在原处，不被新内容往下推。
     * 再往下翻一点（那行不在屏上了）时接上一千多项（最后一页，那行跟着没了）：屏上那条也不动。
     * LazyColumn 自己只在第一项附近约 130 项里找 key，插这么多它找不到，靠 KeepReadingPosition 定位。页走 Conversation 自己接上的那条路。
     */
    @Test
    fun readingPositionStaysWhenAPageLands() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        val shown = turns("h", 1000 until 1020)
        hub.showChat("长对话", shown)
        reducerOf(conversation).items.addAll(shown)
        conversation.force("earlier", EarlierHistory(file, 4000, "row3999"))
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
        val first = compose.onNodeWithText("第 1000 轮的提问")
        val before = first.getBoundsInRoot().top
        // 300 轮，600 项
        deliver(conversation, turns("p1", 700 until 1000), EarlierHistory(file, 2800, "row2799"))
        landed("第 999 轮的回答")
        assertEquals(before.value, first.getBoundsInRoot().top.value, 1f)
        compose.onNodeWithText("第 999 轮的回答", substring = true).assertExistsCompat()

        // 往下翻一点，再接最后一页：700 轮，1400 项，“更早的记录”那行没了
        conversation.force("earlierState", EarlierState.Loading)
        compose.mainClock.autoAdvance = true
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 200.dp.toPx()) }
        compose.mainClock.autoAdvance = false
        settle()
        val anchor = compose.onNodeWithText("第 1001 轮的提问")
        val top = anchor.getBoundsInRoot().top
        deliver(conversation, turns("p2", 0 until 700), null)
        // 这时正看着的那条就是屏上第一项：上面的回答解析完、高度变了也不挪，等排版都停下再量
        settle()
        assertEquals(top.value, anchor.getBoundsInRoot().top.value, 1f)
        assertEquals(null, conversation.earlier)
        assertEquals(1020 * 2, conversation.items.size)
    }

    /**
     * 一帧一帧走，直到新接上的那页排版出来（[text] 是页里最后那条回答）。在这一帧量位置：没缓存过的回答先用原文占位，
     * 解析完高度会差一点，翻到最顶上时正看着的那条上面紧挨着的就是这样一条，再过一帧会跟着它挪几个 dp
     * （往上翻进没解析过的回答时也一样），那不是接页造成的。
     */
    private fun landed(text: String) {
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (runCatching { compose.onNodeWithText(text, substring = true).fetchSemanticsNode() }.isSuccess) return
        }
        throw AssertionError("接上的那页一直没排版出来")
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

    /** 把读回来的一页交给对话（它自己读回来时走的就是这里）。[left] 是这页之前还剩的。 */
    private fun deliver(conversation: Conversation, items: List<ChatItem>, left: EarlierHistory?) {
        val apply = Conversation::class.java.getDeclaredMethod("applyEarlier", List::class.java, HistoryRead::class.java, String::class.java)
            .apply { isAccessible = true }
        compose.runOnUiThread { apply.invoke(conversation, items, HistoryRead(left, emptyList(), UsageTracker(), stale = false), null) }
    }
}
