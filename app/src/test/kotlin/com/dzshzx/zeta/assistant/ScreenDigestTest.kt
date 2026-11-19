package com.dzshzx.zeta.assistant

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.NoticeItem
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
        override val childCount: Int get() = kids.size
        override fun child(index: Int): ScreenNode = kids[index]
    }

    @Test
    fun collectsTextInScreenOrderAndDropsRepeats() {
        val root = Node(
            kids = listOf(
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
        val root = Node(
            kids = listOf(
                Node(text = "看得见"),
                Node(skipped = true, text = "密码", kids = listOf(Node(text = "下面也不要"))),
            ),
        )
        assertEquals(listOf("看得见"), ScreenDigest.collect(listOf(root)).first)
    }

    @Test
    fun shortDescriptionsAreButtonNamesNotContent() {
        val root = Node(kids = listOf(Node(description = "返回"), Node(description = "一张猫的照片"), Node(text = "正文", description = "被文字盖过")))
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
        val app = ScreenApp("com.android.settings", "设置", webUri = null, lines = listOf("WLAN", "蓝牙"), truncated = true)
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
    fun replyJoinsThisTurnsAnswers() {
        val items = listOf(
            UserItem("u0", "上一个问题"),
            AnswerItem("a0", "上一轮的回答", streaming = false),
            UserItem("u1", "这页讲了什么"),
            AnswerItem("a1", "先看一下。", streaming = false),
            WorkItem("w1", emptyList()),
            AnswerItem("a2", "这是设置页。", streaming = true),
        )
        val view = AssistantReplies.view(items, busy = true, pendingTool = null)
        assertEquals("先看一下。\n\n这是设置页。", view.text)
        assertTrue(view.streaming)
        assertNull(view.status)
    }

    @Test
    fun replyStatusBeforeTheAnswer() {
        val asked = listOf(UserItem("u1", "这页讲了什么"))
        assertEquals("Claude 正在看…", AssistantReplies.view(asked, busy = true, pendingTool = null).status)
        val working = AssistantReplies.view(asked + WorkItem("w", emptyList()), busy = true, pendingTool = null)
        assertEquals("Claude 正在处理…", working.status)
        val waiting = AssistantReplies.view(asked, busy = true, pendingTool = "Bash")
        assertTrue(waiting.attention)
        assertTrue(waiting.status!!.contains("Bash"))
        val failed = AssistantReplies.view(asked + NoticeItem("n", "进程退出了", error = true), busy = false, pendingTool = null)
        assertEquals("进程退出了", failed.error)
        assertNull(failed.status)
        assertEquals("这一轮没有回答", AssistantReplies.view(asked, busy = false, pendingTool = null).status)
    }

    @Test
    fun recognitionGoesToXiaomiFirstAndNeverToZeta() {
        val claude = "com.anthropic.claude/com.anthropic.claude.bell.assist.ClaudeRecognitionService"
        val zeta = "com.dzshzx.zeta.dev/com.dzshzx.zeta.assistant.ZetaRecognitionService"
        assertEquals(ZetaRecognitionService.PREFERRED, ZetaRecognitionService.pickDelegate(listOf(zeta, claude, ZetaRecognitionService.PREFERRED)))
        assertEquals(claude, ZetaRecognitionService.pickDelegate(listOf(zeta, claude)))
        assertNull(ZetaRecognitionService.pickDelegate(listOf(zeta)))
    }
}
