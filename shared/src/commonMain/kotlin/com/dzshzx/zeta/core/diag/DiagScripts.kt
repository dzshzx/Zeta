package com.dzshzx.zeta.core.diag

import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.i18n.Strings
import java.util.Base64

/** 诊断页“Claude Code”一节列出的日志文件从哪来。 */
enum class LogKind(val code: String) {
    /** 正在跑的对话进程的标准错误输出（`~/.cache/<名字>/hosts/<标识>/err.log`）。 */
    Running("running"),

    /** 已经结束的对话进程留下的那份（zeta_host.sh 存到 `~/.cache/<名字>/stderr/`）。 */
    Finished("finished"),

    /** Claude Code 自己的调试日志（`~/.claude/debug/`）。 */
    Debug("debug"),

    /** Antigravity CLI 自己的运行日志（`~/.gemini/antigravity-cli/log/`）。 */
    Agy("agy");

    /** 界面上的名字，按界面语言取。 */
    val label: String
        get() =
            when (this) {
                Running -> Strings.diagLogRunning()
                Finished -> Strings.diagLogFinished()
                Debug -> Strings.diagLogDebug()
                Agy -> Strings.diagLogAgy()
            }
}

/** Linux 环境里的一个日志文件。[modified] 是毫秒；[title] 是对话标题（只有运行中的有）。 */
data class LogFile(
    val kind: LogKind,
    val path: String,
    val size: Long,
    val modified: Long,
    val title: String = "",
) {
    val name: String
        get() = path.substringAfterLast('/')
}

/** 进 Linux 环境查一次得到的东西。 */
data class LinuxProbe(
    val claude: String,
    val node: String,
    val debian: String,
    val files: List<LogFile>,
    val agy: String = "",
) {
    fun files(kind: LogKind): List<LogFile> = files.filter { it.kind == kind }
}

/** 诊断页在 Linux 环境里跑的几段脚本（以 Claude Code 的那个 Linux 用户身份），和解析它们输出的纯函数。 只读，不改 `~/.claude` 里的任何东西。 */
object DiagScripts {
    /** 看日志、打包时每个文件只取最后这么多字节。 */
    const val TAIL_BYTES = 64 * 1024

    /** 结束的对话进程留几份、调试日志列几个（和 zeta_host.sh 里留的份数一致）。 */
    const val FINISHED_LIMIT = 20
    const val DEBUG_LIMIT = 30

    /** 版本号，加上可看的日志文件：每行 `file<Tab>类别<Tab>修改时间（秒）<Tab>字节数<Tab>路径<Tab>标题`。 */
    val PROBE: String =
        """
        H="${'$'}HOME/.cache/${'$'}{ZETA_NAME:-zeta}"
        echo "claude=${'$'}(claude --version 2>/dev/null | head -n 1)"
        echo "agy=${'$'}(agy --version 2>/dev/null | head -n 1)"
        echo "node=${'$'}(node --version 2>/dev/null | head -n 1)"
        echo "debian=${'$'}(cat /etc/debian_version 2>/dev/null)"
        for f in "${'$'}H"/hosts/*/err.log; do
          [ -f "${'$'}f" ] || continue
          t=${'$'}(head -c 200 "${'$'}{f%/err.log}/title" 2>/dev/null | tr '\t\n' '  ')
          printf 'file\trunning\t%s\t%s\t%s\t%s\n' "${'$'}(stat -c %Y "${'$'}f")" "${'$'}(stat -c %s "${'$'}f")" "${'$'}f" "${'$'}t"
        done
        find "${'$'}H/stderr" -maxdepth 1 -type f -name '*.log' -printf '%T@\t%s\t%p\n' 2>/dev/null | sort -rn | head -n $FINISHED_LIMIT | sed 's/^/file\tfinished\t/'
        find "${'$'}HOME/.claude/debug" -maxdepth 1 -type f -printf '%T@\t%s\t%p\n' 2>/dev/null | sort -rn | head -n $DEBUG_LIMIT | sed 's/^/file\tdebug\t/'
        find "${'$'}HOME/.gemini/antigravity-cli/log" -maxdepth 1 -type f -name '*.log' -printf '%T@\t%s\t%p\n' 2>/dev/null | sort -rn | head -n $DEBUG_LIMIT | sed 's/^/file\tagy\t/'
        exit 0
    """
            .trimIndent()

    /** 打包用：每个参数一行 `路径<Tab>最后 TAIL_BYTES 字节的 base64`。 */
    val TAILS: String =
        "for f in \"\$@\"; do printf '%s\\t' \"\$f\"; tail -c $TAIL_BYTES -- \"\$f\" 2>/dev/null | base64 -w0; echo; done"

