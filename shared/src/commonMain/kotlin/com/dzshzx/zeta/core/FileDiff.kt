package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class DiffKind {
    Context,
    Added,
    Removed,
}

/** 对比里的一行。[oldLine]、[newLine] 是它在原文件、新文件里的行号（从 1 起），不知道或不适用时为 null。 */
@Immutable
data class DiffLine(
    val kind: DiffKind,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

/** 一段连续的改动连同前后几行上下文；两段之间的行没变，界面上用分隔条表示。 */
@Immutable data class DiffHunk(val lines: List<DiffLine>)

/**
 * Claude Code 给的改文件结果（工具结果里的 `tool_use_result`）： [created] 是 Write 新建了文件；[hunks] 来自
 * `structuredPatch`，带文件里的真实行号。
 */
@Immutable
data class EditPatch(val created: Boolean, val hunks: List<DiffHunk>) {
    companion object {
        /** 不是改文件工具的结果（或格式不对）时返回 null。 */
        fun parse(result: JsonObject?): EditPatch? {
            result ?: return null
            val patch = result["structuredPatch"] as? JsonArray ?: return null
            val hunks = patch.mapNotNull { hunk -> parseHunk(hunk as? JsonObject) }
            return EditPatch(created = result.str("type") == "create", hunks = hunks)
        }

        private fun parseHunk(hunk: JsonObject?): DiffHunk? {
            hunk ?: return null
            var old = hunk.int("oldStart") ?: return null
            var new = hunk.int("newStart") ?: return null
            val lines =
                (hunk["lines"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val raw = (element as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    when (raw.firstOrNull()) {
                        '+' -> DiffLine(DiffKind.Added, raw.substring(1), newLine = new++)
                        '-' -> DiffLine(DiffKind.Removed, raw.substring(1), oldLine = old++)
                        ' ' -> DiffLine(DiffKind.Context, raw.substring(1), old++, new++)
                        // "\ No newline at end of file" 之类的说明行
                        else -> null
                    }
                }
            return if (lines.isEmpty()) null else DiffHunk(lines)
        }
    }
}

/** 一次改文件的工具调用改了什么，界面据此画红绿对比。 */
@Immutable
data class FileChange(
    val path: String,
    val kind: Kind,
    val hunks: List<DiffHunk>,
    /** 行号是文件里的真实行号；只按新旧字符串对比时不知道在文件第几行，不显示行号。 */
    val numbered: Boolean,
    /** 历史记录里过长的内容被截断过，对比只到截断处。 */
    val truncated: Boolean = false,
    /** Edit 的 replace_all：文件里所有相同的片段都换了。 */
    val replaceAll: Boolean = false,
) {
    enum class Kind {
        Create,
        Overwrite,
        Edit,
    }

    val added: Int = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffKind.Added } }
    val removed: Int = hunks.sumOf { hunk -> hunk.lines.count { it.kind == DiffKind.Removed } }
    val lineCount: Int = hunks.sumOf { it.lines.size }
}

/** 会改文件、能画成对比的工具。 */
val FileEditTools = setOf("Edit", "MultiEdit", "Write", "edit_file", "write_file")

private val WriteToolNames = setOf("Write", "write_file")

/** zeta-helper 截断过长字符串时加的尾巴（不分语言）；只在步骤标着 clipped 时去掉它，不靠它认截断。 */
private const val ClipMarker = "\n…"

/**
 * 从一次工具调用算出文件改动；不是改文件的工具、参数还没到时返回 null。 有 Claude Code 给的 [patch] 就照它画（带真实行号），否则按参数里的新旧字符串自己对比。
 * [clipped] 是核心截断过参数（[ToolStep.clipped]），这时对比只画得出一部分。
 */
fun fileChangeOf(
    name: String,
    input: JsonObject?,
    patch: EditPatch?,
    clipped: Boolean = false,
): FileChange? {
    if (name !in FileEditTools || input == null) return null
    val path = input.str("file_path") ?: input.str("Path") ?: return null
    if (patch != null && (patch.hunks.isNotEmpty() || patch.created)) {
        return patchedFileChange(name, path, input, patch, clipped)
    }
    return when (name) {
        // 还不知道是不是新建：先当作整份新内容显示，结果到了再换成真实对比
        "Write",
        "write_file" -> writeAsNewFile(path, input, clipped)
        "Edit",
        "edit_file" -> singleEditChange(path, input, clipped)
        "MultiEdit" -> multiEditChange(path, input, clipped)
        else -> null
    }
}

private fun patchedFileChange(
    name: String,
    path: String,
    input: JsonObject,
    patch: EditPatch,
    clipped: Boolean,
): FileChange? =
    when {
        name in WriteToolNames && patch.created -> writeAsNewFile(path, input, clipped)
        name in WriteToolNames ->
            FileChange(path, FileChange.Kind.Overwrite, patch.hunks, numbered = true)
        else ->
            FileChange(
                path,
                FileChange.Kind.Edit,
                patch.hunks,
                numbered = true,
                replaceAll = input.bool("replace_all") == true || input.bool("ReplaceAll") == true,
            )
    }

private fun singleEditChange(
    path: String,
    input: JsonObject,
    clipped: Boolean,
): FileChange? {
    val oldRaw = input.str("old_string") ?: input.str("Target") ?: return null
    val newRaw = input.str("new_string") ?: input.str("Replacement") ?: return null
    val old = unclip(oldRaw, clipped)
    val new = unclip(newRaw, clipped)
    return FileChange(
        path,
        FileChange.Kind.Edit,
        LineDiff.hunks(old.first, new.first),
        numbered = false,
        truncated = old.second || new.second,
        replaceAll = input.bool("replace_all") == true || input.bool("ReplaceAll") == true,
    )
}

private fun multiEditChange(path: String, input: JsonObject, clipped: Boolean): FileChange? {
    var truncated = false
    val hunks =
        (input["edits"] as? JsonArray).orEmpty().flatMap { element ->
            val edit = element as? JsonObject ?: return@flatMap emptyList()
            val old = unclip(edit.str("old_string").orEmpty(), clipped)
            val new = unclip(edit.str("new_string").orEmpty(), clipped)
            truncated = truncated || old.second || new.second
            LineDiff.hunks(old.first, new.first)
        }
    return if (hunks.isEmpty()) null
    else
        FileChange(
            path,
            FileChange.Kind.Edit,
            hunks,
            numbered = false,
            truncated = truncated,
        )
}

private fun writeAsNewFile(path: String, input: JsonObject, clipped: Boolean): FileChange? {
    val raw = input.str("content") ?: input.str("Content") ?: return null
    val (content, truncated) = unclip(raw, clipped)
    val lines = splitLines(content)
    val hunk =
        DiffHunk(
            lines.mapIndexed { index, text -> DiffLine(DiffKind.Added, text, newLine = index + 1) },
        )
    return FileChange(
        path,
        FileChange.Kind.Create,
        if (lines.isEmpty()) emptyList() else listOf(hunk),
        numbered = true,
        truncated = truncated,
    )
}

private fun unclip(text: String, clipped: Boolean): Pair<String, Boolean> =
    if (clipped && text.endsWith(ClipMarker)) text.removeSuffix(ClipMarker) to true
    else text to false

/** 按行切开；结尾的换行不算多出一个空行。 */
fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n')
    return if (text.endsWith('\n')) lines.dropLast(1) else lines
}

