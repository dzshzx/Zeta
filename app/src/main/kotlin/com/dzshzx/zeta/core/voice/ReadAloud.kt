package com.dzshzx.zeta.core.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 朗读回答：用系统默认的朗读引擎（Android [TextToSpeech]，手机上是小米的），不接第三方服务。 进程里一份，同时只读一条，后点的替换前一条。读之前把 Markdown
 * 换成念得顺的文字（[SpeechText]）。
 *
 * 出声期间开着前台服务 [ReadAloudService]，退到后台、锁屏后接着读，通知上能停。 申请临时音频焦点（音乐会暂停，读完恢复）；焦点被抢走（来电、别的应用放声音）或拔掉耳机时停下。
 * 在主线程上调用。
 */
object ReadAloud {
    /** [id] 是正在读的那条回答，null 是没在读；[preparing] 是已经点了、还没出声。 */
    data class State(val id: String? = null, val preparing: Boolean = false)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var engine: TextToSpeech? = null
    private var engineReady = false
    private var waiting: (() -> Unit)? = null
    private var session = 0L
    private var focus: AudioFocusRequest? = null
    private var noisyRegistered = false

    /** 读完多久后放掉引擎（它是另一个应用里的服务，常连着占资源）。 */
    private const val IDLE_SHUTDOWN_MS = 120_000L

    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val shutdown = Runnable {
        engine?.shutdown()
        engine = null
        engineReady = false
    }

    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop(context)
            }
        }

    /** 正在读 [id] 就停下，否则读它。 */
    fun toggle(context: Context, id: String, markdown: String) {
        if (state.value.id == id) stop(context) else speak(context, id, markdown)
    }

    fun speak(context: Context, id: String, markdown: String) {
        val appContext = context.applicationContext
        app = appContext
        stop(appContext)
        main.removeCallbacks(shutdown)
        val current = ++session
        mutableState.value = State(id, preparing = true)
        scope.launch {
            val chunks =
                withContext(Dispatchers.Default) {
                    SpeechText.chunks(SpeechText.readable(markdown))
                }
            if (current != session) return@launch
            if (chunks.isEmpty()) {
                fail(appContext, Strings.voiceNothingToRead())
                return@launch
            }
            withEngine(appContext) { ready ->
                if (current != session) return@withEngine
                if (!ready) fail(appContext, Strings.voiceTtsUnavailable())
                else start(appContext, current, chunks)
            }
        }
    }

    fun stop(context: Context) {
        val wasActive = mutableState.value.id != null
        session++
        waiting = null
        if (engineReady) runCatching { engine?.stop() }
        release(context.applicationContext)
        mutableState.value = State()
        if (wasActive) main.postDelayed(shutdown, IDLE_SHUTDOWN_MS)
    }

    private fun start(context: Context, current: Long, chunks: List<String>) {
        val tts = engine ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(
                    { change ->
                        // 可以压低音量的短暂失去（导航播报之类）由系统压低，不停；其余的失去都停下
                        if (
                            current == session &&
                                change < 0 &&
                                change != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                        ) {
                            DiagLog.info(DiagTopic.App, "朗读：音频焦点被拿走（$change），停下")
                            stop(context)
                        }
                    },
                    main,
                )
                .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail(context, Strings.voiceNoAudioFocus())
            return
        }
        focus = request
        runCatching {
            context.registerReceiver(
                noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                Context.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        // 这时 Zeta 多半在前台（用户刚点了朗读），可以开前台服务；自动朗读时在后台，开不了也照样读
        ReadAloudService.start(context)
        tts.setAudioAttributes(attributes)
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val id = if (index == chunks.lastIndex) "$current:last" else "$current:$index"
            if (tts.speak(chunk, mode, null, id) != TextToSpeech.SUCCESS) {
                fail(context, Strings.voiceTtsRejected())
                return
            }
        }
        DiagLog.info(DiagTopic.App, "朗读：开始，${chunks.size} 段，${chunks.sumOf { it.length }} 字")
    }

    /** 引擎连好了再做 [then]（参数是连没连上）。连接是异步的，连着的时候又来的请求只留最后一个。 */
    private fun withEngine(context: Context, then: (Boolean) -> Unit) {
        if (engine != null && engineReady) {
            then(true)
            return
        }
        waiting = { then(engineReady) }
        if (engine != null) return
        var created: TextToSpeech? = null
        created =
            TextToSpeech(context) { status ->
                main.post {
                    if (engine !== created) return@post
                    engineReady = status == TextToSpeech.SUCCESS
                    if (engineReady) {
                        created?.setOnUtteranceProgressListener(Progress)
                    } else {
                        DiagLog.warn(DiagTopic.App, "朗读：引擎连不上（$status）")
                        created?.shutdown()
                        engine = null
                    }
                    val next = waiting
                    waiting = null
                    next?.invoke()
                }
            }
        engine = created
    }

    /** 引擎的回调在它自己的线程上，转回主线程。 */
    private object Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                if (sessionOf(utteranceId) == session && mutableState.value.preparing) {
                    mutableState.value = mutableState.value.copy(preparing = false)
                }
            }
        }

        override fun onDone(utteranceId: String) {
            main.post {
                if (sessionOf(utteranceId) == session && utteranceId.endsWith(":last")) {
                    DiagLog.info(DiagTopic.App, "朗读：读完了")
                    app?.let(::stop)
                }
            }
        }

        @Deprecated("系统还会调旧的这个", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) {
            main.post {
                if (sessionOf(utteranceId) == session)
                    app?.let { fail(it, Strings.voiceTtsError(errorCode.toString())) }
            }
        }
    }

    private fun sessionOf(utteranceId: String): Long? =
        utteranceId.substringBefore(':').toLongOrNull()

    private fun fail(context: Context, message: String) {
        DiagLog.warn(DiagTopic.App, "朗读：$message")
        stop(context)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun release(context: Context) {
        focus?.let {
            context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
        }
        focus = null
        if (noisyRegistered) runCatching { context.unregisterReceiver(noisy) }
        noisyRegistered = false
        ReadAloudService.stop(context)
    }
}
