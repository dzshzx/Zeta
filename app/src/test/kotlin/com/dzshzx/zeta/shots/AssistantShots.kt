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
import com.dzshzx.zeta.assistant.AssistantEdgeGlowFrame
import com.dzshzx.zeta.assistant.AssistantOwner
import com.dzshzx.zeta.assistant.AssistantReplies
import com.dzshzx.zeta.assistant.AssistantScreen
import com.dzshzx.zeta.assistant.AssistantSuggestions
import com.dzshzx.zeta.assistant.PowerKeyStatus
import com.dzshzx.zeta.assistant.ReplyView
import com.dzshzx.zeta.assistant.ScreenChip
import com.dzshzx.zeta.assistant.WakeHandoffSeconds
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.ThinkingStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.settings.AssistantDiagnosticsGroup
import com.dzshzx.zeta.ui.settings.AssistantEntry
import com.dzshzx.zeta.ui.settings.AssistantSettingsScreen
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.squircle.LocalSquircleEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.junit.Test

/** 数字助理：浮窗刚唤起、去掉屏幕内容、刚发出去、回答中、答完、长回答、唤起光效；设置里的一行和诊断页的一组。 */
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

    private fun screen(removed: Boolean = false) =
        ScreenChip(
            app = "设置",
            thumbnail = thumbnail(),
            removed = removed,
        )

    private val question = "这页讲了什么"

    private val answer =
        "这是系统**设置**的首页，列着网络和显示几类入口：\n\n- WLAN、蓝牙、移动网络管连接\n- 显示与亮度、声音与振动管屏幕和声音\n\n要改哪一项，点进去就行。"

    private val thought = WorkItem("w1", listOf(ThinkingStep("k1", "先看截图", false, 2)))

    private fun reply(vararg items: ChatItem, busy: Boolean): ReplyView =
        AssistantReplies.view(
            listOf(UserItem("u1", question)) + items,
            busy = busy,
            waitingFor = null,
            screen = screen(),
        )

    @Composable
    private fun Assistant(
        reply: ReplyView?,
        screen: ScreenChip? = screen(),
        draft: String = "",
        suggestions: List<String> = AssistantSuggestions.forContext(true),
    ) {
        OverApp {
            AssistantScreen(
                screen = screen,
                reply = reply,
                draft = remember { TextFieldState(draft) },
                suggestions = if (reply == null) suggestions else emptyList(),
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

    /** 刚唤起：带上的屏幕、一行三个建议问题、输入栏（空着，发送键是灰的）。 */
    @Test
    fun entry() {
        shoot("assistant-entry") { Assistant(reply = null) }
    }

    /** 还没发：用户去掉了屏幕内容，可以再带上；输入框里有字，发送键是主色。 */
    @Test
    fun removed() {
        shoot("assistant-removed") {
            Assistant(
                reply = null,
                screen = screen(removed = true),
                draft = "今天天气怎么样",
                suggestions = AssistantSuggestions.forContext(false),
            )
        }
    }

    /** 刚发出去：键盘收起、面板升起，顶上是问题的气泡（带着屏幕），下面「正在分析任务」，输入栏右边是停止键。 */
    @Test
    fun analyzing() {
        shoot("assistant-analyzing") { Assistant(reply = reply(busy = true)) }
    }

    /** 回答正在往外出：「思考 1 次」折叠着，下面流式出字。 */
    @Test
    fun streaming() {
        shoot("assistant-streaming") {
            Assistant(
                reply =
                    reply(
                        thought,
                        AnswerItem("a1", "这是系统设置的首页，上面几项管网络连接", streaming = true),
                        busy = true,
                    ),
            )
        }
    }

    /** 答完：正文下面是复制键，输入栏右边换回发送键。 */
    @Test
    fun answered() {
        shoot("assistant-reply") {
            Assistant(
                reply = reply(thought, AnswerItem("a1", answer, streaming = false), busy = false),
            )
        }
    }

    /** 回答很长、还在往外出：面板长到上限，回答在面板里滚动、跟到底。 */
    @Test
    fun longReply() {
        val long = (1..30).joinToString("\n\n") { "第 $it 段：蓝牙、WLAN 和移动网络都在这一页的上半部分。" }
        shoot("assistant-long-reply") {
            Assistant(reply = reply(thought, AnswerItem("a1", long, streaming = true), busy = true))
        }
    }

    /** 唤起光效放到一半：流光正沿右边框往下。 */
    @Test
    fun wakeGlowStreaming() {
        shoot("assistant-wake-glow-streaming") {
            OverApp { AssistantEdgeGlowFrame(seconds = 0.2f) }
        }
    }

    /** 唤起光效：流光落到底边、底部光场起来。 */
    @Test
    fun wakeGlowLanded() {
        shoot("assistant-wake-glow-landed") {
            OverApp { AssistantEdgeGlowFrame(seconds = WakeHandoffSeconds) }
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
