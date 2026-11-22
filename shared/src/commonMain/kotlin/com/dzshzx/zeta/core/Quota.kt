package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class QuotaSeverity {
    Normal,
    Warning,
    Critical,
}

/**
 * 订阅额度的一个窗口：5 小时、每周、每周里按模型分的……账号的套餐有哪些就有哪些，种类和次序都照服务器给的。 [kind]
 * 是服务器的分类（session、weekly_all、weekly_scoped，以后可能有新的），[scope] 是按模型或入口分的窗口的显示名。
 */
@Immutable
data class QuotaWindow(
    val kind: String,
    val group: String,
    val scope: String?,
    /** 已用比例，通常 0～1；超过上限后偶尔会大于 1。 */
    val used: Double,
    /** 重置时刻（毫秒）；不知道时为 null。 */
    val resetsAt: Long?,
    val severity: QuotaSeverity,
    /** 服务器挑的“只能显示一个时显示这个”。 */
    val active: Boolean = false,
) {
    val key: String
        get() = kind + (scope?.let { ":$it" } ?: "")

    val label: String
        get() =
            when {
                kind == "session" || (group == "session" && scope == null) -> "5 小时"
                kind == "weekly_all" -> "每周 · 全部模型"
                group == "weekly" || kind.startsWith("weekly") ->
                    if (scope != null) "每周 · $scope" else "每周"
                else -> scope ?: kind
            }

    val percent: Int
        get() = (used * 100).toInt().coerceAtLeast(0)
}

/** 额外用量（超出套餐后按量付费）。金额是 [currency] 的最小单位（美元是分）。 */
@Immutable
data class ExtraUsage(
    val enabled: Boolean,
    val usedMinor: Double?,
    val limitMinor: Double?,
    val currency: String?,
    val used: Double?,
)

/** 一个账号的订阅额度。数据都来自 Claude Code：控制请求 get_usage 的应答和对话里的 rate_limit_event。 */
@Immutable
data class QuotaSnapshot(
    val windows: List<QuotaWindow>,
    val extra: ExtraUsage? = null,
    /** Claude Code 报的订阅类型（pro、max、team…）。 */
    val subscription: String? = null,
    /** false：这种登录方式没有订阅额度（API key、Bedrock、Vertex，或登录缺 profile 权限）。 */
    val available: Boolean = true,
    /** 请求已经被拒：到上限了，[blockedUntil] 解除。 */
    val blocked: Boolean = false,
    val blockedUntil: Long? = null,
    /** 收到这份数据的时刻（毫秒）。 */
    val updatedAt: Long,
) {
    /** 按 [now] 看：已经过了重置时刻的窗口算从零开始，过了解除时刻的拒绝不再算数。 */
    fun current(now: Long): QuotaSnapshot {
        val expired = windows.any { it.resetsAt != null && it.resetsAt <= now }
        val unblocked = blocked && blockedUntil != null && blockedUntil <= now
        if (!expired && !unblocked) return this
        return copy(
            windows =
                windows.map {
                    if (it.resetsAt != null && it.resetsAt <= now)
                        it.copy(used = 0.0, resetsAt = null, severity = QuotaSeverity.Normal)
                    else it
                },
            blocked = blocked && !unblocked,
            blockedUntil = if (unblocked) null else blockedUntil,
        )
    }

    /** 只能显示一个时显示哪个：被拒时是最满的那个，否则服务器挑的，再否则最满的。 */
    val headline: QuotaWindow?
        get() =
            when {
                blocked -> windows.maxByOrNull { it.used }
                else -> windows.firstOrNull { it.active } ?: windows.maxByOrNull { it.used }
            }

    companion object {
        /** 服务器没给严重程度时，自己按比例折算的门槛。 */
        const val WARN_AT = 0.8
        const val CRITICAL_AT = 0.95
    }
}

/** 解析 Claude Code 给的额度数据，和把实时事件并进已有的数据。纯函数，不依赖 Android。 */
object Quota {
    /**
     * 控制请求 get_usage 的应答（`response.response`，2.1.328 实测）。不是它的应答时返回 null。 有
     * `rate_limits.limits`（服务器的额度行）时照它显示：有哪些窗口、叫什么、多严重都是服务器定的； 没有时（Claude Code 只拿到了响应头里的数）退回固定的几项。
     */
    fun parseUsage(response: JsonObject?, now: Long): QuotaSnapshot? {
        response ?: return null
        if (!response.containsKey("rate_limits_available")) return null
        val available = response.bool("rate_limits_available") != false
        val limits = response.obj("rate_limits")
        val rows = limits?.get("limits") as? JsonArray
        val windows = if (rows != null) serverRows(rows) else fixedWindows(limits)
        val extra =
            limits?.obj("extra_usage")?.let { e ->
                ExtraUsage(
                    enabled = e.bool("is_enabled") == true,
                    usedMinor = e.double("used_credits"),
                    limitMinor = e.double("monthly_limit"),
                    currency = e.str("currency"),
                    used = e.double("utilization")?.let { it / 100 },
                )
            }
        return QuotaSnapshot(
            windows = windows,
            extra = extra,
            subscription = response.str("subscription_type"),
            available = available,
            updatedAt = now,
        )
    }

