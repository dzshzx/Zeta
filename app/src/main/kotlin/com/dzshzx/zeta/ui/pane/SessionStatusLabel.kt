package com.dzshzx.zeta.ui.pane

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.SessionStatus
import com.dzshzx.zeta.core.SessionStatus.Activity
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Layers
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 状态记号占的格子：各种记号都画在这一样大的格子里，文字才对得齐。 */
private val MarkBox = 14.dp

internal fun statusText(status: SessionStatus): String =
    when (status.activity) {
        Activity.Waiting -> "等你确认"
        Activity.Answering -> "回答中"
        Activity.Background -> "后台 ${status.tasks}"
        Activity.Unread -> "新回答"
        Activity.Idle -> "闲置"
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

/** 记号画出来的宽。 */
private fun markWidth(activity: Activity): Dp =
    when (activity) {
        Activity.Answering -> 12.dp
        Activity.Background -> 13.dp
        Activity.Waiting -> 11.dp
        Activity.Idle,
        Activity.Unread -> 9.dp
    }

/** 会话列表行尾的状态：一个记号加两三个字，颜色跟着状态走。 */
@Composable
internal fun SessionStatusLabel(status: SessionStatus) {
    val color = statusColor(status.activity)
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 各个记号自带的留白不一样：尺寸按画出来一样大来定，格子的宽取画出来的宽，和文字的间隔才一样；
        // 中文字形的中线比行框的略低，记号跟着往下挪一点
        Box(
            Modifier.size(markWidth(status.activity), MarkBox).offset(y = 0.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (status.activity) {
                Activity.Answering -> Spinner(color)
                Activity.Unread -> Box(Modifier.size(9.dp).background(color, CircleShape))
                Activity.Waiting -> Glyph(ZetaIcons.Bell, 15.dp, color)
                Activity.Background -> Glyph(COUIIcons.Layers, 14.dp, color)
                Activity.Idle -> Glyph(COUIIcons.Pause, 17.dp, color)
            }
        }
        Spacer(Modifier.width(5.dp))
        // 字重用常规：中文、英文和数字在各种字体下都是同一个粗细
        Text(
            text = statusText(status),
            style =
                COUITheme.textStyles.bodyXS.copy(
                    fontWeight = FontWeight.Normal,
                    // 去掉行高带来的上下留白，文字才和记号对在一条中线上
                    lineHeightStyle =
                        LineHeightStyle(
                            LineHeightStyle.Alignment.Center,
                            LineHeightStyle.Trim.Both,
                        ),
                ),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun Glyph(icon: ImageVector, size: Dp, color: Color) =
    Icon(icon, null, Modifier.requiredSize(size), tint = color)

/** 转着的缺口圆环：在回答。 */
@Composable
private fun Spinner(color: Color) {
    val turn by
        rememberInfiniteTransition(label = "status")
            .animateFloat(
                0f,
                360f,
                infiniteRepeatable(tween(1000, easing = LinearEasing)),
                label = "status-spin",
            )
    Canvas(Modifier.size(11.dp)) {
        val stroke = 1.6.dp.toPx()
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, style = Stroke(stroke))
        drawArc(color, turn - 90f, 250f, false, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
