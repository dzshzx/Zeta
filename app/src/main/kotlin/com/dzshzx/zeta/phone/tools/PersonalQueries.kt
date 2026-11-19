package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** 调用参数不对：message 原样交给 Claude。 */
internal class BadInput(message: String) : IllegalArgumentException(message)

/** search_personal 的数据来源。 */
internal enum class PersonalSource(val key: String, val label: String) {
    Calendar("calendar", "日程"),
    Calendars("calendars", "日历"),
    Notifications("notifications", "通知"),
    Codes("codes", "验证码"),
    Sms("sms", "短信"),
    Calls("calls", "通话记录"),
    Contacts("contacts", "联系人"),
    Photos("photos", "照片"),
    Videos("videos", "视频"),
    PhotoContent("photo_content", "照片内容"),
    Audio("audio", "音频"),
    Files("files", "文件"),
    Downloads("downloads", "下载记录"),
    Notes("notes", "便签"),
    Health("health", "健康数据"),
    ;

    companion object {
        val keys = entries.map { it.key }
        fun of(key: String?): PersonalSource? = entries.firstOrNull { it.key == key }
    }
}

/** 解析好的一次查询。时间是毫秒，[start] 含、[end] 不含。 */
internal data class SearchRequest(
    val source: PersonalSource,
    val query: String?,
    val start: Long?,
    val end: Long?,
    val id: Long?,
    val limit: Int,
    val offset: Int,
)

/** 一行查询结果：列名到值。 */
internal typealias Row = Map<String, JsonElement>

internal fun Row.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun Row.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

/**
 * 个人数据查询的纯逻辑：参数校验、拼 Provider 查询、把结果排成给 Claude 看的文字、从短信和通知里认验证码。
 * 不碰 Android 运行时，全部有单元测试；真正的读写在 [PersonalTools] 经 [RootProvider] 做。
 */
internal object PersonalQueries {
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** 一次结果里正文的总字数上限。条数少时每条能多给一些，单条最多 [ITEM_MAX] 字。 */
    const val OUTPUT_BUDGET = 12_000
    const val ITEM_MIN = 160
    const val ITEM_MAX = 4_000

    const val MINUTE = 60_000L
    const val HOUR = 60 * MINUTE
    const val DAY = 24 * HOUR
    const val MAX_WINDOW_DAYS = 366

    /** 验证码默认只看最近这么久。 */
    const val CODE_WINDOW = HOUR

    /** 联系人按行取回再按人合并，最多取这么多行。 */
    const val CONTACT_ROWS = 800

    /** 通知除了当前的，再向系统要最近移除的这么多条。 */
    const val NOTIFICATION_ARCHIVE = 200

    const val CALENDAR = "com.android.calendar"
    const val EVENTS = "content://com.android.calendar/events"
    const val REMINDERS = "content://com.android.calendar/reminders"
    const val CALENDARS = "content://com.android.calendar/calendars"
    const val SMS = "content://sms"
    const val CALLS = "content://call_log/calls"
    const val CONTACT_DATA = "content://com.android.contacts/data"
    const val IMAGES = "content://media/external/images/media"
    const val VIDEOS = "content://media/external/video/media"

    /** 媒体库的全部文件：audio 也查这张表，小米录音机的目录有 .nomedia，录音不在 audio 那张表里。 */
    const val FILES = "content://media/external/file"
    const val DOWNLOADS = "content://media/external/downloads"

    private const val MIME_NAME = "vnd.android.cursor.item/name"
    private const val MIME_PHONE = "vnd.android.cursor.item/phone_v2"
    private const val MIME_EMAIL = "vnd.android.cursor.item/email_v2"

    /** CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR：到这一级才能写。 */
    private const val CAL_ACCESS_CONTRIBUTOR = 500

    // ---- 时间 ----

    /**
     * 读时间：带偏移的 ISO 8601（`2026-11-19T08:00+08:00`、`…Z`），不带偏移的按 [zone] 当地时间（`2026-11-19T08:00`、
     * `2026-11-19 08:00`），只有日期的取那天零点。读不出来返回 null。
     */
    fun parseTime(text: String, zone: ZoneId): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    /** 全天日程用的日期：只取日期部分（带时间的按 [zone] 换成当地日期）。 */
    fun parseDate(text: String, zone: ZoneId): LocalDate? {
        val t = text.trim()
        runCatching { return LocalDate.parse(t) }
        return parseTime(t, zone)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    }

    private fun time(input: JsonObject, key: String, zone: ZoneId): Long? = input.str(key)?.takeIf { it.isNotBlank() }?.let {
        parseTime(it, zone) ?: throw BadInput("$key 读不出来：用 ISO 8601，例如 2026-11-19、2026-11-19T08:00 或 2026-11-19T08:00+08:00")
    }

