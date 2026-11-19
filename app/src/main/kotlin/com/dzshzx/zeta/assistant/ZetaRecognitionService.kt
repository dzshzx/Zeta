package com.dzshzx.zeta.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.dzshzx.zeta.core.AppVariant

/**
 * 数字助理必须带一个识别服务才能被选上，选上后系统的默认语音识别也跟着换成它（secure 设置 voice_recognition_service）。
 * Zeta 自己不做识别：转给手机上原有的识别服务（小米的 com.xiaomi.mibrain.speech/.asr.AsrService，没有时取别的），
 * 这样换了默认助理，别的应用经系统默认识别器的语音输入照常能用。回调原样转回去。
 *
 * 转发要 Zeta 有麦克风权限（RECORD_AUDIO）：下游识别服务按调用方核对。没有时下游报权限错误，原样转给调用方。
 */
class ZetaRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var active: Callback? = null
    private var recognizer: SpeechRecognizer? = null

    override fun getMaxConcurrentSessionsCount(): Int = 1

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        main.post {
            release()
            val target = delegateTarget(this)
            if (target == null) {
                runCatching { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
                return@post
            }
            val delegate = runCatching { SpeechRecognizer.createSpeechRecognizer(this, target) }.getOrNull()
            if (delegate == null) {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                return@post
            }
            active = listener
            recognizer = delegate
            delegate.setRecognitionListener(Forward(listener))
            runCatching { delegate.startListening(recognizerIntent) }.onFailure {
                runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
                release(listener)
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        main.post { if (active === listener) runCatching { recognizer?.stopListening() } }
    }

    override fun onCancel(listener: Callback) {
        main.post {
            if (active === listener) {
                runCatching { recognizer?.cancel() }
                release(listener)
            }
        }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release(expected: Callback? = null) {
        if (expected != null && active !== expected) return
        recognizer?.let {
            it.setRecognitionListener(null)
            runCatching { it.destroy() }
        }
        recognizer = null
        active = null
    }

    private inner class Forward(private val callback: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle) { runCatching { callback.readyForSpeech(params) } }
        override fun onBeginningOfSpeech() { runCatching { callback.beginningOfSpeech() } }
        override fun onRmsChanged(rmsdB: Float) { runCatching { callback.rmsChanged(rmsdB) } }
        override fun onBufferReceived(buffer: ByteArray) { runCatching { callback.bufferReceived(buffer) } }
        override fun onEndOfSpeech() { runCatching { callback.endOfSpeech() } }
        override fun onPartialResults(partialResults: Bundle) { runCatching { callback.partialResults(partialResults) } }
        override fun onSegmentResults(segmentResults: Bundle) { runCatching { callback.segmentResults(segmentResults) } }
        override fun onLanguageDetection(results: Bundle) {
            if (Build.VERSION.SDK_INT >= 34) runCatching { callback.languageDetection(results) }
        }
        override fun onEvent(eventType: Int, params: Bundle) = Unit

        override fun onError(error: Int) {
            runCatching { callback.error(error) }
            release(callback)
        }

        override fun onResults(results: Bundle) {
            runCatching { callback.results(results) }
            release(callback)
        }

        override fun onEndOfSegmentedSession() {
            runCatching { callback.endOfSegmentedSession() }
            release(callback)
        }
    }

    companion object {
        /** 先找小米的识别服务。 */
        const val PREFERRED = "com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService"

        /** 转给谁：手机上别的识别服务里优先小米的；Zeta 自己的（正式版、测试版）不算。 */
        fun delegateTarget(context: Context): ComponentName? {
            val services = runCatching {
                context.packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), PackageManager.ResolveInfoFlags.of(0))
            }.getOrNull().orEmpty().mapNotNull { info -> info.serviceInfo?.let { it.packageName + "/" + it.name } }
            return pickDelegate(services)?.let(ComponentName::unflattenFromString)
        }

        /** [services] 是“包名/完整类名”。纯函数，单元测试覆盖。 */
        fun pickDelegate(services: List<String>): String? {
            val others = services.filterNot { AppVariant.isZetaPackage(it.substringBefore('/')) }
            return others.firstOrNull { it == PREFERRED } ?: others.firstOrNull()
        }
    }
}
