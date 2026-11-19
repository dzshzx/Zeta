package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId

/**
 * 个人数据：日程（含增、改、删）、通知、验证码、短信、通话记录、联系人、照片和视频。
 * 都经 root 辅助进程 [ProviderHelper] 读系统的 ContentProvider 和通知服务，Zeta 不申请这些权限。
 * 除日程外只读；不发短信、不打电话。结果有条数和字数上限，不写日志，读到的内容不在 Zeta 里留存。
 */
internal object PersonalTools : PhoneToolGroup {
    private const val SEARCH = "search_personal"
    private const val VIEW = "view_media"
    private const val EDIT = "edit_calendar"

    private const val TIME_FORMAT = "ISO 8601: 2026-11-19, 2026-11-19T08:00 (local) or with offset."

    override val tools: List<JsonElement> = listOf(
        tool(
            SEARCH,
            "Search the user's personal data on this phone (read-only). Sources: calendar (events in a window, default next 7 days), " +
                "calendars (ids, writable or not), notifications (current plus recently dismissed), codes (verification codes from SMS and " +
                "notifications, default last hour), sms, calls, contacts, photos (incl. screenshots), videos. Newest first, except calendar.",
            readOnly = true,
        ) {
            schema(required = listOf("source")) {
                enumProp("source", PersonalSource.keys, "Where to search.")
                prop("query", "string", "Keyword in title, text, name, number or file path (e.g. Screenshots).")
                prop("start", "string", "From this time, inclusive. $TIME_FORMAT")
                prop("end", "string", "Before this time.")
                prop("id", "integer", "Fetch just this item (event, message, call, contact, calendar, photo or video id) with full text.")
                prop("limit", "integer", "1-50, default 20.")
                prop("offset", "integer", "Skip this many, for the next page.")
            }
        },
        tool(VIEW, "Look at a photo, screenshot or video (its preview frame) found by search_personal.", readOnly = true) {
            schema(required = listOf("id")) {
                prop("id", "integer", "Photo or video id.")
                enumProp("kind", listOf("photo", "video"), "Default photo.")
                prop("max_edge", "integer", "Longest side in pixels, 128-1568, default 1024. Larger to read small text.")
            }
        },
        tool(
            EDIT,
            "Create, update or delete a calendar event, then read it back. Recurring events cannot be updated; " +
                "deleting one removes the whole series and needs series: true.",
        ) {
            schema(required = listOf("action")) {
                enumProp("action", listOf("create", "update", "delete"), "What to do.")
                prop("event_id", "integer", "Event to update or delete.")
                prop("calendar_id", "integer", "Calendar for create; default the primary writable one.")
                prop("title", "string", "Title.")
                prop("start", "string", "Start. $TIME_FORMAT For all-day events a date.")
                prop("end", "string", "End; default start + 1 hour. For all-day events the last day is exclusive.")
                prop("all_day", "boolean", "All-day event.")
                prop("location", "string", "Location.")
                prop("description", "string", "Notes.")
                putJsonObject("reminders") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "integer") }
                    put("description", "Minutes before start, up to 5. On update replaces all; [] clears.")
                }
                prop("series", "boolean", "Confirm deleting a recurring event with all its occurrences.")
            }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = try {
        when (name) {
            SEARCH -> toolText(search(context, input))
            VIEW -> view(context, input)
            EDIT -> toolText(edit(context, input))
            else -> toolFailure("没有这个工具：$name")
        }
    } catch (e: BadInput) {
        toolFailure(e.message ?: "参数不对")
    } catch (e: Exception) {
        toolFailure("执行失败：" + (e.message ?: e.javaClass.simpleName))
    }

    override fun title(name: String, input: JsonObject): String = when (name) {
        SEARCH -> {
            val source = PersonalSource.of(input.str("source"))
            val query = input.str("query")?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > 12) it.take(12) + "…" else it }
            when {
                source == PersonalSource.Codes -> "找验证码"
                source == null -> "查个人数据"
                query != null -> "在${source.label}里找「$query」"
                source == PersonalSource.Calendars -> "查看日历列表"
                else -> "查看${source.label}"
            }
        }
        VIEW -> if (input.str("kind") == "video") "看视频画面" else "看照片"
        EDIT -> when (input.str("action")) {
            "create" -> input.str("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { "新建日程「" + (if (it.length > 12) it.take(12) + "…" else it) + "」" } ?: "新建日程"
            "update" -> "修改日程"
            "delete" -> "删除日程"
            else -> "改日程"
        }
        else -> name
    }

    // ---- 查询 ----

    private fun search(context: Context, input: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val request = PersonalQueries.searchRequest(input, now, zone)
        return when (request.source) {
            PersonalSource.Notifications -> {
                val labels = AppLabels(context)
                val op = PersonalQueries.notificationsOp(1_000, request.start)
                val items = RootProvider.one(context, op).arr("items").orEmpty().map { it.jsonObject }
                // Zeta 自己的前台服务通知不算
                val notices = PersonalQueries.notices(items).filter { it.pkg != context.packageName }
                val matched = PersonalQueries.filterNotices(notices, request, labels::of)
                PersonalQueries.formatNotices(matched, request, zone, now, notificationHistoryEnabledOrRoot(context), labels::of)
            }
            PersonalSource.Codes -> {
                val labels = AppLabels(context)
                val (sms, notices) = RootProvider.run(context, listOf(PersonalQueries.codeSmsOp(request), PersonalQueries.notificationsOp(1_000, request.start)))
                val problems = listOfNotNull(
                    sms.takeIf { it.bool("ok") != true }?.let { "短信没读到：" + it.str("error") },
                    notices.takeIf { it.bool("ok") != true }?.let { "通知没读到：" + it.str("error") },
                )
                val found = PersonalQueries.codes(
                    if (sms.bool("ok") == true) RootProvider.rows(sms) else emptyList(),
                    if (notices.bool("ok") == true) PersonalQueries.notices(notices.arr("items").orEmpty().map { it.jsonObject }) else emptyList(),
                    request, labels::of,
                )
                val partial = if (!notificationHistoryEnabledOrRoot(context)) "（通知只看了当前通知栏里的，系统的通知历史没有开启）" else null
                (problems + PersonalQueries.formatCodes(found, request, zone, now) + listOfNotNull(partial)).joinToString("\n")
            }
            PersonalSource.Calendar -> if (request.id != null) {
                val (event, reminders) = readEvent(context, request.id) ?: return "没有编号为 ${request.id} 的日程。"
                PersonalQueries.formatEvent(event, reminders, zone)
            } else {
                val result = RootProvider.one(context, PersonalQueries.ops(request).single())
                PersonalQueries.formatInstances(RootProvider.rows(result), more(result), request, zone, now)
            }
            else -> {
                val result = RootProvider.one(context, PersonalQueries.ops(request).single())
                val rows = RootProvider.rows(result)
                when (request.source) {
                    PersonalSource.Sms -> PersonalQueries.formatSms(rows, more(result), request, zone, now)
                    PersonalSource.Calls -> PersonalQueries.formatCalls(rows, more(result), request, zone, now)
                    PersonalSource.Contacts -> PersonalQueries.formatContacts(rows, more(result), request, zone, now)
                    PersonalSource.Calendars -> PersonalQueries.formatCalendars(rows, more(result), request, zone, now)
                    else -> PersonalQueries.formatMedia(rows, more(result), request, zone, now)
                }
            }
        }
    }

    private fun more(result: JsonObject): Boolean = result.bool("more") == true

    /** 一条日程和它的提醒；没有（或已删除）返回 null。 */
    private fun readEvent(context: Context, id: Long): Pair<Row, List<Row>>? {
        val (event, reminders) = RootProvider.run(context, PersonalQueries.eventOps(id))
        if (event.bool("ok") != true) throw IllegalStateException(event.str("error"))
        val row = RootProvider.rows(event).firstOrNull() ?: return null
        return row to (if (reminders.bool("ok") == true) RootProvider.rows(reminders) else emptyList())
    }

    // ---- 照片 ----

    private fun view(context: Context, input: JsonObject): JsonObject {
        val id = (input["id"] as? JsonPrimitive)?.longOrNull ?: throw BadInput("需要 id（search_personal 查到的照片或视频编号）")
        val video = input.str("kind") == "video"
        val edge = (input.int("max_edge") ?: 1024).coerceIn(128, 1568)
        val uri = (if (video) PersonalQueries.VIDEOS else PersonalQueries.IMAGES) + "/" + id
        val request = SearchRequest(if (video) PersonalSource.Videos else PersonalSource.Photos, null, null, null, id, 1, 0)
        val (meta, image) = RootProvider.run(
            context,
            listOf(
                PersonalQueries.ops(request).single(),
                buildJsonObject { put("op", "image"); put("uri", uri); put("edge", edge); put("video", video) },
            ),
            timeoutMs = 45_000,
        )
        if (meta.bool("ok") != true) throw IllegalStateException(meta.str("error"))
        val rows = RootProvider.rows(meta)
        if (rows.isEmpty()) throw BadInput("没有编号为 $id 的" + (if (video) "视频" else "照片") + "：先用 search_personal 查")
        if (image.bool("ok") != true) throw IllegalStateException("画面没取到：" + image.str("error"))
        val zone = ZoneId.systemDefault()
        val line = PersonalQueries.formatMedia(rows, false, request, zone, System.currentTimeMillis()).lines().drop(1).joinToString("\n") +
            "\n画面 ${image.int("width")}x${image.int("height")}" +
            (image.int("sourceWidth")?.takeIf { it > 0 }?.let { "（原图 ${it}x${image.int("sourceHeight")}）" } ?: "")
        return buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "text"); put("text", line) })
                add(buildJsonObject { put("type", "image"); put("data", image.str("jpeg").orEmpty()); put("mimeType", "image/jpeg") })
            }
        }
    }

    // ---- 日程 ----

    private fun edit(context: Context, input: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val eventId = (input["event_id"] as? JsonPrimitive)?.longOrNull
        return when (input.str("action")) {
            "create" -> {
                val reminders = CalendarWrites.reminders(input).orEmpty()
                val calendarId = (input["calendar_id"] as? JsonPrimitive)?.longOrNull ?: run {
                    val calendars = RootProvider.one(
                        context,
                        PersonalQueries.queryOp(PersonalQueries.CALENDARS, PersonalQueries.CALENDAR_COLUMNS, sort = "_id ASC", limit = 50, maxChars = 200),
                    )
                    PersonalQueries.pickCalendar(RootProvider.rows(calendars)) ?: throw BadInput("手机上没有可写的日历")
                }
                val values = CalendarWrites.createValues(input, calendarId, zone)
                val result = RootProvider.one(context, CalendarWrites.createBatch(values, reminders))
                val uri = result.arr("results")?.firstOrNull()?.jsonObject?.str("uri")
                val id = CalendarWrites.idFromUri(uri) ?: throw IllegalStateException("日程写入后没有拿到编号，先用 search_personal 查一下，不要重复新建")
                val (event, saved) = readEvent(context, id) ?: throw IllegalStateException("新建的日程 #$id 读不回来，先用 search_personal 查一下，不要重复新建")
                "已新建。\n" + PersonalQueries.formatEvent(event, saved, zone)
            }
            "update" -> {
                val id = eventId ?: throw BadInput("update 需要 event_id")
                val (existing, _) = readEvent(context, id) ?: throw BadInput("没有编号为 $id 的日程")
                val values = CalendarWrites.updateValues(input, existing, zone)
                RootProvider.one(context, CalendarWrites.updateBatch(id, values, CalendarWrites.reminders(input)))
                val (event, saved) = readEvent(context, id) ?: throw IllegalStateException("改完读不回日程 #$id")
                "已修改。\n" + PersonalQueries.formatEvent(event, saved, zone)
            }
            "delete" -> {
                val id = eventId ?: throw BadInput("delete 需要 event_id")
                val (existing, _) = readEvent(context, id) ?: throw BadInput("没有编号为 $id 的日程（可能已经删了）")
                if (CalendarWrites.recurring(existing) && input.bool("series") != true) {
                    throw BadInput("这是重复日程，删除会删掉全部重复；确认的话加 series: true")
                }
                RootProvider.one(context, CalendarWrites.deleteBatch(id))
                if (readEvent(context, id) != null) throw IllegalStateException("删除后还能读到日程 #$id")
                "已删除日程 #$id「" + (existing.text("title") ?: "") + "」。"
            }
            else -> throw BadInput("action 是 create、update 或 delete")
        }
    }

    /** 系统设置里的“通知历史”（Settings.Secure.NOTIFICATION_HISTORY_ENABLED）。读不到时返回 null。 */
    const val NOTIFICATION_HISTORY_KEY = "notification_history_enabled"

    fun notificationHistoryEnabled(context: Context): Boolean? = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, NOTIFICATION_HISTORY_KEY, 0) == 1
    }.getOrNull()

    /** 应用自己读不到这个设置时经 root 读（会阻塞几百毫秒）。 */
    fun notificationHistoryEnabledOrRoot(context: Context): Boolean = notificationHistoryEnabled(context)
        ?: (rootShell("settings get secure $NOTIFICATION_HISTORY_KEY", 10_000).stdout.trim() == "1")

    /** 包名换成应用名；看不到的包（不在 Zeta 的可见范围里）就用包名。 */
    private class AppLabels(context: Context) {
        private val packages = context.packageManager
        private val cache = HashMap<String, String>()

        fun of(pkg: String): String = cache.getOrPut(pkg) {
            runCatching { packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
        }
    }
}
