package com.dzshzx.zeta.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次唤起采到的屏幕。系统分几次异步交来（每个前台 activity 一份应用内容，再加一张截图），到了就存；
 * 浮窗发送时最多再等 [WAIT_MS]，没到的就不带了。状态是 Compose 的，只在主线程上改。
 * ASSIST intent 唤起时没有截图和界面文字，只有包名（[AssistCaptures.fromIntent]）。
 */
@Stable
class AssistCapture internal constructor(
    val id: String,
    apps: List<ScreenApp>,
    /** 还在等应用内容（系统说了要给的份数还没到齐）。 */
    waitingAssist: Boolean,
    waitingScreenshot: Boolean,
) {
    var apps by mutableStateOf(apps)
        private set
    var screenshot by mutableStateOf<Attachment?>(null)
        private set
    var waitingAssist by mutableStateOf(waitingAssist)
        private set
    var waitingScreenshot by mutableStateOf(waitingScreenshot)
        private set

    /** 系统说一共有几份应用内容、已经收了哪几份。 */
    private var expected = 1
    private val received = HashSet<Int>()
    private var reading = 0
    private var closed = false

    /** 浮窗上显示的应用名：有焦点的那个。 */
    val focusedApp: ScreenApp? get() = apps.firstOrNull { it.focused } ?: apps.firstOrNull()

    /** 什么都没采到（也不再等了）。 */
    val empty: Boolean get() = apps.isEmpty() && screenshot == null && !waitingAssist && !waitingScreenshot

    /** 等系统把东西交齐，最多等到唤起后 [WAIT_MS]。在主线程上调用。 */
    suspend fun settle(timeoutMs: Long = WAIT_MS) {
        withTimeoutOrNull(timeoutMs) { snapshotFlow { waitingAssist || waitingScreenshot }.first { !it } }
        waitingAssist = false
        waitingScreenshot = false
    }

    /** 收一份应用内容（VoiceInteractionSession.onHandleAssist，主线程）。读界面树放到后台线程。 */
    internal fun acceptAssist(context: Context, state: VoiceInteractionSession.AssistState) {
        if (closed || state.index in received) return
        received += state.index
        expected = maxOf(expected, state.count.coerceIn(1, MAX_ACTIVITIES))
        if (received.size > MAX_ACTIVITIES && !state.isFocused) return finishAssist()
        reading++
        val app = context.applicationContext
        thread(name = "zeta-assist-read", isDaemon = true) {
            val read = runCatching { AssistReader.read(app, state) }.onFailure {
                DiagLog.warn(DiagTopic.App, "读应用内容出错：${it.javaClass.simpleName}")
            }.getOrNull()
            main.post {
                reading--
                if (!closed && read != null) apps = merge(apps, read)
                finishAssist()
            }
        }
    }

    private fun finishAssist() {
        if (reading == 0 && received.size >= expected) waitingAssist = false
    }

    /** 收截图（VoiceInteractionSession.onHandleScreenshot，主线程）。缩小、编码放到后台线程。 */
    internal fun acceptScreenshot(bitmap: Bitmap?) {
        if (closed || bitmap == null) {
            bitmap?.recycle()
            waitingScreenshot = false
            return
        }
        thread(name = "zeta-assist-shot", isDaemon = true) {
            val attachment = runCatching {
                // 系统给的常是硬件位图，先拷成软件位图才能缩放、编码
                val soft = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                Attachments.fromBitmap(soft).also { if (soft !== bitmap) soft.recycle() }
            }.getOrNull()
            bitmap.recycle()
            main.post {
                if (!closed) screenshot = attachment
                waitingScreenshot = false
            }
        }
    }

    /** 用户把屏幕内容去掉了，或者浮窗关了：迟到的不再收。 */
    internal fun close() {
        closed = true
        waitingAssist = false
        waitingScreenshot = false
    }

    companion object {
        /** 唤起后最多等系统多久交齐应用内容和截图。 */
        const val WAIT_MS = 2_000L
        private const val MAX_ACTIVITIES = 4
        private val main = Handler(Looper.getMainLooper())

        /** 同一个包的读到了内容就替换前台清单里只有包名的那一项，否则加在后面。 */
        internal fun merge(apps: List<ScreenApp>, read: ScreenApp): List<ScreenApp> {
            val index = apps.indexOfFirst { it.packageName == read.packageName && it.lines.isEmpty() && it.webUri == null }
            return if (index >= 0) apps.toMutableList().also { it[index] = read } else apps + read
        }
    }
}

