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
import com.dzshzx.zeta.core.TurnUsage
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.core.formatCost
import com.dzshzx.zeta.core.formatElapsed
import com.dzshzx.zeta.core.formatExactTokens
import com.dzshzx.zeta.core.formatTokens
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.isDark
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.ZetaIcons
import com.dzshzx.zeta.ui.components.success
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 思考强度按钮，对应 Eta 输入栏里的“思考 / 深度思考”。Claude Code 的思考由 effort 控制（自适应思考，强度越高想得越多），
 * 所以这里列的是它的档位。按钮上只有一个刻度表（[ZetaIcons.gauge]，格数就是档位数），亮几格是第几档；没指定时画 Claude Code
 * 实际生效的档位，用中性的灰，指定了用主题色。档位名在读屏说明、长按提示和点开的单选菜单里。
 */
@Composable
fun EffortChip(
    effort: String?,
    applied: String?,
    enabled: Boolean = true,
    allowed: List<String>? = null,
    onChange: (String?) -> Unit,
) {
    val shownLevels =
        remember(allowed) {
            if (allowed == null) EffortLevels else EffortLevels.filter { it.first in allowed }
        }
    if (shownLevels.isEmpty()) return
    val choose by rememberUpdatedState(onChange)
    val defaultText =
        if (effort == null && applied != null)
            Strings.composerEffortDefaultApplied(effortLabel(applied).orEmpty())
        else Strings.composerEffortDefault()
    val entries =
        remember(defaultText, effort, shownLevels) {
            val items =
                listOf(
                    MenuItem(
                        text = defaultText,
                        selected = effort == null,
                        onClick = { choose(null) },
                    ),
                ) +
                    shownLevels.map { (value, label) ->
                        MenuItem(
                            text = label,
                            selected = value == effort,
                            onClick = { choose(value) },
                        )
                    }
            listOf(MenuEntry(items = items))
        }
    // 第几档，从 1 数；不知道是哪档时为 0
    val level = EffortLevels.indexOfFirst { it.first == (effort ?: applied) } + 1
    BarMenuButton(
        description = Strings.composerEffortDescription(effortLabel(effort) ?: defaultText),
        entries = entries,
        enabled = enabled,
        colors = if (effort == null) barChipColors(null) else barChipThemeColors(),
    ) {
        Icon(imageVector = EffortGauges[level], contentDescription = null)
    }
}

/** 思考强度的刻度表，第 i 个亮 i 格（0 是不知道档位）。 */
val EffortGauges: List<ImageVector> by lazy {
    (0..EffortLevels.size).map { ZetaIcons.gauge(it, EffortLevels.size) }
}

/** 上下文用了多少的颜色，三档固定：够用绿色，快满是 [StatusWarning]，马上要压缩或发不出去了红色。圆环和用量弹窗里的条共用。 */
@Composable
private fun levelColor(level: ContextLevel): Color =
    when (level) {
        ContextLevel.Critical -> MiuixTheme.colorScheme.error
        ContextLevel.Warning -> StatusWarning
        ContextLevel.Normal -> MiuixTheme.colorScheme.success
    }

/**
 * 思考强度后面的用量按钮：上下文圆环，颜色按用了多少分三档（[levelColor]），快满时底色跟着变；百分比在读屏说明和用量弹窗里。点开是用量弹窗（订阅额度、上下文、累计）。
 * 上下文用量还不知道时是空环，照样点得开。
 */
@Composable
fun UsageButton(usage: ChatUsage, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val dark = colors.isDark
    val ink = colors.onBackground
    val level = usage.level
    val color = levelColor(level)
    val percent = usage.fraction?.let { (it * 100).toInt() }
    val loud = level != ContextLevel.Normal && percent != null
    BarButton(
        description =
            percent?.let { Strings.composerContextUsagePercent(it) }
                ?: usage.contextTokens?.let { Strings.composerContextUsageTokens(formatTokens(it)) }
                ?: Strings.composerContextUsage(),
        onClick = onClick,
        colors = barChipColors(if (loud) color else null),
    ) {
        ContextRing(
            fraction = usage.fraction ?: 0f,
            color = color,
            track = ink.copy(alpha = if (dark) 0.14f else 0.09f),
        )
    }
}

/**
 * 上下文圆环：画在底栏图标位（[BarIconSize]）正中，外径按 [ZetaIcons.ROUND_OUTER] 折算，和思考强度的刻度表一样大、一样和按钮同心； 线宽
 * [RING_STROKE]，比图标的线（1.7/24，约 1.1dp）略粗，颜色才看得清。
 */
@Composable
private fun ContextRing(fraction: Float, color: Color, track: Color) {
    Canvas(Modifier.size(BarIconSize)) {
        val stroke = RING_STROKE.toPx()
        val outer = size.width * ZetaIcons.ROUND_OUTER / 24f
        val inset = (size.width - outer) / 2 + stroke / 2
        val arcSize = Size(outer - stroke, outer - stroke)
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
    }
}

