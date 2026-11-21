package com.dzshzx.zeta.core

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.dzshzx.zeta.ui.chat.insertPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SavedPromptsTest {
    @Test
    fun defaultNameIsTheOpeningOfTheFirstLine() {
        assertEquals("检查 Git 状态", SavedPrompts.defaultName("\n  检查 Git 状态\n然后汇报"))
        assertEquals("看一下这个项目的结构和最…", SavedPrompts.defaultName("看一下这个项目的结构和最近的提交，告诉我做到哪了"))
        assertEquals("a b c", SavedPrompts.defaultName("a   b\tc"))
        assertEquals("", SavedPrompts.defaultName("  \n "))
        // 按码点数，不切开表情
        assertEquals("😀".repeat(12) + "…", SavedPrompts.defaultName("😀".repeat(13)))
    }

    @Test
    fun nameEqualToTheDefaultIsStoredEmptySoItFollowsTheText() {
        val list = SavedPrompts.add(emptyList(), "a", "检查 Git 状态", "检查 Git 状态")
        assertEquals("", list.single().name)
        val edited = SavedPrompts.update(list, "a", "", "看看磁盘还剩多少")
        assertEquals("看看磁盘还剩多少", SavedPrompts.displayName(edited.single()))
        val named = SavedPrompts.update(edited, "a", "  磁盘  ", "看看磁盘还剩多少")
        assertEquals("磁盘", named.single().name)
    }

    @Test
    fun encodeDecodeKeepsOrderAndSkipsBadEntries() {
        val list =
            SavedPrompts.add(SavedPrompts.add(emptyList(), "1", "", "第一条"), "2", "二", "第二条\n多行")
        // 新加的在最前面
        assertEquals(listOf("2", "1"), list.map { it.id })
        assertEquals(list, SavedPrompts.decode(SavedPrompts.encode(list)))
        val messy =
            """[{"id":"x","name":"n","text":"ok"},{"id":"y","text":"  "},{"name":"no id","text":"still ok"},3,{"id":"x","text":"dup"}]"""
        val decoded = SavedPrompts.decode(messy)
        assertEquals(listOf("ok", "still ok"), decoded.map { it.text })
        assertEquals(emptyList<SavedPrompt>(), SavedPrompts.decode("not json"))
        assertEquals(emptyList<SavedPrompt>(), SavedPrompts.decode(null))
    }

    @Test
    fun moveRemoveAndDuplicates() {
        val list =
            listOf(SavedPrompt("a", "", "A"), SavedPrompt("b", "", "B"), SavedPrompt("c", "", "C"))
        assertEquals(listOf("b", "a", "c"), SavedPrompts.move(list, "b", -1).map { it.id })
        assertEquals(listOf("a", "c", "b"), SavedPrompts.move(list, "b", 1).map { it.id })
        assertSame(list, SavedPrompts.move(list, "a", -1))
        assertSame(list, SavedPrompts.move(list, "c", 1))
        assertSame(list, SavedPrompts.move(list, "zzz", 1))
        assertEquals(listOf("a", "c"), SavedPrompts.remove(list, "b").map { it.id })
        assertEquals("b", SavedPrompts.findSame(list, "  B\n")?.id)
        assertNull(SavedPrompts.findSame(list, "D"))
    }

    @Test
    fun problems() {
        assertEquals("内容不能为空", SavedPrompts.problem(emptyList(), "名字", "  "))
        assertNotNull(SavedPrompts.problem(emptyList(), "", "x".repeat(SavedPrompts.MAX_TEXT + 1)))
        assertNotNull(
            SavedPrompts.problem(emptyList(), "n".repeat(SavedPrompts.MAX_NAME + 1), "ok"),
        )
        val full = List(SavedPrompts.MAX) { SavedPrompt("$it", "", "p$it") }
        assertNotNull(SavedPrompts.problem(full, "", "new"))
        // 满了也能改已有的
        assertNull(SavedPrompts.problem(full, "", "changed", editingId = "3"))
    }

    @Test
    fun pickingFillsEmptyInputOrInsertsAtTheCursor() {
        val empty = TextFieldState("  ")
        insertPrompt(empty, "检查 Git 状态")
        assertEquals("检查 Git 状态", empty.text.toString())
        assertEquals(TextRange(9), empty.selection)

        val draft = TextFieldState("先做 再做", initialSelection = TextRange(3))
        insertPrompt(draft, "这个")
        assertEquals("先做 这个再做", draft.text.toString())
        assertEquals(TextRange(5), draft.selection)

        val selected = TextFieldState("把 XX 改掉", initialSelection = TextRange(2, 4))
        insertPrompt(selected, "这里")
        assertEquals("把 这里 改掉", selected.text.toString())
    }
}