/** 最多留着当前这一次唤起，浮窗按 id 取。都在主线程上用。 */
object AssistCaptures {
    private var current: AssistCapture? = null

    /** 系统会话开始（onPrepareShow）：[foreground] 是系统给的前台 activity 的包名，先占上位置，读到内容再补。 */
    internal fun begin(context: Context, showFlags: Int, foreground: List<String>): AssistCapture {
        current?.close()
        val apps = foreground.distinct().mapIndexed { i, pkg -> ScreenApp(pkg, appLabel(context, pkg), focused = i == 0) }
        return AssistCapture(
            id = UUID.randomUUID().toString(),
            apps = apps,
            waitingAssist = showFlags and VoiceInteractionSession.SHOW_WITH_ASSIST != 0,
            waitingScreenshot = showFlags and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0,
        ).also { current = it }
    }

    /** ASSIST intent 唤起：系统只给前台应用的包名（EXTRA_ASSIST_PACKAGE），没有截图和界面文字。 */
    internal fun fromIntent(context: Context, packageName: String?): AssistCapture {
        current?.close()
        val apps = listOfNotNull(packageName?.takeIf { it.isNotBlank() }?.let { ScreenApp(it, appLabel(context, it)) })
        return AssistCapture(UUID.randomUUID().toString(), apps, waitingAssist = false, waitingScreenshot = false).also { current = it }
    }

    fun find(id: String?): AssistCapture? = current?.takeIf { it.id == id }

    fun release(capture: AssistCapture?) {
        capture?.close()
        if (current === capture) current = null
    }

    internal fun appLabel(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrNull()
}

/**
 * 把系统给的一份应用内容（AssistStructure、AssistContent）读成 [ScreenApp]。只读系统交来的数据，
 * 不执行应用附带的 Intent；密码框、应用声明了不给助理看的部分不读。
 */
internal object AssistReader {
    fun read(context: Context, state: VoiceInteractionSession.AssistState): ScreenApp? {
        val structure = state.assistStructure
        val pkg = structure?.activityComponent?.packageName
        val content = state.assistContent
        val roots = structure?.let { s -> (0 until s.windowNodeCount).map { ViewNodeAdapter(s.getWindowNodeAt(it).rootViewNode) } }.orEmpty()
        val (lines, truncated) = ScreenDigest.collect(roots)
        if (pkg == null && lines.isEmpty() && content?.webUri == null) return null
        return ScreenApp(
            packageName = pkg,
            label = pkg?.let { AssistCaptures.appLabel(context, it) },
            webUri = content?.webUri?.toString(),
            focused = state.isFocused,
            lines = lines,
            truncated = truncated,
        )
    }

    private class ViewNodeAdapter(private val node: AssistStructure.ViewNode) : ScreenNode {
        override val skipped: Boolean
            get() = node.visibility != View.VISIBLE || node.isAssistBlocked || isPassword(node.inputType)
        override val text: String? get() = node.text?.toString()
        override val description: String? get() = node.contentDescription?.toString()
        override val childCount: Int get() = node.childCount
        override fun child(index: Int): ScreenNode = ViewNodeAdapter(node.getChildAt(index))
    }

    fun isPassword(inputType: Int): Boolean {
        val type = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (type == InputType.TYPE_CLASS_TEXT && variation in PASSWORDS) ||
            (type == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private val PASSWORDS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )
}