/** 用量统计：订阅额度（[quota]）、上下文占用、本对话累计、各轮明细，底部是一键压缩。Miuix 的对话框，要在它的 Scaffold 里才显示。 */
@Composable
fun UsageDialog(
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
        title = Strings.composerUsageTitle(),
        summary = null,
        confirmText = Strings.composerCompact(),
        confirmEnabled = canCompact,
        cancelText = Strings.composerClose(),
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
    SectionHeader(
        Strings.composerTotals(),
        if (usage.turns.isEmpty()) null else Strings.composerTurnCount(usage.turns.size),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatRow(Strings.composerInputUncached(), "${formatExactTokens(total.input)} tok")
        StatRow(Strings.composerCacheRead(), "${formatExactTokens(total.cacheRead)} tok")
        StatRow(Strings.composerCacheWrite(), "${formatExactTokens(total.cacheWrite)} tok")
        StatRow(Strings.composerOutput(), "${formatExactTokens(total.output)} tok")
        StatRow(
            Strings.composerCost(),
            usage.totalCost?.let { costLabel(it, usage.costEstimated) } ?: "—",
        )
    }
    val notes = buildList {
        if (usage.costEstimated) add(Strings.composerCostEstimatedNote())
        if (usage.costPartial) add(Strings.composerCostPartialNote())
        if (skippedHistory > 0) add(Strings.composerSkippedHistoryNote())
    }
    if (notes.isNotEmpty())
        Note(notes.joinToString(Strings.composerSentenceGap()), Modifier.padding(top = 8.dp))
}

/** 各轮明细，新的在上；太长的对话只列最近 50 轮。 */
@Composable
private fun TurnsSection(usage: ChatUsage) {
    Spacer(Modifier.height(18.dp))
    SectionHeader(Strings.composerPerTurn(), null)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        usage.turns.withIndex().reversed().take(50).forEach { (index, turn) ->
            TurnRow(index + 1, turn)
        }
    }
}

@Composable
private fun ContextSection(usage: ChatUsage) {
    val colors = MiuixTheme.colorScheme
    val tokens = usage.contextTokens
    val window = usage.contextWindow
    val percent = usage.fraction?.let { String.format(java.util.Locale.US, "%.1f%%", it * 100) }
    SectionHeader(
        Strings.composerContext(),
        when {
            tokens == null -> null
            window == null -> "${formatTokens(tokens)} tokens"
            else -> "${formatTokens(tokens)} / ${formatTokens(window)} · $percent"
        },
    )
    usage.fraction?.let { fraction ->
        val color = levelColor(usage.level)
        UsageBar(fraction, color)
        Spacer(Modifier.height(8.dp))
    }
    val compactAt = usage.compactAt
    val detail = buildList {
        add(
            when {
                tokens == null -> Strings.composerContextEmpty()
                usage.autoCompact == false && usage.level == ContextLevel.Critical ->
                    Strings.composerContextFullNoAuto()
                usage.autoCompact == false -> Strings.composerContextNoAuto()
                usage.level == ContextLevel.Critical -> Strings.composerContextFull()
                usage.contextEstimated -> Strings.composerContextEstimated()
                else -> Strings.composerContextMeasured()
            },
        )
        if (compactAt != null && usage.autoCompact != false) {
            add(
                usage.compactFraction?.let {
                    Strings.composerCompactAtPercent(formatTokens(compactAt), (it * 100).toInt())
                } ?: Strings.composerCompactAt(formatTokens(compactAt)),
            )
        }
        if (usage.compactions > 0) add(Strings.composerCompactions(usage.compactions))
        val meta =
            listOfNotNull(
                    usage.model,
                    effortLabel(usage.appliedEffort)?.let { Strings.composerEffortMeta(it) },
                )
                .joinToString(" · ")
        if (meta.isNotEmpty()) add(meta)
    }
    Note(detail.joinToString("\n"))
}

/** 6dp 高的用量条：底槽上按 [fraction] 填一段 [color]。上下文和订阅额度共用。 */
@Composable
fun UsageBar(fraction: Float, color: Color) {
    val ink = MiuixTheme.colorScheme.onBackground
    val dark = MiuixTheme.colorScheme.isDark
    Box(
        Modifier.fillMaxWidth()
            .height(6.dp)
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
}

@Composable
private fun SectionHeader(title: String, value: String?) {
    val ink = MiuixTheme.colorScheme.onBackground
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style =
                MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = ink,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style =
                    MiuixTheme.textStyles.body2.copy(
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
    val colors = MiuixTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
            color = colors.onBackgroundVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style =
                MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    fontFeatureSettings = "tnum",
                ),
            color = colors.onBackground.copy(alpha = 0.84f),
            maxLines = 1,
        )
    }
}

/** 费用的写法：估出来的前面加 ≈。 */
private fun costLabel(usd: Double, estimated: Boolean): String =
    (if (estimated) "≈" else "") + formatCost(usd)

@Composable
private fun TurnRow(number: Int, turn: TurnUsage) {
    val colors = MiuixTheme.colorScheme
    val t = turn.tokens
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    Strings.composerTurnNumber(number.toString()) +
                        (turn.durationMs?.let { " · ${formatElapsed(it)}" } ?: ""),
                style =
                    MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                color = colors.onBackground.copy(alpha = 0.84f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = turn.costUsd?.let { costLabel(it, turn.costEstimated) } ?: "—",
                style =
                    MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        fontFeatureSettings = "tnum",
                    ),
                color = colors.onBackground.copy(alpha = 0.84f),
            )
        }
        Text(
            text =
                Strings.composerTurnTokens(
                    formatTokens(t.input),
                    formatTokens(t.cacheRead),
                    formatTokens(t.cacheWrite),
                    formatTokens(t.output),
                ),
            style =
                MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFeatureSettings = "tnum",
                ),
            color = colors.onBackgroundVariant,
        )
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = modifier,
    )
}

/** 上下文圆环的线宽（见 [ContextRing]）。 */
private val RING_STROKE = 1.4.dp
