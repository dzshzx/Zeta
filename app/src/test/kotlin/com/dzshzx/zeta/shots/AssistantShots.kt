package com.dzshzx.zeta.shots

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.assistant.AssistantOwner
import com.dzshzx.zeta.assistant.AssistantScreen
import com.dzshzx.zeta.assistant.AssistantSuggestions
import com.dzshzx.zeta.assistant.AssistantWakeGlowFrame
import com.dzshzx.zeta.assistant.PowerKeyStatus
import com.dzshzx.zeta.assistant.ReplyView
import com.dzshzx.zeta.assistant.ScreenChip
import com.dzshzx.zeta.assistant.WakeHandoffSeconds
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.settings.AssistantDiagnosticsGroup
import com.dzshzx.zeta.ui.settings.AssistantEntry
import com.dzshzx.zeta.ui.settings.AssistantSettingsScreen
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.LocalSquircleEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.junit.Test

/** 数字助理：浮窗刚唤起、发出去以后、回答中带停止键、去掉屏幕内容、唤起光效放到一半；设置里的一行和诊断页的一组。 */
class AssistantShots : ShotTest() {
    private fun thumbnail() =
        Bitmap.createBitmap(120, 260, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFF3A6EA5.toInt()) }
            .asImageBitmap()

    /** 浮窗下面垫一个假的“当前应用”，看得出浮窗是盖在别的应用上。 */
    @Composable
    private fun OverApp(content: @Composable () -> Unit) {
        // 平滑圆角在 JVM 上画不出来（整块空白），截图里换成普通圆角
        CompositionLocalProvider(LocalSquircleEnabled provides false) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F4F7))) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("设置", style = COUITheme.textStyles.headlineM, color = Color(0xFF222222))
                    listOf("WLAN", "蓝牙", "移动网络", "更多连接方式", "显示与亮度", "声音与振动").forEach {
                        Text(
                            it,
                            style = COUITheme.textStyles.bodyL,
                            color = Color(0xFF222222),
                            modifier = Modifier.padding(top = 18.dp),
                        )
                    }
                }
                content()
            }
        }
    }

    @Test
    fun entry() {
        shoot("assistant-entry") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = thumbnail(),
                            waiting = false,
                            removed = false,
                            textOnly = false,
                        ),
                    reply = null,
                    draft = remember { TextFieldState() },
                    suggestions = AssistantSuggestions.forContext(true),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    @Test
    fun reply() {
        shoot("assistant-reply") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = thumbnail(),
                            waiting = false,
                            removed = false,
                            textOnly = false,
                            sent = true,
                        ),
                    reply =
                        ReplyView(
                            text =
                                "这是系统**设置**的首页，列着网络和显示几类入口：\n\n- WLAN、蓝牙、移动网络管连接\n- 显示与亮度、声音与振动管屏幕和声音\n\n要改哪一项，点进去就行。",
                            status = null,
                            streaming = false,
                            attention = false,
                            error = null,
                        ),
                    draft = remember { TextFieldState() },
                    suggestions = AssistantSuggestions.forContext(true),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    /** ASSIST 唤起（只有应用信息）、用户去掉了屏幕内容、还在等回答。 */
    @Test
    fun removedAndWaiting() {
        shoot("assistant-waiting") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = null,
                            waiting = false,
                            removed = true,
                            textOnly = true,
                            sent = true,
                        ),
                    reply =
                        ReplyView(
                            text = "",
                            status = "Claude 正在看…",
                            streaming = false,
                            attention = false,
                            error = null,
                            busy = true,
                        ),
                    draft = remember { TextFieldState("再说说蓝牙") },
                    suggestions = emptyList(),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    /** 还没发：用户去掉了屏幕内容，可以再带上。 */
    @Test
    fun removed() {
        shoot("assistant-removed") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = thumbnail(),
                            waiting = false,
                            removed = true,
                            textOnly = false,
                        ),
                    reply = null,
                    draft = remember { TextFieldState("今天天气怎么样") },
                    suggestions = AssistantSuggestions.forContext(false),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    /** 回答很长、还在往外出：面板长到上限，回答在面板里滚动、跟到底。 */
    @Test
    fun longReply() {
        val answer = (1..30).joinToString("\n\n") { "第 $it 段：蓝牙、WLAN 和移动网络都在这一页的上半部分。" }
        shoot("assistant-long-reply") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = thumbnail(),
                            waiting = false,
                            removed = false,
                            textOnly = false,
                            sent = true,
                        ),
                    reply =
                        ReplyView(
                            text = answer,
                            status = null,
                            streaming = true,
                            attention = false,
                            error = null,
                            busy = true,
                        ),
                    draft = remember { TextFieldState() },
                    suggestions = emptyList(),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    /** 回答正在往外出：回答还短，面板停在基础高度，输入栏右边是停止键。 */
    @Test
    fun streaming() {
        shoot("assistant-streaming") {
            OverApp {
                AssistantScreen(
                    screen =
                        ScreenChip(
                            app = "设置（com.android.settings）",
                            thumbnail = thumbnail(),
                            waiting = false,
                            removed = false,
                            textOnly = false,
                            sent = true,
                        ),
                    reply =
                        ReplyView(
                            text = "这是系统设置的首页，上面几项管网络连接",
                            status = null,
                            streaming = true,
                            attention = false,
                            error = null,
                            busy = true,
                        ),
                    draft = remember { TextFieldState() },
                    suggestions = emptyList(),
                    sending = false,
                    problem = null,
                    visible = true,
                    onSend = {},
                    onToggleScreen = {},
                    onOpen = {},
                    onDismiss = {},
                    onStop = {},
                )
            }
        }
    }

    /** 唤起光效放到一半：流光正沿右边框往下。 */
    @Test
    fun wakeGlowStreaming() {
        shoot("assistant-wake-glow-streaming") {
            OverApp { AssistantWakeGlowFrame(seconds = 0.2f, bottomCornerRadius = 0f) }
        }
    }

    /** 唤起光效：流光落到底边、底部光场起来，输入栏从这一刻开始出现。 */
    @Test
    fun wakeGlowLanded() {
        shoot("assistant-wake-glow-landed") {
            OverApp {
                AssistantWakeGlowFrame(seconds = WakeHandoffSeconds, bottomCornerRadius = 0f)
            }
        }
    }

    @Test
    fun assistantPage() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val hub = Fixtures.readyHub(app)
        hub.settings.assistantModel = "sonnet"
        hub.settings.assistantEffort = "low"
        val owner =
            AssistantOwner(
                self = true,
                packageName = "com.dzshzx.zeta",
                label = "Zeta",
                recognizerPackage = "com.dzshzx.zeta",
            )
        shoot("assistant-page") { AssistantSettingsScreen(hub = hub, onBack = {}, owner = owner) }
    }

    @Test
    fun settingsAndDiagnostics() {
        val status =
            PowerKeyStatus(
                installed = true,
                installNote = null,
                recent =
                    listOf(
                        "09:01:02 装上了：ShortCutActionsUtils.triggerFunction(String, String, Bundle, boolean, String)",
                        "10:11:12 imperceptible_press_power_key：已显示 com.dzshzx.zeta 的助理会话，消费这次动作",
                        "10:11:12 long_press_power_key：同一次按键的重复派发，已吞掉",
                    ),
            )
        val owner =
            AssistantOwner(self = false, packageName = "com.miui.voiceassist", label = "超级小爱")
        shoot("assistant-settings") {
            CouiPage(title = "数字助理", onBack = {}) {
                item { PreferenceGroup(title = "能力") { item { AssistantEntry(owner) {} } } }
                item { AssistantDiagnosticsGroup(reloads = 0, owner = owner, status = status) }
            }
        }
    }

    /** 诊断页那一组出问题的样子：默认助理是 Zeta 但系统语音识别不是（开机保不住），模块还没有任何记录。 */
    @Test
    fun diagnosticsTrouble() {
        val owner =
            AssistantOwner(
                self = true,
                packageName = "com.dzshzx.zeta",
                label = "Zeta",
                recognizerPackage = "com.xiaomi.mibrain.speech",
            )
        val status = PowerKeyStatus(installed = null, installNote = null, recent = emptyList())
        shoot("assistant-diagnostics-trouble") {
            CouiPage(title = "诊断", onBack = {}) {
                item { AssistantDiagnosticsGroup(reloads = 0, owner = owner, status = status) }
            }
        }
    }
}
