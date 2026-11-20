package com.dzshzx.zeta.assistant

import android.app.ActivityOptions
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.RoundedCorner
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.IntakeSource
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import com.dzshzx.zeta.core.offerIntake
import com.dzshzx.zeta.ui.MainActivity
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.overlay.EdgeGlow
import com.dzshzx.zeta.phone.OverlayPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 数字助理的浮窗：半透明的 activity，盖在当前应用上，底部一张面板（[AssistantScreen]）。
 * 两个入口：系统助理会话（[ZetaVoiceInteractionSession]，带截图和应用内容）；
 * ACTION_ASSIST（默认助理是按 activity 选的、或别的应用发来，只带前台应用的包名）。
 *
 * 发出去时新开一个对话：截图作图片附件，应用内容整理成精简的文字接在问题后面（[ScreenDigest]），
 * 对话放在后台跑，不打扰 Zeta 主界面正开着的对话；回答显示在面板上。上推把手或点「在 Zeta 中继续」打开 Zeta 接着这个对话。
 * 切走（按主屏、锁屏）就收起；在后台的对话照样跑完，Zeta 照常提醒。
 */
class AssistantActivity : ComponentActivity() {
    private lateinit var hub: ConversationHub
    private lateinit var chat: AssistantChat
    private var panelVisible by mutableStateOf(true)
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        hub = ConversationHub.get(application)
        hub.onUiStarted()
        showing = true
        chat = AssistantChat(hub, captureFrom(intent))
        // ASSIST 带着问题来的（快捷方式之类，SearchManager.QUERY）：先填进输入框，由用户确认再发
        if (savedInstanceState == null && intent?.action == Intent.ACTION_ASSIST) {
            intent.getStringExtra(SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }?.let { chat.draft.setTextAndPlaceCursorAtEnd(it.take(2000)) }
        }
        val onMainScreen = (display?.displayId ?: 0) == 0
        val corner = runCatching { display?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() }.getOrNull() ?: 0f
        setContent {
            val dark = hub.settings.darkTheme ?: isSystemInDarkTheme()
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }
            ZetaTheme(darkTheme = hub.settings.darkTheme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AssistantScreen(
                        screen = chat.screenChip,
                        reply = chat.reply,
                        draft = chat.draft,
                        suggestions = chat.suggestions,
                        sending = chat.sending,
                        problem = chat.problem,
                        visible = panelVisible,
                        onSend = { chat.send(it, lifecycleScope) },
                        onToggleScreen = chat::toggleScreen,
                        onOpen = ::openInZeta,
                        onDismiss = ::dismiss,
                        autoFocus = onMainScreen,
                    )
                    // Claude 还没开口时屏幕边缘流光，和 Zeta 操作手机时同一种光（照 Eta 的助理光效）
                    EdgeGlow(phase = OverlayPhase.RUNNING, visible = panelVisible && chat.thinking, hidden = false, cornerRadius = corner)
                }
                BackHandler(onBack = ::dismiss)
            }
        }
    }

    /** 浮窗开着时又唤起一次：屏幕就是浮窗自己，保持原样（新带来的采集不要）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CAPTURE)?.let { id -> AssistCaptures.find(id)?.takeIf { it !== chat.capture }?.let(AssistCaptures::release) }
    }

    override fun onStop() {
        super.onStop()
        // 切走了（按主屏、锁屏、打开了别的界面）：浮窗不留着
        if (!isChangingConfigurations && !isFinishing) close()
    }

    override fun onDestroy() {
        // 被系统收掉的（没经过 close）也要放开；经过 close 的已经放开了，这时再收会话可能收掉紧接着的新一次唤起
        if (!closing) release()
        AssistCaptures.release(chat.capture)
        super.onDestroy()
    }

    /** 不再算开着，收起系统那边的会话：下一次唤起是新的一次，重新采屏幕。 */
    private fun release() {
        showing = false
        ZetaVoiceInteractionSession.hideActive()
    }

    private fun dismiss() {
        if (closing) return
        panelVisible = false
        lifecycleScope.launch {
            delay(200)
            close()
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        release()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * 打开 Zeta：发出去了就切到那个对话；还没发就把问题草稿和截图放进 Zeta 的输入栏（照分享进来的做法，先问放进哪个对话）。
     * 开在浮窗所在的那块屏上。
     */
    private fun openInZeta() {
        if (closing) return
        val conversation = chat.conversation
        val intent = if (conversation != null) {
            MainActivity.conversationIntent(this, conversation.openKey)
        } else {
            val shot = chat.capture?.screenshot?.takeIf { chat.includeScreen }
            val text = chat.draft.text.toString().trim().ifEmpty { null }
            if (shot != null || text != null) hub.offerIntake(Intake(IntakeSource.Screenshot, text = text, images = listOfNotNull(shot)))
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val options = ActivityOptions.makeBasic().apply { display?.displayId?.takeIf { it != 0 }?.let { launchDisplayId = it } }
        runCatching { startActivity(intent, options.toBundle()) }
            .onFailure { DiagLog.warn(DiagTopic.App, "数字助理打不开 Zeta：${it.javaClass.simpleName}") }
        close()
    }

    private fun captureFrom(intent: Intent?): AssistCapture? {
        intent ?: return null
        intent.getStringExtra(EXTRA_CAPTURE)?.let { return AssistCaptures.find(it) }
        if (intent.action == Intent.ACTION_ASSIST) {
            DiagLog.info(DiagTopic.App, "数字助理被唤起（ASSIST）")
            return AssistCaptures.fromIntent(this, intent.getStringExtra(Intent.EXTRA_ASSIST_PACKAGE))
        }
        return null
    }

    companion object {
        private const val EXTRA_CAPTURE = "com.dzshzx.zeta.extra.ASSIST_CAPTURE"

        /** 浮窗开着（没销毁）：再唤起时不重新采屏幕。主线程上读写。 */
        @Volatile
        var showing = false
            private set

        /** 系统助理会话用的：打开浮窗，带上这次采集的 id。 */
        internal fun intent(context: Context, captureId: String): Intent = Intent(context, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_CAPTURE, captureId)
    }
}

/** 浮窗这一次的状态和发送：带上的屏幕、草稿、发出去以后的那个对话。只在主线程上用。 */
@Stable
internal class AssistantChat(private val hub: ConversationHub, val capture: AssistCapture?) {
    val draft = TextFieldState()

    /** 用户没把屏幕内容去掉。 */
    var includeScreen by mutableStateOf(capture != null)
        private set
    var conversation by mutableStateOf<Conversation?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    /** 发出去了、Claude 还没开口（也不是在等确认）：屏幕边缘流光。 */
    val thinking: Boolean
        get() = sending || conversation?.let { it.busy && it.pending == null && reply?.text.isNullOrEmpty() } == true

    val screenChip: ScreenChip?
        get() {
            val capture = capture ?: return null
            if (capture.empty) return null
            val app = capture.focusedApp?.let(ScreenDigest::appName) ?: "当前屏幕"
            return ScreenChip(
                app = app,
                thumbnail = capture.screenshot?.preview,
                waiting = capture.waitingAssist || capture.waitingScreenshot,
                removed = !includeScreen,
                textOnly = capture.screenshot == null && !capture.waitingScreenshot,
                sent = sending || conversation != null,
            )
        }

    val suggestions: List<String> get() = AssistantSuggestions.forContext(includeScreen && screenChip != null)

    val reply: ReplyView?
        get() {
            val c = conversation ?: return null
            return AssistantReplies.view(c.items, c.busy, c.pending?.toolName)
        }

    fun toggleScreen() {
        includeScreen = !includeScreen
    }

    /**
     * 第一次：新开一个对话，把问题和屏幕内容发出去，对话放到后台跑。之后：在同一个对话里接着问。
     * 发之前最多等系统把截图和应用内容交齐（[AssistCapture.settle]）。
     */
    fun send(text: String, scope: CoroutineScope) {
        val question = text.trim()
        if (question.isEmpty() || sending) return
        problem = null
        conversation?.let {
            it.send(question)
            return
        }
        val config = hub.settings.env ?: run {
            problem = "Zeta 还没有配置 Linux 环境，先打开 Zeta 设置好"
            return
        }
        sending = true
        scope.launch {
            val capture = capture?.takeIf { includeScreen }
            capture?.settle()
            val shot = capture?.screenshot
            val block = capture?.let { ScreenDigest.contextBlock(it.apps, shot != null) }
            val started = Conversation(hub, config.defaultCwd, sessionId = null, title = "", historyFile = null)
            // 设置 › 能力 › 数字助理 里单独定的模型和思考强度；没定（null）时跟随 Claude Code 的默认
            started.preset(model = hub.settings.assistantModel, effort = hub.settings.assistantEffort, hint = HINT)
            started.send(ScreenDigest.message(question, block), listOfNotNull(shot))
            sending = false
            if (!started.busy) {
                problem = started.items.filterIsInstance<NoticeItem>().lastOrNull { it.error }?.text ?: "没有发出去"
                return@launch
            }
            hub.park(started)
            hub.onActivityChanged()
            conversation = started
            DiagLog.info(DiagTopic.App, "数字助理开了新对话" + if (capture != null) "（带屏幕内容${if (shot != null) "和截图" else ""}）" else "")
        }
    }

    companion object {
        /** 追加进系统提示：这是从数字助理浮窗问的，回答先显示在一块小面板上。 */
        const val HINT = "The user invoked Zeta as the phone's digital assistant (for example by long-pressing the power button) " +
            "while another app was on screen. When the message has a <screen_context> block, it describes that screen " +
            "(app name, package, visible text) and the attached image is a screenshot of it. Answer about that screen directly. " +
            "Your reply first appears in a small panel over the app, so lead with the answer and keep it short unless asked for more."
    }
}
