package com.dzshzx.zeta.core.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.i18n.Strings
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音识别：只用手机自带的识别服务（Android [SpeechRecognizer]），不接第三方服务。和界面无关， 输入栏的按住说话、以后的助手浮窗都用它。一次一场：[start]
 * 开始，[state] 里是实时文字， [finish] 停止收音、等最后的结果交给 onResult，[cancel] 丢掉这一场。都在主线程上调用。
 *
 * 系统的识别服务说完一句、停顿一会儿就自己结束。按住说话时用户还没松手，所以这里把这一句记下、 接着再听，直到 [finish]，交出去的是各句连起来的文字。
 *
 * 识别期间把识别服务所在的进程提到前台（[ProcessBoost]），否则 HyperOS 上小米的服务录不了音。
 */
class SpeechInput(private val context: Context, private val backend: SpeechBackend? = null) {
    private val mutableState = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0L
    private var segments = SpeechSegments()
    private var restarts = 0

    /** 识别服务正在听（startListening 之后、结果或出错之前）。 */
    private var listening = false
    private val relisten = Runnable {
        if (mutableState.value.phase == SpeechPhase.Listening) recognizer?.let(::listen)
    }
    private var onResult: ((String) -> Unit)? = null
    private var onError: ((SpeechInputError) -> Unit)? = null
    private val finishTimeout = Runnable { deliver() }
    private val boost = ProcessBoost(context.applicationContext, main)

    /**
     * 开始一场识别。[onResult] 在 [finish] 之后拿到最后的文字时调用（可能是空串，表示没听到话）； [onError] 在识别出错时调用。[cancel]
     * 之后两者都不再调用。已有一场时先取消它。
     */
    fun start(onResult: (String) -> Unit, onError: (SpeechInputError) -> Unit) {
        cancel()
        this.onResult = onResult
        this.onError = onError
        if (
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            fail(SpeechInputError(SpeechInputError.NO_PERMISSION, Strings.voiceNoMicPermission()))
            return
        }
        val chosen = backend ?: SpeechBackend.pick(context)
        if (chosen == null) {
            fail(SpeechInputError(SpeechInputError.NO_SERVICE, Strings.voiceNoRecognizer()))
            return
        }
        val created = runCatching {
            chosen.create(context)
        }
            .onFailure {
                DiagLog.warn(DiagTopic.App, "语音识别：建不了识别器（$chosen）：${it.javaClass.simpleName}")
            }
            .getOrNull()
        if (created == null) {
            fail(
                SpeechInputError(SpeechInputError.NO_SERVICE, Strings.voiceRecognizerUnavailable()),
            )
            return
        }
        recognizer = created
        segments = SpeechSegments()
        restarts = 0
        mutableState.value = SpeechState(SpeechPhase.Starting)
        val id = ++session
        created.setRecognitionListener(Listener(id))
        boost.bind((chosen as? SpeechBackend.Service)?.component?.packageName) {
            if (id == session) listen(created)
        }
        DiagLog.info(DiagTopic.App, "语音识别：开始（$chosen）")
    }

    /** 松手：停止收音，等识别服务交回最后一句。服务一直不回时几秒后用已有的文字收尾。 服务还没准备好就松手的，什么也没录到，直接交空串。 */
    fun finish() {
        val current = mutableState.value
        if (!current.active || current.phase == SpeechPhase.Finishing) return
        // 还没准备好，或一句刚结束、正等着接着听：没有在录的，直接用已有的文字收尾
        if (current.phase == SpeechPhase.Starting || !listening) {
            deliver()
            return
        }
        mutableState.value = current.copy(phase = SpeechPhase.Finishing, level = 0f)
        runCatching { recognizer?.stopListening() }
        main.postDelayed(finishTimeout, FINISH_TIMEOUT_MS)
    }

    /** 丢掉这一场，不交结果。 */
    fun cancel() {
        session++
        main.removeCallbacks(finishTimeout)
        main.removeCallbacks(relisten)
        listening = false
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        boost.release()
        onResult = null
        onError = null
        segments = SpeechSegments()
        mutableState.value = SpeechState()
    }

    private fun listen(target: SpeechRecognizer) {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        listening = true
        target.startListening(intent)
    }

    /** 交出结果并收尾。 */
    private fun deliver() {
        val callback = onResult
        val text = segments.text()
        cancel()
        DiagLog.info(DiagTopic.App, "语音识别：结束，${text.length} 字")
        callback?.invoke(text)
    }

    private fun fail(error: SpeechInputError) {
        val callback = onError
        DiagLog.warn(DiagTopic.App, "语音识别：${error.message}（${error.code}）")
        cancel()
        callback?.invoke(error)
    }

