package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.CommandResult

/** 设备工具执行命令的口子：平时是 [rootShell]，单元测试里换成假的。 */
internal fun interface Shell {
    fun run(command: String, timeoutMs: Long): CommandResult
}

/** 交给 Claude 的失败原因（中文，一句话）。 */
internal class ToolError(message: String) : Exception(message)

internal val RootShell = Shell { command, timeoutMs -> rootShell(command, timeoutMs) }

/** 执行并返回标准输出；退出码不是 0 时抛 [ToolError]，带上 stderr（或 stdout）开头一段。 */
internal fun Shell.out(command: String, timeoutMs: Long = 15_000): String {
    val result = run(command, timeoutMs)
    if (result.exitCode == 0) return result.stdout
    if (result.exitCode == -1 && result.stderr == "timeout") throw ToolError("命令超时：${command.take(80)}")
    val detail = result.stderr.ifBlank { result.stdout }.trim().take(300).ifBlank { "退出码 ${result.exitCode}" }
    throw ToolError("执行失败：$detail")
}

/** sh 的单引号引用。 */
internal fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * `content`、`svc` 一类经 app_process 起 Java 的命令要系统的启动类路径；经 su 进来时环境里不一定有，
 * 从 system_server 那里抄一份（做法同 VirtualScreen 起辅助进程）。
 */
internal const val SYSTEM_ENV =
    "for kv in \$(tr '\\000' ' ' < /proc/\$(pidof system_server)/environ); do " +
        "case \"\$kv\" in BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*|ANDROID_*=*) export \"\$kv\";; esac; done; "

/** 一段脚本里用 `echo '##名字'` 分出的几节输出：名字 → 这一节的文字。 */
internal fun sections(output: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    var name: String? = null
    val body = StringBuilder()
    fun flush() {
        name?.let { result[it] = body.toString().trimEnd() }
        body.setLength(0)
    }
    for (line in output.lineSequence()) {
        if (line.startsWith(SECTION)) {
            flush()
            name = line.removePrefix(SECTION).trim()
        } else if (name != null) {
            body.append(line).append('\n')
        }
    }
    flush()
    return result
}

internal const val SECTION = "##"

/** 一节脚本：先打出节名，再执行 [script]（出错也不打断后面的节）。 */
internal fun section(name: String, script: String): String = "echo '$SECTION$name'; { $script ; } 2>&1; "

/**
 * 解析 `content query` 的输出。每行形如 `Row: 0 a=1, b=x, c=…`；值里可能有逗号，
 * 所以按 [columns]（查询时的 projection 顺序）找下一个 `, 列名=` 来切，最后一列取到行尾。
 */
internal fun contentRows(output: String, columns: List<String>): List<Map<String, String>> =
    output.lineSequence().mapNotNull { line ->
        val match = ROW.find(line) ?: return@mapNotNull null
        val rest = line.substring(match.range.last + 1)
        val row = LinkedHashMap<String, String>()
        var start = 0
        for ((i, column) in columns.withIndex()) {
            val head = if (i == 0) "$column=" else ", $column="
            val at = rest.indexOf(head, start)
            if (at < 0) return@mapNotNull null
            val valueStart = at + head.length
            val end = columns.getOrNull(i + 1)?.let { rest.indexOf(", $it=", valueStart).takeIf { e -> e >= 0 } } ?: rest.length
            row[column] = rest.substring(valueStart, end)
            start = end
        }
        row
    }.toList()

private val ROW = Regex("^Row: \\d+ ")

/** 包名格式（不查是否存在）。 */
internal val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

/** 毫秒数说成「1 小时 5 分」这样。 */
internal fun spoken(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> if (m > 0) "${h}h${m}m" else "${h}h"
        m > 0 -> if (s > 0 && m < 10) "${m}m${s}s" else "${m}m"
        else -> "${s}s"
    }
}

/** 字节数说成 KB / MB / GB。 */
internal fun bytes(value: Long): String = when {
    value >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / (1L shl 30).toDouble())
    value >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", value / (1L shl 20).toDouble())
    value >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.0f KB", value / (1L shl 10).toDouble())
    else -> "$value B"
}
