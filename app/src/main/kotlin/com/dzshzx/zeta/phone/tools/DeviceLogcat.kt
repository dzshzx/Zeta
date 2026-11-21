package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.clipChars
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** logcat：读最近的系统日志，按应用、标签、级别、正则、时间筛，条数有上限。 */
internal object DeviceLogcat {
    val LEVELS = listOf("V", "D", "I", "W", "E", "F")
    val BUFFERS = listOf("main", "system", "crash", "events", "radio", "all")
    const val DEFAULT_LINES = 100
    const val MAX_LINES = 500

    /** 返回给 Claude 的总字数上限；单行过长截断。 */
    const val MAX_CHARS = 60_000
    private const val MAX_LINE = 1_000

    class Query(
        val lines: Int = DEFAULT_LINES,
        val uid: Int? = null,
        val tags: List<String> = emptyList(),
        val level: String = "V",
        val grep: String? = null,
        val since: LocalDateTime? = null,
        val buffer: String? = null,
    )

    /** 包名 → uid 的命令，输出形如 `package:com.x uid:10282`。 */
    fun uidCommand(packageName: String): String =
        "pm list packages -U ${LinuxEnv.quote(packageName)}"

    fun parseUid(output: String, packageName: String): Int? =
        output
            .lineSequence()
            .firstOrNull { it.trim().startsWith("package:$packageName uid:") }
            ?.substringAfter(" uid:")
            ?.takeWhile { it.isDigit() }
            ?.toIntOrNull()

    /** 标签只认字母、数字和 `_.-$/[]`，逗号分开。 */
    fun parseTags(value: String?): List<String> {
        val tags = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        tags.firstOrNull { !TAG.matches(it) }?.let { throw ToolError("tag「$it」里有不支持的字符") }
        return tags
    }

    fun command(q: Query): String = buildString {
        append("logcat -d -v threadtime")
        q.buffer?.let { append(" -b ").append(it) }
        q.uid?.let { append(" --uid=").append(it) }
        q.since?.let { append(" -T ").append(LinuxEnv.quote(it.format(SINCE))) }
        q.grep?.let { append(" -e ").append(LinuxEnv.quote(it)) }
        if (q.tags.isEmpty()) append(" '*:").append(q.level).append("'")
        else {
            for (tag in q.tags) append(' ').append(LinuxEnv.quote("$tag:${q.level}"))
            append(" '*:S'")
        }
        // 先在手机上截掉多余的行，不把整个缓冲区搬过来
        append(" | grep -v '^--------- beginning of' | tail -n ")
            .append(q.lines.coerceIn(1, MAX_LINES))
    }

    /** 截掉过长的行，总长超出时丢掉前面较旧的行。 */
    fun trim(output: String): String {
        val lines = output.lines().filter { it.isNotEmpty() }.map { it.clipChars(MAX_LINE, "…") }
        val kept = ArrayDeque<String>()
        var total = 0
        for (line in lines.asReversed()) {
            if (total + line.length + 1 > MAX_CHARS) break
            kept.addFirst(line)
            total += line.length + 1
        }
        val dropped = lines.size - kept.size
        return buildString {
            if (dropped > 0) append("(older $dropped lines omitted to fit)\n")
            append(kept.joinToString("\n"))
        }
    }

    private val TAG = Regex("[A-Za-z0-9_.$/\\[\\]\\-]{1,100}")
    private val SINCE = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.000", Locale.ROOT)
}
