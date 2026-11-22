package com.dzshzx.zeta.ui.pane

import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.HelperServe
import com.dzshzx.zeta.core.MainHandler
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.callCore
import com.dzshzx.zeta.core.openCore
import com.dzshzx.zeta.core.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 侧栏用到的几样会话工具（核心的 sessions.search / sessions.export；按 id 查会话在
// SessionList.find）。大的会话记录只在主机上读，应用只拿结果。

/**
 * 一次内容搜索（核心的 `sessions.search`）：那边每找到一个会话推一行，在连接的线程上交给主线程（[onHit]：会话 id 和命中处的摘要）， 找完调 [onDone]（true
 * 表示全部搜完；false 是限时限量截断了；null 是没搜成）。换了关键词或侧栏不要了就 [cancel]。 每次搜索单开一条连接： 推来的命中只归这一次，[cancel]
 * 关掉它，核心发现连接断了就不再找。
 */
class ContentSearch(
    hub: ConversationHub,
    backend: String,
    query: String,
    onHit: (id: String, snippet: String) -> Unit,
    onDone: (complete: Boolean?) -> Unit,
) {
    private val main = MainHandler()
    private var serve: HelperServe? = null
    private var cancelled = false

    init {
        if (hub.shell(backend) == null) {
            onDone(null)
        } else {
            thread(name = "zeta-search", isDaemon = true) {
                val opened =
                    hub.openCore(backend) { o ->
                        val (id, snippet) = searchHitOf(o) ?: return@openCore
                        main.post { if (!isCancelled()) onHit(id, snippet) }
                    }
                val stop =
                    synchronized(this) {
                        serve = opened
                        cancelled
                    }
                if (stop) {
                    opened.close()
                    return@thread
                }
                val complete = runCatching {
                    val done =
                        opened.call(
                            "sessions.search",
                            buildJsonObject { put("query", query) },
                            SEARCH_TIMEOUT_MS,
                        ) as? JsonObject
                    done?.bool("complete") == true
                }
                    .getOrNull()
                opened.close()
                // 命中在应答之前推来、先交给了主线程，所以 onDone 总在它们之后
                main.post { if (!isCancelled()) onDone(complete) }
            }
        }
    }

    private fun isCancelled() = synchronized(this) { cancelled }

    fun cancel() {
        val running =
            synchronized(this) {
                cancelled = true
                serve
            }
        running?.close()
    }

    private companion object {
        /** 核心最多找 10 秒；连上（可能要先装 zeta-helper、拉起核心）另算。 */
        const val SEARCH_TIMEOUT_MS = 60_000L
    }
}

/** 导出成 Markdown 的一个会话（核心的 `sessions.export`）：标题、正文和核心建议的文件名。 */
class ExportedMarkdown(val title: String, val markdown: String, val fileName: String)

/**
 * 让主机 [backend] 上的核心把会话 [session] 导出成 Markdown（`sessions.export`，照 Eta 的导出格式）。没成返回 null。耗时，别在主线程调用。
 */
fun exportMarkdown(hub: ConversationHub, backend: String, session: String): ExportedMarkdown? =
    runCatching {
        hub.callCore(
            backend,
            "sessions.export",
            buildJsonObject { put("session", session) },
            EXPORT_TIMEOUT_MS,
        )
    }
    .getOrNull()
    ?.let(::exportedMarkdown)

/** 一次导出几个会话（每个调一次 `sessions.export`），按 [sessions] 的顺序返回导出成了的那些（导不出来的不在里面）。耗时，别在主线程调用。 */
fun exportMarkdowns(
    hub: ConversationHub,
    backend: String,
    sessions: List<String>,
): List<ExportedMarkdown> = sessions.mapNotNull { exportMarkdown(hub, backend, it) }

/** 一个会话的导出最多等多久：几十 MB 的记录要读一会儿。 */
private const val EXPORT_TIMEOUT_MS = 120_000L

/** 核心的 `sessions.export` 的结果 `{title, markdown, file_name}`；不成样子时是 null。 */
fun exportedMarkdown(result: JsonElement): ExportedMarkdown? {
    val o = result as? JsonObject ?: return null
    return ExportedMarkdown(
        o.str("title").orEmpty(),
        o.str("markdown") ?: return null,
        o.str("file_name").orEmpty(),
    )
}

/** `sessions.search` 推来的一行（`sessions.match`）里的会话 id 和命中处的摘要；别的消息是 null。 */
fun searchHitOf(o: JsonObject): Pair<String, String>? =
    if (o.str("type") != "sessions.match") null
    else (o.str("id") ?: return null) to o.str("snippet").orEmpty()

private val FileNameUnsafe = Regex("[\\\\/:*?\"<>|\\p{C}]")
private val Whitespace = Regex("\\s+")

/** 导出文件的默认名字，照 Eta：Zeta-标题-年月日-时分.md。几个会话一起导出时标题写“N个对话”， 设置备份写“设置”、扩展名 json。 */
fun exportFileName(
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
