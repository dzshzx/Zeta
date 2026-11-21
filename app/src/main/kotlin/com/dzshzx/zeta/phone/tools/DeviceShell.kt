package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.LinuxEnv

/** 设备工具执行命令的口子：平时是 [rootShell]，单元测试里换成假的。 */
internal fun interface Shell {
    fun run(command: String, timeoutMs: Long): CommandResult
}

/** 交给 Claude 的失败原因（中文，一句话）：[toolCall] 把 message 原样交出去。参数不对的是 [BadInput]。 */
internal open class ToolError(message: String) : Exception(message)

internal val RootShell = Shell { command, timeoutMs -> rootShell(command, timeoutMs) }

/** 执行并返回标准输出；退出码不是 0 时抛 [ToolError]，带上 stderr（或 stdout）开头一段。 */
internal fun Shell.out(command: String, timeoutMs: Long = 15_000): String {
    val result = run(command, timeoutMs)
    if (result.exitCode == 0) return result.stdout
    if (result.timedOut) throw ToolError("命令超时：${command.take(80)}")
    val detail =
        result.stderr
            .ifBlank { result.stdout }
            .trim()
            .take(300)
            .ifBlank { "退出码 ${result.exitCode}" }
    throw ToolError("执行失败：$detail")
}

/**
 * app_process 起的 Java 进程要和系统一样的启动类路径；经 su 进来时环境里不一定有，从 system_server 那里抄一份。 [tr] 是用哪个 tr：经
 * [LinuxEnv.androidScript] 跑时用 $1 的 BusyBox，经 [rootShell] 跑时没有 $1，用系统的。
 */
private fun systemEnv(tr: String): String =
    "for kv in \$($tr '\\000' ' ' < /proc/\$(pidof system_server)/environ); do " +
        "case \"\$kv\" in BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*|ANDROID_*=*) export \"\$kv\";; esac; done; "

/** `content`、`svc` 一类经 app_process 起 Java 的系统命令，经 [rootShell] 跑时前面加这一段。 */
internal val SYSTEM_ENV = systemEnv("tr")

/**
 * 以 root 起 app_process 辅助进程，跑本 APK（[apk]）里的 [mainClass]，[args] 依次是它 main 的参数。 返回给 `su -c` 的整段脚本：经
 * [LinuxEnv.androidScript] 在 Android 一侧执行，APK 路径和参数走位置参数（$2 起），不拼进脚本。 [uid] 不为空时以这个身份（uid 和 gid
 * 相同）起，用 BusyBox nsenter 的 -S、-G：Binder 在 app_process 起来时就按当时的身份打开了， 进程里再换身份不管用。
 */
internal fun appProcess(
    apk: String,
    mainClass: String,
    args: List<String> = emptyList(),
    uid: Int? = null,
): String {
    require(args.size <= 7) { "位置参数最多到 \$9" }
    val launch =
        if (uid == null) "exec app_process"
        else "exec \"\$1\" nsenter -t 1 -m -S $uid -G $uid -- app_process"
    val script =
        systemEnv("\"\$1\" tr") +
            "CLASSPATH=\"\$2\" $launch /system/bin $mainClass" +
            args.indices.joinToString("") { " \"\$${it + 3}\"" }
    return LinuxEnv.androidScript(script, apk, *args.toTypedArray())
}

/** 同 [appProcess]，给 [rootShell] 跑的一段命令：APK 路径引好写进去，[words] 原样拼在后面，只放固定的词。 */
internal fun appProcessCommand(apk: String, mainClass: String, vararg words: String): String =
    SYSTEM_ENV +
        "CLASSPATH=${LinuxEnv.quote(apk)} exec app_process /system/bin $mainClass" +
        words.joinToString("") { " $it" }

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
internal fun section(name: String, script: String): String =
    "echo '$SECTION$name'; { $script ; } 2>&1; "

/**
 * 解析 `content query` 的输出。每行形如 `Row: 0 a=1, b=x, c=…`；值里可能有逗号， 所以按 [columns]（查询时的 projection 顺序）找下一个
 * `, 列名=` 来切，最后一列取到行尾。
 */
internal fun contentRows(output: String, columns: List<String>): List<Map<String, String>> =
    output
        .lineSequence()
        .mapNotNull { line ->
            val match = ROW.find(line) ?: return@mapNotNull null
            val rest = line.substring(match.range.last + 1)
            val row = LinkedHashMap<String, String>()
            var start = 0
            for ((i, column) in columns.withIndex()) {
                val head = if (i == 0) "$column=" else ", $column="
                val at = rest.indexOf(head, start)
                if (at < 0) return@mapNotNull null
                val valueStart = at + head.length
                val end =
                    columns.getOrNull(i + 1)?.let {
                        rest.indexOf(", $it=", valueStart).takeIf { e -> e >= 0 }
                    } ?: rest.length
                row[column] = rest.substring(valueStart, end)
                start = end
            }
            row
        }
        .toList()

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
internal fun bytes(value: Long): String =
    when {
        value >= 1L shl 30 ->
            String.format(java.util.Locale.ROOT, "%.1f GB", value / (1L shl 30).toDouble())
        value >= 1L shl 20 ->
            String.format(java.util.Locale.ROOT, "%.0f MB", value / (1L shl 20).toDouble())
        value >= 1L shl 10 ->
            String.format(java.util.Locale.ROOT, "%.0f KB", value / (1L shl 10).toDouble())
        else -> "$value B"
    }
