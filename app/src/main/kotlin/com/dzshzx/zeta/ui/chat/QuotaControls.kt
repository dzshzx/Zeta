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
import com.dzshzx.zeta.core.Quota
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.rememberNow
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Timer
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 额度严重程度的颜色：严重红色，警告是 [StatusWarning]，平时用 [normal]。额度提醒、额度窗口、设置首页的账号摘要共用。 */
@Composable
internal fun severityColor(severity: QuotaSeverity, normal: Color): Color =
    when (severity) {
        QuotaSeverity.Critical -> COUITheme.colorScheme.error
        QuotaSeverity.Warning -> StatusWarning
        QuotaSeverity.Normal -> normal
    }

/**
 * 额度提醒（见 [UsageButtons]）：快到上限（服务器说要注意，或用了八成以上）或已经被拒时是一个沙漏，按严重程度着色；
 * 没到这一步时不占地方。用了多少、是哪个窗口写在读屏说明和长按提示里，点开的用量弹窗里有全部窗口。 [current] 是按 [now]
 * 折算过的额度（[QuotaSnapshot.current]）。
 */
@Composable
internal fun QuotaAlarm(current: QuotaSnapshot, now: Long, onClick: () -> Unit) {
    val window = current.alarming ?: return
    val severity =
        if (current.blocked) QuotaSeverity.Critical
        else maxOf(window.severity, Quota.severityOf(window.used))
    BarButton(
        description =
            if (current.blocked) {
                "订阅额度已达上限" + (Quota.resetText(current.blockedUntil, now)?.let { "，$it" } ?: "")
            } else {
                "订阅额度（${window.label}）已用 ${window.percent}%"
            },
        onClick = onClick,
        colors = barChipColors(severityColor(severity, StatusWarning)),
    ) {
        Icon(imageVector = COUIIcons.Timer, contentDescription = null)
    }
}

/** 各个额度窗口：名字、已用比例、进度条、什么时候重置。用量弹窗和设置里的账号页共用。 */
@Composable
internal fun QuotaWindows(snapshot: QuotaSnapshot, now: Long, modifier: Modifier = Modifier) {
    val current = snapshot.current(now)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current.blocked) {
            QuotaNote(
                "已达上限，暂时发不出请求" +
                    (Quota.resetText(current.blockedUntil, now)?.let { "，$it" } ?: "") +
                    "。",
                color = COUITheme.colorScheme.error,
            )
        }
        current.windows.forEach { QuotaRow(it, now) }
        current.extra
            ?.takeIf { it.enabled }
            ?.let { extra ->
                val used = Quota.money(extra.usedMinor, extra.currency)
                val limit = Quota.money(extra.limitMinor, extra.currency)
                QuotaLine(
                    "额外用量（本月）",
                    listOfNotNull(used, limit).joinToString(" / ").ifBlank { "已开启" },
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
        window.label,
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
    val colors = COUITheme.colorScheme
    val ink = colors.labelPrimary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = COUITheme.textStyles.bodyS.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = ink.copy(alpha = 0.88f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style =
                    COUITheme.textStyles.bodyS.copy(
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
internal fun QuotaNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = if (color == Color.Unspecified) COUITheme.colorScheme.labelSecondary else color,
        modifier = modifier,
    )
}

/** 额度的一句说明：从哪来、多久以前的数、有没有在刷新。 */
internal fun quotaFootnote(
    snapshot: QuotaSnapshot?,
    refreshing: Boolean,
    error: String?,
    now: Long,
): String = buildList {
    when {
        snapshot == null && refreshing -> add("正在向 Claude Code 要额度…")
        snapshot == null -> add("还没有额度数据：发一条消息，或者点刷新。")
        !snapshot.available -> add("这种登录方式（API key、Bedrock、Vertex 等）没有订阅额度。")
        snapshot.windows.isEmpty() -> add("Claude Code 还没拿到这个账号的额度。")
        else ->
            add("${Quota.ageText(snapshot.updatedAt, now)}更新" + if (refreshing) "，正在刷新…" else "。")
    }
    error?.let { add(it) }
}
    .joinToString("")

/** 用量弹窗顶上的“订阅额度”：打开时数不够新就去问一次。 */
@Composable
internal fun QuotaDialogSection(
    hub: com.dzshzx.zeta.core.ConversationHub,
    backend: String,
    visible: Boolean,
) {
    val quota = hub.quota(backend)
    val now = rememberNow()
    LaunchedEffect(visible) { if (visible) quota.refresh() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "订阅额度",
            style =
                COUITheme.textStyles.bodyM.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = COUITheme.colorScheme.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        quota.snapshot?.subscription?.let { sub ->
            Text(
                text = sub.replaceFirstChar { it.uppercase() },
                style =
                    COUITheme.textStyles.bodyM.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                color = COUITheme.colorScheme.labelSecondary,
            )
        }
    }
    quota.snapshot?.takeIf { it.available }?.let { QuotaWindows(it, now) }
    QuotaNote(
        quotaFootnote(quota.snapshot, quota.refreshing, quota.error, now),
        Modifier.padding(top = 8.dp),
    )
}
