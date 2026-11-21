package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDiffTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun <T : Any> notNull(value: T?): T = value ?: throw AssertionError("expected a value")

    /** 把编辑序列重放一遍，得到的应当正是新文本；同时返回改动行数。 */
    private fun replay(old: List<String>, new: List<String>, edits: List<LineDiff.Edit>): Int {
        val rebuilt = ArrayList<String>()
        var changes = 0
        var oldCursor = 0
        var newCursor = 0
        for (edit in edits) {
            when (edit.op) {
                LineDiff.Op.Equal -> {
                    assertEquals(oldCursor, edit.oldIndex)
                    assertEquals(newCursor, edit.newIndex)
                    assertEquals(old[edit.oldIndex], new[edit.newIndex])
                    rebuilt.add(old[oldCursor++])
                    newCursor++
                }
                LineDiff.Op.Delete -> {
                    assertEquals(oldCursor, edit.oldIndex)
                    oldCursor++
                    changes++
                }
                LineDiff.Op.Insert -> {
                    assertEquals(newCursor, edit.newIndex)
                    rebuilt.add(new[newCursor++])
                    changes++
                }
            }
        }
        assertEquals(old.size, oldCursor)
        assertEquals(new, rebuilt)
        return changes
    }

    @Test
    fun diffFindsMinimalEdits() {
        val old = "a b c a b b a".split(' ')
        val new = "c b a b a c".split(' ')
        // Myers 论文里的例子：最短编辑距离是 5
        assertEquals(5, replay(old, new, LineDiff.diff(old, new)))
    }

    @Test
    fun diffHandlesEmptySides() {
        assertEquals(0, replay(emptyList(), emptyList(), LineDiff.diff(emptyList(), emptyList())))
        assertEquals(
            2,
            replay(emptyList(), listOf("x", "y"), LineDiff.diff(emptyList(), listOf("x", "y"))),
        )
        assertEquals(1, replay(listOf("x"), emptyList(), LineDiff.diff(listOf("x"), emptyList())))
        assertEquals(
            0,
            replay(listOf("same"), listOf("same"), LineDiff.diff(listOf("same"), listOf("same"))),
        )
    }

    @Test
    fun diffMatchesSmallCasesExhaustively() {
        // 两个字母的字母表上所有长度不超过 5 的串两两对比：结果都能重放，且编辑数等于 LCS 算出的最短值
        val words =
            (0..5).flatMap { length ->
                (0 until (1 shl length)).map { bits ->
                    List(length) { if (bits shr it and 1 == 1) "x" else "y" }
                }
            }
        for (a in words) for (b in words) {
            val changes = replay(a, b, LineDiff.diff(a, b))
            assertEquals("$a → $b", a.size + b.size - 2 * lcs(a, b), changes)
        }
    }

    private fun lcs(a: List<String>, b: List<String>): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            table[i][j] =
                if (a[i] == b[j]) table[i + 1][j + 1] + 1
                else maxOf(table[i + 1][j], table[i][j + 1])
        }
        return table[0][0]
    }

    @Test
    fun largeInputsFallBackWithoutHanging() {
        // 两份完全不同的大文件：超过编辑上限后整段当作删旧加新，几毫秒内返回
        val old = List(20_000) { "old line $it" }
        val new = List(20_000) { "new line $it" }
        val started = System.nanoTime()
        val edits = LineDiff.diff(old, new)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(40_000, replay(old, new, edits))
        assertTrue("took $millis ms", millis < 2_000)
    }

    @Test
    fun largeFileWithSmallChangeStaysExact() {
        val old = List(50_000) { "line $it" }
        val new =
            old.toMutableList().apply {
                set(10, "changed 10")
                add(30_000, "inserted")
                removeAt(40_000)
            }
        assertEquals(4, replay(old, new, LineDiff.diff(old, new)))
    }

    @Test
    fun hunksKeepThreeLinesOfContext() {
        val old = (1..20).joinToString("\n") { "line $it" }
        val new = old.replace("line 5\n", "line five\n").replace("line 18\n", "")
        val hunks = LineDiff.hunks(old, new)
        assertEquals(2, hunks.size)
        val first = hunks[0].lines
        assertEquals(listOf("line 2", "line 3", "line 4"), first.take(3).map { it.text })
        assertEquals(DiffLine(DiffKind.Removed, "line 5", oldLine = 5), first[3])
        assertEquals(DiffLine(DiffKind.Added, "line five", newLine = 5), first[4])
        assertEquals(DiffLine(DiffKind.Context, "line 6", 6, 6), first[5])
        val second = hunks[1].lines
        assertEquals(
            DiffLine(DiffKind.Removed, "line 18", oldLine = 18),
            second.single { it.kind != DiffKind.Context },
        )
        // 两处改动隔得近时并成一段
        assertEquals(1, LineDiff.hunks("a\nb\nc\nd\ne", "A\nb\nc\nd\nE").size)
        assertTrue(LineDiff.hunks("same", "same").isEmpty())
    }

    @Test
    fun editUsesStringDiffWithoutLineNumbers() {
        val input =
            json(
                """{"file_path":"/w/app/Main.kt","old_string":"val a = 1\nval b = 2","new_string":"val a = 1\nval b = 3\nval c = 4"}""",
            )
        val change = notNull(fileChangeOf("Edit", input, patch = null))
        assertEquals("/w/app/Main.kt", change.path)
        assertEquals(FileChange.Kind.Edit, change.kind)
        assertFalse(change.numbered)
        assertEquals(2, change.added)
        assertEquals(1, change.removed)
    }

    @Test
    fun structuredPatchGivesRealLineNumbers() {
        val result =
            json(
                """{"filePath":"/w/a.txt","oldString":"b","newString":"c","originalFile":"…","structuredPatch":[
              {"oldStart":40,"oldLines":3,"newStart":40,"newLines":3,"lines":[" a","-b","+c"," d","\\ No newline at end of file"]}
            ],"userModified":false,"replaceAll":false}""",
            )
        val patch = notNull(EditPatch.parse(result))
        assertFalse(patch.created)
        val change =
            notNull(
                fileChangeOf(
                    "Edit",
                    json("""{"file_path":"/w/a.txt","old_string":"b","new_string":"c"}"""),
                    patch,
                ),
            )
        assertTrue(change.numbered)
        assertEquals(
            listOf(
                DiffLine(DiffKind.Context, "a", 40, 40),
                DiffLine(DiffKind.Removed, "b", oldLine = 41),
                DiffLine(DiffKind.Added, "c", newLine = 41),
                DiffLine(DiffKind.Context, "d", 42, 42),
            ),
            change.hunks.single().lines,
        )
        // 别的工具的结构化结果不是改动
        assertNull(EditPatch.parse(json("""{"stdout":"ok","stderr":""}""")))
    }

    @Test
    fun writeShowsWholeFileAsAdded() {
        val input = json("""{"file_path":"/tmp/x/notes.md","content":"one\ntwo\nthree\n"}""")
        val running = notNull(fileChangeOf("Write", input, patch = null))
        assertEquals(FileChange.Kind.Create, running.kind)
        assertEquals(3, running.added)
        assertEquals(listOf(1, 2, 3), running.hunks.single().lines.map { it.newLine })
        val created =
            notNull(fileChangeOf("Write", input, EditPatch(created = true, hunks = emptyList())))
        assertEquals(FileChange.Kind.Create, created.kind)
        // 覆盖已有文件：照 Claude Code 给的对比画
        val overwrite =
            EditPatch.parse(
                json(
                    """{"type":"update","structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-old","+one"]}]}""",
                ),
            )
        val updated = notNull(fileChangeOf("Write", input, overwrite))
        assertEquals(FileChange.Kind.Overwrite, updated.kind)
        assertEquals(1, updated.added)
        assertEquals(1, updated.removed)
    }

    @Test
    fun clippedHistoryIsMarkedTruncated() {
        val input = json("""{"file_path":"/a","old_string":"x","new_string":"y\n…（已截断）"}""")
        val change = notNull(fileChangeOf("Edit", input, patch = null))
        assertTrue(change.truncated)
        assertEquals(listOf("x", "y"), change.hunks.single().lines.map { it.text })
    }

    @Test
    fun multiEditConcatenatesEachEdit() {
        val input =
            json(
                """{"file_path":"/a","edits":[{"old_string":"a","new_string":"b"},{"old_string":"c\nd","new_string":"c\ne"}]}""",
            )
        val change = notNull(fileChangeOf("MultiEdit", input, patch = null))
        assertEquals(2, change.hunks.size)
        assertEquals(2, change.added)
        assertEquals(2, change.removed)
    }

    @Test
    fun otherToolsAreNotFileChanges() {
        assertNull(fileChangeOf("Read", json("""{"file_path":"/a"}"""), null))
        assertNull(fileChangeOf("Edit", null, null))
        assertNull(fileChangeOf("Edit", json("""{"file_path":"/a"}"""), null))
    }

    @Test
    fun reducerAttachesPatchFromToolUseResult() {
        val reducer = TranscriptReducer()
        reducer.applyAssistant(
            json(
                """{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"/a","old_string":"x","new_string":"y"}}]}""",
            ),
        )
        reducer.applyUser(
            json(
                """{"content":[{"type":"tool_result","tool_use_id":"t1","content":"The file /a has been updated successfully."}]}""",
            ),
            replay = false,
            toolUseResult =
                json(
                    """{"structuredPatch":[{"oldStart":7,"oldLines":1,"newStart":7,"newLines":1,"lines":["-x","+y"]}]}""",
                ),
        )
        val step = (reducer.items.single() as WorkItem).steps.single() as ToolStep
        assertEquals(ToolStatus.Success, step.status)
        assertEquals(7, notNull(step.patch).hunks.single().lines.first().oldLine)
    }
}
