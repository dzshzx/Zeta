package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.conv.HostCpu
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 输入栏上的负载标识隔多久问一次（Claude Code 状态栏是 15 秒，这里在眼前，快一些）。 */
const val HOST_CPU_POLL_MS = 5_000L

/** CPU 使用率到这么高算忙（标识变成 [StatusWarning]），到 [CPU_CRITICAL] 算满（变红）。 */
private const val CPU_WARNING = 70
private const val CPU_CRITICAL = 90

/** 负载数字往上挪多少才在图标位的视觉正中（截图测试 ChatPartsShots.usageChips 里量的）。 */
private val DIGIT_RAISE = (-0.45).dp

/**
 * 对话所在主机（手机本机的 Debian 或远程主机）整机的 CPU 使用率：聊天页看得见（生命周期至少 STARTED）时每 [HOST_CPU_POLL_MS] 问一次那台主机上的核心
 * （system.cpu），看不见时不问；[refresh] 变了马上再问一次。还没问到、问不成时为 null。
 */
@Composable
fun rememberHostCpu(conversation: Conversation, refresh: Int = 0): HostCpu? {
    var cpu by remember(conversation) { mutableStateOf<HostCpu?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(conversation, lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                cpu = withContext(Dispatchers.IO) { conversation.hostCpu() }
                delay(HOST_CPU_POLL_MS)
            }
        }
    }
    return cpu
}

@Composable
private fun cpuColor(percent: Int?): Color =
    when {
        percent == null -> COUITheme.colorScheme.labelSecondary
        percent >= CPU_CRITICAL -> COUITheme.colorScheme.error
        percent >= CPU_WARNING -> StatusWarning
        else -> COUITheme.colorScheme.labelSecondary
    }

/**
 * 上下文圆环左边的负载标识：主机整机的 CPU 使用率，字是百分数的数字（两位，字高和旁边的图标相当；%
 * 在长按提示和说明里），忙时变色（[CPU_WARNING]、[CPU_CRITICAL]）；还不知道时是一道横线。点开是 [HostCpuDialog]。
 */
@Composable
fun HostCpuButton(cpu: HostCpu?, onClick: () -> Unit) {
    val percent = cpu?.percent
    BarButton(
        description = "设备负载" + (percent?.let { "：CPU $it%" } ?: ""),
        onClick = onClick,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Text(
                // 文字按行高居中时数字偏下（行里下面留着降部的位置），往上挪回到字形的正中
                modifier = Modifier.offset(y = DIGIT_RAISE),
                // 图标位只放得下两位：满载写 99（已经是红的），确切的数在长按提示和说明里
                text = percent?.coerceAtMost(99)?.toString() ?: "–",
                style =
                    COUITheme.textStyles.bodyS.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.sp,
                    ),
                color = cpuColor(percent),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 负载标识点开的说明：哪台主机、CPU 使用率、几个核，怎么算的。 */
@Composable
fun HostCpuDialog(visible: Boolean, host: String, cpu: HostCpu?, onDismissRequest: () -> Unit) {
    val percent = cpu?.percent
    val reading =
        when {
            cpu == null -> "还没有读到（对话没连上，或那台主机上的 Zeta 服务还没更新）。"
            percent == null -> "那台主机不让读 CPU 计数（/proc/stat）。"
            else -> "CPU 使用率 $percent%" + (if (cpu.cores > 0) "，${cpu.cores} 个核的平均" else "") + "。"
        }
    NoticeDialog(
        show = visible,
        title = "设备负载",
        summary =
            "$host：$reading\n\n整机所有进程的 CPU 忙碌占比，和 Claude Code 状态栏里的 cpu% 同一个算法（两次读 /proc/stat 相减，等磁盘也算空闲）。" +
                "聊天页开着时每 ${HOST_CPU_POLL_MS / 1000} 秒更新一次。",
        onDismissRequest = onDismissRequest,
    )
}
