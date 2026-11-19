package com.dzshzx.zeta.phone.tools

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/** search_personal 的 health 汇总的几类数据，各要健康连接的一项读权限。 */
internal enum class HealthKind(val label: String, val permission: String) {
    Steps("步数", "android.permission.health.READ_STEPS"),
    Sleep("睡眠", "android.permission.health.READ_SLEEP"),
    Exercise("运动", "android.permission.health.READ_EXERCISE"),
    HeartRate("心率", "android.permission.health.READ_HEART_RATE"),
    RestingHeartRate("静息心率", "android.permission.health.READ_RESTING_HEART_RATE"),
    Weight("体重", "android.permission.health.READ_WEIGHT"),
    Oxygen("血氧", "android.permission.health.READ_OXYGEN_SATURATION"),
}

/** 一段睡眠或一次运动。时间是毫秒。 */
internal data class HealthSession(val start: Long, val end: Long, val type: Int = 0, val title: String? = null)

/** 一次测量：体重（kg）、血氧（%）。 */
internal data class HealthReading(val time: Long, val value: Double)

/** 心率的统计（次/分）；[count] 是读数个数，读不到时为 null。 */
internal data class HeartStats(val avg: Long, val min: Long, val max: Long, val count: Long?)

/** 健康连接里一段时间的数据，由 HealthReader 读出、[HealthSummary.format] 排成文字。只在内存里，不留存。 */
internal data class HealthData(
    val stepsTotal: Long? = null,
    val stepsByDay: List<Pair<LocalDate, Long>> = emptyList(),
    val sleep: List<HealthSession> = emptyList(),
    val exercise: List<HealthSession> = emptyList(),
    val heart: HeartStats? = null,
    val restingHeart: HeartStats? = null,
    /** 新的在前。 */
    val weight: List<HealthReading> = emptyList(),
    /** 新的在前。 */
    val oxygen: List<HealthReading> = emptyList(),
    /** 没给读权限的。 */
    val denied: Set<HealthKind> = emptySet(),
    /** 读的时候出错的，值是原因。 */
    val failed: Map<HealthKind, String> = emptyMap(),
)

/**
 * health 的纯逻辑：要哪些权限、时间窗口、把读到的数据排成给 Claude 看的汇总。只给统计和少量明细，不给原始测量序列。
 * 读数据在 [HealthReader]（健康连接的公开接口 androidx.health.connect），不读系统私有的数据库。
 */
internal object HealthSummary {
    /** 后台也能读：Zeta 在后台跑对话时调用工具，没有它健康连接会拒绝。 */
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** 能读授权之前 30 天以外的数据。 */
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /** Zeta 声明并经 root 授予的全部健康权限（AndroidManifest.xml 里要逐项声明）。 */
    val PERMISSIONS: List<String> = HealthKind.entries.map { it.permission } + BACKGROUND + HISTORY

    const val DEFAULT_DAYS = 7L

    /** 窗口不超过这么多天时逐日列步数。 */
    const val DAILY_MAX_DAYS = 31L

    /** 睡眠、运动最多列这么多段（新的在前），其余只算进合计。 */
    const val LIST_MAX = 14

    /** 权限健康页上这一项的位置，告诉 Claude 用户去哪里授权。 */
    const val GRANT_HINT = "用户在 Zeta 的「设置 → 权限健康 → 健康数据」点一下即可经 root 授予"

