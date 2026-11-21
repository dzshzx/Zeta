package com.dzshzx.zeta.core

import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.phone.TouchMark
import com.dzshzx.zeta.phone.VirtualScreen
import kotlinx.coroutines.flow.SharedFlow

/**
 * 顶部胶囊眼里用手机工具 [phone] 的一方（对话 [ConversationFocus] 或外部客户端 [BridgeFocus]）里和手机工具直接对应的部分：
 * 后台屏幕、触摸、暂停与接管、在展开画面上动手，都照原样交给 [phone]。
 */
internal abstract class PhoneFocus(val phone: PhoneTools) : OverlayFocus {
    override val screen: VirtualScreen?
        get() = phone.agentDisplay

    override val appName: String?
        get() = phone.agentDisplayApp

    override val actingOnMain: Boolean
        get() = phone.actingOnMain

    override val userControlling: Boolean
        get() = phone.userControlling

    override val touches: SharedFlow<TouchMark>
        get() = phone.touches

    override fun takeOver() = phone.takeOverByUser()

    override fun resumeAuto() = phone.resumeByUser()

    override fun pause() = phone.pauseByUser()

    override fun resume() = phone.resumeByUser()

    override fun userTap(x: Float, y: Float, longPress: Boolean) = phone.userTap(x, y, longPress)

    override fun userSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int,
    ) = phone.userSwipe(startX, startY, endX, endY, durationMs)

    override fun handoffToMain() = phone.handoffToMainByUser()
}
