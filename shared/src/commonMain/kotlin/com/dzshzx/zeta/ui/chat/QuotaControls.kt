package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.Quota
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.rememberNow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 额度严重程度的颜色：严重红色，警告是 [StatusWarning]，平时用 [normal]。额度窗口、各台设备页的账号摘要共用。 */
@Composable
fun severityColor(severity: QuotaSeverity, normal: Color): Color =
    when (severity) {
        QuotaSeverity.Critical -> MiuixTheme.colorScheme.error
        QuotaSeverity.Warning -> StatusWarning
        QuotaSeverity.Normal -> normal
    }

/** 各个额度窗口：名字、已用比例、进度条、什么时候重置。用量弹窗和设置里的账号页共用。 */
@Composable
fun QuotaWindows(snapshot: QuotaSnapshot, now: Long, modifier: Modifier = Modifier) {
    val current = snapshot.current(now)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current.blocked) {
            QuotaNote(
                Quota.resetText(current.blockedUntil, now)?.let {
                    Strings.composerQuotaBlockedUntil(it)
                } ?: Strings.composerQuotaBlocked(),
                color = MiuixTheme.colorScheme.error,
            )
        }
        current.windows.forEach { QuotaRow(it, now) }
        current.extra
            ?.takeIf { it.enabled }
            ?.let { extra ->
                val used = Quota.money(extra.usedMinor, extra.currency)
                val limit = Quota.money(extra.limitMinor, extra.currency)
                val label =
                    if (extra.currency.equals("credits", ignoreCase = true)) "AI Credits"
                    else Strings.composerExtraUsage()
                QuotaLine(
                    label,
                    listOfNotNull(used, limit).joinToString(" / ").ifBlank {
                        Strings.composerExtraOn()
                    },
                    extra.used,
                    QuotaSeverity.Normal,
                    null,
                )
            }
    }
}

@Composable
private fun QuotaRow(window: QuotaWindow, now: Long) {
    QuotaLine(
        window.labelSaid(),
        "${window.percent}%",
        window.used,
        window.severity,
        Quota.resetText(window.resetsAt, now),
    )
}

@Composable
private fun QuotaLine(
    label: String,
    value: String,
    fraction: Double?,
    severity: QuotaSeverity,
    detail: String?,
) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onBackground
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = ink.copy(alpha = 0.88f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style =
                    MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                color = severityColor(severity, ink.copy(alpha = 0.88f)),
            )
        }
        if (fraction != null) {
            Spacer(Modifier.height(5.dp))
            UsageBar(fraction.toFloat(), severityColor(severity, colors.primary))
        }
        if (detail != null) {
            Spacer(Modifier.height(3.dp))
            QuotaNote(detail)
        }
    }
}

@Composable
fun QuotaNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color =
            if (color == Color.Unspecified) MiuixTheme.colorScheme.onBackgroundVariant else color,
        modifier = modifier,
    )
}

/** 额度的一句说明：从哪来、多久以前的数、有没有在刷新。 */
fun quotaFootnote(
    snapshot: QuotaSnapshot?,
    refreshing: Boolean,
    error: String?,
    now: Long,
): String = buildList {
    when {
        snapshot == null && refreshing -> add(Strings.composerQuotaFetching())
        snapshot == null -> add(Strings.composerQuotaNoData())
        !snapshot.available -> add(Strings.composerQuotaUnavailable())
        snapshot.windows.isEmpty() && snapshot.extra?.enabled != true ->
            add(Strings.composerQuotaNotYet())
        else ->
            add(
                Quota.ageText(snapshot.updatedAt, now).let {
                    if (refreshing) Strings.composerQuotaUpdatedRefreshing(it)
                    else Strings.composerQuotaUpdated(it)
                },
            )
    }
    error?.let { add(it) }
}
    .joinToString(Strings.composerSentenceGap())

/** 用量弹窗顶上的“订阅额度”：打开时数不够新就去问一次。 */
@Composable
fun QuotaDialogSection(
    hub: com.dzshzx.zeta.core.ConversationHub,
    backend: String,
    visible: Boolean,
    harness: String = Harnesses.CLAUDE,
) {
    val quota = hub.quota(backend, harness)
    val now = rememberNow()
    LaunchedEffect(visible, harness) { if (visible) quota.refresh() }
    // 在用中转时没有订阅额度：只说一句，不显示窗口、套餐和更新时间
    quota.relay?.let { relay ->
        QuotaNote(Strings.settingsAccountsQuotaRelay(relay), Modifier.padding(bottom = 8.dp))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Strings.composerQuotaTitle(),
            style =
                MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        quota.snapshot?.planLabel?.let { plan ->
            Text(
                text = plan,
                style =
                    MiuixTheme.textStyles.body2.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
        }
    }
    quota.snapshot?.takeIf { it.available }?.let { QuotaWindows(it, now) }
    QuotaNote(
        quotaFootnote(quota.snapshot, quota.refreshing, quota.error, now),
        Modifier.padding(top = 8.dp),
    )
}