    /** 默认从 6 天前零点到现在（含今天共 7 天）。 */
    fun defaultStart(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(DEFAULT_DAYS - 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private val DAY = DateTimeFormatter.ofPattern("MM-dd")
    private val DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** 时长写成 7h09m / 32m。 */
    fun hm(ms: Long): String {
        val minutes = (ms / 60_000.0).roundToLong().coerceAtLeast(0)
        return if (minutes >= 60) "%dh%02dm".format(minutes / 60, minutes % 60) else "${minutes}m"
    }

    private fun dayTime(ms: Long, zone: ZoneId) = DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** 一段时间：同一天的写 11-19 00:42–07:51，跨天的写 11-18 23:40–07:51（按结束日看是哪一晚）。 */
    fun span(start: Long, end: Long, zone: ZoneId): String = dayTime(start, zone) + "–" + TIME.format(Instant.ofEpochMilli(end).atZone(zone))

    private fun number(value: Double, digits: Int = 1): String = "%.${digits}f".format(value).trimEnd('0').trimEnd('.')

    /** 健康连接的运动类型（ExerciseSessionRecord.EXERCISE_TYPE_*），常见的写中文，其余写编号。 */
    fun exerciseName(type: Int): String = EXERCISE_TYPES[type] ?: "运动（类型 $type）"

    private val EXERCISE_TYPES = mapOf(
        0 to "其他运动", 2 to "羽毛球", 5 to "篮球", 8 to "骑行", 9 to "动感单车", 16 to "跳舞", 25 to "椭圆机",
        33 to "呼吸训练", 36 to "高强度间歇训练", 37 to "徒步", 48 to "普拉提", 51 to "攀岩", 53 to "划船", 54 to "划船机",
        56 to "跑步", 57 to "跑步机", 61 to "滑雪", 64 to "足球", 68 to "爬楼梯", 70 to "力量训练", 71 to "拉伸",
        73 to "开放水域游泳", 74 to "泳池游泳", 75 to "乒乓球", 76 to "网球", 78 to "排球", 79 to "步行", 81 to "举重", 83 to "瑜伽",
    )

    /** 排成文字：第一行是时间窗口，之后每类一行（步数逐日，睡眠、运动带最近几段的明细），最后是没读到、没权限的。 */
    fun format(data: HealthData, start: Long, end: Long, zone: ZoneId, now: Long): String = buildString {
        append("健康数据 ").append(PersonalQueries.formatTime(start, zone)).append(" 到 ").append(PersonalQueries.formatTime(end, zone))
        append("（时间为 ").append(PersonalQueries.zoneLabel(zone, now)).append("；来自健康连接，由小米运动健康等应用写入）")
        val shown = HealthKind.entries.filter { it !in data.denied && it !in data.failed }.toSet()
        if (HealthKind.Steps in shown) append('\n').append(steps(data))
        if (HealthKind.Sleep in shown) append('\n').append(sleep(data, zone))
        if (HealthKind.Exercise in shown) append('\n').append(exercise(data, zone))
        // 心率没读成时，只有读到了静息心率才列这一行
        if (HealthKind.HeartRate in shown || HealthKind.RestingHeartRate in shown && data.restingHeart != null) append('\n').append(heart(data, shown))
        if (HealthKind.Weight in shown) append('\n').append(readings("体重", data.weight, " kg", 1, zone))
        if (HealthKind.Oxygen in shown) append('\n').append(readings("血氧", data.oxygen, "%", 0, zone))
        for ((kind, reason) in data.failed) append('\n').append(kind.label).append("没读到：").append(reason.take(300))
        if (data.denied.isNotEmpty()) {
            append('\n').append("没有读权限：").append(data.denied.joinToString("、") { it.label }).append("。").append(GRANT_HINT)
        }
    }

    private fun steps(data: HealthData): String {
        val total = data.stepsTotal ?: data.stepsByDay.sumOf { it.second }.takeIf { data.stepsByDay.isNotEmpty() }
        if (total == null || total == 0L && data.stepsByDay.none { it.second > 0 }) return "步数：这段时间没有记录"
        val days = data.stepsByDay.filter { it.second > 0 }
        return buildString {
            append("步数：合计 ").append(total)
            if (days.isNotEmpty()) {
                append("，日均 ").append(total / days.size).append("（按有记录的 ").append(days.size).append(" 天）")
                append("；每天 ").append(days.joinToString(" · ") { DAY.format(it.first) + " " + it.second })
            }
        }
    }

    private fun sleep(data: HealthData, zone: ZoneId): String {
        if (data.sleep.isEmpty()) return "睡眠：这段时间没有记录"
        val sessions = data.sleep.sortedByDescending { it.end }
        val total = sessions.sumOf { it.end - it.start }
        // 按醒来那天算是哪一晚
        val nights = sessions.map { Instant.ofEpochMilli(it.end).atZone(zone).toLocalDate() }.distinct().size
        return buildString {
            append("睡眠：").append(sessions.size).append(" 段，合计 ").append(hm(total))
            append("，每晚平均 ").append(hm(total / nights)).append("（").append(nights).append(" 晚）")
            append("；最近 ").append(sessions.take(LIST_MAX).joinToString(" · ") { span(it.start, it.end, zone) + " " + hm(it.end - it.start) })
            if (sessions.size > LIST_MAX) append(" 等")
        }
    }

    private fun exercise(data: HealthData, zone: ZoneId): String {
        if (data.exercise.isEmpty()) return "运动：这段时间没有记录"
        val sessions = data.exercise.sortedByDescending { it.start }
        return buildString {
            append("运动：").append(sessions.size).append(" 次，合计 ").append(hm(sessions.sumOf { it.end - it.start }))
            append("；最近 ").append(
                sessions.take(LIST_MAX).joinToString(" · ") { s ->
                    dayTime(s.start, zone) + " " + exerciseName(s.type) + " " + hm(s.end - s.start) +
                        (s.title?.trim()?.takeIf { it.isNotEmpty() }?.let { "「" + PersonalQueries.clip(it, 40) + "」" } ?: "")
                },
            )
            if (sessions.size > LIST_MAX) append(" 等")
        }
    }

    private fun heart(data: HealthData, shown: Set<HealthKind>): String {
        val heart = data.heart.takeIf { HealthKind.HeartRate in shown }
        val resting = data.restingHeart.takeIf { HealthKind.RestingHeartRate in shown }
        if (heart == null && resting == null) return "心率：这段时间没有记录"
        val parts = listOfNotNull(
            heart?.let { h -> "平均 ${h.avg}，最低 ${h.min}，最高 ${h.max} 次/分" + (h.count?.let { "（$it 个读数）" } ?: "") },
            resting?.let { r -> "静息心率平均 ${r.avg}（${r.min}–${r.max}）" },
        )
        return "心率：" + parts.joinToString("；")
    }

    private fun readings(label: String, values: List<HealthReading>, unit: String, digits: Int, zone: ZoneId): String {
        if (values.isEmpty()) return "$label：这段时间没有记录"
        val latest = values.maxBy { it.time }
        return buildString {
            append(label).append("：最近 ").append(number(latest.value, digits)).append(unit).append("（").append(dayTime(latest.time, zone)).append("）")
            if (values.size > 1) {
                append("；这段时间 ").append(values.size).append(" 次，")
                append(number(values.minOf { it.value }, digits)).append("–").append(number(values.maxOf { it.value }, digits)).append(unit)
                append("，平均 ").append(number(values.sumOf { it.value } / values.size, digits)).append(unit)
            }
        }
    }

    /** 这台手机没有健康连接（或要更新）时的说明。 */
    const val UNAVAILABLE = "这台手机上的健康连接不可用（没有装或需要更新），读不到健康数据。"

    /** 一项读权限都没有时只说这一句。 */
    const val NO_PERMISSION = "Zeta 还没有健康连接的读权限，读不到步数、睡眠、心率等数据。$GRANT_HINT（也可以在系统的健康连接设置里给「Zeta」开读取权限）。"
}
