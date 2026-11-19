package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.long
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.ZoneId
import java.util.Base64

/**
 * 一条剪贴板记录。[time] 是复制的时间（毫秒），也当编号用。[kind] 为 null 是文字，否则 [text] 是文件名或路径；
 * [device] 是别的设备经小米互联同步过来时那台设备的名字。
 */
internal data class ClipEntry(val time: Long, val text: String, val kind: String? = null, val device: String? = null)

/**
 * 剪贴板历史（search_personal 的 clipboard）：合并两处输入法记下的剪贴板记录，只读。两处都不加密。
 *
 * - 系统键盘工具栏的剪贴板（com.miui.phrase，小米定制的搜狗、百度、讯飞输入法经它的 InputProvider 读）：
 *   `shared_prefs/sp_name_clip_board.xml` 的 `clipboard_cipher_list`。名字带 cipher，其实只是 base64 编码的 JSON 数组
 *   （InputProvider.c 读出来直接 `Base64.decode` 再交给 `B0.h.b` 当 JSON 解析），每项一个 ClipboardContentModel：
 *   time（毫秒）、type（11–24 是文件或图片，见 MiuiClipboardManager.isFileOrImage）、content（ClipDataItemModel 数组的 JSON 字符串：
 *   text、htmlText、uriFileName…）、isAcrossDevices、deviceName。每次复制都记，只留最近 20 条（MAX_CLIPBOARD_LIST_SIZE）。
 * - 微信输入法（com.tencent.wetype）：`databases/ime_database`（Room，WAL）的 `clipboard_record` 表（ClipboardRecordDao_Impl）：
 *   id、content、createTime（毫秒）、type（1 是图片，本地路径在 path）。它只记自己弹出键盘时见到的剪贴板，但留得久（实测一个月、几百条）。
 *
 * 两处按文字去重，留时间新的那条。库在别的应用的私有目录里：XML 以 root 读出来解析；SQLite 连同 -wal 以 root 读出字节，
 * 写进 Zeta 自己的缓存目录再打开，读完就删，不碰原库。纯逻辑（解析、合并、筛选、排版）有单元测试。
 */
internal object ClipboardHistory {
    const val MIUI_PACKAGE = "com.miui.phrase"
    const val WETYPE_PACKAGE = "com.tencent.wetype"

    fun miuiPrefs(user: Int) = "/data/user/$user/$MIUI_PACKAGE/shared_prefs/sp_name_clip_board.xml"
    fun wetypeDatabase(user: Int) = "/data/user/$user/$WETYPE_PACKAGE/databases/ime_database"

    /** 微信输入法的记录最多取这么多条（它自己留一个月左右，实测 500 条）。 */
    const val WETYPE_ROWS = 2_000

    /** 系统键盘里文件或图片的 type（MiuiClipboardManager.isFileOrImage）。 */
    private val MIUI_FILE_TYPES = 11..24

    /** 微信输入法 clipboard_record 的 type：图片。 */
    private const val WETYPE_IMAGE = 1L

    private const val MAX_PREFS = 4 * 1024 * 1024
    private const val MAX_DATABASE = 64 * 1024 * 1024

    private const val ORDER = "新的在前；合并了系统键盘（只留最近 20 条）和微信输入法的记录；编号是复制的时间，用 id 看全文"

    // ---- 读 ----

    fun search(context: Context, request: SearchRequest, zone: ZoneId, now: Long): String {
        val user = RootFiles.user
        val problems = mutableListOf<String>()
        val miui = runCatching { parseMiui(RootFiles.read(miuiPrefs(user), MAX_PREFS).toString(Charsets.UTF_8)) }
            .getOrElse { problems += "系统键盘的剪贴板没读到：" + reason(it); null }
        val wetype = runCatching { readWetype(context, user) }
            .getOrElse { problems += "微信输入法的剪贴板没读到：" + reason(it); null }
        if (miui == null && wetype == null) throw IllegalStateException(problems.joinToString("；"))
        return format(merge(miui.orEmpty(), wetype.orEmpty()), request, zone, now, problems)
    }

