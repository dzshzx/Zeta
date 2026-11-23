package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.InstalledApps
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 个人数据：日程（含增、改、删）、通知、验证码、短信、通话记录、联系人、照片和视频、音频与录音、文件、下载记录、小米便签、健康数据，
 * 输入法的剪贴板历史（[ClipboardHistory]），微信、QQ 的聊天图片缓存（[ChatImages]）。 除健康数据、剪贴板历史和聊天图片外都经 root 辅助进程
 * [ProviderHelper] 读系统的 ContentProvider 和通知服务，Zeta 不申请这些权限； 剪贴板历史和聊天图片以 root
 * 直接读那几个应用私有目录里的文件（[RootFiles]）； 健康数据经健康连接的公开接口读（[HealthReader]），权限由权限健康页经 root 授予。
 * 除日程外只读；不发短信、不打电话。结果有条数和字数上限，不写日志，读到的内容不在 Zeta 里留存。
 */
internal object PersonalTools : PhoneToolGroup {
    private const val SEARCH = "search_personal"
    private const val VIEW = "view_media"
    private const val EDIT = "edit_calendar"

    private const val TIME_FORMAT = "ISO 8601: 2026-11-19, 2026-11-19T08:00 (local) or with offset."

    override val tools: List<PhoneTool> =
        listOf(
            tool(
                SEARCH,
                { Strings.phoneToolSearchPersonal() },
                "Search the user's personal data on this phone (read-only). Sources: calendar (events in a window, default next 7 days), " +
                    "calendars (ids, writable or not), notifications (current plus recently dismissed), codes (verification codes from SMS and " +
                    "notifications, default last hour), sms, calls, contacts, photos (incl. screenshots), videos, photo_content (photos and " +
                    "videos by what is in them: text recognized in the image, scene tags like cat or document, or place taken; needs query; " +
                    "only photos the phone has already analyzed), audio (music and voice/call " +
                    "recordings), files (any file in shared storage, by name or folder), downloads (with source URL), notes (Xiaomi Notes: " +
                    "title, text and voice-note transcript; id for the full note), " +
                    "health (summary of steps, sleep, exercise, heart rate, weight, SpO2 from Health Connect; default last 7 days), " +
                    "clipboard (copy history kept by the keyboards; id for full text), wechat_images / qq_images (images cached from " +
                    "WeChat / QQ chats, by time only; chat unknown). " +
                    "Newest first, except calendar.",
                readOnly = true,
            ) {
                schema(required = listOf("source")) {
                    enumProp("source", PersonalSource.keys, "Where to search.")
                    prop(
                        "query",
                        "string",
                        "Keyword in title, text, name, number or file path (e.g. Screenshots, sound_recorder).",
                    )
                    prop("start", "string", "From this time, inclusive. $TIME_FORMAT")
                    prop("end", "string", "Before this time.")
                    prop(
                        "id",
                        "integer",
                        "Fetch just this item (event, message, call, contact, calendar, media, file or note id) with full text.",
                    )
                    prop("limit", "integer", "1-50, default 20.")
                    prop("offset", "integer", "Skip this many, for the next page.")
                }
            },
            tool(
                VIEW,
                { Strings.phoneToolViewMedia() },
                "Look at a photo, screenshot, video (its preview frame) or chat image found by search_personal.",
                readOnly = true,
            ) {
                schema(required = listOf("id")) {
                    prop("id", "integer", "Photo, video or chat image id.")
                    enumProp(
                        "kind",
                        listOf("photo", "video") + ChatImages.KINDS,
                        "Default photo; wechat or qq for chat images.",
                    )
                    prop(
                        "max_edge",
                        "integer",
                        "Longest side in pixels, 128-1568, default 1024. Larger to read small text.",
                    )
                }
            },
            tool(
                EDIT,
                { Strings.phoneToolEditCalendar() },
                "Create, update or delete a calendar event, then read it back. Recurring events cannot be updated; " +
                    "deleting one removes the whole series and needs series: true.",
            ) {
                schema(required = listOf("action")) {
                    enumProp("action", listOf("create", "update", "delete"), "What to do.")
                    prop("event_id", "integer", "Event to update or delete.")
                    prop(
                        "calendar_id",
                        "integer",
                        "Calendar for create; default the primary writable one.",
                    )
                    prop("title", "string", "Title.")
                    prop("start", "string", "Start. $TIME_FORMAT For all-day events a date.")
                    prop(
                        "end",
                        "string",
                        "End; default start + 1 hour. For all-day events the last day is exclusive.",
                    )
                    prop("all_day", "boolean", "All-day event.")
                    prop("location", "string", "Location.")
                    prop("description", "string", "Notes.")
                    putJsonObject("reminders") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                        put(
                            "description",
                            "Minutes before start, up to 5. On update replaces all; [] clears.",
                        )
                    }
                    prop(
                        "series",
                        "boolean",
                        "Confirm deleting a recurring event with all its occurrences.",
                    )
                }
            },
        )

    override fun call(call: CallContext, name: String, input: JsonObject): JsonObject = toolCall {
        when (name) {
            SEARCH -> toolText(search(call.context, input))
            VIEW -> view(call.context, input)
            EDIT -> toolText(edit(call.context, input))
            else -> toolFailure("没有这个工具：$name")
        }
    }

    override fun title(name: String, input: JsonObject): String =
        when (name) {
            SEARCH -> {
                val source = PersonalSource.of(input.str("source"))
                val query =
                    input.str("query")?.trim()?.takeIf { it.isNotEmpty() }?.clipChars(12, "…")
                when {
                    source == PersonalSource.Codes -> Strings.phoneTitleFindCodes()
                    source == null -> Strings.phoneTitleSearchPersonal()
                    query != null ->
                        Strings.phoneTitleSearchIn(Strings.phoneSourceName(source.key), query)
                    source == PersonalSource.Calendars -> Strings.phoneTitleViewCalendars()
                    else -> Strings.phoneTitleViewSource(Strings.phoneSourceName(source.key))
                }
            }
            VIEW ->
                ChatImages.viewTitle(input.str("kind"))
                    ?: if (input.str("kind") == "video") Strings.phoneTitleViewVideo()
                    else Strings.phoneTitleViewPhoto()
            EDIT ->
                when (input.str("action")) {
                    "create" ->
                        input
                            .str("title")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { Strings.phoneTitleCreateEventNamed(it.clipChars(12, "…")) }
                            ?: Strings.phoneTitleCreateEvent()
                    "update" -> Strings.phoneTitleUpdateEvent()
                    "delete" -> Strings.phoneTitleDeleteEvent()
                    else -> Strings.phoneTitleEditEvent()
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
                val labels = InstalledApps.Labels(context)
                val op = PersonalQueries.notificationsOp(1_000, request.start)
                val items =
                    RootProvider.one(context, op).arr("items").orEmpty().map { it.jsonObject }
                // Zeta 自己的前台服务通知不算
                val notices =
                    PersonalQueries.notices(items).filter { it.pkg != context.packageName }
                val matched = PersonalQueries.filterNotices(notices, request, labels::of)
                PersonalQueries.formatNotices(
                    matched,
                    request,
                    zone,
                    now,
                    notificationHistoryEnabledOrRoot(context),
                    labels::of,
                )
            }
            PersonalSource.Codes -> {
                val labels = InstalledApps.Labels(context)
                val (sms, notices) =
                    RootProvider.run(
                        context,
                        listOf(
                            PersonalQueries.codeSmsOp(request),
                            PersonalQueries.notificationsOp(1_000, request.start),
                        ),
                    )
                val problems =
                    listOfNotNull(
                        sms.takeIf { it.bool("ok") != true }?.let { "短信没读到：" + it.str("error") },
                        notices
                            .takeIf { it.bool("ok") != true }
                            ?.let { "通知没读到：" + it.str("error") },
                    )
                val found =
                    PersonalQueries.codes(
                        if (sms.bool("ok") == true) RootProvider.rows(sms) else emptyList(),
                        if (notices.bool("ok") == true)
                            PersonalQueries.notices(
                                notices.arr("items").orEmpty().map { it.jsonObject },
                            )
                        else emptyList(),
                        request,
                        labels::of,
                    )
                val partial =
                    if (!notificationHistoryEnabledOrRoot(context)) "（通知只看了当前通知栏里的，系统的通知历史没有开启）"
                    else null
                (problems +
                        PersonalQueries.formatCodes(found, request, zone, now) +
                        listOfNotNull(partial))
                    .joinToString("\n")
            }
            PersonalSource.Health -> health(context, request, zone, now)
            PersonalSource.Audio -> audio(context, request, zone, now)
            PersonalSource.Notes -> notes(context, request, zone, now)
            PersonalSource.PhotoContent -> PhotoContent.search(context, request, zone, now)
            PersonalSource.Clipboard -> ClipboardHistory.search(context, request, zone, now)
            PersonalSource.WechatImages,
            PersonalSource.QqImages -> ChatImages.search(request, zone, now)
            PersonalSource.Calendar ->
                if (request.id != null) {
                    val (event, reminders) =
                        readEvent(context, request.id) ?: return "没有编号为 ${request.id} 的日程。"
                    PersonalQueries.formatEvent(event, reminders, zone)
                } else {
                    val result = RootProvider.one(context, PersonalQueries.ops(request).single())
                    PersonalQueries.formatInstances(
                        RootProvider.rows(result),
                        more(result),
                        request,
                        zone,
                        now,
                    )
                }
            else -> {
                val result = RootProvider.one(context, PersonalQueries.ops(request).single())
                val rows = RootProvider.rows(result)
                when (request.source) {
                    PersonalSource.Sms ->
                        PersonalQueries.formatSms(rows, more(result), request, zone, now)
                    PersonalSource.Calls ->
                        PersonalQueries.formatCalls(rows, more(result), request, zone, now)
                    PersonalSource.Contacts ->
                        PersonalQueries.formatContacts(rows, more(result), request, zone, now)
                    PersonalSource.Calendars ->
                        PersonalQueries.formatCalendars(rows, more(result), request, zone, now)
                    PersonalSource.Files,
                    PersonalSource.Downloads ->
                        PersonalQueries.formatFiles(rows, more(result), request, zone, now)
                    else -> PersonalQueries.formatMedia(rows, more(result), request, zone, now)
                }
            }
        }
    }

    private fun more(result: JsonObject): Boolean = result.bool("more") == true

    /** 音频列表；按 id 看一条录音时，附上录音机里它的备注、摘要和转写。 */
    private fun audio(context: Context, request: SearchRequest, zone: ZoneId, now: Long): String {
        val result = RootProvider.one(context, PersonalQueries.ops(request).single())
        val rows = RootProvider.rows(result)
        val text = PersonalQueries.formatFiles(rows, more(result), request, zone, now)
        val row = rows.singleOrNull()?.takeIf { request.id != null } ?: return text
        val name =
            row.text("_display_name")?.takeIf {
                PersonalQueries.recordingKind(row.text("relative_path").orEmpty()) != null
            } ?: return text
        return text + "\n" + recorderTexts(context, name)
    }

    /** 录音机里这条录音的备注、摘要和转写；录音机读不到时说一句，不当成整次调用失败。 */
    private fun recorderTexts(context: Context, fileName: String): String =
        try {
            val record =
                RootProvider.rows(
                        RootProvider.one(context, SoundRecorder.recordOp(fileName), system = true),
                    )
                    .firstOrNull()
            val sha1 = record?.text("sha1")?.takeIf { it.isNotBlank() }
            if (record == null || sha1 == null) {
                "录音机里没有这条录音的记录。"
            } else {
                val (summary, sentences, transcripts) =
                    RootProvider.run(context, SoundRecorder.textOps(sha1), system = true)
                fun ok(r: JsonObject) =
                    if (r.bool("ok") == true) RootProvider.rows(r) else emptyList()
                SoundRecorder.format(record, ok(summary), ok(sentences), ok(transcripts))
            }
        } catch (e: Exception) {
            "录音机的摘要和转写没读到：" + (e.message ?: e.javaClass.simpleName)
        }

    /** 小米便签：列表，或按 id 读一条的全文。它的 Provider 只认系统身份的调用方。 */
    private fun notes(context: Context, request: SearchRequest, zone: ZoneId, now: Long): String {
        request.id?.let { id ->
            return MiuiNotes.read(context, id)?.let { MiuiNotes.formatNote(it, zone) }
                ?: (MiuiNotes.missing(id) + "。")
        }
        val result = RootProvider.one(context, MiuiNotes.searchOp(request), system = true)
        return MiuiNotes.formatList(RootProvider.rows(result), more(result), request, zone, now)
    }

    /** 健康连接里 [request] 窗口内的汇总；没有健康连接、一项权限都没有时说明怎么办。 */
    private fun health(context: Context, request: SearchRequest, zone: ZoneId, now: Long): String {
        if (!HealthReader.available(context)) return HealthSummary.UNAVAILABLE
        val start = request.start ?: HealthSummary.defaultStart(now, zone)
        val end = request.end ?: now
        val data = HealthReader.read(context, start, end, zone)
        if (data.denied.size == HealthKind.entries.size) return HealthSummary.NO_PERMISSION
        return HealthSummary.format(data, start, end, zone, now)
    }

    /** 一条日程和它的提醒；没有（或已删除）返回 null。 */
    private fun readEvent(context: Context, id: Long): Pair<Row, List<Row>>? {
        val (event, reminders) = RootProvider.run(context, PersonalQueries.eventOps(id))
        if (event.bool("ok") != true) throw IllegalStateException(event.str("error"))
        val row = RootProvider.rows(event).firstOrNull() ?: return null
        return row to
            (if (reminders.bool("ok") == true) RootProvider.rows(reminders) else emptyList())
    }

    // ---- 照片 ----

    private fun view(context: Context, input: JsonObject): JsonObject {
        val id =
            (input["id"] as? JsonPrimitive)?.longOrNull
                ?: throw BadInput("需要 id（search_personal 查到的照片或视频编号）")
        val video = input.str("kind") == "video"
        val edge = (input.int("max_edge") ?: 1024).coerceIn(128, 1568)
        input
            .str("kind")
            ?.takeIf { it in ChatImages.KINDS }
            ?.let {
                return ChatImages.view(it, id, edge, ZoneId.systemDefault())
            }
        val uri = (if (video) PersonalQueries.VIDEOS else PersonalQueries.IMAGES) + "/" + id
        val request =
            SearchRequest(
                if (video) PersonalSource.Videos else PersonalSource.Photos,
                null,
                null,
                null,
                id,
                1,
                0,
            )
        val (meta, image) =
            RootProvider.run(
                context,
                listOf(
                    PersonalQueries.ops(request).single(),
                    buildJsonObject {
                        put("op", "image")
                        put("uri", uri)
                        put("edge", edge)
                        put("video", video)
                    },
                ),
                timeoutMs = 45_000,
            )
        if (meta.bool("ok") != true) throw IllegalStateException(meta.str("error"))
        val rows = RootProvider.rows(meta)
        if (rows.isEmpty())
            throw BadInput("没有编号为 $id 的" + (if (video) "视频" else "照片") + "：先用 search_personal 查")
        if (image.bool("ok") != true) throw IllegalStateException("画面没取到：" + image.str("error"))
        val zone = ZoneId.systemDefault()
        val line =
            PersonalQueries.formatMedia(rows, false, request, zone, System.currentTimeMillis())
                .lines()
                .drop(1)
                .joinToString("\n") +
                "\n画面 ${image.int("width")}x${image.int("height")}" +
                (image
                    .int("sourceWidth")
                    ?.takeIf { it > 0 }
                    ?.let { "（原图 ${it}x${image.int("sourceHeight")}）" } ?: "")
        return buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", line)
                    },
                )
                add(
                    buildJsonObject {
                        put("type", "image")
                        put("data", image.str("jpeg").orEmpty())
                        put("mimeType", "image/jpeg")
                    },
                )
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
                val calendarId =
                    (input["calendar_id"] as? JsonPrimitive)?.longOrNull
                        ?: run {
                            val calendars =
                                RootProvider.one(
                                    context,
                                    PersonalQueries.queryOp(
                                        PersonalQueries.CALENDARS,
                                        PersonalQueries.CALENDAR_COLUMNS,
                                        sort = "_id ASC",
                                        limit = 50,
                                        maxChars = 200,
                                    ),
                                )
                            PersonalQueries.pickCalendar(RootProvider.rows(calendars))
                                ?: throw BadInput("手机上没有可写的日历")
                        }
                val values = CalendarWrites.createValues(input, calendarId, zone)
                val result =
                    RootProvider.one(context, CalendarWrites.createBatch(values, reminders))
                val uri = result.arr("results")?.firstOrNull()?.jsonObject?.str("uri")
                val id =
                    CalendarWrites.idFromUri(uri)
                        ?: throw IllegalStateException("日程写入后没有拿到编号，先用 search_personal 查一下，不要重复新建")
                val (event, saved) =
                    readEvent(context, id)
                        ?: throw IllegalStateException(
                            "新建的日程 #$id 读不回来，先用 search_personal 查一下，不要重复新建",
                        )
                "已新建。\n" + PersonalQueries.formatEvent(event, saved, zone)
            }
            "update" -> {
                val id = eventId ?: throw BadInput("update 需要 event_id")
                val (existing, _) = readEvent(context, id) ?: throw BadInput("没有编号为 $id 的日程")
                val values = CalendarWrites.updateValues(input, existing, zone)
                RootProvider.one(
                    context,
                    CalendarWrites.updateBatch(id, values, CalendarWrites.reminders(input)),
                )
                val (event, saved) =
                    readEvent(context, id) ?: throw IllegalStateException("改完读不回日程 #$id")
                "已修改。\n" + PersonalQueries.formatEvent(event, saved, zone)
            }
            "delete" -> {
                val id = eventId ?: throw BadInput("delete 需要 event_id")
                val (existing, _) =
                    readEvent(context, id) ?: throw BadInput("没有编号为 $id 的日程（可能已经删了）")
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
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            NOTIFICATION_HISTORY_KEY,
            0,
        ) == 1
    }
        .getOrNull()

    /** 应用自己读不到这个设置时经 root 读（会阻塞几百毫秒）。 */
    fun notificationHistoryEnabledOrRoot(context: Context): Boolean =
        notificationHistoryEnabled(context)
            ?: (rootShell("settings get secure $NOTIFICATION_HISTORY_KEY", 10_000).stdout.trim() ==
                "1")
}
