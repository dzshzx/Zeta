package com.dzshzx.zeta.ui.setup

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiSwitchPreference
import com.itos.coui.layout.Group
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/** “手机操作”：要不要把手机工具交给 Claude，以及它依赖的无障碍服务的状态。 */
@Composable
internal fun PhoneScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(PhoneTools.accessibilityConnected) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = PhoneTools.accessibilityConnected
            delay(700)
        }
    }
    CouiScaffoldPage(title = "手机操作", onBack = onBack) {
        Group(
            title = "能力",
            footer = "打开后，Claude 可以读屏幕上的内容，点按、滑动、输入文字，打开应用，以及在 Android 一侧以 root 执行命令" +
                "（pm、am、settings、dumpsys 这些）。每次调用仍然按对话当前的权限模式征求你的同意。开关对之后新开始的对话生效。",
        ) {
            CouiSwitchPreference(title = "让 Claude 操作手机", checked = hub.phoneTools, onCheckedChange = hub::choosePhoneTools)
        }
        Group(
            title = "无障碍服务",
            footer = "读控件列表、输入中文、做手势都靠它。没有它，Claude 只能靠截图和坐标操作主屏幕。" +
                "Zeta 只在 Claude 调用手机工具时才用到它；Claude 要用而服务没连上时，Zeta 会经 root 自动把它打开。",
        ) {
            CouiPreference(
                title = "状态",
                value = when {
                    connected -> "已连接"
                    working -> "正在开启…"
                    failed -> "没有开启成功"
                    else -> "未开启"
                },
            )
            if (connected) {
                CouiPreference(
                    title = "关闭无障碍服务",
                    onClick = { thread(isDaemon = true) { PhoneTools.disableAccessibility() } },
                    showChevron = false,
                )
            } else {
                CouiPreference(
                    title = "开启无障碍服务",
                    summary = "经 root 直接开启，不用到系统设置里找",
                    enabled = !working,
                    showChevron = false,
                    onClick = {
                        working = true
                        failed = false
                        thread(isDaemon = true) {
                            val ok = PhoneTools.enableAccessibility(context.applicationContext)
                            failed = !ok
                            working = false
                        }
                    },
                )
                CouiPreference(
                    title = "打开系统的无障碍设置",
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    },
                )
            }
        }
    }
}
