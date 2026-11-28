package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.i18n.coreSaid
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class QuotaSeverity {
    Normal,
    Warning,
    Critical,
}

/**
 * 订阅额度的一个窗口：5 小时、每周、每周里按模型分的……账号的套餐有哪些就有哪些，种类和次序都照服务器给的。 [kind]
 * 是服务器的分类（session、weekly_all、weekly_scoped，以后可能有新的），[scope] 是按模型或入口分的窗口的显示名，[label] 是核心起好的名字。
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
    val label: String,
    /** [label] 是核心起的名字时它的种类码（参数 scope 就是 [scope]）；按模型分、名字是服务器原文的窗口为空。 */
    val labelCode: String = "",
) {
    /** 窗口名用界面语言说出来。 */
    fun labelSaid(): String =
        coreSaid(labelCode, JsonObject(mapOf("scope" to JsonPrimitive(scope.orEmpty()))), label)

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

/** 一个账号的订阅额度：主机上的核心存着、推来的那份（`quota.snapshot`，见 docs/core.md「账号与额度」）。 */
@Immutable
data class QuotaSnapshot(
    val windows: List<QuotaWindow>,
    val extra: ExtraUsage? = null,
    /** Claude Code 报的订阅类型（pro、max、team…）；Max 与 Ultra 都是 max，分不开。 */
    val subscription: String? = null,
    /** 套餐的展示名（Pro、Max、Ultra…），核心取在用登录的资料；旧快照里没有时为 null。 */
    val plan: String? = null,
    /** false：这种登录方式没有订阅额度（API key、Bedrock、Vertex，或登录缺 profile 权限）。 */
    val available: Boolean = true,
    /** 请求已经被拒：到上限了，[blockedUntil] 解除。 */
    val blocked: Boolean = false,
    val blockedUntil: Long? = null,
    /** 核心收到这份数据的时刻（毫秒）。 */
    val updatedAt: Long,
) {
    /** 套餐的显示名：[plan]，没有时由订阅类型写成；两者都没有（API key 等没有订阅的登录）时为 null。 */
    val planLabel: String?
        get() = planLabel(plan, subscription).ifEmpty { null }

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
}

/** 核心给的额度（`helper/accounts/quota.go` 的 `Snapshot`）换成界面用的样子，和显示用的几句话。纯函数，不依赖 Android。 */
object Quota {
    /**
     * `quota.snapshot`、`quota.usage` 等结果里的 `snapshot`。核心说还没有数（`null`）或不是这个样子时返回 null。 时刻是 RFC 3339
     * 的文字，换成毫秒。
     */
    fun fromCore(o: JsonObject?): QuotaSnapshot? {
        o ?: return null
        return QuotaSnapshot(
            windows = o.arr("windows").orEmpty().mapNotNull { (it as? JsonObject)?.let(::window) },
            extra = o.obj("extra")?.let(::extra),
            subscription = o.str("subscription")?.ifEmpty { null },
            plan = o.str("plan")?.ifEmpty { null },
            available = o.bool("unavailable") != true,
            blocked = o.bool("blocked") == true,
            blockedUntil = time(o.str("blockedUntil")),
            updatedAt = time(o.str("updatedAt")) ?: return null,
        )
    }

    /** 存在本机的那份（核心给的原文，见 QuotaService）读回来；读不懂时为 null。 */
    fun fromSaved(text: String?): QuotaSnapshot? = runCatching {
        fromCore(Json.parseToJsonElement(text ?: return null) as? JsonObject)
    }
        .getOrNull()

    private fun window(w: JsonObject): QuotaWindow? =
        QuotaWindow(
            kind = w.str("kind") ?: return null,
            group = w.str("group").orEmpty(),
            scope = w.str("scope")?.ifEmpty { null },
            used = w.double("used") ?: return null,
            resetsAt = time(w.str("resetsAt")),
            severity =
                when (w.str("severity")) {
                    "critical" -> QuotaSeverity.Critical
                    "warning" -> QuotaSeverity.Warning
                    else -> QuotaSeverity.Normal
                },
            active = w.bool("active") == true,
            label = w.str("label") ?: return null,
            labelCode = w.str("labelCode").orEmpty(),
        )

    private fun extra(e: JsonObject) =
        ExtraUsage(
            enabled = e.bool("enabled") == true,
            usedMinor = e.double("usedMinor"),
            limitMinor = e.double("limitMinor"),
            currency = e.str("currency")?.ifEmpty { null },
            used = e.double("used"),
        )

    private fun time(text: String?): Long? = text?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    }

    /** “2 小时 5 分钟后重置”“周六 01:00 重置”。 */
    fun resetText(resetsAt: Long?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        resetsAt ?: return null
        val left = resetsAt - now
        if (left <= 0) return Strings.coreQuotaHasReset()
        val minutes = ((left + 59_999) / 60_000).toInt()
        return when {
            minutes < 60 -> Strings.coreQuotaResetInMinutes(minutes)
            minutes < 24 * 60 ->
                if (minutes % 60 == 0) Strings.coreQuotaResetInHours(minutes / 60)
                else Strings.coreQuotaResetInHoursMinutes(minutes / 60, minutes % 60)
            else -> {
                val at = Instant.ofEpochMilli(resetsAt).atZone(zone)
                val days = (left / 86_400_000).toInt()
                val time =
                    DateTimeFormatter.ofPattern(
                            Strings.coreQuotaResetTimePattern(),
                            Locale.forLanguageTag(UiLanguage.current),
                        )
                        .format(at)
                if (days >= 7) Strings.coreQuotaResetAtFar(time, days)
                else Strings.coreQuotaResetAt(time)
            }
        }
    }

    /** “刚刚”“3 分钟前”。 */
    fun ageText(updatedAt: Long, now: Long): String {
        val minutes = ((now - updatedAt) / 60_000).toInt()
        return when {
            minutes < 1 -> Strings.coreAgeJustNow()
            minutes < 60 -> Strings.coreAgeMinutes(minutes)
            minutes < 24 * 60 -> Strings.coreAgeHours(minutes / 60)
            else -> Strings.coreAgeDays(minutes / (24 * 60))
        }
    }

    fun money(minor: Double?, currency: String?): String? {
        minor ?: return null
        val amount = String.format(Locale.US, "%.2f", minor / 100)
        if (currency.equals("credits", ignoreCase = true)) {
            return Strings.coreCreditsUnit(amount.removeSuffix(".00"))
        }
        return when (currency?.uppercase()) {
            null,
            "USD" -> "$$amount"
            else -> "$amount ${currency.uppercase()}"
        }
    }
}
