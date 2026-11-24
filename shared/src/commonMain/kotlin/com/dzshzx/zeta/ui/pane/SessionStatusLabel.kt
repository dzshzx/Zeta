package com.dzshzx.zeta.ui.pane

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.SessionStatus
import com.dzshzx.zeta.core.SessionStatus.Activity
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.rememberIndicatorClock
import com.dzshzx.zeta.ui.components.turn
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

/** 状态记号的大小：五种记号都是这一样大的圆，里面的笔画不同。 */
private val MarkSize = 12.dp
private val MarkStroke = 1.4.dp

/** 字形的中线在基线上方多少个字号：汉字、数字和大写字母都差不多在这里，和用哪套字体关系不大。 */
private const val GLYPH_CENTER_EM = 0.36f

fun statusText(status: SessionStatus): String =
    when (status.activity) {
        Activity.Waiting -> Strings.sidebarStatusWaiting()
        Activity.Answering -> Strings.sidebarStatusAnswering()
        Activity.Background -> Strings.sidebarStatusBackground(status.tasks)
        Activity.Unread -> Strings.sidebarStatusUnread()
        Activity.Idle -> Strings.sidebarStatusIdle()
    }

@Composable
private fun statusColor(activity: Activity): Color =
    when (activity) {
        Activity.Waiting -> StatusWarning
        Activity.Answering,
        Activity.Background -> COUITheme.colorScheme.primary
        Activity.Unread -> COUITheme.colorScheme.success
        Activity.Idle -> COUITheme.colorScheme.labelSecondaryVariant
    }

/** 会话列表行尾的状态：一个记号加两三个字，颜色跟着状态走。记号的圆心对着文字字形的中线。 */
@Composable
fun SessionStatusLabel(status: SessionStatus) {
    val color = statusColor(status.activity)
    // 字重用常规：中文、英文和数字在各种字体下都是同一个粗细
    val style = COUITheme.textStyles.bodyXS.copy(fontWeight = FontWeight.Normal)
    Layout(
        content = {
            StatusMark(status.activity, color)
            Text(text = statusText(status), style = style, color = color, maxLines = 1)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mark = measurables[0].measure(loose)
        val text = measurables[1].measure(loose)
        val gap = 5.dp.roundToPx()
        // 行框的中线随字体的上下留白变，字形的中线不变：按基线算
        val center = text[FirstBaseline] - (style.fontSize.toPx() * GLYPH_CENTER_EM).roundToInt()
        val markTop = center - mark.height / 2
        val shift = maxOf(0, -markTop)
        layout(mark.width + gap + text.width, maxOf(text.height, markTop + mark.height) + shift) {
            mark.place(0, markTop + shift)
            text.place(mark.width + gap, shift)
        }
    }
}

/** 状态的记号：同一个圆，在回答的缺口转着，其余的圆里各画一样东西；有新回答是实心的。 */
@Composable
private fun StatusMark(activity: Activity, color: Color) {
    // 转着的角度只在画的时候读：每帧只重画这个记号，不重组
    val clock = if (activity == Activity.Answering) rememberIndicatorClock() else null
    Canvas(Modifier.size(MarkSize)) {
        val stroke = MarkStroke.toPx()
        val line = Stroke(stroke, cap = StrokeCap.Round)
        val radius = (size.minDimension - stroke) / 2
        val c = center
        fun ring(tint: Color = color) = drawCircle(tint, radius, style = line)
        fun bar(x1: Dp, y1: Dp, x2: Dp, y2: Dp) =
            drawLine(
                color,
                c + Offset(x1.toPx(), y1.toPx()),
                c + Offset(x2.toPx(), y2.toPx()),
                stroke,
                StrokeCap.Round,
            )
        when (activity) {
            Activity.Unread -> drawCircle(color)
            Activity.Answering -> {
                ring(color.copy(alpha = 0.25f))
                drawArc(
                    color,
                    turn(clock?.value ?: 0L, 1000) - 90f,
                    250f,
                    false,
                    Offset(stroke / 2, stroke / 2),
                    Size(radius * 2, radius * 2),
                    style = line,
                )
            }
            // 感叹号
            Activity.Waiting -> {
                ring()
                bar(0.dp, (-2.6).dp, 0.dp, 0.4.dp)
                bar(0.dp, 2.6.dp, 0.dp, 2.6.dp)
            }
            // 钟面的两根针
            Activity.Background -> {
                ring()
                bar(0.dp, (-2.8).dp, 0.dp, 0.dp)
                bar(0.dp, 0.dp, 2.2.dp, 0.dp)
            }
            // 暂停的两竖
            Activity.Idle -> {
                ring()
                bar((-1.4).dp, (-2.dp), (-1.4).dp, 2.dp)
                bar(1.4.dp, (-2.dp), 1.4.dp, 2.dp)
            }
        }
    }
}
