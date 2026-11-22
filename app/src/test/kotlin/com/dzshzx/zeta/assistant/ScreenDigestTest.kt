package com.dzshzx.zeta.assistant

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDigestTest {
    private class Node(
        override val text: String? = null,
        override val description: String? = null,
        override val skipped: Boolean = false,
        val kids: List<Node> = emptyList(),
    ) : ScreenNode {
        override val childCount: Int
            get() = kids.size

        override fun child(index: Int): ScreenNode = kids[index]
    }

    @Test
    fun collectsTextInScreenOrderAndDropsRepeats() {
        val root =
            Node(
                kids =
                    listOf(
                        Node(text = "  标题 \n 第二行 "),
                        Node(kids = listOf(Node(text = "正文"), Node(text = "标题 第二行"))),
                        Node(text = "尾巴"),
                    ),
            )
        val (lines, truncated) = ScreenDigest.collect(listOf(root))
        assertEquals(listOf("标题 第二行", "正文", "尾巴"), lines)
        assertFalse(truncated)
    }

    @Test
    fun skipsHiddenAndPasswordSubtrees() {
        val root =
            Node(
                kids =
                    listOf(
                        Node(text = "看得见"),
                        Node(skipped = true, text = "密码", kids = listOf(Node(text = "下面也不要"))),
                    ),
            )
        assertEquals(listOf("看得见"), ScreenDigest.collect(listOf(root)).first)
    }

    @Test
    fun shortDescriptionsAreButtonNamesNotContent() {
        val root =
            Node(
                kids =
                    listOf(
                        Node(description = "返回"),
                        Node(description = "一张猫的照片"),
                        Node(text = "正文", description = "被文字盖过"),
                    ),
            )
        assertEquals(listOf("一张猫的照片", "正文"), ScreenDigest.collect(listOf(root)).first)
    }

    @Test
    fun stopsAtTheCharacterLimit() {
        val root = Node(kids = (1..100).map { Node(text = "第 $it 行" + "字".repeat(60)) })
        val (lines, truncated) = ScreenDigest.collect(listOf(root))
        assertTrue(truncated)
        assertTrue(lines.sumOf { it.length } <= ScreenDigest.MAX_CHARS)
        assertEquals("第 1 行" + "字".repeat(60), lines.first())
    }

    @Test
    fun longLinesAreCut() {
        val (lines, _) = ScreenDigest.collect(listOf(Node(text = "长".repeat(1000))))
        assertEquals(ScreenDigest.MAX_LINE + 1, lines.single().length)
        assertTrue(lines.single().endsWith("…"))
    }

    @Test
    fun blockNamesTheAppAndListsText() {
        val app =
            ScreenApp(
                "com.android.settings",
                "设置",
                webUri = null,
                lines = listOf("WLAN", "蓝牙"),
                truncated = true,
            )
        val block = ScreenDigest.contextBlock(listOf(app), screenshot = true)!!
        assertEquals(
            "<screen_context>\n唤起 Zeta 时的屏幕，截图见附图。\n应用：设置（com.android.settings）\n屏幕上的文字：\nWLAN\n蓝牙\n（文字太多，后面的没有列出）\n</screen_context>",
            block,
        )
    }

    @Test
    fun focusedAppComesFirstAndUrlIsShown() {
        val side = ScreenApp("com.a", "甲", focused = false)
        val main = ScreenApp("com.b", "乙", webUri = "https://example.com/x", focused = true)
        val block = ScreenDigest.contextBlock(listOf(side, main), screenshot = false)!!
        assertTrue(block.contains("没有截图"))
        assertTrue(block.indexOf("乙（com.b）") < block.indexOf("甲（com.a）"))
        assertTrue(block.contains("网址：https://example.com/x"))
    }

    @Test
    fun nothingCapturedMeansNoBlock() {
        assertNull(ScreenDigest.contextBlock(emptyList(), screenshot = false))
        assertEquals("这页讲了什么", ScreenDigest.message("  这页讲了什么 ", null))
        assertEquals("问\n\n<screen_context>…", ScreenDigest.message("问", "<screen_context>…"))
    }

    @Test
    fun appNameFallsBack() {
        assertEquals("com.x", ScreenDigest.appName(ScreenApp("com.x", null)))
        assertEquals("设置", ScreenDigest.appName(ScreenApp(null, "设置")))
        assertEquals("不知道是哪个应用", ScreenDigest.appName(ScreenApp(null, " ")))
    }

    @Test
    fun suggestionsOnlyWithScreen() {
        assertTrue(AssistantSuggestions.forContext(true).contains("这页讲了什么"))
        assertTrue(AssistantSuggestions.forContext(false).isEmpty())
    }

    @Test
    fun readContentReplacesThePackageOnlyPlaceholder() {
        val placeholder = listOf(ScreenApp("com.a", "甲"), ScreenApp("com.b", "乙", focused = false))
        val read = ScreenApp("com.a", "甲", lines = listOf("正文"))
        assertEquals(listOf(read, placeholder[1]), AssistCapture.merge(placeholder, read))
        val other = ScreenApp("com.c", "丙", lines = listOf("x"))
        assertEquals(placeholder + other, AssistCapture.merge(placeholder, other))
    }

    @Test
    fun questionDropsTheScreenBlock() {
        val block = ScreenDigest.contextBlock(listOf(ScreenApp("com.a", "甲")), screenshot = true)
        assertEquals("这页讲了什么", ScreenDigest.question(ScreenDigest.message(" 这页讲了什么 ", block)))
        assertEquals("只问问题", ScreenDigest.question(ScreenDigest.message("只问问题", null)))
    }

    @Test
    fun replyListsEveryTurnLikeEta() {
        val screen = ScreenChip("设置", null, removed = false)
        val asked = ScreenDigest.message("这页讲了什么", ScreenDigest.contextBlock(emptyList(), true))
        val items =
            listOf(
                UserItem("u0", asked),
                AnswerItem("a0", "上一轮的回答", streaming = false),
                UserItem("u1", "再说说蓝牙"),
                WorkItem("w0", emptyList()),
                AnswerItem("a1", "先看一下。", streaming = false),
                NoticeItem("n0", "已压缩上下文", error = false),
                AnswerItem("a2", "这是设置页。", streaming = true),
            )
        val view = AssistantReplies.view(items, busy = true, waitingFor = null, screen = screen)
        val keys = view.entries.map { it.key }
        // 空的工作过程、不是错误的提示不显示；回答在流式输出时不加状态行
        assertEquals(listOf("u0", "a0", "u1", "a1", "a2"), keys)
        val first = view.entries[0] as ReplyEntry.Question
        assertEquals("这页讲了什么", first.text)
        assertEquals(screen, first.screen)
        assertNull((view.entries[2] as ReplyEntry.Question).screen)
        // 最后一问之后的回答字数（各段去掉首尾空白）
        assertEquals("先看一下。".length + "这是设置页。".length, view.answerLength)
        assertTrue(view.busy)
    }

    @Test
    fun askingShowsBeforeTheConversationStarts() {
        val screen = ScreenChip("设置", null, removed = false)
        // 刚发出去、对话还没开起来：只有这一问（带着屏幕）和「正在分析任务」
        val view =
            AssistantReplies.view(emptyList(), true, null, screen = screen, asking = "这页讲了什么")
        val question = view.entries.first() as ReplyEntry.Question
        assertEquals("这页讲了什么", question.text)
        assertEquals(screen, question.screen)
        assertEquals(WorkSummary.ANALYZING, (view.entries.last() as ReplyEntry.Status).text)
        assertEquals(0, view.answerLength)
        // 接着问时屏幕只画在第一问上面
        val again = AssistantReplies.view(listOf(UserItem("u0", "上一问")), true, null, screen, "再问")
        assertNull((again.entries[1] as ReplyEntry.Question).screen)
    }

    @Test
    fun replyStatusBeforeTheAnswer() {
        val asked = listOf(UserItem("u1", "这页讲了什么"))
        fun status(view: ReplyView) = view.entries.last() as? ReplyEntry.Status
        // 刚发出去：「正在分析任务」，图标主色
        val analyzing = status(AssistantReplies.view(asked, busy = true, waitingFor = null))
        assertEquals("正在分析任务", analyzing?.text)
        assertTrue(analyzing!!.active)
        // 工具在跑：工作过程那一行自己说在做什么，不再加状态行
        val tool = ToolStep("t", "Bash", null, ToolStatus.Running, null)
        val running = AssistantReplies.view(asked + WorkItem("w", listOf(tool)), true, null)
        assertTrue(running.entries.last() is ReplyEntry.Work)
        assertTrue(running.busy)
        val waiting =
            status(AssistantReplies.view(asked, busy = true, waitingFor = "Claude 想用 Bash"))
        assertTrue(waiting!!.attention)
        assertTrue(waiting.text.contains("Bash"))
        val failed =
            AssistantReplies.view(
                asked + NoticeItem("n", "进程退出了", error = true),
                busy = false,
                waitingFor = null,
            )
        assertTrue(failed.entries.last() is ReplyEntry.Problem)
        assertFalse(failed.busy)
        assertEquals(
            "这一轮没有回答",
            status(AssistantReplies.view(asked, busy = false, waitingFor = null))?.text,
        )
    }

    @Test
    fun workSummaryCountsThinkingAndTools() {
        val thinking = ThinkingStep("k", "想想", running = false, seconds = 2)
        val done = ToolStep("t1", "Bash", null, ToolStatus.Success, "ok")
        val failed = ToolStep("t2", "Bash", null, ToolStatus.Failed, "no")
        assertEquals("思考 1 次", WorkSummary.text(WorkItem("w", listOf(thinking))))
        assertEquals(
            "思考 1 次 · 调用 2 个工具 · 1 个失败",
            WorkSummary.text(WorkItem("w", listOf(thinking, done, failed))),
        )
        val stillThinking = thinking.copy(running = true)
        assertEquals("正在分析任务", WorkSummary.text(WorkItem("w", listOf(stillThinking))))
        val running = ToolStep("t3", "Bash", null, ToolStatus.Running, null)
        assertTrue(
            WorkSummary.text(WorkItem("w", listOf(done, running))).startsWith("正在处理 · 第 2 步 · "),
        )
    }
}
