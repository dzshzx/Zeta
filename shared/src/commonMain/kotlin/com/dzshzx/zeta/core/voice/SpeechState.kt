package com.dzshzx.zeta.core.voice

/** 一场语音识别走到哪一步。 */
enum class SpeechPhase {
    Idle,
    Starting,
    Listening,
    Finishing,
}

/** 一场语音识别的进展，输入栏按住说话时显示。识别本身在手机上（app 的 core/voice/SpeechInput.kt），桌面没有。 */
data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.Idle,
    /** 到目前为止识别出的文字（已说完的几句加上正在说的这句）。 */
    val text: String = "",
    /** 0 到 1 的音量，画波纹用。 */
    val level: Float = 0f,
) {
    val active: Boolean
        get() = phase != SpeechPhase.Idle
}