    private fun serverRows(rows: JsonArray): List<QuotaWindow> = rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val percent = row.double("percent") ?: return@mapNotNull null
        val scope = row.obj("scope")
        QuotaWindow(
            kind = row.str("kind").orEmpty(),
            group = row.str("group").orEmpty(),
            scope =
                scope?.obj("model")?.str("display_name")
                    ?: scope?.obj("surface")?.str("display_name"),
            used = percent / 100,
            resetsAt = parseTime(row.str("resets_at")),
            severity =
                when (row.str("severity")) {
                    "critical" -> QuotaSeverity.Critical
                    "warning" -> QuotaSeverity.Warning
                    "normal" -> QuotaSeverity.Normal
                    else -> severityOf(percent / 100)
                },
            active = row.bool("is_active") == true,
        )
    }

    /** 没有服务器的额度行时：Claude Code 的 /usage 文字版也是这几项。 */
    private fun fixedWindows(limits: JsonObject?): List<QuotaWindow> {
        limits ?: return emptyList()
        val list = buildList {
            fun add(key: String, kind: String, group: String, scope: String?) {
                val w = limits.obj(key) ?: return
                val utilization = w.double("utilization") ?: return
                add(
                    QuotaWindow(
                        kind,
                        group,
                        scope,
                        utilization / 100,
                        parseTime(w.str("resets_at")),
                        severityOf(utilization / 100),
                    ),
                )
            }
            add("five_hour", "session", "session", null)
            add("seven_day", "weekly_all", "weekly", null)
            add("seven_day_opus", "weekly_scoped", "weekly", "Opus")
            add("seven_day_sonnet", "weekly_scoped", "weekly", "Sonnet")
            (limits["model_scoped"] as? JsonArray).orEmpty().forEach { element ->
                val m = element as? JsonObject ?: return@forEach
                val utilization = m.double("utilization") ?: return@forEach
                val name = m.str("display_name") ?: return@forEach
                if (none { it.scope == name }) {
                    add(
                        QuotaWindow(
                            "weekly_scoped",
                            "weekly",
                            name,
                            utilization / 100,
                            parseTime(m.str("resets_at")),
                            severityOf(utilization / 100),
                        ),
                    )
                }
            }
        }
        return list
    }

    /**
     * stream-json 里的 rate_limit_event（`rate_limit_info`）。每次模型请求的响应头里都有，数变了 Claude Code 才发：
     * `unifiedWindows` 里 five_hour、seven_day 是 0～1 的比例和秒级的重置时刻；顶层 status 为 rejected 表示请求被拒了。
     * 只更新事件里带的窗口，别的（按模型分的每周窗口、额外用量）沿用 [previous]。
     */
    fun applyEvent(previous: QuotaSnapshot?, info: JsonObject?, now: Long): QuotaSnapshot? {
        info ?: return previous
        var windows = previous?.windows.orEmpty()
        info.obj("unifiedWindows")?.let { unified ->
            for ((key, kind, group) in
                listOf(
                    Triple("five_hour", "session", "session"),
                    Triple("seven_day", "weekly_all", "weekly"),
                )) {
                val updated = unifiedWindow(unified.obj(key) ?: continue, kind, group) ?: continue
                windows = mergeWindow(windows, updated)
            }
        }
        val status = info.str("status")
        val blocked = status == "rejected" && info.bool("isUsingOverage") != true
        val base = previous ?: QuotaSnapshot(windows = emptyList(), updatedAt = now)
        if (windows == base.windows && status == null) return previous
        return base.copy(
            windows = windows,
            blocked = blocked,
            blockedUntil = if (blocked) info.lenientLong("resetsAt")?.let { it * 1000 } else null,
            updatedAt = now,
        )
    }

    /** rate_limit_event 里的一个窗口 [w]；没有用量比例时是 null。 */
    private fun unifiedWindow(w: JsonObject, kind: String, group: String): QuotaWindow? {
        val used = w.double("utilization") ?: return null
        val severity =
            when (w.str("status")) {
                "rejected" -> QuotaSeverity.Critical
                "allowed_warning" -> maxOf(QuotaSeverity.Warning, severityOf(used))
                else -> severityOf(used)
            }
        return QuotaWindow(
            kind,
            group,
            null,
            used,
            w.lenientLong("resetsAt")?.let { it * 1000 },
            severity,
        )
    }

    /** 用 [updated] 换掉 [windows] 里同一种（不分模型的）窗口，保留它的 active；还没有就加上，5 小时的放在前面。 */
    private fun mergeWindow(windows: List<QuotaWindow>, updated: QuotaWindow): List<QuotaWindow> {
        val index = windows.indexOfFirst { it.kind == updated.kind && it.scope == null }
        if (index >= 0)
            return windows.toMutableList().also {
                it[index] = updated.copy(active = windows[index].active)
            }
        // 新出现的放在前面：5 小时在每周之前
        return if (updated.kind == "session") listOf(updated) + windows else windows + updated
    }

    fun severityOf(used: Double): QuotaSeverity =
        when {
            used >= QuotaSnapshot.CRITICAL_AT -> QuotaSeverity.Critical
            used >= QuotaSnapshot.WARN_AT -> QuotaSeverity.Warning
            else -> QuotaSeverity.Normal
        }

    private fun parseTime(text: String?): Long? = text?.let {
        runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    /** “2 小时 5 分钟后重置”“周六 01:00 重置”。 */
    fun resetText(resetsAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        resetsAt ?: return null
        val left = resetsAt - now
        if (left <= 0) return "已重置"
        val minutes = (left + 59_999) / 60_000
        return when {
            minutes < 60 -> "$minutes 分钟后重置"
            minutes < 24 * 60 ->
                "${minutes / 60} 小时" +
                    (if (minutes % 60 != 0L) " ${minutes % 60} 分钟" else "") +
                    "后重置"
            else -> {
                val at = Instant.ofEpochMilli(resetsAt).atZone(zone)
                val days = (left / 86_400_000).toInt()
                // 星期自己查表：桌面版的运行时不带 jdk.localedata，Locale.CHINA 的 EEE 会退回英文
                "周" +
                    "一二三四五六日"[at.dayOfWeek.value - 1] +
                    DateTimeFormatter.ofPattern(" HH:mm").format(at) +
                    " 重置" +
                    if (days >= 7) "（${days} 天后）" else ""
            }
        }
    }

    /** “刚刚”“3 分钟前”。 */
    fun ageText(updatedAt: Long, now: Long): String {
        val minutes = (now - updatedAt) / 60_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 24 * 60 -> "${minutes / 60} 小时前"
            else -> "${minutes / (24 * 60)} 天前"
        }
    }

    fun money(minor: Double?, currency: String?): String? {
        minor ?: return null
        val amount = String.format(Locale.US, "%.2f", minor / 100)
        return when (currency?.uppercase()) {
            null,
            "USD" -> "$$amount"
            else -> "$amount ${currency.uppercase()}"
        }
    }

    // ---------- 存起来，应用下次打开时先显示上次的数 ----------

    fun encode(snapshot: QuotaSnapshot): String = buildJsonObject {
        put("updatedAt", snapshot.updatedAt)
        put("available", snapshot.available)
        put("blocked", snapshot.blocked)
        snapshot.blockedUntil?.let { put("blockedUntil", it) }
        snapshot.subscription?.let { put("subscription", it) }
        put(
            "windows",
            buildJsonArray {
                snapshot.windows.forEach { w ->
                    add(
                        buildJsonObject {
                            put("kind", w.kind)
                            put("group", w.group)
                            w.scope?.let { put("scope", it) }
                            put("used", w.used)
                            w.resetsAt?.let { put("resetsAt", it) }
                            put("severity", w.severity.name)
                            put("active", w.active)
                        },
                    )
                }
            },
        )
        snapshot.extra?.let { e ->
            put(
                "extra",
                buildJsonObject {
                    put("enabled", e.enabled)
                    e.usedMinor?.let { put("usedMinor", it) }
                    e.limitMinor?.let { put("limitMinor", it) }
                    e.currency?.let { put("currency", it) }
                    e.used?.let { put("used", it) }
                },
            )
        }
    }
        .toString()

    fun decode(text: String?): QuotaSnapshot? = runCatching {
        val o =
            kotlinx.serialization.json.Json.parseToJsonElement(text ?: return null) as JsonObject
        QuotaSnapshot(
            windows =
                (o["windows"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val w = element as? JsonObject ?: return@mapNotNull null
                    QuotaWindow(
                        kind = w.str("kind") ?: return@mapNotNull null,
                        group = w.str("group").orEmpty(),
                        scope = w.str("scope"),
                        used = w.double("used") ?: return@mapNotNull null,
                        resetsAt = w.lenientLong("resetsAt"),
                        severity =
                            runCatching { QuotaSeverity.valueOf(w.str("severity").orEmpty()) }
                                .getOrDefault(QuotaSeverity.Normal),
                        active = w.bool("active") == true,
                    )
                },
            extra =
                o.obj("extra")?.let { e ->
                    ExtraUsage(
                        e.bool("enabled") == true,
                        e.double("usedMinor"),
                        e.double("limitMinor"),
                        e.str("currency"),
                        e.double("used"),
                    )
                },
            subscription = o.str("subscription"),
            available = o.bool("available") != false,
            blocked = o.bool("blocked") == true,
            blockedUntil = o.lenientLong("blockedUntil"),
            updatedAt = o.lenientLong("updatedAt") ?: return null,
        )
    }
        .getOrNull()

    /** 时刻：整数，或者带小数的秒数。 */
    private fun JsonObject.lenientLong(key: String): Long? = long(key) ?: double(key)?.toLong()
}
