package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ChatUsage
import com.dzshzx.zeta.core.ContextLevel
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.TurnUsage
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.core.formatCost
import com.dzshzx.zeta.core.formatElapsed
import com.dzshzx.zeta.core.formatExactTokens
import com.dzshzx.zeta.core.formatTokens
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import com.dzshzx.zeta.ui.components.rememberNow
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 思考强度按钮，对应 Eta 输入栏里的“思考 / 深度思考”。Claude Code 的思考由 effort 控制（自适应思考，强度越高想得越多），
 * 所以这里列的是它的档位。按钮上只有一个刻度表（[ZetaIcons.gauge]，格数就是档位数），亮几格是第几档；没指定时画 Claude Code
 * 实际生效的档位，用中性的灰，指定了用主题色。档位名在读屏说明、长按提示和点开的单选菜单里。
 */
@Composable
internal fun EffortChip(
    effort: String?,
    applied: String?,
    enabled: Boolean = true,
    onChange: (String?) -> Unit,
) {
    val choose by rememberUpdatedState(onChange)
    val defaultText = if (effort == null && applied != null) "默认（${effortLabel(applied)}）" else "默认"
    val entries =
        remember(defaultText, effort) {
            val items =
                listOf(
                    DropdownItem(
                        text = defaultText,
                        selected = effort == null,
                        onClick = { choose(null) },
                    ),
                ) +
                    EffortLevels.map { (value, label) ->
                        DropdownItem(
                            text = label,
                            selected = value == effort,
                            onClick = { choose(value) },
                        )
                    }
            listOf(DropdownEntry(items = items))
        }
    // 第几档，从 1 数；不知道是哪档时为 0
    val level = EffortLevels.indexOfFirst { it.first == (effort ?: applied) } + 1
    BarMenuButton(
        description = "思考强度：${effortLabel(effort) ?: defaultText}",
        entries = entries,
        enabled = enabled,
        colors = if (effort == null) barChipColors(null) else barChipThemeColors(),
    ) {
        Icon(imageVector = EffortGauges[level], contentDescription = null)
    }
}

/** 思考强度的刻度表，第 i 个亮 i 格（0 是不知道档位）。 */
internal val EffortGauges: List<ImageVector> by lazy {
    (0..EffortLevels.size).map { ZetaIcons.gauge(it, EffortLevels.size) }
}

@Composable
private fun levelColor(level: ContextLevel, normal: Color): Color =
    when (level) {
        ContextLevel.Critical -> COUITheme.colorScheme.error
        ContextLevel.Warning -> StatusWarning
        ContextLevel.Normal -> normal
    }

/**
 * 发送键左边的用量按钮，点开都是用量弹窗（订阅额度、上下文、累计）：订阅额度快到上限或已被拒时有额度提醒（[QuotaAlarm]），
 * 上下文用量知道了就有上下文圆环。两样各占一个按钮，额度报警时上下文快满也看得见；都没有时不占地方。
 */
@Composable
internal fun UsageButtons(quota: QuotaSnapshot?, usage: ChatUsage, onClick: () -> Unit) {
    val now = rememberNow()
    quota?.current(now)?.let { QuotaAlarm(it, now, onClick) }
    if (usage.known) ContextMeter(usage, onClick)
}

/** 上下文圆环，快满时变色、底色跟着变；百分比在读屏说明和用量弹窗里。 */
@Composable
private fun ContextMeter(usage: ChatUsage, onClick: () -> Unit) {
    val colors = COUITheme.colorScheme
    val dark = colors.isDark
    val ink = colors.labelPrimary
    val level = usage.level
    val color = levelColor(level, ink.copy(alpha = if (dark) 0.52f else 0.46f))
    val percent = usage.fraction?.let { (it * 100).toInt() }
    val loud = level != ContextLevel.Normal && percent != null
    BarButton(
        description =
            "上下文用量" +
                (percent?.let { "：$it%" }
                    ?: usage.contextTokens?.let { "：${formatTokens(it)} tokens" }
                    ?: ""),
        onClick = onClick,
        colors = barChipColors(if (loud) color else null),
    ) {
        ContextRing(
            fraction = usage.fraction ?: 0f,
            color = color,
            track = ink.copy(alpha = if (dark) 0.14f else 0.09f),
            mark = usage.compactFraction,
            markColor = ink.copy(alpha = if (dark) 0.62f else 0.5f),
        )
    }
}

