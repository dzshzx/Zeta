package com.dzshzx.zeta.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.voice.SpeechInput

/*
 * 输入栏的按住说话，交互照 Eta：输入框空着时发送键换成麦克风，按住开始识别，识别出的字实时显示在输入框里，
 * 松手发送，按住往上滑一段松手取消。识别本身在 core/voice/SpeechInput.kt（系统自带的识别服务），这里只管手势和提示。
 */

/** 一次按住说话的状态，随聊天页存在。 */
@Stable
internal class HoldToTalk(
    val input: SpeechInput,
    private val context: Context,
    private val askPermission: () -> Unit,
) {
    /** 手指滑到了取消区：松手就丢掉。 */
    var cancelling by mutableStateOf(false)

    /** 按住超过 [HOLD_DELAY_MS]：开始一场识别，识别完把文字交给 [onText]。没有麦克风权限时先去要，返回 false。 */
    fun begin(onText: (String) -> Unit): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission()
            return false
        }
        cancelling = false
        input.start(
            onResult = { text -> if (text.isBlank()) toast("没有听到说话") else onText(text.trim()) },
            onError = { toast(it.message) },
        )
        return true
    }

    /** 松手：在取消区就丢掉，否则等最后的结果再发送。 */
    fun release() {
        if (cancelling) input.cancel() else input.finish()
        cancelling = false
    }

    fun cancel() {
        input.cancel()
        cancelling = false
    }

    fun tapped() = toast("按住说话，松开发送")

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    companion object {
        /** 按住多久才算说话；更短的是轻点，只提示怎么用。 */
        const val HOLD_DELAY_MS = 250L

        /** 往上滑多远进取消区。 */
        val CancelDistance = 56.dp
    }
}

/** 手机上有能用的识别服务时给一个 [HoldToTalk]，没有时为 null（发送键照旧）。离开聊天页时丢掉进行中的识别。 */
@Composable
internal fun rememberHoldToTalk(): HoldToTalk? {
    val context = LocalContext.current
    val available = remember { SpeechInput.available(context) }
    if (!available) return null
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val text = if (granted) "可以按住说话了" else "没有麦克风权限，到设置的权限页一键授予"
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
    val voice = remember { HoldToTalk(SpeechInput(context), context) { permission.launch(Manifest.permission.RECORD_AUDIO) } }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    return voice
}

/**
 * 发送键上的按住说话手势：轻点只提示用法；按住超过 [HoldToTalk.HOLD_DELAY_MS] 开始识别（震一下），
 * 跟着手指判断是否滑进了取消区，松手交给 [HoldToTalk.release]。手势被打断（页面切走、来电）时丢掉这一场。
 */
internal fun Modifier.holdToTalk(voice: HoldToTalk, haptics: HapticFeedback, onText: (String) -> Unit): Modifier =
    pointerInput(voice) {
        val cancelPx = HoldToTalk.CancelDistance.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            // 到点前松开（或手指没了）是轻点；到点还按着返回 null
            val tapped = withTimeoutOrNull(HoldToTalk.HOLD_DELAY_MS) {
                var change = down
                while (change.pressed) change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                true
            }
            if (tapped != null) {
                voice.tapped()
                return@awaitEachGesture
            }
            if (!voice.begin(onText)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            var done = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    change.consume()
                    val cancelling = change.position.y - down.position.y < -cancelPx
                    if (cancelling != voice.cancelling) {
                        voice.cancelling = cancelling
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                    if (!change.pressed) break
                }
                voice.release()
                done = true
            } finally {
                if (!done) voice.cancel()
            }
        }
    }
