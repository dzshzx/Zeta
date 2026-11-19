package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.voice.SpeechInput
import com.dzshzx.zeta.ui.chat.Composer
import com.dzshzx.zeta.ui.chat.HoldToTalk
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

/**
 * 输入栏的按住说话：输入框空着时发送键是麦克风；按住时识别出的字显示在输入框里、卡片描主题色的边、底栏换成提示；
 * 往上滑进取消区时换成红色。JVM 上没有识别服务，状态直接摆上去。
 */
class HoldToTalkShots : ShotTest() {
    private fun voice(state: SpeechInput.State, cancelling: Boolean = false): HoldToTalk {
        val input = SpeechInput(app)
        @Suppress("UNCHECKED_CAST")
        val flow = SpeechInput::class.java.getDeclaredField("mutableState").apply { isAccessible = true }.get(input) as MutableStateFlow<SpeechInput.State>
        flow.value = state
        return HoldToTalk(input, app) {}.also { it.cancelling = cancelling }
    }

    private fun shootComposer(name: String, voice: HoldToTalk) = shoot(name) {
        Scaffold(containerColor = COUITheme.colorScheme.background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(12.dp), contentAlignment = Alignment.BottomCenter) {
                Composer(
                    state = TextFieldState(),
                    busy = false,
                    enabled = true,
                    attachments = emptyList(),
                    files = emptyList(),
                    commands = emptyList(),
                    onPickImages = {},
                    onPickFiles = {},
                    onPickFolder = {},
                    onEnterPath = {},
                    onOpenPrompts = {},
                    onRemoveAttachment = {},
                    onRemoveFile = {},
                    onSlashTyped = {},
                    onSend = {},
                    onStop = {},
                    voice = voice,
                )
            }
        }
    }

    @Test
    fun idle() = shootComposer("hold-to-talk-idle", voice(SpeechInput.State()))

    @Test
    fun listening() = shootComposer(
        "hold-to-talk-listening",
        voice(SpeechInput.State(SpeechInput.Phase.Listening, text = "帮我看看今天下午的日程，顺便", level = 0.6f)),
    )

    @Test
    fun cancelling() = shootComposer(
        "hold-to-talk-cancelling",
        voice(SpeechInput.State(SpeechInput.Phase.Listening, text = "帮我看看今天下午的日程", level = 0.3f), cancelling = true),
    )
}