    private fun reason(e: Throwable): String = when {
        e is RootReadError && e.missing -> "没有这份记录（输入法没装或没用过剪贴板）"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * 微信输入法的库：先读 -wal 再读库。两次读之间它若把 WAL 并回库里，库是新的、WAL 是旧的，重放一遍旧帧内容一样；
     * 反过来先读库就可能拿到旧库配新 WAL，对不上。
     */
    private fun readWetype(context: Context, user: Int): List<ClipEntry> {
        val source = wetypeDatabase(user)
        val wal = try {
            RootFiles.read("$source-wal", MAX_DATABASE)
        } catch (e: RootReadError) {
            if (e.missing) null else throw e
        }
        val main = RootFiles.read(source, MAX_DATABASE)
        val dir = File(context.cacheDir, "clipboard-" + System.nanoTime())
        try {
            dir.mkdirs()
            val db = File(dir, "ime_database")
            db.writeBytes(main)
            wal?.let { File(dir, "ime_database-wal").writeBytes(it) }
            return SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READWRITE).use { database ->
                database.rawQuery("SELECT * FROM clipboard_record ORDER BY createTime DESC LIMIT $WETYPE_ROWS", null).use { c ->
                    val time = c.getColumnIndexOrThrow("createTime")
                    val content = c.getColumnIndexOrThrow("content")
                    val type = c.getColumnIndex("type")
                    val path = c.getColumnIndex("path")
                    buildList {
                        while (c.moveToNext()) {
                            add(
                                wetypeEntry(
                                    c.getLong(time),
                                    if (type >= 0) c.getLong(type) else 0,
                                    c.getString(content),
                                    if (path >= 0) c.getString(path) else null,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 解析 ----

    private val CIPHER_LIST = Regex("<string name=\"clipboard_cipher_list\">([^<]*)</string>")
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);")

    /** 系统键盘的 sp_name_clip_board.xml → 记录（文件里的顺序）。没有这一项时是空的；内容坏了抛出异常。 */
    fun parseMiui(xml: String): List<ClipEntry> {
        val encoded = CIPHER_LIST.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        if (encoded.isEmpty()) return emptyList()
        val json = Base64.getMimeDecoder().decode(encoded).toString(Charsets.UTF_8)
        val list = Json.parseToJsonElement(json) as? JsonArray ?: throw IllegalStateException("clipboard_cipher_list 解开后不是 JSON 数组")
        return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val time = item.long("time") ?: return@mapNotNull null
            val parts = item.str("content")?.let { content ->
                runCatching { (Json.parseToJsonElement(content) as JsonArray).mapNotNull { it as? JsonObject } }.getOrNull()
            }.orEmpty()
            val device = item.str("deviceName")?.trim()?.takeIf { item.bool("isAcrossDevices") == true && it.isNotEmpty() }
            if (item.int("type") in MIUI_FILE_TYPES) {
                ClipEntry(time, parts.mapNotNull { it.str("uriFileName")?.takeIf(String::isNotBlank) }.joinToString(", "), "文件或图片", device)
            } else {
                val text = parts.mapNotNull { part ->
                    part.str("text")?.takeIf(String::isNotEmpty) ?: part.str("htmlText")?.let { unescape(it.replace(TAG, "")) }?.takeIf(String::isNotEmpty)
                }
                ClipEntry(time, text.joinToString("\n"), null, device)
            }
        }
    }

    /** 微信输入法 clipboard_record 的一行。 */
    fun wetypeEntry(time: Long, type: Long, content: String?, path: String?): ClipEntry =
        if (type == WETYPE_IMAGE) ClipEntry(time, path.orEmpty(), "图片") else ClipEntry(time, content.orEmpty())

    private fun unescape(text: String): String = text.replace(ENTITY) { m ->
        when (val name = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> (if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull())
                ?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    // ---- 合并、筛选、排版 ----

    /** 几处的记录合在一起，时间新的在前；文字（去掉首尾空白）相同的只留最新那条。 */
    fun merge(vararg sources: List<ClipEntry>): List<ClipEntry> =
        sources.flatMap { it }.sortedByDescending { it.time }.distinctBy { (it.kind ?: "") + "\u0000" + it.text.trim() }

    /** 关键词（不分大小写）和时间窗口。 */
    fun filter(entries: List<ClipEntry>, request: SearchRequest): List<ClipEntry> = entries.filter { e ->
        (request.query == null || e.text.contains(request.query, ignoreCase = true)) &&
            (request.start == null || e.time >= request.start) &&
            (request.end == null || e.time < request.end)
    }

    fun format(all: List<ClipEntry>, request: SearchRequest, zone: ZoneId, now: Long, problems: List<String> = emptyList()): String {
        val notes = problems.joinToString("") { "\n（$it）" }
        request.id?.let { id ->
            val entry = all.firstOrNull { it.time == id }
                ?: return "没有编号为 $id 的剪贴板记录（编号是复制的时间；输入法可能已经清掉了这条）。$notes"
            return formatEntry(entry, zone) + notes
        }
        val matched = filter(all, request)
        val page = matched.drop(request.offset).take(request.limit)
        val more = matched.size > request.offset + page.size
        val cap = PersonalQueries.itemCap(page.size)
        val lines = page.map { e ->
            "#${e.time} ${PersonalQueries.formatTime(e.time, zone)}${tags(e)}: " + PersonalQueries.clip(e.text, cap).ifEmpty { "(空)" }
        }
        return PersonalQueries.assemble(
            PersonalQueries.header(PersonalSource.Clipboard, page.size, more, request, zone, now, ORDER), lines, "没有符合条件的剪贴板记录。",
        ) + notes
    }

    private fun tags(e: ClipEntry): String = listOfNotNull(e.kind, e.device?.let { "来自 $it" }).joinToString("") { " [$it]" }

    /** 一条记录的全文。 */
    fun formatEntry(entry: ClipEntry, zone: ZoneId): String = buildString {
        append("剪贴板记录 #").append(entry.time).append('\n')
        append("复制于：").append(PersonalQueries.formatTime(entry.time, zone)).append('\n')
        entry.kind?.let { append("类型：").append(it).append('\n') }
        entry.device?.let { append("来自：").append(it).append('\n') }
        append(if (entry.kind == null) "内容：\n" else "文件：\n")
        append(PersonalQueries.clip(entry.text, PersonalQueries.OUTPUT_BUDGET, full = true).ifEmpty { "(空)" })
    }
}