    fun parseProbe(stdout: String): LinuxProbe {
        var claude = ""
        var agy = ""
        var node = ""
        var debian = ""
        val files = ArrayList<LogFile>()
        for (line in stdout.lineSequence()) {
            when {
                line.startsWith("claude=") ->
                    claude = line.removePrefix("claude=").trim().substringBefore(" (")
                line.startsWith("agy=") ->
                    agy = line.removePrefix("agy=").trim().substringBefore(" (")
                line.startsWith("node=") ->
                    node = line.removePrefix("node=").trim().removePrefix("v")
                line.startsWith("debian=") -> debian = line.removePrefix("debian=").trim()
                line.startsWith("file\t") -> parseFile(line)?.let(files::add)
            }
        }
        val sorted =
            files
                .distinctBy { it.path }
                .sortedWith(compareBy<LogFile> { it.kind.ordinal }.thenByDescending { it.modified })
        return LinuxProbe(claude, node, debian, sorted, agy)
    }

    private fun parseFile(line: String): LogFile? {
        val f = line.split('\t')
        if (f.size < 5) return null
        val kind = LogKind.entries.firstOrNull { it.code == f[1] } ?: return null
        val modified = f[2].toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return null
        val size = f[3].toLongOrNull() ?: return null
        val path = f[4].takeIf { it.startsWith("/") } ?: return null
        return LogFile(kind, path, size, modified, f.getOrNull(5).orEmpty().trim())
    }

    /**
     * 只许读这几处的文件：`~/.claude/debug/` 下的，`~/.gemini/antigravity-cli/log/` 下的，Zeta 自己（[linuxName]）的
     * `stderr/` 下的，和 `hosts/<标识>/err.log`。路径里不能有 `..`。
     */
    fun readable(path: String, home: String, linuxName: String): Boolean {
        if (!path.startsWith("/") || path.split('/').any { it == ".." || it == "." }) return false
        val h = home.trimEnd('/')
        val cache = "$h/.cache/$linuxName/"
        fun directChild(dir: String) =
            path.startsWith(dir) && path.length > dir.length && '/' !in path.substring(dir.length)
        return directChild("$h/.claude/debug/") ||
            directChild("$h/.gemini/antigravity-cli/log/") ||
            directChild("${cache}stderr/") ||
            (path.startsWith("${cache}hosts/") &&
                path.endsWith("/err.log") &&
                path.removePrefix("${cache}hosts/").count { it == '/' } == 1)
    }

    /** [TAILS] 的输出：路径 → 内容（按 UTF-8 解，坏字节换成替代符）。解不开的行跳过。 */
    fun parseTails(stdout: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in stdout.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val bytes =
                runCatching { Base64.getDecoder().decode(line.substring(tab + 1).trim()) }
                    .getOrNull() ?: continue
            result[line.substring(0, tab)] = String(bytes, Charsets.UTF_8)
        }
        return result
    }

    /** 文件大小给人看（见 [formatBytes]）。 */
    fun size(bytes: Long): String = formatBytes(bytes)
}

/** 诊断包里的文件：名字 → 内容。全部打过码。纯函数。 */
object DiagBundle {
    /**
     * [info] 环境与权限，[log] 运行日志，[logcat] 本应用的 logcat，[tails] Claude Code 那边各文件的尾部 （键是
     * [LogFile]，值是内容）。同名的文件加序号区分。
     */
    fun files(
        info: String,
        log: String,
        logcat: String,
        tails: Map<LogFile, String>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["info.txt"] = info
        out["zeta-log.txt"] = log
        out["logcat.txt"] = logcat
        for ((file, text) in tails) {
            val dir =
                when (file.kind) {
                    LogKind.Running -> "claude-stderr/running"
                    LogKind.Finished -> "claude-stderr"
                    LogKind.Debug -> "claude-debug"
                    LogKind.Agy -> "agy-log"
                }
            val base =
                if (file.kind == LogKind.Running)
                    file.path.substringBeforeLast("/err.log").substringAfterLast('/') + ".log"
                else file.name
            val safe = safeName(base)
            var name = "$dir/$safe"
            var n = 2
            while (name in out) {
                val dot = safe.lastIndexOf('.')
                name =
                    if (dot > 0) "$dir/${safe.substring(0, dot)}-$n${safe.substring(dot)}"
                    else "$dir/$safe-$n"
                n++
            }
            val note =
                if (file.size > DiagScripts.TAIL_BYTES)
                    "# 原文件 ${DiagScripts.size(file.size)}，这里只有最后 ${DiagScripts.TAIL_BYTES / 1024} KB：${file.path}\n"
                else "# ${file.path}\n"
            out[name] = note + text
        }
        return out.mapValues { (_, text) -> Redact.text(text) }
    }

    /** 包里的文件名只留安全的字符。 */
    fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').ifEmpty { "file" }.take(100)
}
