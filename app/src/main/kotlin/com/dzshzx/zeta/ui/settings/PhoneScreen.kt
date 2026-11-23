package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import kotlin.concurrent.thread
import kotlinx.coroutines.delay

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
    CouiPage(title = Strings.settingsTitlePhone(), onBack = onBack) {
        // 「让 Claude 操作手机」给 Claude 读屏、点按、滑动、输入、打开应用，以及在 Android 一侧以 root 执行命令（pm、am、settings、dumpsys
        // 这些）；
        // 只对之后新开的 Claude Code 进程生效。「在后台屏幕上操作」每次打开应用时现读，立即生效：
        // 每个对话各用一块看不见的后台屏幕，屏上的应用不抢音频，后台屏幕关掉时上面的应用一并关闭；关掉开关后应用都在主屏上打开。
        // 聊天页输入框上方的「后台屏幕」条能看实时画面、把应用移到主屏
        item(key = "phone") {
            PreferenceGroup(
                footer = Strings.settingsPhoneFooter(),
            ) {
                item {
                    SwitchPreference(
                        checked = hub.settings.phoneTools,
                        onCheckedChange = { hub.settings.phoneTools = it },
                        title = Strings.settingsPhoneTools(),
                        summary = Strings.settingsPhoneToolsSummary(),
                    )
                }
                item {
                    SwitchPreference(
                        checked = hub.settings.agentDisplay,
                        onCheckedChange = { hub.settings.agentDisplay = it },
                        title = Strings.settingsPhoneAgentDisplay(),
                        summary = Strings.settingsPhoneAgentDisplaySummary(),
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
                title = Strings.settingsPermAccessibility(),
                footer = Strings.settingsPhoneA11yFooter(),
            ) {
                item {
                    BasicComponent(
                        title = Strings.settingsPhoneStatus(),
                        endActions = {
                            PreferenceValue(accessibilityState(connected, working, failed))
                        },
                    )
                }
                if (connected) {
                    item {
                        BasicComponent(
                            title = Strings.settingsPhoneA11yTurnOff(),
                            onClick = {
                                thread(isDaemon = true) {
                                    AccessibilityGuard.remove(context.applicationContext, "手机操作页")
                                }
                            },
                        )
                    }
                } else {
                    item {
                        BasicComponent(
                            title = Strings.settingsPhoneA11yTurnOn(),
                            summary = Strings.settingsPhoneA11yTurnOnSummary(),
                            enabled = !working,
                            onClick = {
                                working = true
                                failed = false
                                thread(isDaemon = true) {
                                    val ok =
                                        AccessibilityGuard.enable(
                                            context.applicationContext,
                                            "手机操作页",
                                        )
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
                        title = Strings.settingsPhoneAutoRestore(),
                    )
                }
                // 经 root 开不成时的退路，少用，放最后
                if (!connected) {
                    item {
                        ArrowPreference(
                            title = Strings.settingsPhoneOpenSystem(),
                            onClick = {
                                openPermissionSettings(context, PermissionId.Accessibility)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 无障碍服务的状态：已连接、正在开启、开启失败或未开启。 */
private fun accessibilityState(connected: Boolean, working: Boolean, failed: Boolean): String =
    when {
        connected -> Strings.settingsPermConnected()
        working -> Strings.settingsPhoneTurningOn()
        failed -> Strings.settingsPhoneTurnOnFailed()
        else -> Strings.commonNotEnabled()
    }