    private inner class Listener(private val id: Long) : RecognitionListener {
        private val live
            get() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (!live) return
            DiagLog.info(DiagTopic.App, "语音识别：可以说话了")
            val current = mutableState.value
            if (current.phase == SpeechPhase.Starting)
                mutableState.value = current.copy(phase = SpeechPhase.Listening)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            if (!live || mutableState.value.phase != SpeechPhase.Listening) return
            mutableState.value = mutableState.value.copy(level = SpeechSegments.level(rmsdB))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            segments.partial(partialResults.best())
            mutableState.value = mutableState.value.copy(text = segments.text())
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            listening = false
            segments.close(results.best())
            if (mutableState.value.phase == SpeechPhase.Finishing) {
                deliver()
            } else {
                // 服务认为这句说完了，用户还按着：接着听下一句
                mutableState.value = mutableState.value.copy(text = segments.text())
                listenAgain()
            }
        }

        override fun onError(error: Int) {
            if (!live) return
            listening = false
            val finishing = mutableState.value.phase == SpeechPhase.Finishing
            when {
                // 没听到话：松手后就用已有的文字收尾，还按着就接着听
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    segments.close(null)
                    if (finishing) deliver() else listenAgain()
                }
                // 松手后服务说“客户端出错”多半是 stopListening 和它自己结束撞上了，按收尾处理
                finishing && error == SpeechRecognizer.ERROR_CLIENT -> {
                    segments.close(null)
                    deliver()
                }
                // 接着听的那一下撞上服务还没收拾完上一句：再等一下重来（次数有上限）
                restarts > 0 &&
                    (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) -> listenAgain()
                else -> fail(SpeechInputError(error, SpeechInputError.describe(error)))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * 按着还没松手时接着听。要等一下再开始：在结果或出错的回调里马上 startListening， 系统还当上一句在进行，回
     * ERROR_CLIENT（真机实测）。服务接连马上结束（坏了）时不无限重来，按松手收尾。
     */
    private fun listenAgain() {
        if (++restarts > MAX_RESTARTS) {
            DiagLog.warn(DiagTopic.App, "语音识别：服务接连结束了 $restarts 次，不再接着听")
            deliver()
            return
        }
        main.postDelayed(relisten, RESTART_DELAY_MS)
    }

    private fun Bundle?.best(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        /** 松手后最多等识别服务这么久。 */
        const val FINISH_TIMEOUT_MS = 6_000L

        /** 一场里最多接着听几句。 */
        const val MAX_RESTARTS = 30

        /** 一句结束后隔多久接着听。 */
        const val RESTART_DELAY_MS = 250L

        /** 手机上有没有能用的识别服务（不查麦克风权限）。 */
        fun available(context: Context): Boolean = SpeechBackend.pick(context) != null
    }
}

/**
 * 把识别服务所在的进程提到前台。
 *
 * 系统替应用绑定识别服务时，HyperOS 上小米服务的进程留在后台（缓存状态，没有“使用中”的能力）， 它的麦克风权限只许在前台用，Android 核对录音的委托链时就在它这一环拒掉，回
 * ERROR_INSUFFICIENT_PERMISSIONS（9）； Zeta 自己这一环是通过的。所以识别期间由前台的 Zeta 带着 BIND_INCLUDE_CAPABILITIES
 * 绑定同一个应用里 不要权限的导出服务（小米的是它的朗读服务），那个进程跟着有了前台和麦克风能力。识别完解绑。 找不到这样的服务时直接往下走。
 */
private class ProcessBoost(private val context: Context, private val main: Handler) :
    ServiceConnection {
    private var bound = false
    private var then: (() -> Unit)? = null
    private val giveUp = Runnable { proceed() }

    /** 绑上 [packageName] 里的那个服务后调 [then]；没有这样的服务、绑不上或等了 [CONNECT_TIMEOUT_MS] 也调。 */
    fun bind(packageName: String?, then: () -> Unit) {
        release()
        val service = packageName?.let(::companion)
        if (service == null) {
            then()
            return
        }
        this.then = then
        bound =
            runCatching {
                    context.bindService(
                        Intent(TTS_SERVICE).setComponent(service),
                        this,
                        Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES,
                    )
                }
                .getOrDefault(false)
        if (bound) main.postDelayed(giveUp, CONNECT_TIMEOUT_MS) else proceed()
    }

    fun release() {
        main.removeCallbacks(giveUp)
        then = null
        if (bound) runCatching { context.unbindService(this) }
        bound = false
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) = proceed()

    override fun onNullBinding(name: ComponentName?) = proceed()

    override fun onServiceDisconnected(name: ComponentName?) = Unit

    private fun proceed() {
        main.removeCallbacks(giveUp)
        val next = then
        then = null
        next?.invoke()
    }

    /** 同一个应用里答应朗读服务、又不要权限的导出服务。 */
    private fun companion(packageName: String): ComponentName? = runCatching {
        context.packageManager
            .queryIntentServices(
                Intent(TTS_SERVICE).setPackage(packageName),
                PackageManager.ResolveInfoFlags.of(0),
            )
            .mapNotNull { it.serviceInfo }
            .firstOrNull { it.exported && it.permission == null }
            ?.let { ComponentName(it.packageName, it.name) }
    }
        .getOrNull()

    private companion object {
        const val TTS_SERVICE = "android.intent.action.TTS_SERVICE"
        const val CONNECT_TIMEOUT_MS = 2_000L
    }
}

/** 识别出错。[code] 是 [SpeechRecognizer] 的错误码，或这里的 [NO_PERMISSION]、[NO_SERVICE]。 */
data class SpeechInputError(val code: Int, val message: String) {
    companion object {
        const val NO_PERMISSION = -1
        const val NO_SERVICE = -2

        fun describe(code: Int): String =
            when (code) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK -> Strings.voiceErrorNetwork()
                SpeechRecognizer.ERROR_AUDIO -> Strings.voiceErrorAudio()
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> Strings.voiceErrorServer()
                SpeechRecognizer.ERROR_CLIENT -> Strings.voiceErrorClient()
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Strings.voiceErrorPermission()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Strings.voiceErrorBusy()
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> Strings.voiceErrorTooMany()
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Strings.voiceErrorLanguage()
                SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
                SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
                    Strings.voiceErrorUnavailable()
                else -> Strings.voiceErrorOther(code.toString())
            }
    }
}