/**
 * 行级对比（Myers 差分）。纯函数，不依赖 Android。
 *
 * 先去掉两边相同的开头和结尾，只对中间部分跑 Myers；编辑距离超过 [MaxEdits] 或中间部分太长时 不再找最短改法，整段当作“删掉旧的、加上新的”，免得大文件卡住界面。
 */
object LineDiff {
    const val MaxEdits = 400
    const val MaxLines = 100_000
    const val Context = 3

    enum class Op {
        Equal,
        Insert,
        Delete,
    }

    /**
     * 一步编辑：[Op.Equal] 和 [Op.Delete] 的 [oldIndex] 有效，[Op.Equal] 和 [Op.Insert] 的 [newIndex] 有效（都从 0
     * 起）。
     */
    data class Edit(val op: Op, val oldIndex: Int, val newIndex: Int)

    fun diff(old: List<String>, new: List<String>, maxEdits: Int = MaxEdits): List<Edit> {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (
            suffix < old.size - prefix &&
                suffix < new.size - prefix &&
                old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) suffix++
        val a = old.subList(prefix, old.size - suffix)
        val b = new.subList(prefix, new.size - suffix)
        val middle =
            (if (a.size + b.size <= MaxLines) myers(a, b, maxEdits) else null)
                ?: replaceAll(a.size, b.size)
        val edits = ArrayList<Edit>(prefix + middle.size + suffix)
        for (i in 0 until prefix) edits.add(Edit(Op.Equal, i, i))
        middle.forEach { edits.add(Edit(it.op, it.oldIndex + prefix, it.newIndex + prefix)) }
        for (i in 0 until suffix) edits.add(
            Edit(Op.Equal, old.size - suffix + i, new.size - suffix + i),
        )
        return edits
    }

    /** 两段文字的对比，切成带 [context] 行上下文的若干段。行号从 1 起，是相对这两段文字的。 */
    fun hunks(old: String, new: String, context: Int = Context): List<DiffHunk> {
        val a = splitLines(old)
        val b = splitLines(new)
        val lines =
            diff(a, b).map { edit ->
                when (edit.op) {
                    Op.Equal ->
                        DiffLine(
                            DiffKind.Context,
                            a[edit.oldIndex],
                            edit.oldIndex + 1,
                            edit.newIndex + 1,
                        )
                    Op.Delete ->
                        DiffLine(DiffKind.Removed, a[edit.oldIndex], oldLine = edit.oldIndex + 1)
                    Op.Insert ->
                        DiffLine(DiffKind.Added, b[edit.newIndex], newLine = edit.newIndex + 1)
                }
            }
        return group(lines, context)
    }

