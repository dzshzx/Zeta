package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.Quota
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.rememberNow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun severityColor(severity: QuotaSeverity, normal: Color): Color = when (severity) {
    QuotaSeverity.Critical -> StatusError
    QuotaSeverity.Warning -> StatusWarning
    QuotaSeverity.Normal -> normal
}

/**
 * 发送键左边、上下文圆环旁的额度提醒：快到上限（服务器说要注意，或用了八成以上）或已经被拒时才出现，
 * 平时不占地方。点开是用量弹窗，里面有各个窗口的详情。
 */
@Composable
internal fun QuotaChip(snapshot: QuotaSnapshot?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val current = snapshot?.current(now) ?: return
    val window = current.alarming ?: return
    val severity = if (current.blocked) QuotaSeverity.Critical else maxOf(window.severity, Quota.severityOf(window.used))
    val color = severityColor(severity, StatusWarning)
    val text = if (current.blocked) "已达上限" else "${window.shortLabel} ${window.percent}%"
    Row(
        modifier = modifier
            .heightIn(min = 30.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp)
            .semantics {
                contentDescription = if (current.blocked) {
                    "订阅额度已达上限" + (Quota.resetText(current.blockedUntil, now)?.let { "，$it" } ?: "")
                } else {
                    "订阅额度：${window.label}已用 ${window.percent}%"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
        Spacer(Modifier.width(3.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
        )
    }
}

/** 各个额度窗口：名字、已用比例、进度条、什么时候重置。用量弹窗和设置里的账号页共用。 */
@Composable
internal fun QuotaWindows(snapshot: QuotaSnapshot, now: Long, modifier: Modifier = Modifier) {
    val current = snapshot.current(now)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (current.blocked) {
            QuotaNote(
                "已达上限，暂时发不出请求" + (Quota.resetText(current.blockedUntil, now)?.let { "，$it" } ?: "") + "。",
                color = StatusError,
            )
        }
        current.windows.forEach { QuotaRow(it, now) }
        current.extra?.takeIf { it.enabled }?.let { extra ->
            val used = Quota.money(extra.usedMinor, extra.currency)
            val limit = Quota.money(extra.limitMinor, extra.currency)
            QuotaLine("额外用量（本月）", listOfNotNull(used, limit).joinToString(" / ").ifBlank { "已开启" }, extra.used, QuotaSeverity.Normal, null)
        }
    }
}

@Composable
private fun QuotaRow(window: QuotaWindow, now: Long) {
    QuotaLine(window.label, "${window.percent}%", window.used, window.severity, Quota.resetText(window.resetsAt, now))
}

@Composable
private fun QuotaLine(label: String, value: String, fraction: Double?, severity: QuotaSeverity, detail: String?) {
    val colors = MiuixTheme.colorScheme
    val ink = colors.onSurface
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
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
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
internal fun QuotaNote(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = if (color == Color.Unspecified) MiuixTheme.colorScheme.onSurfaceVariantSummary else color,
        modifier = modifier,
    )
}

/** 额度的一句说明：从哪来、多久以前的数、有没有在刷新。 */
internal fun quotaFootnote(snapshot: QuotaSnapshot?, refreshing: Boolean, error: String?, now: Long): String = buildList {
    when {
        snapshot == null && refreshing -> add("正在向 Claude Code 要额度…")
        snapshot == null -> add("还没有额度数据：发一条消息，或者点刷新。")
        !snapshot.available -> add("这种登录方式（API key、Bedrock、Vertex 等）没有订阅额度。")
        snapshot.windows.isEmpty() -> add("Claude Code 还没拿到这个账号的额度。")
        else -> add("${Quota.ageText(snapshot.updatedAt, now)}更新" + if (refreshing) "，正在刷新…" else "。")
    }
    error?.let { add(it) }
}.joinToString("")

/** 用量弹窗顶上的“订阅额度”：打开时数不够新就去问一次。 */
@Composable
internal fun QuotaDialogSection(hub: com.dzshzx.zeta.core.ConversationHub, visible: Boolean) {
    val quota = hub.quota
    val now = rememberNow()
    LaunchedEffect(visible) { if (visible) quota.refresh() }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "订阅额度",
            style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        quota.snapshot?.subscription?.let { sub ->
            Text(
                text = sub.replaceFirstChar { it.uppercase() },
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    quota.snapshot?.takeIf { it.available }?.let { QuotaWindows(it, now) }
    QuotaNote(quotaFootnote(quota.snapshot, quota.refreshing, quota.error, now), Modifier.padding(top = 8.dp))
}
