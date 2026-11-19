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
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.PhoneTools
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
    var connected by remember { mutableStateOf(PhoneTools.accessibilityConnected) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var autoRestore by remember { mutableStateOf(AccessibilityGuard.autoRestore(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            connected = PhoneTools.accessibilityConnected
            delay(700)
        }
    }
    CouiPage(title = "手机操作", onBack = onBack) {
        item(key = "ability") {
            PreferenceGroup(
                title = "能力",
                footer = "打开后，Claude 可以读屏幕上的内容，点按、滑动、输入文字，打开应用，以及在 Android 一侧以 root 执行命令" +
                    "（pm、am、settings、dumpsys 这些）。每次调用仍然按对话当前的权限模式征求你的同意。开关对之后新开始的对话生效。",
            ) {
                item { SwitchPreference(checked = hub.phoneTools, onCheckedChange = hub::choosePhoneTools, title = "让 Claude 操作手机") }
            }
        }
        item(key = "background") {
            PreferenceGroup(
                title = "后台执行",
                footer = "Claude 打开的应用放在一块看不见的后台屏幕上运行，不占用你正在用的屏幕；每个对话各用一块。" +
                    "聊天页输入框上方会出现“后台屏幕”条，点开能看实时画面，也可以把应用移到主屏。" +
                    "需要你亲自操作（付款、验证码、登录）时，Claude 会把应用移到主屏交给你。" +
                    "后台屏幕上的应用不会抢音频；后台屏幕关掉时，上面的应用一并关闭。关掉这个开关后，应用都在主屏上打开。",
            ) {
                item { SwitchPreference(checked = hub.backgroundScreen, onCheckedChange = hub::chooseBackgroundScreen, title = "在后台屏幕上操作") }
            }
        }
        item(key = "accessibility") {
            PreferenceGroup(
                title = "无障碍服务",
                footer = "读控件列表、输入中文、做手势都靠它。没有它，Claude 只能靠截图和坐标操作主屏幕。" +
                    "Zeta 只在 Claude 调用手机工具时才用到它；Claude 要用而服务没连上时，Zeta 会经 root 自动把它打开。" +
                    "Zeta 被强行停止等情况下系统会停用它：Zeta 启动、对话开始用手机工具、系统设置变化时会检查，" +
                    "被关了就只把 Zeta 这一项加回启用列表，别的服务原样保留。在这里关掉的不会被加回。",
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
                            onClick = { thread(isDaemon = true) { PhoneTools.disableAccessibility(context.applicationContext, "手机操作页") } },
                        )
                    }
                } else {
                    item {
                        BasicComponent(
                            title = "开启无障碍服务",
                            summary = "经 root 直接开启，不用到系统设置里找",
                            enabled = !working,
                            onClick = {
                                working = true
                                failed = false
                                thread(isDaemon = true) {
                                    val ok = PhoneTools.enableAccessibility(context.applicationContext, "手机操作页")
                                    failed = !ok
                                    working = false
                                }
                            },
                        )
                    }
                    item {
                        ArrowPreference(
                            title = "打开系统的无障碍设置",
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
                        summary = "有 root 时自动加回，没有时发通知提醒",
                    )
                }
            }
        }
    }
}