    /** 只留改动行和它们前后 [context] 行；相隔不超过 2×[context] 行的改动并成一段。 */
    fun group(lines: List<DiffLine>, context: Int = Context): List<DiffHunk> {
        val changed = lines.indices.filter { lines[it].kind != DiffKind.Context }
        if (changed.isEmpty()) return emptyList()
        val hunks = ArrayList<DiffHunk>()
        var start = (changed.first() - context).coerceAtLeast(0)
        var end = (changed.first() + context).coerceAtMost(lines.lastIndex)
        for (index in changed.drop(1)) {
            if (index - context <= end + 1) {
                end = (index + context).coerceAtMost(lines.lastIndex)
            } else {
                hunks.add(DiffHunk(lines.subList(start, end + 1).toList()))
                start = index - context
                end = (index + context).coerceAtMost(lines.lastIndex)
            }
        }
        hunks.add(DiffHunk(lines.subList(start, end + 1).toList()))
        return hunks
    }

    private fun replaceAll(oldSize: Int, newSize: Int): List<Edit> =
        List(oldSize) { Edit(Op.Delete, it, -1) } + List(newSize) { Edit(Op.Insert, -1, it) }

    /** Myers 的 O((N+M)·D) 算法；超过 [maxEdits] 步返回 null。 每一轮只存下 k ∈ [-d-1, d+1] 那一段，回溯用，内存是 O(D²)。 */
    private fun myers(a: List<String>, b: List<String>, maxEdits: Int): List<Edit>? {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return replaceAll(n, m)
        val trace = ArrayList<IntArray>()
        val found = myersSearch(a, b, maxEdits, trace)
        if (found < 0) return null
        return myersBacktrack(trace, found, n, m)
    }

    /** 前向搜索：每一轮先把 v 的那一段存进 [trace]，返回走到终点用了几步；超过 [maxEdits] 步是 -1。 */
    private fun myersSearch(
        a: List<String>,
        b: List<String>,
        maxEdits: Int,
        trace: MutableList<IntArray>,
    ): Int {
        val n = a.size
        val m = b.size
        val max = n + m
        val offset = max + 1
        val v = IntArray(2 * max + 3)
        for (d in 0..minOf(max, maxEdits)) {
            trace.add(v.copyOfRange(offset - d - 1, offset + d + 2))
            var k = -d
            while (k <= d) {
                val start =
                    if (fromAbove(k, d, v[offset + k - 1], v[offset + k + 1])) v[offset + k + 1]
                    else v[offset + k - 1] + 1
                val x = followSnake(a, b, start, k)
                v[offset + k] = x
                if (x >= n && x - k >= m) return d
                k += 2
            }
        }
        return -1
    }

    /** 第 d 轮对角线 k 上的点是从 k+1（插入）下来的，而不是从 k-1（删除）过来的；[left]、[right] 是上一轮 k-1、k+1 走到的 x。 */
    private fun fromAbove(k: Int, d: Int, left: Int, right: Int): Boolean =
        k == -d || (k != d && left < right)

    /** 从对角线 [k] 上的 x = [start] 起，沿相同的行一直往下走，返回走到的 x。 */
    private fun followSnake(a: List<String>, b: List<String>, start: Int, k: Int): Int {
        var x = start
        var y = x - k
        while (x < a.size && y < b.size && a[x] == b[y]) {
            x++
            y++
        }
        return x
    }

    /** 从终点照 [trace] 往回走到起点，得出每一步的编辑。 */
    private fun myersBacktrack(trace: List<IntArray>, found: Int, n: Int, m: Int): List<Edit> {
        val edits = ArrayList<Edit>()
        var x = n
        var y = m
        for (d in found downTo 0) {
            val snapshot = trace[d]
            fun at(k: Int) = snapshot[k + d + 1]
            val k = x - y
            val prevK = if (fromAbove(k, d, at(k - 1), at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                x--
                y--
                edits.add(Edit(Op.Equal, x, y))
            }
            if (d > 0) edits.add(stepEdit(x == prevX, prevX, prevY))
            x = prevX
            y = prevY
        }
        edits.reverse()
        return edits
    }

    /** 回溯时这一轮那一步：x 没变是插入新的第 [prevY] 行，否则是删掉旧的第 [prevX] 行。 */
    private fun stepEdit(inserted: Boolean, prevX: Int, prevY: Int): Edit =
        if (inserted) Edit(Op.Insert, -1, prevY) else Edit(Op.Delete, prevX, -1)
}
