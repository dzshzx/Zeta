package com.dzshzx.zeta.assistant

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic
import java.lang.ref.WeakReference

/**
 * Zeta 作为系统的数字助理（设置里把默认数字助理选成 Zeta 后由系统绑定）。本身不做事： 系统要显示助理时（长按电源键经 Zeta 的 LSPosed 模块、或别的系统入口）新建
 * [ZetaVoiceInteractionSession]。 元数据在 res/xml/zeta_voice_interaction.xml；识别服务见
 * [ZetaRecognitionService]。
 */
class ZetaVoiceInteractionService : VoiceInteractionService() {
    /** 系统连上了当前的数字助理服务：选上 Zeta 之后、开机后都会来一次。 */
    override fun onReady() {
        super.onReady()
        DiagLog.info(DiagTopic.App, "数字助理服务就绪（Zeta 是默认数字助理）")
        val context = applicationContext
        Thread { AssistantRole.claimRecognizer(context) }.start()
    }
}

class ZetaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        ZetaVoiceInteractionSession(this)
}

/**
 * 一次助理会话：收系统给的截图和应用内容（存进 [AssistCapture]），再打开 [AssistantActivity] 显示浮窗。
 * 会话自己不画界面（setUiEnabled(false)），浮窗是 Zeta 自己的半透明 activity，输入法、动画都照常。
 *
 * 浮窗还开着时又唤起一次（再长按一下电源键）：这时的屏幕就是浮窗自己，不再采，浮窗保持原样。
 */
internal class ZetaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private var capture: AssistCapture? = null

    /** 这一次是在浮窗开着时唤起的，不采不开。 */
    private var coalesced = false

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        active = WeakReference(this)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        coalesced = AssistantActivity.showing
        if (coalesced) return
        val foreground =
            args
                ?.getParcelableArrayList(KEY_FOREGROUND_ACTIVITIES, ComponentName::class.java)
                .orEmpty()
                .map { it.packageName }
        capture = AssistCaptures.begin(context, showFlags, foreground)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (coalesced) return
        val id = capture?.id ?: return
        val source = args?.getString(SOURCE_EXTRA)
        DiagLog.info(DiagTopic.App, "数字助理被唤起" + (source?.let { "（$it）" } ?: ""))
        val intent = AssistantActivity.intent(context, id)
        runCatching { startAssistantActivity(intent) }
            .onFailure {
                DiagLog.warn(
                    DiagTopic.App,
                    "数字助理打不开浮窗：${it.javaClass.simpleName} ${it.message.orEmpty()}",
                )
                runCatching { context.startActivity(intent) }
            }
    }

    override fun onHandleAssist(state: AssistState) {
        if (coalesced) return
        capture?.acceptAssist(context, state)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        val target = capture
        if (coalesced || target == null) {
            screenshot?.recycle()
            return
        }
        target.acceptScreenshot(screenshot)
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        /** 唤起来源：电源键模块（xposed/PowerKeyHook.kt）请系统显示会话时放进 args。 */
        const val SOURCE_EXTRA = "com.dzshzx.zeta.extra.ASSIST_SOURCE"

        private var active: WeakReference<ZetaVoiceInteractionSession>? = null

        /** 浮窗关了：收起系统那边的会话，下次唤起是一次新的。在主线程上调用。 */
        fun hideActive() {
            active?.get()?.let { runCatching { it.hide() } }
        }
    }
}