/** 用哪个识别服务：系统设置里指定的、或手机上装着的某个识别服务组件，或者系统的端侧识别。 */
sealed interface SpeechBackend {
    fun create(context: Context): SpeechRecognizer

    data class Service(val component: ComponentName) : SpeechBackend {
        override fun create(context: Context): SpeechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext, component)

        override fun toString() = component.flattenToShortString()
    }

    data object OnDevice : SpeechBackend {
        override fun create(context: Context): SpeechRecognizer =
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context.applicationContext)

        override fun toString() = "端侧识别"
    }

    companion object {
        /** 小米的识别服务（小爱同学的语音引擎）。HyperOS 上系统设置 voice_recognition_service 是空的，要显式指定它。 */
        val XIAOMI =
            ComponentName("com.xiaomi.mibrain.speech", "com.xiaomi.mibrain.speech.asr.AsrService")
        const val XIAOMI_NAME = "com.xiaomi.mibrain.speech/com.xiaomi.mibrain.speech.asr.AsrService"

        /** 系统默认语音识别服务的 secure 设置键。 */
        const val SETTING = "voice_recognition_service"

        fun pick(context: Context): SpeechBackend? {
            val configured = runCatching {
                Settings.Secure.getString(context.contentResolver, SETTING)
            }
                .getOrNull()
                ?.let(ComponentName::unflattenFromString)
                ?.flattenToString()
            val installed = runCatching {
                context.packageManager
                    .queryIntentServices(
                        Intent(RecognitionService.SERVICE_INTERFACE),
                        PackageManager.ResolveInfoFlags.of(0),
                    )
                    .mapNotNull { it.serviceInfo }
                    .filter { it.exported }
                    .map { ComponentName(it.packageName, it.name).flattenToString() }
            }
                .getOrDefault(emptyList())
            choose(installed, configured)?.let(ComponentName::unflattenFromString)?.let {
                return Service(it)
            }
            return OnDevice.takeIf {
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)
            }
        }

        /**
         * 在装着的识别服务（完整的“包名/类名”）里挑真正干活的那个：系统设置里指定的优先，其次小米的，再其次第一个。 Zeta
         * 自己的（正式版、测试版）只是转给别人的代理，不算：数字助理是 Zeta 时系统默认识别会是它（见 AssistantRole.claimRecognizer），
         * 要是挑上它，一个版本的语音输入就要经另一个版本转一道。按住说话和代理服务转给谁都用它。纯函数。
         */
        fun choose(installed: List<String>, configured: String?): String? {
            val others = installed.filterNot { AppVariant.isZetaPackage(it.substringBefore('/')) }
            return configured?.takeIf { it in others }
                ?: XIAOMI_NAME.takeIf { it in others }
                ?: others.firstOrNull()
        }
    }
}

/** 一场识别里的文字：说完的几句和正在说的这句。系统服务一句一结束，这里把它们连起来。纯逻辑，单元测试覆盖。 */
class SpeechSegments {
    private val closed = mutableListOf<String>()
    private var current = ""

    /** 正在说的这句的中间结果。 */
    fun partial(text: String?) {
        if (!text.isNullOrBlank()) current = text.trim()
    }

    /** 这句说完了；[text] 为空时用最后一次中间结果。 */
    fun close(text: String?) {
        val final = text?.trim().takeUnless { it.isNullOrEmpty() } ?: current
        if (final.isNotEmpty()) closed += final
        current = ""
    }

    fun text(): String = join(closed + listOfNotNull(current.takeIf { it.isNotEmpty() }))

    companion object {
        /** 连起来：中文之间不加空格，两边都是拉丁字母或数字时加一个。 */
        fun join(parts: List<String>): String =
            parts.fold("") { acc, part ->
                when {
                    acc.isEmpty() -> part
                    acc.last().isLetterOrDigit() &&
                        acc.last().code < 0x2E80 &&
                        part.first().isLetterOrDigit() &&
                        part.first().code < 0x2E80 -> "$acc $part"
                    else -> acc + part
                }
            }

        /** 识别服务给的音量（dB，大约 -2 到 10）换成 0 到 1。 */
        fun level(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }
}
