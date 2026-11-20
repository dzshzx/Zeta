package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

/** “手机操作”：要不要把手机工具交给 Claude，以及它依赖的无障碍服务的状态。 */
@Composable
internal fun PhoneScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(AccessibilityGuard.connected) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var autoRestore by remember { mutableStateOf(AccessibilityGuard.autoRestore(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = AccessibilityGuard.connected
            delay(700)
        }
    }
    CouiPage(title = "手机操作", onBack = onBack) {
        // 「让 Claude 操作手机」给 Claude 读屏、点按、滑动、输入、打开应用，以及在 Android 一侧以 root 执行命令（pm、am、settings、dumpsys 这些）；
        // 只对之后新开的 Claude Code 进程生效。「在后台屏幕上操作」每次打开应用时现读，立即生效：
        // 每个对话各用一块看不见的后台屏幕，屏上的应用不抢音频，后台屏幕关掉时上面的应用一并关闭；关掉开关后应用都在主屏上打开。
        // 聊天页输入框上方的「后台屏幕」条能看实时画面、把应用移到主屏
        item(key = "phone") {
            PreferenceGroup(
                footer = "「让 Claude 操作手机」对之后新开的对话生效，每次操作仍按权限模式征求你同意。" +
                    "要你亲自操作（付款、验证码、登录）时，Claude 会把应用移到主屏。",
            ) {
                item {
                    SwitchPreference(
                        checked = hub.settings.phoneTools,
                        onCheckedChange = { hub.settings.phoneTools = it },
                        title = "让 Claude 操作手机",
                        summary = "读屏、点按、输入，执行 root 命令",
                    )
                }
                item {
                    SwitchPreference(
                        checked = hub.settings.backgroundScreen,
                        onCheckedChange = { hub.settings.backgroundScreen = it },
                        title = "在后台屏幕上操作",
                        summary = "Claude 打开的应用不占你的屏幕",
                    )
                }
            }
        }
        // Zeta 只在 Claude 调用手机工具时才用到无障碍服务，要用而没连上时经 root 自动打开（AccessibilityGuard.enable），
        // 在这里关掉的也一样。「被系统关掉后自动打开」是另一回事（AccessibilityGuard）：Zeta 被强行停止等情况下系统会停用它，
        // Zeta 启动、对话开始用手机工具、系统设置变化时检查，被关了就只把 Zeta 这一项加回启用列表，别的服务原样保留；
        // 没有 root 时只发通知提醒。在这里关掉的，守护不会加回
        item(key = "accessibility") {
            PreferenceGroup(
                title = "无障碍服务",
                footer = "Claude 读控件、输入中文、做手势都靠它，没有它只能靠截图和坐标操作主屏幕。Claude 要用时会自动打开。",
            ) {
                item {
                    BasicComponent(
                        title = "状态",
                        endActions = {
                            PreferenceValue(
                                when {
                                    connected -> "已连接"
                                    working -> "正在开启…"
                                    failed -> "没有开启成功"
                                    else -> "未开启"
                                },
                            )
                        },
                    )
                }
                if (connected) {
                    item {
                        BasicComponent(
                            title = "关闭无障碍服务",
                            onClick = { thread(isDaemon = true) { AccessibilityGuard.remove(context.applicationContext, "手机操作页") } },
                        )
                    }
                } else {
                    item {
                        BasicComponent(
                            title = "开启无障碍服务",
                            summary = "经 root 直接开启",
                            enabled = !working,
                            onClick = {
                                working = true
                                failed = false
                                thread(isDaemon = true) {
                                    val ok = AccessibilityGuard.enable(context.applicationContext, "手机操作页")
                                    failed = !ok
                                    working = false
                                }
                            },
                        )
                    }
                }
                item {
                    SwitchPreference(
                        checked = autoRestore,
                        onCheckedChange = {
                            autoRestore = it
                            AccessibilityGuard.setAutoRestore(context.applicationContext, it)
                        },
                        title = "被系统关掉后自动打开",
                    )
                }
                // 经 root 开不成时的退路，少用，放最后
                if (!connected) {
                    item {
                        ArrowPreference(
                            title = "打开系统的无障碍设置",
                            onClick = { openPermissionSettings(context, PermissionId.Accessibility) },
                        )
                    }
                }
            }
        }
    }
}