    /** LIKE 的通配符转义，配 `ESCAPE '\'`。 */
    fun likePattern(query: String): String =
        "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** 像电话号码（只有数字、空格、加号、横线、括号，至少 3 位数字）时只留数字，库里的号码写法各不相同。 */
    fun phoneDigits(query: String): String? =
        query.takeIf { PHONE_LIKE.matches(it.trim()) }?.filter(Char::isDigit)?.takeIf { it.length >= 3 }

    private val PHONE_LIKE = Regex("[0-9 +()\\-]+")

    /** 比较电话号码用：只留数字，去掉中国的国家码 86 / 0086。 */
    fun phoneKey(number: String): String {
        val digits = number.filter(Char::isDigit)
        return when {
            digits.length == 13 && digits.startsWith("86") -> digits.drop(2)
            digits.length == 15 && digits.startsWith("0086") -> digits.drop(4)
            else -> digits
        }
    }

    // ---- 参数 ----

    fun searchRequest(input: JsonObject, now: Long, zone: ZoneId): SearchRequest {
        val source = PersonalSource.of(input.str("source")) ?: throw BadInput("source 必须是其中之一：" + PersonalSource.keys.joinToString(", "))
        val id = (input["id"] as? JsonPrimitive)?.let { it.longOrNull ?: throw BadInput("id 必须是整数") }
        if (id != null && source in setOf(PersonalSource.Notifications, PersonalSource.Codes, PersonalSource.Health, PersonalSource.PhotoContent)) throw BadInput("${source.key} 不支持 id")
        val query = input.str("query")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > 200) throw BadInput("query 最多 200 字")
        }
        var start = time(input, "start", zone)
        var end = time(input, "end", zone)
        when (source) {
            PersonalSource.Calendar -> if (id == null) {
                // 日程总在一个时间窗口里查（重复日程要按窗口展开），默认从今天零点起 7 天
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                if (start == null) start = if (end != null) end - 7 * DAY else today
                if (end == null) end = start + 7 * DAY
                if (end - start > MAX_WINDOW_DAYS * DAY) throw BadInput("日程的时间范围最多 $MAX_WINDOW_DAYS 天")
            }
            PersonalSource.Codes -> if (start == null) start = now - CODE_WINDOW
            PersonalSource.Health -> {
                // 汇总总在一个窗口里算：默认最近 7 天（含今天）
                if (end == null) end = now
                if (start == null) start = HealthSummary.defaultStart(end, zone)
                if (end - start > MAX_WINDOW_DAYS * DAY) throw BadInput("健康数据的时间范围最多 $MAX_WINDOW_DAYS 天")
            }
            else -> {}
        }
        if (start != null && end != null && end <= start) throw BadInput("end 必须晚于 start")
        val limit = (input["limit"] as? JsonPrimitive)?.longOrNull?.toInt() ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw BadInput("limit 在 1 到 $MAX_LIMIT 之间")
        val offset = (input["offset"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
        if (offset !in 0..10_000) throw BadInput("offset 在 0 到 10000 之间")
        return if (id != null) SearchRequest(source, null, null, null, id, 1, 0)
        else SearchRequest(source, query, start, end, null, limit, offset)
    }

    // ---- 拼查询 ----

    /** 一次 query 操作，交给 [ProviderHelper]。 */
    fun queryOp(
        uri: String,
        projection: List<String>,
        clauses: List<Pair<String, List<String>>> = emptyList(),
        sort: String? = null,
        limit: Int,
        offset: Int = 0,
        maxChars: Int = ITEM_MAX,
    ): JsonObject = buildJsonObject {
        put("op", "query")
        put("uri", uri)
        put("projection", JsonArray(projection.map(::JsonPrimitive)))
        if (clauses.isNotEmpty()) {
            put("selection", clauses.joinToString(" AND ") { "(" + it.first + ")" })
            put("args", JsonArray(clauses.flatMap { it.second }.map(::JsonPrimitive)))
        }
        sort?.let { put("sort", it) }
        put("limit", limit)
        if (offset > 0) put("offset", offset)
        put("maxChars", maxChars)
    }

    /** 关键词在这几列里任一列出现。 */
    private fun anyLike(columns: List<String>, query: String): Pair<String, List<String>> {
        val pattern = likePattern(query)
        return columns.joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" } to columns.map { pattern }
    }

    /**
     * 时间窗口。参数经 Provider 总是按文字绑定：和列比较时 SQLite 会按列的类型转成数，但 [column] 是 COALESCE 这类表达式时没有类型，
     * 数和文字比较永远是数小，窗口就失效了，所以参数一律 CAST 成整数。
     */
    private fun window(column: String, request: SearchRequest, scale: Long = 1): List<Pair<String, List<String>>> = listOfNotNull(
        request.start?.let { "$column >= CAST(? AS INTEGER)" to listOf((it / scale).toString()) },
        request.end?.let { "$column < CAST(? AS INTEGER)" to listOf((it / scale).toString()) },
    )

    /** 照片、视频的时间：拍摄时间，没有的话用入库时间（秒）。 */
    private const val MEDIA_TIME = "COALESCE(datetaken, date_added * 1000)"

    /** 音频、文件、下载的时间：修改时间，没有的话用入库时间（都是秒）。 */
    private const val FILE_TIME = "COALESCE(NULLIF(date_modified, 0), date_added)"

    /** 音频：媒体库认作音频的，加上 .nomedia 目录里（比如小米录音机的）按类型是音频的。 */
    private const val IS_AUDIO = "media_type = 2 OR mime_type LIKE 'audio/%'"

    /** 媒体库里的目录本身（MTP 的 association 格式）不算文件。 */
    private const val NOT_DIRECTORY = "format IS NULL OR format != 12289"

    /** 音频、文件、下载各查哪张表、哪些列，[kind] 是额外的筛选，[searched] 是关键词匹配的列。 */
    private class FileQuery(val uri: String, val columns: List<String>, val kind: String?, val searched: List<String>)

    /** [request] 对应的 Provider 查询（通知、验证码不走这里）。 */
    fun ops(request: SearchRequest): List<JsonObject> {
        val id = request.id
        val q = request.query
        val cap = if (id != null) ITEM_MAX else itemCap(request.limit)
        return when (request.source) {
            PersonalSource.Sms -> listOf(
                queryOp(
                    SMS, SMS_COLUMNS,
                    listOfNotNull(
                        id?.let { "_id = ?" to listOf(it.toString()) },
                        q?.let { query -> phoneDigits(query)?.let { anyLike(listOf("address"), it) } ?: anyLike(listOf("address", "body"), query) },
                    ) + window("date", request),
                    "date DESC", request.limit, request.offset, cap,
                ),
            )
            PersonalSource.Calls -> listOf(
                queryOp(
                    CALLS, CALL_COLUMNS,
                    listOfNotNull(
                        id?.let { "_id = ?" to listOf(it.toString()) },
                        q?.let { query -> phoneDigits(query)?.let { anyLike(listOf("number"), it) } ?: anyLike(listOf("name", "number"), query) },
                    ) + window("date", request),
                    "date DESC", request.limit, request.offset, cap,
                ),
            )
            PersonalSource.Contacts -> listOf(
                queryOp(
                    CONTACT_DATA, CONTACT_COLUMNS,
                    listOfNotNull(
                        "mimetype IN (?, ?, ?)" to listOf(MIME_NAME, MIME_PHONE, MIME_EMAIL),
                        id?.let { "contact_id = ?" to listOf(it.toString()) },
                        q?.let { query ->
                            // data4 是电话的规范化号码（+86…）；按名字、号码、邮箱都能找到这个人
                            phoneDigits(query)?.let { anyLike(listOf("data1", "data4"), it) } ?: anyLike(listOf("display_name", "data1"), query)
                        },
                    ),
                    "sort_key ASC, contact_id ASC", CONTACT_ROWS, 0, 200,
                ),
            )
            PersonalSource.Calendar -> if (id != null) eventOps(id) else listOf(
                queryOp(
                    "content://com.android.calendar/instances/when/${request.start}/${request.end}", INSTANCE_COLUMNS,
                    listOfNotNull("deleted = 0" to emptyList(), q?.let { anyLike(listOf("title", "eventLocation", "description"), it) }),
                    "begin ASC", request.limit, request.offset, cap,
                ),
            )
            PersonalSource.Calendars -> listOf(
                queryOp(
                    CALENDARS, CALENDAR_COLUMNS,
                    listOfNotNull(id?.let { "_id = ?" to listOf(it.toString()) }, q?.let { anyLike(listOf("calendar_displayName", "account_name"), it) }),
                    "_id ASC", request.limit, request.offset, 200,
                ),
            )
            PersonalSource.Photos, PersonalSource.Videos -> listOf(
                queryOp(
                    if (request.source == PersonalSource.Photos) IMAGES else VIDEOS,
                    if (request.source == PersonalSource.Photos) PHOTO_COLUMNS else VIDEO_COLUMNS,
                    listOfNotNull(
                        id?.let { "_id = ?" to listOf(it.toString()) },
                        q?.let { anyLike(listOf("_display_name", "relative_path"), it) },
                    ) + window(MEDIA_TIME, request),
                    "$MEDIA_TIME DESC", request.limit, request.offset, 300,
                ),
            )
            PersonalSource.Audio, PersonalSource.Files, PersonalSource.Downloads -> {
                val files = when (request.source) {
                    PersonalSource.Audio -> FileQuery(FILES, AUDIO_COLUMNS, IS_AUDIO, listOf("_display_name", "relative_path", "title", "artist"))
                    PersonalSource.Files -> FileQuery(FILES, FILE_COLUMNS, NOT_DIRECTORY, listOf("_display_name", "relative_path"))
                    else -> FileQuery(DOWNLOADS, DOWNLOAD_COLUMNS, null, listOf("_display_name", "relative_path", "download_uri"))
                }
                listOf(
                    queryOp(
                        files.uri, files.columns,
                        listOfNotNull(
                            files.kind?.let { it to emptyList() },
                            id?.let { "_id = ?" to listOf(it.toString()) },
                            q?.let { anyLike(files.searched, it) },
                        ) + window(FILE_TIME, request, 1000),
                        "$FILE_TIME DESC, _id DESC", request.limit, request.offset, 400,
                    ),
                )
            }
            PersonalSource.Notes -> listOf(MiuiNotes.searchOp(request))
            PersonalSource.PhotoContent -> PhotoContent.searchOps(request)
            PersonalSource.Notifications, PersonalSource.Codes, PersonalSource.Health -> throw IllegalArgumentException("${request.source.key} 不走 Provider 查询")
        }
    }

    /** 一条日程的完整内容和它的提醒。 */
    fun eventOps(id: Long): List<JsonObject> = listOf(
        queryOp(EVENTS, EVENT_COLUMNS, listOf("_id = ?" to listOf(id.toString()), "deleted = 0" to emptyList()), null, 1, 0, ITEM_MAX),
        queryOp(REMINDERS, listOf("minutes", "method"), listOf("event_id = ?" to listOf(id.toString())), "minutes ASC", 10),
    )

    /** 验证码要读的短信：收件箱里窗口内最新的 50 条。 */
    fun codeSmsOp(request: SearchRequest): JsonObject =
        queryOp(SMS, listOf("date", "address", "body"), listOf("type = 1" to emptyList<String>()) + window("date", request), "date DESC", 50, 0, 1_000)

    /** 当前通知，加上系统通知历史里 [since] 以来的（开了的话）。 */
    fun notificationsOp(maxChars: Int, since: Long?): JsonObject = buildJsonObject {
        put("op", "notifications")
        put("archive", NOTIFICATION_ARCHIVE)
        put("history", true)
        since?.let { put("since", it) }
        put("maxChars", maxChars)
    }

    /** 每条正文能占的字数：总预算按条数平分，夹在 [ITEM_MIN] 和 [ITEM_MAX] 之间。 */
    fun itemCap(count: Int): Int = (OUTPUT_BUDGET / count.coerceAtLeast(1)).coerceIn(ITEM_MIN, ITEM_MAX)

    val SMS_COLUMNS = listOf("_id", "date", "type", "read", "address", "body")
    val CALL_COLUMNS = listOf("_id", "date", "type", "duration", "number", "name")
    val CONTACT_COLUMNS = listOf("contact_id", "display_name", "mimetype", "data1")
    val INSTANCE_COLUMNS = listOf("event_id", "begin", "end", "allDay", "calendar_id", "rrule", "title", "eventLocation", "description")
    val EVENT_COLUMNS = listOf("_id", "dtstart", "dtend", "duration", "allDay", "eventTimezone", "calendar_id", "rrule", "original_id", "title", "eventLocation", "description")
    val CALENDAR_COLUMNS = listOf("_id", "calendar_displayName", "account_type", "calendar_access_level", "visible", "isPrimary")
    val PHOTO_COLUMNS = listOf("_id", "datetaken", "date_added", "width", "height", "_size", "mime_type", "relative_path", "_display_name")
    val VIDEO_COLUMNS = PHOTO_COLUMNS + "duration"
    val FILE_COLUMNS = listOf("_id", "date_modified", "date_added", "_size", "mime_type", "media_type", "relative_path", "_display_name")
    val AUDIO_COLUMNS = FILE_COLUMNS + listOf("duration", "title", "artist")
    val DOWNLOAD_COLUMNS = listOf("_id", "date_modified", "date_added", "_size", "mime_type", "relative_path", "_display_name", "download_uri")

    // ---- 排版 ----

    private val CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm")
    private val WHITESPACE = Regex("\\s+")

    fun formatTime(ms: Long, zone: ZoneId): String = CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** 一行里放得下的正文：空白并成一个空格，超长截断。完整内容（[full]）保留换行。 */
    fun clip(value: String?, cap: Int, full: Boolean = false): String {
        val text = if (full) value.orEmpty().trim() else value.orEmpty().replace(WHITESPACE, " ").trim()
        return if (text.length <= cap) text else text.take(cap) + "…（共 ${text.length} 字）"
    }

    fun zoneLabel(zone: ZoneId, now: Long): String = "UTC" + zone.rules.getOffset(Instant.ofEpochMilli(now)).id.let { if (it == "Z") "+00:00" else it }

    /** 结果的第一行：多少条、怎么排、时间是哪个时区、还有没有下一页。 */
    fun header(source: PersonalSource, count: Int, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long, order: String?): String = buildString {
        append(source.label).append(' ').append(count).append(" 条")
        val notes = listOfNotNull(order, "时间为 " + zoneLabel(zone, now))
        append("（").append(notes.joinToString("，")).append("）")
        if (more) append("；还有更多，用 offset=").append(request.offset + count).append(" 继续")
    }

    /** 把行排成文字，总长不超过 [OUTPUT_BUDGET]；超出的部分说明省略了几条。 */
    fun assemble(header: String, lines: List<String>, empty: String): String {
        if (lines.isEmpty()) return empty
        val out = StringBuilder(header)
        var used = 0
        for ((index, line) in lines.withIndex()) {
            if (used + line.length > OUTPUT_BUDGET && index > 0) {
                out.append("\n（后面 ${lines.size - index} 条超出长度上限没有列出，缩小范围或用 offset 继续）")
                break
            }
            out.append('\n').append(line)
            used += line.length
        }
        return out.toString()
    }

    fun formatSms(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val full = request.id != null
        val cap = if (full) ITEM_MAX else itemCap(rows.size)
        val lines = rows.map { r ->
            val type = SMS_TYPES[r.long("type")?.toInt()] ?: "other"
            val unread = if (r.long("type") == 1L && r.long("read") == 0L) " unread" else ""
            "#${r.long("_id")} ${r.long("date")?.let { formatTime(it, zone) }} $type$unread ${r.text("address").orEmpty()}: " + clip(r.text("body"), cap, full)
        }
        return assemble(header(PersonalSource.Sms, rows.size, more, request, zone, now, "新的在前"), lines, "没有符合条件的短信。")
    }

    fun formatCalls(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val lines = rows.map { r ->
            val name = r.text("name")?.takeIf { it.isNotBlank() }
            val number = r.text("number").orEmpty()
            val who = if (name != null) "$name ($number)" else number
            "#${r.long("_id")} ${r.long("date")?.let { formatTime(it, zone) }} ${CALL_TYPES[r.long("type")?.toInt()] ?: "other"} $who ${duration(r.long("duration") ?: 0)}"
        }
        return assemble(header(PersonalSource.Calls, rows.size, more, request, zone, now, "新的在前"), lines, "没有符合条件的通话记录。")
    }

    /** 秒数写成 1h02m03s / 2m05s / 7s。 */
    fun duration(seconds: Long): String = when {
        seconds >= 3600 -> "%dh%02dm%02ds".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
        seconds >= 60 -> "%dm%02ds".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }

    /** 通讯录的行按人合并：名字、电话、邮箱。分页按人算。 */
    fun formatContacts(rows: List<Row>, rowsTruncated: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        data class Person(val id: Long, var name: String?, val phones: LinkedHashMap<String, String> = linkedMapOf(), val emails: LinkedHashSet<String> = linkedSetOf())
        val people = LinkedHashMap<Long, Person>()
        for (r in rows) {
            val id = r.long("contact_id") ?: continue
            val person = people.getOrPut(id) { Person(id, r.text("display_name")) }
            if (person.name.isNullOrBlank()) person.name = r.text("display_name")
            val value = r.text("data1")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            when (r.text("mimetype")) {
                // 同一个号码常以几种写法各存一份（+86 138…、138 …），按数字只留第一种写法
                MIME_PHONE -> person.phones.putIfAbsent(phoneKey(value), value)
                MIME_EMAIL -> person.emails += value
            }
        }
        val all = people.values.toList()
        val page = all.drop(request.offset).take(request.limit)
        val more = all.size > request.offset + request.limit || rowsTruncated
        val lines = page.map { p ->
            buildString {
                append('#').append(p.id).append(' ').append(p.name ?: "(无名)")
                if (p.phones.isNotEmpty()) append(" · 电话 ").append(p.phones.values.joinToString(", "))
                if (p.emails.isNotEmpty()) append(" · 邮箱 ").append(p.emails.joinToString(", "))
            }
        }
        return assemble(header(PersonalSource.Contacts, page.size, more, request, zone, now, "按名字排"), lines, "没有符合条件的联系人。")
    }

    /** 日程的起止：全天的按 UTC 日期（结束日不含），其余按当地时间。 */
    fun eventSpan(start: Long, end: Long?, allDay: Boolean, zone: ZoneId): String {
        if (allDay) {
            val first = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
            val last = end?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1) }?.takeIf { it > first }
            return if (last == null) "$first 全天" else "$first → $last 全天"
        }
        val from = Instant.ofEpochMilli(start).atZone(zone)
        if (end == null) return CLOCK.format(from)
        val to = Instant.ofEpochMilli(end).atZone(zone)
        return if (to.toLocalDate() == from.toLocalDate()) CLOCK.format(from) + "–" + HOUR_MINUTE.format(to) else CLOCK.format(from) + " → " + CLOCK.format(to)
    }

    fun formatInstances(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val cap = itemCap(rows.size)
        val lines = rows.map { r ->
            buildString {
                append('#').append(r.long("event_id")).append(' ')
                append(eventSpan(r.long("begin") ?: 0, r.long("end"), r.long("allDay") == 1L, zone)).append(' ')
                append(r.text("title")?.takeIf { it.isNotBlank() } ?: "(无标题)")
                r.text("eventLocation")?.takeIf { it.isNotBlank() }?.let { append(" @ ").append(clip(it, 120)) }
                if (!r.text("rrule").isNullOrBlank()) append(" [重复]")
                append(" (日历 ").append(r.long("calendar_id")).append(')')
                r.text("description")?.takeIf { it.isNotBlank() }?.let { append(" — ").append(clip(it, cap)) }
            }
        }
        val window = formatTime(request.start ?: 0, zone) + " 到 " + formatTime(request.end ?: 0, zone)
        return assemble(header(PersonalSource.Calendar, rows.size, more, request, zone, now, "$window，按开始时间排"), lines, "$window 没有符合条件的日程。")
    }

    /** 一条日程的完整内容（[event] 是 [EVENT_COLUMNS] 那一行），[reminders] 是提醒行。 */
    fun formatEvent(event: Row, reminders: List<Row>, zone: ZoneId): String = buildString {
        val start = event.long("dtstart") ?: 0
        val allDay = event.long("allDay") == 1L
        val end = event.long("dtend") ?: event.text("duration")?.let { rfcDuration(it) }?.let { start + it }
        append("日程 #").append(event.long("_id")).append('\n')
        append("标题：").append(event.text("title")?.takeIf { it.isNotBlank() } ?: "(无标题)").append('\n')
        append("时间：").append(eventSpan(start, end, allDay, zone))
        if (!allDay) event.text("eventTimezone")?.let { append("（").append(it).append("）") }
        append('\n')
        event.text("rrule")?.takeIf { it.isNotBlank() }?.let { append("重复：").append(it).append('\n') }
        event.text("eventLocation")?.takeIf { it.isNotBlank() }?.let { append("地点：").append(it).append('\n') }
        append("日历：").append(event.long("calendar_id")).append('\n')
        append("提醒：").append(
            reminders.mapNotNull { it.long("minutes") }.sorted().joinToString("、") { "提前 $it 分钟" }.ifEmpty { "无" },
        )
        event.text("description")?.takeIf { it.isNotBlank() }?.let { append("\n说明：").append(clip(it, ITEM_MAX, full = true)) }
    }

    /** 重复日程的 duration 列（RFC 2445，如 P3600S、PT1H、P1D）换成毫秒。 */
    fun rfcDuration(value: String): Long? {
        // 日历库里常见的是不带 T 的 P3600S
        val m = Regex("^([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$").find(value.trim())?.takeIf { value.trim().length > 1 } ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0
        val ms = ((g(2) * 7 + g(3)) * 86_400 + g(4) * 3600 + g(5) * 60 + g(6)) * 1000
        return if (m.groupValues[1] == "-") -ms else ms
    }

    fun formatCalendars(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val lines = rows.map { r ->
            val access = r.long("calendar_access_level") ?: 0
            buildString {
                append('#').append(r.long("_id")).append(' ').append(r.text("calendar_displayName") ?: "(无名)")
                append(" (").append(r.text("account_type") ?: "local").append(") ")
                append(if (access >= CAL_ACCESS_CONTRIBUTOR) "可写" else "只读")
                if (r.long("isPrimary") == 1L) append("，主日历")
                if (r.long("visible") == 0L) append("，已隐藏")
            }
        }
        return assemble(header(PersonalSource.Calendars, rows.size, more, request, zone, now, null), lines, "没有日历。")
    }

    /** 新建日程没指定日历时用哪一个：可写、可见的，主日历优先，其次编号最小的。 */
    fun pickCalendar(rows: List<Row>): Long? = rows
        .filter { (it.long("calendar_access_level") ?: 0) >= CAL_ACCESS_CONTRIBUTOR && it.long("visible") != 0L }
        .sortedWith(compareBy({ if (it.long("isPrimary") == 1L) 0 else 1 }, { it.long("_id") ?: Long.MAX_VALUE }))
        .firstOrNull()?.long("_id")

    fun formatMedia(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val video = request.source == PersonalSource.Videos
        val lines = rows.map { r ->
            val time = r.long("datetaken")?.takeIf { it > 0 } ?: r.long("date_added")?.let { it * 1000 }
            buildString {
                append('#').append(r.long("_id")).append(' ').append(time?.let { formatTime(it, zone) } ?: "?").append(' ')
                append(r.text("relative_path").orEmpty()).append(r.text("_display_name").orEmpty())
                val w = r.long("width")
                val h = r.long("height")
                if (w != null && h != null && w > 0 && h > 0) append(' ').append(w).append('x').append(h)
                r.long("_size")?.let { append(' ').append(size(it)) }
                if (video) r.long("duration")?.let { append(' ').append(duration(it / 1000)) }
            }
        }
        return assemble(
            header(request.source, rows.size, more, request, zone, now, "新的在前；用 view_media 看画面"), lines,
            "没有符合条件的" + request.source.label + "。",
        )
    }

    /** 音频、文件、下载：路径、大小；音频带时长、歌手并标出录音，文件带类型，下载带来源网址。时间是秒。 */
    fun formatFiles(rows: List<Row>, more: Boolean, request: SearchRequest, zone: ZoneId, now: Long): String {
        val lines = rows.map { r ->
            val time = r.long("date_modified")?.takeIf { it > 0 } ?: r.long("date_added")
            val path = r.text("relative_path").orEmpty()
            buildString {
                append('#').append(r.long("_id")).append(' ').append(time?.let { formatTime(it * 1000, zone) } ?: "?").append(' ')
                append(path).append(r.text("_display_name").orEmpty())
                r.long("_size")?.let { append(' ').append(size(it)) }
                when (request.source) {
                    PersonalSource.Audio -> {
                        r.long("duration")?.takeIf { it > 0 }?.let { append(' ').append(duration(it / 1000)) }
                        r.text("artist")?.takeIf { it.isNotBlank() && it != "<unknown>" }?.let { append(" · ").append(clip(it, 60)) }
                        recordingKind(path)?.let { append(" [").append(it).append(']') }
                    }
                    PersonalSource.Downloads -> r.text("download_uri")?.takeIf { it.isNotBlank() }?.let { append(" 来自 ").append(clip(it, 200)) }
                    else -> r.text("mime_type")?.let { append(' ').append(it) }
                }
            }
        }
        val order = when {
            request.source == PersonalSource.Files -> "按修改时间新的在前；图片可用 view_media 看，视频加 kind: video"
            request.source == PersonalSource.Audio && request.id == null && rows.any { recordingKind(it.text("relative_path").orEmpty()) != null } ->
                "按修改时间新的在前；录音用 id 看录音机里的摘要和转写"
            else -> "按修改时间新的在前"
        }
        return assemble(header(request.source, rows.size, more, request, zone, now, order), lines, "没有符合条件的" + request.source.label + "。")
    }

    /** 录音机存的文件：通话录音、普通录音；别的音频返回 null。 */
    fun recordingKind(relativePath: String): String? = when {
        relativePath.startsWith("MIUI/sound_recorder/call_rec", ignoreCase = true) -> "通话录音"
        relativePath.startsWith("MIUI/sound_recorder", ignoreCase = true) || relativePath.startsWith("Recordings/", ignoreCase = true) -> "录音"
        else -> null
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1fMB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "${(bytes / 1024.0).roundToInt()}KB"
        else -> "${bytes}B"
    }

    // ---- 通知 ----

    /** 一条通知，来自 [ProviderHelper] 的 notifications。[state]：active 当前的，removed 最近移除的，history 系统通知历史里的。 */
    data class Notice(
        val pkg: String,
        val time: Long,
        val state: String,
        val ongoing: Boolean,
        val title: String?,
        val body: String?,
    )

    private val STATE_ORDER = listOf("active", "removed", "history")

    /**
     * 把 helper 的结果变成通知列表：正文取最长的那段（big、lines、text），跳过没字的（比如分组摘要）。
     * 同一条（同一应用、同一发出时间）在当前通知、最近移除、通知历史里可能各有一份，只留一份，当前的优先。
     */
    fun notices(items: List<JsonObject>): List<Notice> = items.mapNotNull { item ->
        val title = item.str("title")?.trim()?.takeIf { it.isNotEmpty() }
        val body = listOfNotNull(item.str("big"), item.str("lines"), item.str("text")).map { it.trim() }.filter { it.isNotEmpty() }
            .maxByOrNull { it.length }
        val sub = item.str("sub")?.trim()?.takeIf { it.isNotEmpty() }
        if (title == null && body == null) return@mapNotNull null
        Notice(
            pkg = item.str("pkg").orEmpty(),
            time = (item["time"] as? JsonPrimitive)?.longOrNull ?: 0,
            state = item.str("state") ?: "active",
            ongoing = (item["ongoing"] as? JsonPrimitive)?.booleanOrNull == true,
            title = listOfNotNull(title, sub).joinToString(" · ").ifEmpty { null },
            body = body,
        )
    }.sortedBy { STATE_ORDER.indexOf(it.state).let { i -> if (i < 0) STATE_ORDER.size else i } }.distinctBy { it.pkg to it.time }

    /** 按关键词（应用名、包名、标题、正文）和时间窗口筛选，新的在前。 */
    fun filterNotices(all: List<Notice>, request: SearchRequest, label: (String) -> String): List<Notice> {
        val q = request.query?.lowercase()
        return all.filter { n ->
            (request.start == null || n.time >= request.start) && (request.end == null || n.time < request.end) &&
                (q == null || listOfNotNull(n.pkg, label(n.pkg), n.title, n.body).any { q in it.lowercase() })
        }.sortedByDescending { it.time }
    }

    /** [historyEnabled] 为 false 时提示：系统通知历史没开，只查得到当前通知。 */
    fun formatNotices(matched: List<Notice>, request: SearchRequest, zone: ZoneId, now: Long, historyEnabled: Boolean, label: (String) -> String): String {
        val page = matched.drop(request.offset).take(request.limit)
        val more = matched.size > request.offset + request.limit
        val cap = itemCap(page.size)
        val lines = page.map { n ->
            buildString {
                append(formatTime(n.time, zone)).append(' ').append(label(n.pkg))
                when (n.state) {
                    "removed" -> append(" [已移除]")
                    "history" -> append(" [历史]")
                }
                if (n.ongoing) append(" [常驻]")
                append(": ")
                n.title?.let { append(clip(it, 200)) }
                n.body?.let { append(if (n.title != null) " — " else "").append(clip(it, cap)) }
            }
        }
        val text = assemble(
            header(PersonalSource.Notifications, page.size, more, request, zone, now, if (historyEnabled) "当前的和 24 小时内的通知历史，新的在前" else "当前的，新的在前"),
            lines, "没有符合条件的通知。",
        )
        return if (historyEnabled) text else text + "\n" + HISTORY_OFF
    }

    /** 系统通知历史没开时附在结果后面，Claude 据此告诉用户怎么开。 */
    const val HISTORY_OFF = "（系统的通知历史没有开启，只查得到当前通知栏里的。用户在 Zeta 的「设置 → 权限健康 → 通知历史」点一下即可开启，之后能查 24 小时内已划掉的通知）"

    // ---- 验证码 ----

    private val CODE_HINT = Regex(
        "验证码|校验码|动态码|确认码|安全码|动态密码|一次性密码|登录码|认证码|verification code|security code|confirmation code|" +
            "one[- ]time (?:password|passcode|code)|\\bOTP\\b|\\bpasscode\\b|\\bcode\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 4 到 8 位数字，前后不连着别的字母数字，不是金额、日期、尾号、时长的一部分。 */
    private val CODE = Regex(
        "(?<![0-9A-Za-z])(?<![0-9][.,])(?<!尾号)(?<!末四位)([0-9]{4,8})(?![0-9A-Za-z%/-])(?![.,][0-9])" +
            "(?!\\s*(?:元|年|月|日|分钟|秒|小时|天|位|min|minutes|hours))",
    )

    /** 从一段文字里认验证码：要有“验证码”之类的字样，取离它最近的那串数字。认不出返回 null。 */
    fun extractCode(text: String): String? {
        val hints = CODE_HINT.findAll(text).map { it.range }.toList()
        if (hints.isEmpty()) return null
        return CODE.findAll(text).minByOrNull { code ->
            hints.minOf { hint ->
                // 数字在字样后面更常见：在前面的多算一点距离
                if (code.range.first > hint.last) code.range.first - hint.last else (hint.first - code.range.last) * 2
            }
        }?.groupValues?.get(1)
    }

    data class Code(val code: String, val from: String, val time: Long, val via: String)

    /** 短信（[smsRows]：date、address、body）和通知里认出的验证码，新的在前；同一来源同一个码只留最新的。 */
    fun codes(smsRows: List<Row>, notices: List<Notice>, request: SearchRequest, label: (String) -> String): List<Code> {
        val fromSms = smsRows.mapNotNull { r ->
            val code = extractCode(r.text("body").orEmpty()) ?: return@mapNotNull null
            Code(code, r.text("address").orEmpty(), r.long("date") ?: 0, "短信")
        }
        val fromNotices = notices.filter { (request.start == null || it.time >= request.start) && (request.end == null || it.time < request.end) }
            .mapNotNull { n ->
                val code = extractCode(listOfNotNull(n.title, n.body).joinToString(" ")) ?: return@mapNotNull null
                Code(code, label(n.pkg), n.time, "通知")
            }
        // 短信到了往往也弹一条通知：同一个码只报一次，短信优先
        val seen = HashSet<String>()
        return (fromSms + fromNotices).sortedWith(compareBy({ it.via != "短信" }, { -it.time }))
            .filter { seen.add(it.code) }
            .sortedByDescending { it.time }
    }

    fun formatCodes(codes: List<Code>, request: SearchRequest, zone: ZoneId, now: Long): String {
        val page = codes.drop(request.offset).take(request.limit)
        val lines = page.map { "${formatTime(it.time, zone)} ${it.code} · ${it.via} ${it.from}" }
        val since = formatTime(request.start ?: now, zone)
        return assemble(
            header(PersonalSource.Codes, page.size, codes.size > request.offset + request.limit, request, zone, now, "$since 以来，新的在前"), lines,
            "$since 以来的短信和通知里没有认出验证码。",
        )
    }

    // ---- 图片 ----

    /** 把 [width]×[height] 等比缩到最长边不超过 [edge]，不放大。 */
    fun fitWithin(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= edge || longest <= 0) return width to height
        val scale = edge.toDouble() / longest
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    private val SMS_TYPES = mapOf(1 to "received", 2 to "sent", 3 to "draft", 4 to "outbox", 5 to "failed", 6 to "queued")
    private val CALL_TYPES = mapOf(1 to "incoming", 2 to "outgoing", 3 to "missed", 4 to "voicemail", 5 to "rejected", 6 to "blocked", 7 to "answered-elsewhere")
}
