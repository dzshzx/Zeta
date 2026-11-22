package com.dzshzx.zeta.ui.chat

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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.voice.SpeechState
import com.dzshzx.zeta.ui.components.LocalHub
import kotlinx.coroutines.flow.StateFlow

/*
 * 输入栏的按住说话，交互照 Eta：输入框空着时发送键换成麦克风，按住开始识别，识别出的字实时显示在输入框里，
 * 松手发送，按住往上滑一段松手取消。识别和麦克风权限在平台那边（[TalkInput]，手机上是 app 的 ui/chat/SpeechTalk.kt），
 * 这里只管手势和提示。
 */

/** 按住说话背后的识别。手机上是系统识别服务加麦克风权限；桌面没有。 */
interface TalkInput {
    val state: StateFlow<SpeechState>

    /** 开始一场识别：[onResult] 拿到最后的文字（可能是空串），[onError] 拿到出错的一句。没有麦克风权限时先去要，返回 false。 */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit): Boolean

    /** 说完了：等最后的结果。 */
    fun finish()

    /** 丢掉这一场。 */
    fun cancel()
}

/** 一次按住说话的状态，随聊天页存在。 */
@Stable
class HoldToTalk(val input: TalkInput, private val toast: (String) -> Unit) {
    /** 手指滑到了取消区：松手就丢掉。 */
    var cancelling by mutableStateOf(false)

    /** 按住超过 [HOLD_DELAY_MS]：开始一场识别，识别完把文字交给 [onText]。没有麦克风权限时先去要，返回 false。 */
    fun begin(onText: (String) -> Unit): Boolean {
        cancelling = false
        return input.start(
            onResult = { text -> if (text.isBlank()) toast("没有听到说话") else onText(text.trim()) },
            onError = toast,
        )
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

    companion object {
        /** 按住多久才算说话；更短的是轻点，只提示怎么用。 */
        const val HOLD_DELAY_MS = 250L

        /** 往上滑多远进取消区。 */
        val CancelDistance = 56.dp
    }
}

/** 平台有能用的识别时给一个 [HoldToTalk]，没有时（桌面、手机上没有识别服务）为 null，发送键照旧。离开聊天页时丢掉进行中的识别。 */
@Composable
fun rememberHoldToTalk(): HoldToTalk? {
    val input = ChatPlatform.current.rememberTalkInput() ?: return null
    val hub = LocalHub.current
    val voice = remember(input) { HoldToTalk(input, hub.platform::toast) }
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    return voice
}

/**
 * 发送键上的按住说话手势：轻点只提示用法；按住超过 [HoldToTalk.HOLD_DELAY_MS] 开始识别（震一下）， 跟着手指判断是否滑进了取消区，松手交给
 * [HoldToTalk.release]。手势被打断（页面切走、来电）时丢掉这一场。
 */
fun Modifier.holdToTalk(
    voice: HoldToTalk,
    haptics: HapticFeedback,
    onText: (String) -> Unit,
): Modifier =
    pointerInput(voice) {
        val cancelPx = HoldToTalk.CancelDistance.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            if (releasedWithin(down, HoldToTalk.HOLD_DELAY_MS)) {
                voice.tapped()
                return@awaitEachGesture
            }
            if (!voice.begin(onText)) return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            followTalk(down, voice, haptics, cancelPx)
        }
    }

/** 到点前松开（或手指没了）是轻点，返回 true；到点还按着返回 false。 */
private suspend fun AwaitPointerEventScope.releasedWithin(
    down: PointerInputChange,
    timeoutMs: Long,
): Boolean =
    withTimeoutOrNull(timeoutMs) {
        var change = down
        while (change.pressed) change =
            awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
        true
    } != null

/** 识别开始后跟着手指：上滑超过 [cancelPx] 进取消区（进出时震一下），松手交给 [HoldToTalk.release]；手势被打断时丢掉这一场。 */
private suspend fun AwaitPointerEventScope.followTalk(
    down: PointerInputChange,
    voice: HoldToTalk,
    haptics: HapticFeedback,
    cancelPx: Float,
) {
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
