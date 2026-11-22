package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.MainHandler
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// 侧栏用到的几样小助手命令（zeta-helper 的 search / export；按 id 查会话在 SessionList.find）。大的会话记录只在 Debian 里读，应用只拿结果。

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun parseLine(line: String): JsonObject? = runCatching {
    json.parseToJsonElement(line) as? JsonObject
}
    .getOrNull()

/**
 * 一次内容搜索：在自己的线程上边读助手的输出边把命中交给主线程（[onHit]：会话 id 和命中处的摘要）， 读完调 [onDone]（true 表示全部搜完；false
 * 是限时限量截断了；null 是没搜成）。 换了关键词或侧栏不要了就 [cancel]：连同 Debian 里的进程一起结束，之后不再回调。
 */
internal class ContentSearch(
    hub: ConversationHub,
    backend: String,
    query: String,
    onHit: (id: String, snippet: String) -> Unit,
    onDone: (complete: Boolean?) -> Unit,
) {
    private val main = MainHandler()
    private var process: Process? = null
    private var cancelled = false

    private val shell = hub.shell(backend)

    init {
        val shell = shell
        if (shell == null) {
            onDone(null)
        } else {
            thread(name = "zeta-search", isDaemon = true) {
                var complete: Boolean? = null
                runCatching {
                    check(hub.ensureHelper(shell)) { "zeta-helper 没装上" }
                    val started =
                        shell.startPlain(hub.helperProgram.command("search", listOf(query)))
                    val stop =
                        synchronized(this) {
                            process = started
                            cancelled
                        }
                    if (stop) {
                        shell.kill(started)
                        return@thread
                    }
                    started.outputStream.close()
                    // 错误输出也得读走，不然写满管道会把助手卡住
                    thread(isDaemon = true) { runCatching { started.errorStream.readBytes() } }
                    started.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            val o = parseLine(line) ?: continue
                            when (o.str("type")) {
                                "zeta_match" -> {
                                    val id = o.str("id") ?: continue
                                    val snippet = o.str("snippet").orEmpty()
                                    main.post { if (!isCancelled()) onHit(id, snippet) }
                                }
                                "zeta_search_done" -> complete = o.bool("complete") == true
                            }
                        }
                    }
                }
                val result = complete
                main.post { if (!isCancelled()) onDone(result) }
            }
        }
    }

    private fun isCancelled() = synchronized(this) { cancelled }

    fun cancel() {
        val running =
            synchronized(this) {
                cancelled = true
                process
            }
        running?.let { shell?.kill(it) }
    }
}

internal class ExportedMarkdown(val file: String, val title: String, val markdown: String)

/** 让助手把主机 [backend] 上的会话记录转成 Markdown（照 Eta 的导出格式）。没成返回 null。耗时，别在主线程调用。 */
internal fun exportMarkdown(
    hub: ConversationHub,
    backend: String,
    file: String,
): ExportedMarkdown? = exportMarkdowns(hub, backend, listOf(file))?.firstOrNull()

/** 一次导出几个会话，按 [files] 的顺序返回导出成了的那些（导不出来的不在里面）；助手没跑成返回 null。 耗时，别在主线程调用。 */
internal fun exportMarkdowns(
    hub: ConversationHub,
    backend: String,
    files: List<String>,
): List<ExportedMarkdown>? {
    if (files.isEmpty()) return emptyList()
    val timeout = (120_000L + 20_000L * (files.size - 1)).coerceAtMost(600_000L)
    val output = hub.helper("export", files, timeout, backend).outputOrNull ?: return null
    val exported =
        output
            .lineSequence()
            .mapNotNull(::parseLine)
            .filter { it.str("type") == "zeta_export" }
            .mapNotNull { o ->
                ExportedMarkdown(
                    o.str("file").orEmpty(),
                    o.str("title").orEmpty(),
                    o.str("markdown") ?: return@mapNotNull null,
                )
            }
            .associateBy { it.file }
    return files.mapNotNull { exported[it] }
}

private val FileNameUnsafe = Regex("[\\\\/:*?\"<>|\\p{C}]")
private val Whitespace = Regex("\\s+")

/** 导出文件的默认名字，照 Eta：Zeta-标题-年月日-时分.md。几个会话一起导出时标题写“N个对话”， 设置备份写“设置”、扩展名 json。 */
internal fun exportFileName(
    title: String,
    now: Long = System.currentTimeMillis(),
    extension: String = "md",
): String {
    val clean =
        title.replace(Whitespace, " ").replace(FileNameUnsafe, "").trim().take(40).trim().ifBlank {
            "对话"
        }
    return "Zeta-$clean-" +
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) +
        "." +
        extension
}
