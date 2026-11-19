package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 样本都是编造的。 */
class PersonalQueriesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-19 10:30 北京时间。 */
    private val now = ZonedDateTime.of(2026, 11, 19, 10, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun ms(text: String) = PersonalQueries.parseTime(text, zone)!!

    private fun input(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in pairs) when (value) {
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            null -> put(key, JsonNull)
        }
    }

    private fun row(vararg pairs: Pair<String, Any?>): Row = pairs.associate { (key, value) ->
        key to when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        } as JsonElement
    }

    @Test
    fun parsesTimesWithAndWithoutOffset() {
        assertEquals(ZonedDateTime.of(2026, 11, 19, 8, 0, 0, 0, zone).toInstant().toEpochMilli(), ms("2026-11-19T08:00"))
        assertEquals(ms("2026-11-19T08:00"), ms("2026-11-19 08:00"))
        assertEquals(ms("2026-11-19T08:00"), ms("2026-11-19T00:00Z"))
        assertEquals(ms("2026-11-19T08:00"), ms("2026-11-19T08:00:00+08:00"))
        assertEquals(ZonedDateTime.of(2026, 11, 19, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), ms("2026-11-19"))
        assertNull(PersonalQueries.parseTime("tomorrow", zone))
        assertEquals(LocalDate.of(2026, 11, 20), PersonalQueries.parseDate("2026-11-20T09:00", zone))
    }

    @Test
    fun calendarDefaultsToSevenDaysFromToday() {
        val request = PersonalQueries.searchRequest(input("source" to "calendar"), now, zone)
        assertEquals(ms("2026-11-19"), request.start)
        assertEquals(ms("2026-11-26"), request.end)
        assertEquals(PersonalQueries.DEFAULT_LIMIT, request.limit)
        val onlyEnd = PersonalQueries.searchRequest(input("source" to "calendar", "end" to "2026-12-01"), now, zone)
        assertEquals(ms("2026-11-24"), onlyEnd.start)
    }

    @Test
    fun codesDefaultToLastHour() {
        val request = PersonalQueries.searchRequest(input("source" to "codes"), now, zone)
        assertEquals(now - PersonalQueries.HOUR, request.start)
        assertNull(request.end)
    }

    @Test
    fun rejectsBadArguments() {
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "mail"), now, zone) }
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "sms", "limit" to 51), now, zone) }
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "sms", "start" to "yesterday"), now, zone) }
        assertThrows(BadInput::class.java) {
            PersonalQueries.searchRequest(input("source" to "sms", "start" to "2026-11-19", "end" to "2026-11-18"), now, zone)
        }
        assertThrows(BadInput::class.java) {
            PersonalQueries.searchRequest(input("source" to "calendar", "start" to "2026-01-01", "end" to "2027-06-01"), now, zone)
        }
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "codes", "id" to 3), now, zone) }
    }

    @Test
    fun idFetchesOneItemAndIgnoresFilters() {
        val request = PersonalQueries.searchRequest(input("source" to "sms", "id" to 42, "query" to "x", "limit" to 9), now, zone)
        assertEquals(42L, request.id)
        assertEquals(1, request.limit)
        assertNull(request.query)
        val op = PersonalQueries.ops(request).single()
        assertEquals("(_id = ?)", op.str("selection"))
        assertEquals(listOf("42"), op.arr("args")!!.map { it.jsonPrimitive.content })
        assertEquals(PersonalQueries.ITEM_MAX, op.int("maxChars"))
    }

    @Test
    fun keywordsAreBoundAndEscaped() {
        assertEquals("%50\\%\\_off\\\\%", PersonalQueries.likePattern("50%_off\\"))
        val request = PersonalQueries.searchRequest(
            input("source" to "sms", "query" to "it's 50%", "start" to "2026-11-18", "limit" to 5, "offset" to 10), now, zone,
        )
        val op = PersonalQueries.ops(request).single()
        assertEquals(PersonalQueries.SMS, op.str("uri"))
        assertEquals("(address LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\') AND (date >= CAST(? AS INTEGER))", op.str("selection"))
        assertEquals(listOf("%it's 50\\%%", "%it's 50\\%%", ms("2026-11-18").toString()), op.arr("args")!!.map { it.jsonPrimitive.content })
        assertEquals("date DESC", op.str("sort"))
        assertEquals(5, op.int("limit"))
        assertEquals(10, op.int("offset"))
    }

    @Test
    fun phoneNumbersMatchByDigits() {
        assertEquals("13800138000", PersonalQueries.phoneDigits("138 0013-8000"))
        assertEquals("10086", PersonalQueries.phoneDigits("+10086"))
        assertNull(PersonalQueries.phoneDigits("张三"))
        assertNull(PersonalQueries.phoneDigits("12"))
        assertEquals("13800000001", PersonalQueries.phoneKey("+86 138 0000 0001"))
        assertEquals("13800000001", PersonalQueries.phoneKey("0086-138-0000-0001"))
        assertEquals("10086", PersonalQueries.phoneKey("10086"))
        val calls = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "calls", "query" to "138 0013 8000"), now, zone)).single()
        assertEquals("(number LIKE ? ESCAPE '\\')", calls.str("selection"))
        assertEquals(listOf("%13800138000%"), calls.arr("args")!!.map { it.jsonPrimitive.content })
        val contacts = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "contacts", "query" to "王"), now, zone)).single()
        assertTrue(contacts.str("selection")!!.startsWith("(mimetype IN (?, ?, ?)) AND (display_name LIKE ?"))
    }

    @Test
    fun calendarUsesInstancesWindow() {
        val request = PersonalQueries.searchRequest(input("source" to "calendar", "start" to "2026-11-20", "end" to "2026-11-21", "query" to "会"), now, zone)
        val op = PersonalQueries.ops(request).single()
        assertEquals("content://com.android.calendar/instances/when/${ms("2026-11-20")}/${ms("2026-11-21")}", op.str("uri"))
        assertTrue(op.str("selection")!!.startsWith("(deleted = 0) AND (title LIKE ?"))
        assertEquals("begin ASC", op.str("sort"))
        val byId = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "calendar", "id" to 7), now, zone))
        assertEquals(listOf(PersonalQueries.EVENTS, PersonalQueries.REMINDERS), byId.map { it.str("uri") })
    }

    @Test
    fun mediaFiltersOnTakenOrAddedTime() {
        val request = PersonalQueries.searchRequest(input("source" to "photos", "query" to "Screenshots", "start" to "2026-11-19"), now, zone)
        val op = PersonalQueries.ops(request).single()
        assertEquals(PersonalQueries.IMAGES, op.str("uri"))
        assertEquals(
            "(_display_name LIKE ? ESCAPE '\\' OR relative_path LIKE ? ESCAPE '\\') AND (COALESCE(NULLIF(datetaken, 0), date_added * 1000) >= CAST(? AS INTEGER))",
            op.str("selection"),
        )
        assertEquals("COALESCE(NULLIF(datetaken, 0), date_added * 1000) DESC", op.str("sort"))
        val videos = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "videos"), now, zone)).single()
        assertEquals(PersonalQueries.VIDEOS, videos.str("uri"))
        assertTrue("duration" in videos.arr("projection")!!.map { it.jsonPrimitive.content })
    }

    @Test
    fun itemCapSharesTheBudget() {
        assertEquals(PersonalQueries.ITEM_MAX, PersonalQueries.itemCap(1))
        assertEquals(600, PersonalQueries.itemCap(20))
        assertEquals(PersonalQueries.ITEM_MIN, PersonalQueries.itemCap(500))
        assertEquals("a b c", PersonalQueries.clip(" a\n b\tc ", 100))
        assertEquals("abc…（共 6 字）", PersonalQueries.clip("abcdef", 3))
        assertEquals("a\nb", PersonalQueries.clip("a\nb", 100, full = true))
    }

    @Test
    fun formatsSmsWithHeaderAndPaging() {
        val request = PersonalQueries.searchRequest(input("source" to "sms", "limit" to 2), now, zone)
        val rows = listOf(
            row("_id" to 9, "date" to ms("2026-11-19T09:05"), "type" to 1, "read" to 0, "address" to "10690000", "body" to "Your parcel\nis ready"),
            row("_id" to 8, "date" to ms("2026-11-18T21:00"), "type" to 2, "read" to 1, "address" to "+8613800000000", "body" to "ok"),
        )
        val text = PersonalQueries.formatSms(rows, more = true, request, zone, now)
        val lines = text.lines()
        assertEquals("短信 2 条（新的在前，时间为 UTC+08:00）；还有更多，用 offset=2 继续", lines[0])
        assertEquals("#9 2026-11-19 09:05 received unread 10690000: Your parcel is ready", lines[1])
        assertEquals("#8 2026-11-18 21:00 sent +8613800000000: ok", lines[2])
        assertEquals("没有符合条件的短信。", PersonalQueries.formatSms(emptyList(), false, request, zone, now))
    }

    @Test
    fun assembleStopsAtBudget() {
        val long = "x".repeat(5_000)
        val text = PersonalQueries.assemble("head", List(5) { long }, "none")
        assertTrue(text.length <= PersonalQueries.OUTPUT_BUDGET + 200)
        assertTrue(text.endsWith("（后面 3 条超出长度上限没有列出，缩小范围或用 offset 继续）"))
    }

    @Test
    fun formatsCalls() {
        val request = PersonalQueries.searchRequest(input("source" to "calls"), now, zone)
        val rows = listOf(
            row("_id" to 3, "date" to ms("2026-11-19T08:00"), "type" to 3, "duration" to 0, "number" to "10010", "name" to null),
            row("_id" to 2, "date" to ms("2026-11-18T08:00"), "type" to 1, "duration" to 125, "number" to "13800000000", "name" to "测试甲"),
        )
        val lines = PersonalQueries.formatCalls(rows, false, request, zone, now).lines()
        assertEquals("#3 2026-11-19 08:00 missed 10010 0s", lines[1])
        assertEquals("#2 2026-11-18 08:00 incoming 测试甲 (13800000000) 2m05s", lines[2])
        assertEquals("1h01m01s", PersonalQueries.duration(3661))
    }

    @Test
    fun contactsGroupByPersonAndPage() {
        val phone = "vnd.android.cursor.item/phone_v2"
        val email = "vnd.android.cursor.item/email_v2"
        val name = "vnd.android.cursor.item/name"
        val rows = listOf(
            row("contact_id" to 1, "display_name" to "测试甲", "mimetype" to name, "data1" to "测试甲"),
            row("contact_id" to 1, "display_name" to "测试甲", "mimetype" to phone, "data1" to "138 0000 0001"),
            row("contact_id" to 1, "display_name" to "测试甲", "mimetype" to phone, "data1" to "+86 138-0000-0001"),
            row("contact_id" to 1, "display_name" to "测试甲", "mimetype" to email, "data1" to "a@example.com"),
            row("contact_id" to 2, "display_name" to "测试乙", "mimetype" to phone, "data1" to "139 0000 0002"),
            row("contact_id" to 3, "display_name" to "测试丙", "mimetype" to name, "data1" to "测试丙"),
        )
        val request = PersonalQueries.searchRequest(input("source" to "contacts", "limit" to 2), now, zone)
        val lines = PersonalQueries.formatContacts(rows, false, request, zone, now).lines()
        assertTrue(lines[0].contains("还有更多，用 offset=2 继续"))
        assertEquals("#1 测试甲 · 电话 138 0000 0001 · 邮箱 a@example.com", lines[1])
        assertEquals("#2 测试乙 · 电话 139 0000 0002", lines[2])
        val second = PersonalQueries.searchRequest(input("source" to "contacts", "limit" to 2, "offset" to 2), now, zone)
        assertEquals("#3 测试丙", PersonalQueries.formatContacts(rows, false, second, zone, now).lines()[1])
    }

    @Test
    fun eventSpans() {
        assertEquals("2026-11-20 14:00–15:30", PersonalQueries.eventSpan(ms("2026-11-20T14:00"), ms("2026-11-20T15:30"), false, zone))
        assertEquals("2026-11-20 23:00 → 2026-11-21 01:00", PersonalQueries.eventSpan(ms("2026-11-20T23:00"), ms("2026-11-21T01:00"), false, zone))
        val utc = { d: String -> LocalDate.parse(d).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
        assertEquals("2026-11-20 全天", PersonalQueries.eventSpan(utc("2026-11-20"), utc("2026-11-21"), true, zone))
        assertEquals("2026-11-20 → 2026-11-22 全天", PersonalQueries.eventSpan(utc("2026-11-20"), utc("2026-11-23"), true, zone))
        assertEquals(3_600_000L, PersonalQueries.rfcDuration("P3600S"))
        assertEquals(90 * 60_000L, PersonalQueries.rfcDuration("PT1H30M"))
        assertEquals(86_400_000L, PersonalQueries.rfcDuration("P1D"))
        assertNull(PersonalQueries.rfcDuration("soon"))
    }

    @Test
    fun formatsInstancesAndEvent() {
        val request = PersonalQueries.searchRequest(input("source" to "calendar"), now, zone)
        val rows = listOf(
            row(
                "event_id" to 5, "begin" to ms("2026-11-20T14:00"), "end" to ms("2026-11-20T15:00"), "allDay" to 0, "calendar_id" to 1,
                "rrule" to "FREQ=WEEKLY", "title" to "周会", "eventLocation" to "三楼", "description" to "带上\n周报",
            ),
        )
        val lines = PersonalQueries.formatInstances(rows, false, request, zone, now).lines()
        assertTrue(lines[0].startsWith("日程 1 条（2026-11-19 00:00 到 2026-11-26 00:00，按开始时间排"))
        assertEquals("#5 2026-11-20 14:00–15:00 周会 @ 三楼 [重复] (日历 1) — 带上 周报", lines[1])
        val event = row(
            "_id" to 5, "dtstart" to ms("2026-11-20T14:00"), "dtend" to null, "duration" to "P3600S", "allDay" to 0,
            "eventTimezone" to "Asia/Shanghai", "calendar_id" to 1, "rrule" to "FREQ=WEEKLY", "original_id" to null,
            "title" to "周会", "eventLocation" to null, "description" to "带上\n周报",
        )
        val text = PersonalQueries.formatEvent(event, listOf(row("minutes" to 30), row("minutes" to 10)), zone)
        assertEquals(
            "日程 #5\n标题：周会\n时间：2026-11-20 14:00–15:00（Asia/Shanghai）\n重复：FREQ=WEEKLY\n日历：1\n提醒：提前 10 分钟、提前 30 分钟\n说明：带上\n周报",
            text,
        )
    }

    @Test
    fun picksPrimaryWritableCalendar() {
        val rows = listOf(
            row("_id" to 1, "calendar_access_level" to 200, "visible" to 1, "isPrimary" to 1),
            row("_id" to 2, "calendar_access_level" to 700, "visible" to 1, "isPrimary" to 0),
            row("_id" to 3, "calendar_access_level" to 700, "visible" to 1, "isPrimary" to 1),
        )
        assertEquals(3L, PersonalQueries.pickCalendar(rows))
        assertEquals(2L, PersonalQueries.pickCalendar(rows.take(2)))
        assertNull(PersonalQueries.pickCalendar(rows.take(1)))
    }

    @Test
    fun formatsMedia() {
        val request = PersonalQueries.searchRequest(input("source" to "photos"), now, zone)
        val rows = listOf(
            row(
                "_id" to 11, "datetaken" to ms("2026-11-19T09:00"), "date_added" to 0, "width" to 1220, "height" to 2656,
                "_size" to 734_003, "mime_type" to "image/png", "relative_path" to "DCIM/Screenshots/", "_display_name" to "Screenshot_1.png",
            ),
            row(
                "_id" to 10, "datetaken" to null, "date_added" to ms("2026-11-18T09:00") / 1000, "width" to 0, "height" to 0,
                "_size" to 2_500_000, "mime_type" to "image/jpeg", "relative_path" to "Pictures/", "_display_name" to "a.jpg",
            ),
        )
        val lines = PersonalQueries.formatMedia(rows, false, request, zone, now).lines()
        assertEquals("#11 2026-11-19 09:00 DCIM/Screenshots/Screenshot_1.png 1220x2656 717KB", lines[1])
        assertEquals("#10 2026-11-18 09:00 Pictures/a.jpg 2.4MB", lines[2])
    }

    @Test
    fun mediaTimeFallsBackToTheDateAdded() {
        assertEquals(1_000L, PersonalQueries.mediaTime(row("datetaken" to 1_000, "date_added" to 5)))
        // 拍摄时间是 0 和没有一样，查询里的 MEDIA_TIME 也是这样（NULLIF）
        assertEquals(5_000L, PersonalQueries.mediaTime(row("datetaken" to 0, "date_added" to 5)))
        assertEquals(5_000L, PersonalQueries.mediaTime(row("datetaken" to null, "date_added" to 5)))
        assertNull(PersonalQueries.mediaTime(row("_id" to 1)))
        assertEquals("#7 ? a.jpg", PersonalQueries.mediaLine(row("_id" to 7, "_display_name" to "a.jpg"), zone))
    }

    @Test
    fun audioFilesAndDownloadsQueryTheMediaStore() {
        val audio = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "audio", "query" to "会议", "start" to "2026-11-18"), now, zone)).single()
        // 录音机的目录有 .nomedia，录音只在 files 表里，按类型认作音频
        assertEquals(PersonalQueries.FILES, audio.str("uri"))
        assertEquals(
            "(media_type = 2 OR mime_type LIKE 'audio/%') AND " +
                "(_display_name LIKE ? ESCAPE '\\' OR relative_path LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' OR artist LIKE ? ESCAPE '\\') AND " +
                "(COALESCE(NULLIF(date_modified, 0), date_added) >= CAST(? AS INTEGER))",
            audio.str("selection"),
        )
        // 文件的时间是秒
        assertEquals(List(4) { "%会议%" } + (ms("2026-11-18") / 1000).toString(), audio.arr("args")!!.map { it.jsonPrimitive.content })
        assertEquals("COALESCE(NULLIF(date_modified, 0), date_added) DESC, _id DESC", audio.str("sort"))
        assertTrue("duration" in audio.arr("projection")!!.map { it.jsonPrimitive.content })

        val files = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "files", "query" to ".pdf"), now, zone)).single()
        assertEquals(PersonalQueries.FILES, files.str("uri"))
        assertTrue(files.str("selection")!!.startsWith("(format IS NULL OR format != 12289) AND (_display_name LIKE ?"))

        val downloads = PersonalQueries.ops(PersonalQueries.searchRequest(input("source" to "downloads", "id" to 9), now, zone)).single()
        assertEquals(PersonalQueries.DOWNLOADS, downloads.str("uri"))
        assertEquals("(_id = ?)", downloads.str("selection"))
        assertTrue("download_uri" in downloads.arr("projection")!!.map { it.jsonPrimitive.content })
    }

    @Test
    fun formatsAudioFilesAndDownloads() {
        val audioRequest = PersonalQueries.searchRequest(input("source" to "audio"), now, zone)
        val audio = PersonalQueries.formatFiles(
            listOf(
                row(
                    "_id" to 31, "date_modified" to ms("2026-11-19T09:00") / 1000, "date_added" to 0, "_size" to 1_258_291,
                    "mime_type" to "audio/mpeg", "relative_path" to "MIUI/sound_recorder/", "_display_name" to "录音 001.mp3", "duration" to 205_000,
                    "artist" to "<unknown>",
                ),
                row(
                    "_id" to 30, "date_modified" to 0, "date_added" to ms("2026-11-18T20:00") / 1000, "_size" to 64_000,
                    "mime_type" to "audio/aac", "relative_path" to "MIUI/sound_recorder/call_rec/", "_display_name" to "10086(10086)_20261118200000.aac",
                    "duration" to null,
                ),
                row(
                    "_id" to 29, "date_modified" to ms("2026-11-01T08:00") / 1000, "_size" to 4_000_000, "mime_type" to "audio/flac",
                    "relative_path" to "Music/", "_display_name" to "song.flac", "duration" to 240_000, "artist" to "某乐队",
                ),
            ),
            true, audioRequest, zone, now,
        ).lines()
        assertEquals("音频 3 条（按修改时间新的在前；录音用 id 看录音机里的摘要和转写，时间为 UTC+08:00）；还有更多，用 offset=3 继续", audio[0])
        assertEquals("#31 2026-11-19 09:00 MIUI/sound_recorder/录音 001.mp3 1.2MB 3m25s [录音]", audio[1])
        assertEquals("#30 2026-11-18 20:00 MIUI/sound_recorder/call_rec/10086(10086)_20261118200000.aac 63KB [通话录音]", audio[2])
        assertEquals("#29 2026-11-01 08:00 Music/song.flac 3.8MB 4m00s · 某乐队", audio[3])

        val files = PersonalQueries.formatFiles(
            listOf(row("_id" to 5, "date_modified" to ms("2026-11-19T10:00") / 1000, "_size" to 2048, "mime_type" to "application/pdf", "relative_path" to "Documents/", "_display_name" to "a.pdf")),
            false, PersonalQueries.searchRequest(input("source" to "files"), now, zone), zone, now,
        ).lines()
        assertTrue(files[0].contains("view_media"))
        assertEquals("#5 2026-11-19 10:00 Documents/a.pdf 2KB application/pdf", files[1])

        val downloads = PersonalQueries.formatFiles(
            listOf(row("_id" to 7, "date_modified" to ms("2026-11-19T10:00") / 1000, "_size" to 300, "relative_path" to "Download/", "_display_name" to "x.apk", "download_uri" to "https://example.com/x.apk")),
            false, PersonalQueries.searchRequest(input("source" to "downloads"), now, zone), zone, now,
        ).lines()
        assertEquals("#7 2026-11-19 10:00 Download/x.apk 300B 来自 https://example.com/x.apk", downloads[1])
        assertEquals("没有符合条件的下载记录。", PersonalQueries.formatFiles(emptyList(), false, PersonalQueries.searchRequest(input("source" to "downloads"), now, zone), zone, now))
    }

    @Test
    fun healthTakesAWindowButNoIdOrPaging() {
        val request = PersonalQueries.searchRequest(input("source" to "health", "start" to "2026-11-01", "end" to "2026-11-08"), now, zone)
        assertEquals(ms("2026-11-01"), request.start)
        assertEquals(ms("2026-11-08"), request.end)
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "health", "id" to 1), now, zone) }
        assertThrows(BadInput::class.java) { PersonalQueries.searchRequest(input("source" to "health", "start" to "2024-01-01"), now, zone) }
        assertThrows(IllegalArgumentException::class.java) { PersonalQueries.ops(request) }
    }

    @Test
    fun noticesPreferActiveAndTakeLongestBody() {
        val items = listOf(
            buildJsonObject {
                put("pkg", "com.example.chat"); put("time", 1000); put("state", "active"); put("title", "测试甲")
                put("text", "short"); put("big", "a much longer body")
            },
            buildJsonObject { put("pkg", "com.example.chat"); put("time", 1000); put("state", "history"); put("title", "测试甲"); put("text", "short") },
            buildJsonObject { put("pkg", "com.example.chat"); put("time", 500); put("state", "history"); put("title", "测试乙"); put("text", "earlier") },
            buildJsonObject { put("pkg", "com.example.music"); put("time", 900); put("state", "active"); put("summary", true) },
        )
        val notices = PersonalQueries.notices(items)
        assertEquals(2, notices.size)
        assertEquals("active", notices.first { it.time == 1000L }.state)
        assertEquals("a much longer body", notices.first { it.time == 1000L }.body)
        val request = PersonalQueries.searchRequest(input("source" to "notifications", "query" to "chat app"), now, zone)
        val labels = mapOf("com.example.chat" to "Chat App")
        val matched = PersonalQueries.filterNotices(notices, request, { labels[it] ?: it })
        assertEquals(listOf(1000L, 500L), matched.map { it.time })
        val text = PersonalQueries.formatNotices(matched, request, zone, now, historyEnabled = false) { labels[it] ?: it }
        assertTrue(text.lines()[2].contains("Chat App [历史]: 测试乙 — earlier"))
        assertTrue(text.endsWith(PersonalQueries.HISTORY_OFF))
    }

    @Test
    fun extractsVerificationCodes() {
        assertEquals("482913", PersonalQueries.extractCode("【测试银行】您的验证码是482913，5分钟内有效，尾号1234的卡请勿泄露。"))
        assertEquals("482913", PersonalQueries.extractCode("尾号1234的账户登录验证码：482913"))
        assertEquals("7731", PersonalQueries.extractCode("Your verification code is 7731."))
        assertEquals("551207", PersonalQueries.extractCode("G-551207 is your Example code"))
        assertEquals("90210", PersonalQueries.extractCode("code: 90210 (valid 10 minutes)"))
        assertNull(PersonalQueries.extractCode("您的快递已到驿站，取件码 3-2-1104"))
        assertNull(PersonalQueries.extractCode("本月账单 1234.56 元，2026年11月19日到期"))
        assertNull(PersonalQueries.extractCode("会议 2026 年 11 月 19 日"))
        assertEquals("556677", PersonalQueries.extractCode("验证码 556677，有效期至 2026年11月19日"))
        assertEquals("556677", PersonalQueries.extractCode("金额 1234.56 元，验证码 556677"))
        assertNull(PersonalQueries.extractCode("验证码已发送，金额 1234.56 元，2026年到期"))
    }

    @Test
    fun codesPreferSmsAndDropDuplicates() {
        val request = PersonalQueries.searchRequest(input("source" to "codes"), now, zone)
        val sms = listOf(
            row("date" to now - 60_000, "address" to "10690000", "body" to "验证码 123456"),
            row("date" to now - 120_000, "address" to "10690001", "body" to "hello"),
        )
        val notices = listOf(
            PersonalQueries.Notice("com.android.mms", now - 59_000, "active", false, "10690000", "验证码 123456"),
            PersonalQueries.Notice("com.example.bank", now - 30_000, "active", false, "Example Bank", "Your code is 778899"),
            PersonalQueries.Notice("com.example.bank", now - 2 * PersonalQueries.HOUR, "history", false, "Example Bank", "Your code is 111111"),
        )
        val codes = PersonalQueries.codes(sms, notices, request) { if (it == "com.example.bank") "Bank" else it }
        assertEquals(listOf("778899", "123456"), codes.map { it.code })
        assertEquals("短信", codes[1].via)
        val lines = PersonalQueries.formatCodes(codes, request, zone, now).lines()
        assertEquals("2026-11-19 10:29 778899 · 通知 Bank", lines[1])
        assertFalse(PersonalQueries.formatCodes(emptyList(), request, zone, now).contains("条"))
    }
}

class PersonalToolsTest {
    @Test
    fun toolDefinitionsStaySmall() {
        val json = kotlinx.serialization.json.JsonArray(PersonalTools.tools).toString()
        // 约 2.5k token 的预算：英文定义大约 4 个字符一个 token
        println("personal tools definition: ${json.length} chars")
        assertTrue("definitions are ${json.length} chars", json.length <= 8_000)
        val names = PersonalTools.tools.map { (it as JsonObject).str("name") }
        assertEquals(listOf("search_personal", "view_media", "edit_calendar"), names)
    }

    @Test
    fun titlesAreChinese() {
        fun title(name: String, vararg pairs: Pair<String, String>) =
            PersonalTools.title(name, buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } })
        assertEquals("找验证码", title("search_personal", "source" to "codes"))
        assertEquals("查看日程", title("search_personal", "source" to "calendar"))
        assertEquals("在短信里找「快递」", title("search_personal", "source" to "sms", "query" to "快递"))
        assertEquals("看照片", title("view_media"))
        assertEquals("新建日程「Zeta 测试」", title("edit_calendar", "action" to "create", "title" to "Zeta 测试"))
        assertEquals("删除日程", title("edit_calendar", "action" to "delete"))
    }
}
