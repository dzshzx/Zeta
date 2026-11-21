package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.components.AsleepFrameAlpha
import com.dzshzx.zeta.ui.components.FrameRate
import com.dzshzx.zeta.ui.components.LiveFrames
import com.dzshzx.zeta.ui.components.drawFrame
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Close
import io.github.suqi8.coui.kmp.icon.extended.ExpandMore
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 输入卡片上方的“后台屏幕”条：这个对话的后台屏幕在用时出现，带实时缩略图和上面的应用名。 休眠时缩略图是休眠前那一帧，调暗显示。点它打开全屏查看（会唤醒）；右边是“移到主屏”和“关闭”。 */
@Composable
internal fun AgentDisplayStrip(
    phone: PhoneTools,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen = phone.agentDisplay ?: return
    val asleep by screen.asleep.collectAsState()
    val colors = COUITheme.colorScheme
    val shape = remember { SquircleShape(ChatStripCorner) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .chatPanel(shape)
                .clickable(onClickLabel = "查看后台屏幕", onClick = onOpen)
                .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveScreen(
            screen = screen,
            rate = FrameRate.Thumbnail,
            dim = asleep,
            modifier =
                Modifier.height(40.dp)
                    .aspectRatio(screen.width / screen.height.toFloat())
                    .clip(RoundedCornerShape(COUIRoundCorner.XS)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val accent = if (asleep) colors.labelSecondary else colors.primary
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (asleep) "后台屏幕 · 休眠中" else "后台屏幕",
                    style = COUITheme.textStyles.bodyS,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
            }
            Text(
                text = phone.agentDisplayApp ?: "还没有打开应用",
                style = COUITheme.textStyles.bodyM,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionIcon(COUIIcons.ExpandMore, "移到主屏", colors.primary) { phone.moveToMainByUser() }
        ActionIcon(COUIIcons.Close, "关闭后台屏幕", colors.labelSecondary) {
            phone.closeAgentDisplayByUser()
        }
    }
}

/**
 * 全屏查看后台屏幕的实时画面（只能看）。开着时后台屏幕保持醒着（在休眠就唤醒，关掉后睡回去）。后台屏幕没了自动关闭。 是一个全屏的黑底窗口，像看图那样，不随深浅色变；COUI
 * 没有这种全屏查看器，窗口照旧用 Compose 的 Dialog。
 */
@Composable
internal fun AgentDisplayViewer(phone: PhoneTools, onDismiss: () -> Unit) {
    val screen = phone.agentDisplay
    LaunchedEffect(screen) { if (screen == null) onDismiss() }
    if (screen == null) return
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .background(Color.Black)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "后台屏幕",
                        style = COUITheme.textStyles.bodyS,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Text(
                        text = phone.agentDisplayApp ?: "还没有打开应用",
                        style = COUITheme.textStyles.headlineXS,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ActionIcon(COUIIcons.Close, "关闭查看", Color.White, onDismiss)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                LiveScreen(
                    screen = screen,
                    rate = FrameRate.Live,
                    keepAwake = true,
                    modifier =
                        Modifier.aspectRatio(screen.width / screen.height.toFloat())
                            .clip(RoundedCornerShape(COUIRoundCorner.L))
                            .border(
                                0.5.dp,
                                Color.White.copy(alpha = 0.2f),
                                RoundedCornerShape(COUIRoundCorner.L),
                            ),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "关闭后台屏幕",
                    onClick = {
                        phone.closeAgentDisplayByUser()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors =
                        ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.error),
                )
                TextButton(
                    text = "移到主屏",
                    onClick = {
                        phone.moveToMainByUser()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** 后台屏幕的实时画面；在屏上的时候才登记要帧，离开就停。[keepAwake]：看的期间让它醒着。[dim]：调暗（休眠时）。 */
@Composable
private fun LiveScreen(
    screen: VirtualScreen,
    rate: FrameRate,
    modifier: Modifier = Modifier,
    keepAwake: Boolean = false,
    dim: Boolean = false,
) {
    LiveFrames(screen, rate, keepAwake)
    val frame by screen.frames.collectAsState()
    Canvas(modifier.background(Color.Black)) {
        frame?.let {
            drawFrame(it, Rect(Offset.Zero, size), alpha = if (dim) AsleepFrameAlpha else 1f)
        }
    }
}
