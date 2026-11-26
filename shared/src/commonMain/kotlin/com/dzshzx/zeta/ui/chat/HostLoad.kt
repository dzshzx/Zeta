package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.conv.HostCpu
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 输入栏上的负载标识隔多久问一次（Claude Code 状态栏是 15 秒，这里在眼前，快一些）。 */
private const val HOST_CPU_POLL_MS = 5_000L

/** CPU 使用率到这么高算忙（标识变成 [StatusWarning]），到 [CPU_CRITICAL] 算满（变红）。 */
private const val CPU_WARNING = 70
private const val CPU_CRITICAL = 90

/** 负载数字的字高（按 dp 定，不随系统字体放大：图标位是定宽的，放大了会被裁）。 */
private val DIGIT_SIZE = 11.5.dp

/** 负载数字往上挪多少才在图标位的视觉正中（截图测试 ChatPartsShots.usageChips 里量的，真机截图核对过）。 */
private val DIGIT_RAISE = (-0.45).dp

/**
 * 主机 [backend]（手机本机的 Debian 或远程主机）整机的 CPU 使用率：[active]（对话里有了消息）且聊天页看得见（生命周期至少 STARTED）时 每
 * [HOST_CPU_POLL_MS] 问一次那台主机上的核心（[com.dzshzx.zeta.core.ConvLinks.hostCpu]），别的时候不问。还没问到、问不成、不问时为
 * null。 交出去的是 State：只在读它的按钮和说明里重组。
 */
@Composable
fun rememberHostCpu(hub: ConversationHub, backend: String, active: Boolean): State<HostCpu?> {
    val cpu = remember(backend) { mutableStateOf<HostCpu?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(backend, lifecycle, active) {
        if (!active) {
            cpu.value = null
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                cpu.value = withContext(Dispatchers.IO) { hub.convLinks.hostCpu(backend) }
                delay(HOST_CPU_POLL_MS)
            }
        }
    }
    return cpu
}

private fun severityOf(percent: Int?): QuotaSeverity =
    when {
        percent == null || percent < CPU_WARNING -> QuotaSeverity.Normal
        percent < CPU_CRITICAL -> QuotaSeverity.Warning
        else -> QuotaSeverity.Critical
    }

/**
 * 上下文圆环左边的负载标识：主机整机的 CPU 使用率，字是百分数的数字（两位，字高和旁边的图标相当；%
 * 在长按提示和说明里），忙时变色（[CPU_WARNING]、[CPU_CRITICAL]，颜色同额度）；还不知道时是一道横线。点开是 [HostCpuDialog]。
 */
@Composable
fun HostCpuButton(cpu: () -> HostCpu?, onClick: () -> Unit) {
    val percent = cpu()?.percent
    BarButton(
        description = percent?.let { Strings.chatDeviceLoadCpu(it) } ?: Strings.chatDeviceLoad(),
        onClick = onClick,
    ) {
        Box(Modifier.size(BarIconSize), contentAlignment = Alignment.Center) {
            Text(
                // 文字按行高居中时数字偏下（行里下面留着降部的位置），往上挪回到字形的正中
                modifier = Modifier.offset(y = DIGIT_RAISE),
                // 图标位只放得下两位：满载写 99（已经是红的），确切的数在长按提示和说明里
                text = percent?.coerceAtMost(99)?.toString() ?: "–",
                style =
                    MiuixTheme.textStyles.footnote1.copy(
                        fontSize = with(LocalDensity.current) { DIGIT_SIZE.toSp() },
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.sp,
                    ),
                color =
                    severityColor(severityOf(percent), MiuixTheme.colorScheme.onBackgroundVariant),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 负载标识点开的说明：哪台主机、CPU 使用率、几个核，怎么算的。 */
@Composable
fun HostCpuDialog(
    visible: Boolean,
    host: String,
    cpu: () -> HostCpu?,
    onDismissRequest: () -> Unit,
) {
    val reading = cpu()
    val percent = reading?.percent
    val text =
        when {
            reading == null -> Strings.chatCpuNotRead()
            percent == null -> Strings.chatCpuNoAccess()
            else -> Strings.chatCpuUsage(percent, reading.cores)
        }
    NoticeDialog(
        show = visible,
        title = Strings.chatDeviceLoad(),
        summary = Strings.chatCpuExplain(host, text, (HOST_CPU_POLL_MS / 1000).toInt()),
        onDismissRequest = onDismissRequest,
    )
}
