package com.dzshzx.zeta.ui.overlay

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 锁屏、息屏时收起主屏上的悬浮层（顶部胶囊、边缘光）。
 *
 * 无障碍浮层的层级在锁屏界面之上，系统不会替它藏起来：不收的话，锁着的手机上能看到对话在做什么、后台屏幕的实时画面，
 * 还能点“补充”给 Claude 发指令、在展开画面上操作后台屏幕，等于绕过了锁屏。息屏（含息屏显示）时也没人看，转圈白耗电。
 * 解锁（USER_PRESENT）或亮屏时锁屏界面不在，就照常显示。
 *
 * 只管 0 号屏：调试时悬浮层在测试屏上，和手机锁没锁无关。无障碍服务连上时 [attach]，断开时 [detach]，都在主线程上。
 */
internal object ScreenLock {
    private val state = MutableStateFlow(false)

    /** 屏幕关着或锁屏界面在。 */
    val locked: StateFlow<Boolean> get() = state

    private var context: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    fun attach(context: Context) {
        detach()
        this.context = context
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 这几条都是系统发的受保护广播，不导出也收得到
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun detach() {
        context?.let { runCatching { it.unregisterReceiver(receiver) } }
        context = null
        state.value = false
    }

    /** [displayId] 上的悬浮层此刻该不该收起。 */
    fun hides(displayId: Int): Boolean = overlayHiddenByLock(displayId, locked = state.value)

    private fun refresh() {
        val context = context ?: return
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive != false
        val keyguard = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        state.value = screenLocked(interactive, keyguard)
    }
}

/** 息屏或锁屏界面在。 */
internal fun screenLocked(interactive: Boolean, keyguardLocked: Boolean): Boolean = !interactive || keyguardLocked

/** 只有真主屏（0 号屏）上的悬浮层跟着锁屏收起。 */
internal fun overlayHiddenByLock(displayId: Int, locked: Boolean): Boolean = locked && displayId == Display.DEFAULT_DISPLAY
