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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.phone.VirtualScreen
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.itos.coui.shape.CouiShape
import com.itos.coui.theme.CouiTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/** 缩略图的刷新间隔：看个大概就行，省电。 */
private const val ThumbnailIntervalMs = 500L

/** 查看页的刷新间隔（约 20 帧每秒）。 */
private const val ViewerIntervalMs = 50L

/**
 * 输入卡片上方的“后台屏幕”条：这个对话的后台屏幕在用时出现，带实时缩略图和上面的应用名。
 * 休眠时缩略图是休眠前那一帧，调暗显示。点它打开全屏查看（会唤醒）；右边是“移到主屏”和“关闭”。
 */
@Composable
internal fun BackgroundScreenStrip(phone: PhoneTools, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val screen = phone.background ?: return
    val asleep by screen.asleep.collectAsState()
    val dark = CouiTheme.isDark
    val colors = MiuixTheme.colorScheme
    val shape = remember { CouiShape(18.dp, 2.3f) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (dark) Color(0xFF1C1E22) else Color(0xFFF6F7F9), shape)
            .border(0.5.dp, if (dark) Color(0x20FFFFFF) else Color(0x0E000000), shape)
            .clickable(onClickLabel = "查看后台屏幕", onClick = onOpen)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiveScreen(
            screen = screen,
            intervalMs = ThumbnailIntervalMs,
            dim = asleep,
            modifier = Modifier.height(40.dp).aspectRatio(screen.width / screen.height.toFloat()).clip(RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val accent = if (asleep) colors.onSurfaceVariantSummary else colors.primary
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (asleep) "后台屏幕 · 休眠中" else "后台屏幕",
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                    color = accent,
                )
            }
            Text(
                text = phone.backgroundApp ?: "还没有打开应用",
                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ActionIcon(Icons.Rounded.OpenInFull, "移到主屏", colors.primary) { phone.moveToMainByUser() }
        ActionIcon(Icons.Rounded.Close, "关闭后台屏幕", colors.onSurfaceVariantSummary) { phone.closeBackgroundByUser() }
    }
}

/** 全屏查看后台屏幕的实时画面（只能看）。开着时后台屏幕保持醒着（在休眠就唤醒，关掉后睡回去）。后台屏幕没了自动关闭。 */
@Composable
internal fun BackgroundScreenViewer(phone: PhoneTools, onDismiss: () -> Unit) {
    val screen = phone.background
    LaunchedEffect(screen) { if (screen == null) onDismiss() }
    if (screen == null) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台屏幕", style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.5.sp), color = Color(0xB3FFFFFF))
                    Text(
                        text = phone.backgroundApp ?: "还没有打开应用",
                        style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ActionIcon(Icons.Rounded.Close, "关闭查看", Color.White, onDismiss)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                LiveScreen(
                    screen = screen,
                    intervalMs = ViewerIntervalMs,
                    keepAwake = true,
                    modifier = Modifier
                        .aspectRatio(screen.width / screen.height.toFloat())
                        .clip(RoundedCornerShape(14.dp))
                        .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(14.dp)),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CouiButton(
                    text = "关闭后台屏幕",
                    onClick = {
                        phone.closeBackgroundByUser()
                        onDismiss()
                    },
                    role = CouiButtonRole.Destructive,
                    modifier = Modifier.weight(1f),
                )
                CouiButton(
                    text = "移到主屏",
                    onClick = {
                        phone.moveToMainByUser()
                        onDismiss()
                    },
                    role = CouiButtonRole.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 后台屏幕的实时画面；在屏上的时候才登记要帧，离开就停。[keepAwake]：看的期间让它醒着。[dim]：调暗（休眠时）。 */
@Composable
private fun LiveScreen(screen: VirtualScreen, intervalMs: Long, modifier: Modifier = Modifier, keepAwake: Boolean = false, dim: Boolean = false) {
    DisposableEffect(screen, intervalMs, keepAwake) {
        val stop = screen.watch(intervalMs, keepAwake)
        onDispose { stop() }
    }
    val frame by screen.frames.collectAsState()
    Canvas(modifier.background(Color.Black)) {
        val current = frame ?: return@Canvas
        drawImage(
            image = current.bitmap.asImageBitmap(),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(current.width, current.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = if (dim) 0.45f else 1f,
            filterQuality = FilterQuality.Medium,
        )
    }
}
