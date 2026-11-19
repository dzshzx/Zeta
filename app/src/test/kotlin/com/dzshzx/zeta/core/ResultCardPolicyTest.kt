package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardPolicyTest {
    private fun shows(
        usedPhone: Boolean = true,
        stopped: Boolean = false,
        zetaForeground: Boolean = false,
        locked: Boolean = false,
        overlayReady: Boolean = true,
        phoneInUse: Boolean = false,
    ) = ResultCardPolicy.shows(usedPhone, stopped, zetaForeground, locked, overlayReady, phoneInUse)

    @Test
    fun showsOnlyForPhoneTurnsEndingOutsideZeta() {
        assertTrue("用过手机、Zeta 在后台：弹", shows())
        assertFalse("没用手机工具的一轮不弹", shows(usedPhone = false))
        assertFalse("Zeta 在前台：聊天页上看得到，不弹", shows(zetaForeground = true))
        assertFalse("用户叫停的不弹", shows(stopped = true))
        assertFalse("锁屏、息屏时不弹（无障碍浮层在锁屏界面之上）", shows(locked = true))
        assertFalse("无障碍服务没连着，弹不出来", shows(overlayReady = false))
        assertFalse("别的对话正在用手机：卡片会挡住它", shows(phoneInUse = true))
    }

    @Test
    fun keepsUntilSomethingElseNeedsTheScreen() {
        assertTrue(ResultCardPolicy.keeps(zetaForeground = false, locked = false, newTurn = false, phoneInUse = false))
        assertFalse("回到 Zeta", ResultCardPolicy.keeps(zetaForeground = true, locked = false, newTurn = false, phoneInUse = false))
        assertFalse("锁屏", ResultCardPolicy.keeps(zetaForeground = false, locked = true, newTurn = false, phoneInUse = false))
        assertFalse("新的一轮开始", ResultCardPolicy.keeps(zetaForeground = false, locked = false, newTurn = true, phoneInUse = false))
        assertFalse("有对话用上了手机", ResultCardPolicy.keeps(zetaForeground = false, locked = false, newTurn = false, phoneInUse = true))
    }

    @Test
    fun longerAnswersStayLonger() {
        assertEquals(8_000L, ResultCardPolicy.idleDismissMs("好了"))
        assertEquals(8_000L, ResultCardPolicy.idleDismissMs("字".repeat(39)))
        assertEquals(13_000L, ResultCardPolicy.idleDismissMs("字".repeat(200)))
        assertEquals("最多 20 秒", 20_000L, ResultCardPolicy.idleDismissMs("字".repeat(5_000)))
    }

    @Test
    fun outcomeIsTheLastAnswerOfThisTurn() {
        val items = listOf(
            UserItem("u1", "上一轮"),
            AnswerItem("a1", "上一轮的回答", streaming = false),
            UserItem("u2", "把截图归档"),
            AnswerItem("a2", "我先打开相册。", streaming = false),
            WorkItem("w1", listOf(ThinkingStep("t1", "…", running = false, seconds = 2))),
            AnswerItem("a3", "  归档好了，共 23 张。\n", streaming = false),
            WorkItem("w2", emptyList()),
        )
        assertEquals(ResultCardPolicy.Outcome("归档好了，共 23 张。", failed = false), ResultCardPolicy.outcome(items))
    }

    @Test
    fun errorAtTheEndWins() {
        val items = listOf(
            UserItem("u1", "把截图归档"),
            AnswerItem("a1", "我先打开相册。", streaming = false),
            NoticeItem("n1", "API Error: 529 overloaded", error = true),
        )
        assertEquals(ResultCardPolicy.Outcome("API Error: 529 overloaded", failed = true), ResultCardPolicy.outcome(items))
        // 不是错误的提示（比如“已停止”）不算出错
        val stopped = listOf(UserItem("u1", "x"), AnswerItem("a1", "做了一半", streaming = false), NoticeItem("n1", "已停止", error = false))
        assertEquals(ResultCardPolicy.Outcome("做了一半", failed = false), ResultCardPolicy.outcome(stopped))
    }

    @Test
    fun noAnswerInThisTurnDoesNotReachBack() {
        val items = listOf(
            UserItem("u1", "上一轮"),
            AnswerItem("a1", "上一轮的回答", streaming = false),
            UserItem("u2", "打开计算器"),
            AnswerItem("a2", "   ", streaming = false),
            WorkItem("w1", emptyList()),
        )
        assertEquals(ResultCardPolicy.Outcome(ResultCardPolicy.NO_ANSWER, failed = false), ResultCardPolicy.outcome(items))
        assertEquals(ResultCardPolicy.NO_ANSWER, ResultCardPolicy.outcome(emptyList()).text)
    }
}