/** [mark] 是自动压缩点在圆环上的位置（0..1），画一道短刻度；快到窗口尽头的不画，和起点挤在一起看不清。 */
@Composable
private fun ContextRing(
    fraction: Float,
    color: Color,
    track: Color,
    modifier: Modifier = Modifier,
    mark: Float? = null,
    markColor: Color = track,
) {
    Canvas(modifier = modifier.size(16.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            track,
            0f,
            360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(stroke),
        )
        if (fraction > 0f) {
            drawArc(
                color,
                -90f,
                360f * fraction.coerceAtLeast(0.03f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        // 刻度画在最上面，用量越过它时也看得见
        if (mark != null && mark in 0.02f..0.97f) {
            val angle = Math.toRadians((-90.0 + 360.0 * mark))
            val radius = (size.width - stroke) / 2
            val center = Offset(size.width / 2, size.height / 2)
            val direction =
                Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(
                markColor,
                start = center + direction * (radius - stroke * 1.1f),
                end = center + direction * (radius + stroke * 0.5f),
                strokeWidth = 1.2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 用量统计：订阅额度（[quota]）、上下文占用、本对话累计、各轮明细，底部是一键压缩。COUI 2 的对话框，要在它的 Scaffold 里才显示。 */
@Composable
internal fun UsageDialog(
    visible: Boolean,
    usage: ChatUsage,
    skippedHistory: Int,
    canCompact: Boolean,
    onCompact: () -> Unit,
    onDismissRequest: () -> Unit,
    quota: (@Composable () -> Unit)? = null,
) {
    ConfirmDialog(
        show = visible,
        title = "用量",
        summary = null,
        confirmText = "压缩上下文",
        confirmEnabled = canCompact,
        cancelText = "关闭",
        onConfirm = {
            onDismissRequest()
            onCompact()
        },
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            if (quota != null) {
                quota()
                Spacer(Modifier.height(18.dp))
            }
            ContextSection(usage)
            Spacer(Modifier.height(18.dp))
            TotalsSection(usage, skippedHistory)
            if (usage.turns.isNotEmpty()) TurnsSection(usage)
        }
    }
}

/** 本对话累计：各类 token 和按 API 标价算的费用，有缺的注明。 */
@Composable
private fun TotalsSection(usage: ChatUsage, skippedHistory: Int) {
    val total = usage.total
    SectionHeader("本对话累计", if (usage.turns.isEmpty()) null else "${usage.turns.size} 轮")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatRow("输入（未走缓存）", "${formatExactTokens(total.input)} tok")
        StatRow("缓存读取", "${formatExactTokens(total.cacheRead)} tok")
        StatRow("缓存写入", "${formatExactTokens(total.cacheWrite)} tok")
        StatRow("输出", "${formatExactTokens(total.output)} tok")
        StatRow("费用（按 API 标价）", usage.totalCost?.let(::formatCost) ?: "—")
    }
    val notes = buildList {
        if (usage.costPartial) add("会话记录里没有费用，从记录恢复的轮次只计 token。")
        if (skippedHistory > 0) add("更早的记录没有载入，也没有计入。")
    }
    if (notes.isNotEmpty()) Note(notes.joinToString(""), Modifier.padding(top = 8.dp))
}

/** 各轮明细，新的在上；太长的对话只列最近 50 轮。 */
@Composable
private fun TurnsSection(usage: ChatUsage) {
    Spacer(Modifier.height(18.dp))
    SectionHeader("各轮", null)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        usage.turns.withIndex().reversed().take(50).forEach { (index, turn) ->
            TurnRow(index + 1, turn)
        }
    }
}

@Composable
private fun ContextSection(usage: ChatUsage) {
    val colors = COUITheme.colorScheme
    val tokens = usage.contextTokens
    val window = usage.contextWindow
    val percent = usage.fraction?.let { String.format(java.util.Locale.US, "%.1f%%", it * 100) }
    SectionHeader(
        "上下文",
        when {
            tokens == null -> null
            window == null -> "${formatTokens(tokens)} tokens"
            else -> "${formatTokens(tokens)} / ${formatTokens(window)} · $percent"
        },
    )
    usage.fraction?.let { fraction ->
        val color = levelColor(usage.level, colors.primary)
        // 上下各留 3dp，给压缩点的刻度伸出去
        UsageBar(fraction, color, Modifier.padding(vertical = 3.dp), mark = usage.compactFraction)
        Spacer(Modifier.height(8.dp))
    }
    val compactAt = usage.compactAt
    val detail = buildList {
        add(
            when {
                tokens == null -> "发出第一条消息后显示。"
                usage.autoCompact == false && usage.level == ContextLevel.Critical ->
                    "快满了，自动压缩已关闭，请手动压缩，不然很快发不出去。"
                usage.autoCompact == false -> "自动压缩已关闭（Zeta 或 Claude Code 的设置），快满时手动压缩。"
                usage.level == ContextLevel.Critical -> "快满了，Claude Code 马上会自动压缩；也可以现在手动压缩。"
                usage.contextEstimated -> "Claude Code 的估算，下一轮后按实测；快到上限时它会自动压缩。"
                else -> "按上一轮实测；快到上限时 Claude Code 会自动压缩。"
            },
        )
        if (compactAt != null && usage.autoCompact != false) {
            add(
                "自动压缩点：${formatTokens(compactAt)}" +
                    (usage.compactFraction?.let { "（窗口的 ${(it * 100).toInt()}%，条上的刻度）" } ?: "") +
                    "。",
            )
        }
        if (usage.compactions > 0) add("已压缩 ${usage.compactions} 次。")
        val meta =
            listOfNotNull(usage.model, effortLabel(usage.appliedEffort)?.let { "思考 $it" })
                .joinToString(" · ")
        if (meta.isNotEmpty()) add(meta)
    }
    Note(detail.joinToString("\n"))
}

/**
 * 6dp 高的用量条：底槽上按 [fraction] 填一段 [color]。[mark] 处画一道竖线（比如自动压缩点）， 上下各伸出条外
 * 3dp，要的话调用方自己留出这点空。上下文和订阅额度共用。
 */
@Composable
internal fun UsageBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    mark: Float? = null,
) {
    val ink = COUITheme.colorScheme.labelPrimary
    val dark = COUITheme.colorScheme.isDark
    Box(modifier = modifier.fillMaxWidth().height(6.dp)) {
        Box(
            modifier =
                Modifier.matchParentSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(ink.copy(alpha = if (dark) 0.12f else 0.07f)),
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
        if (mark != null) {
            val markColor = ink.copy(alpha = if (dark) 0.6f else 0.5f)
            Canvas(Modifier.matchParentSize()) {
                val x = (size.width * mark).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
                val reach = 3.dp.toPx()
                drawLine(
                    markColor,
                    Offset(x, -reach),
                    Offset(x, size.height + reach),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, value: String?) {
    val ink = COUITheme.colorScheme.labelPrimary
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style =
                COUITheme.textStyles.bodyM.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = ink,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style =
                    COUITheme.textStyles.bodyM.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                color = ink,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = COUITheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style =
                COUITheme.textStyles.bodyS.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontFeatureSettings = "tnum",
                ),
            color = colors.labelPrimary.copy(alpha = 0.84f),
            maxLines = 1,
        )
    }
}

@Composable
private fun TurnRow(number: Int, turn: TurnUsage) {
    val colors = COUITheme.colorScheme
    val t = turn.tokens
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 $number 轮" + (turn.durationMs?.let { " · ${formatElapsed(it)}" } ?: ""),
                style = COUITheme.textStyles.bodyS.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = colors.labelPrimary.copy(alpha = 0.84f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = turn.costUsd?.let(::formatCost) ?: "—",
                style =
                    COUITheme.textStyles.bodyS.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        fontFeatureSettings = "tnum",
                    ),
                color = colors.labelPrimary.copy(alpha = 0.84f),
            )
        }
        Text(
            text =
                "输入 ${formatTokens(t.input)} · 缓存读 ${formatTokens(t.cacheRead)} · 缓存写 ${formatTokens(t.cacheWrite)} · 输出 ${formatTokens(t.output)}",
            style =
                COUITheme.textStyles.bodyS.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFeatureSettings = "tnum",
                ),
            color = colors.labelSecondary,
        )
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = COUITheme.colorScheme.labelSecondary,
        modifier = modifier,
    )
}
