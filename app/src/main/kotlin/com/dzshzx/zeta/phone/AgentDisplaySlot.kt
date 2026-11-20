package com.dzshzx.zeta.phone

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * 一个对话的后台屏幕（agent display：Claude 操作手机用的那块虚拟显示屏，[VirtualScreen]）的一生，[PhoneTools] 持有：要用时建（[ensure]），一轮结束后闲置 [IDLE_SLEEP_MS] 睡下，
 * 对话的进程结束时也睡下（[detach]），要用时唤醒（[wakeIfAsleep]）；用户在界面上关掉、全应用屏幕太多被腾掉（[releaseAsync]）、
 * 对话没了（[destroy]）或辅助进程意外退出时释放。屏上的应用不抢音频焦点（[audio]）。
 *
 * 同步照 PhoneTools 的说明：建、睡、醒、释放都持着 PhoneTools 的屏幕锁 [lock]（标着“持锁调用”的由调用方拿锁，
 * 自己起线程的这里拿）；界面看的 [agentDisplay]、[agentDisplayApp] 只在主线程上改，工具线程看 [current]。
 */
internal class AgentDisplaySlot(
    private val context: Context,
    private val lock: Any,
    private val gate: ControlGate,
    /** 要新建后台屏幕之前调用（工具线程上，持着锁）：全应用的后台屏幕有上限，满了由登记处先腾出一块。 */
    private val beforeCreate: () -> Unit,
    /** 后台屏幕没了（在主线程上调用）：登记处据此丢掉没有屏、也没有对话在用的那些。 */
    private val onScreenGone: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** 这个对话的后台屏幕（界面用，只在主线程上改）。 */
    var agentDisplay by mutableStateOf<VirtualScreen?>(null)
        private set

    /** 后台屏幕上最近打开的应用名（界面用）。 */
    var agentDisplayApp by mutableStateOf<String?>(null)
        private set

    /** 工具线程看到的后台屏幕（醒着或睡着；释放后是 null）。 */
    @Volatile var current: VirtualScreen? = null
        private set

    /** 对话是否正在进行（闲置休眠、腾地方时要避开）。由正在用它的对话经 PhoneTools.attach 给出。 */
    @Volatile var busy: () -> Boolean = { false }

    /** 后台屏幕有了、没了或换了应用（在主线程上调用）。 */
    @Volatile var onChanged: () -> Unit = {}

    /** 最近一次被 Claude Code 调用的时间：闲置休眠看它，腾地方时先收最久没用的。 */
    @Volatile var lastUse = 0L

    /** [destroy] 过了：不再建后台屏幕，免得还没结束的工具调用给已经没人要的会话建出一块没人管的屏。 */
    @Volatile private var destroyed = false

    /** 这次调用前刚把休眠的后台屏幕唤醒：观察结果里提一句（持锁读写，调用方读完清掉）。 */
    var justWoke = false

    /** 后台屏幕上的应用不抢音频焦点（全进程一份）。 */
    val audio by lazy { AudioFocusGuard.of(context) }

    /** 有后台屏幕（醒着或睡着）。 */
    val alive: VirtualScreen? get() = current?.takeIf { it.alive }

    /** 后台屏幕在休眠就唤醒它（持锁调用）。返回问题，没有问题返回 null；[gone] 是屏已经没了时的说明。 */
    fun wakeIfAsleep(screen: VirtualScreen, gone: () -> String?): String? {
        if (!screen.asleep.value) return null
        if (!screen.wake()) return if (screen.alive) "后台屏幕在休眠，没能唤醒。可以再试一次，或者用 open_app 重新打开应用" else gone()
        // 第一帧出来了，再稍等让无障碍那边的窗口列表也跟上
        Thread.sleep(200)
        justWoke = true
        return null
    }

    /** 这个对话的后台屏幕，没有就建一块（和主屏一样大）；在休眠就唤醒（持锁调用）。失败时抛出异常，[gone] 同 [wakeIfAsleep]。 */
    fun ensure(gone: () -> String?): VirtualScreen {
        alive?.let { existing ->
            wakeIfAsleep(existing, gone)?.let { throw IllegalStateException(it) }
            return existing
        }
        check(!destroyed) { "这个会话已经结束" }
        beforeCreate()
        audio.restoreLeftovers()
        val metrics = DisplayMetrics()
        val physical = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("读不到主屏的尺寸")
        @Suppress("DEPRECATION") physical.getRealMetrics(metrics)
        val created = VirtualScreen.create(context, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        created.onGone = { onGone(created) }
        current = created
        main.post {
            agentDisplay = created
            agentDisplayApp = null
            onChanged()
        }
        return created
    }

    /** 在 [screen] 上打开了应用 [label]（界面上显示）。 */
    fun opened(screen: VirtualScreen, label: String) = main.post {
        if (agentDisplay === screen) agentDisplayApp = label
        onChanged()
    }

    /** 屏上的应用变了（比如移走了一个）。 */
    fun changed() = main.post { onChanged() }

    /** 辅助进程意外退出：后台屏幕已经没了。 */
    private fun onGone(screen: VirtualScreen) {
        if (current !== screen) return
        current = null
        audio.screenGone(screen.displayId)
        cleared(screen)
    }

    /** 释放后台屏幕（屏上的应用随之关闭；持锁调用）。当前屏幕若是它，PhoneTools 保持指着它，好让模型下次调用时知道它没了。 */
    fun release() {
        val screen = current ?: return
        current = null
        screen.release()
        audio.screenGone(screen.displayId)
        cleared(screen)
    }

    private fun cleared(screen: VirtualScreen) = main.post {
        if (agentDisplay === screen) {
            agentDisplay = null
            agentDisplayApp = null
            // 屏没了接管不下去：改为暂停，等用户在胶囊上点继续
            if (gate.userControlling) gate.set(pause = true, control = false)
        }
        onChanged()
        onScreenGone()
    }

    /**
     * 另起线程拿锁释放：界面上的“关闭”，或全应用的后台屏幕满了、登记处挑中这块腾出来。
     * 和 [release] 一样，当前屏幕仍指着它，模型下次调用时会知道它没了。
     */
    fun releaseAsync() {
        thread(name = "zeta-phone-release", isDaemon = true) { synchronized(lock) { release() } }
    }

    /** 一轮结束：过一阵还没再用就让后台屏幕休眠，省电；应用和页面都留着，下次用时唤醒。 */
    fun onTurnEnded() {
        if (current == null) return
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_SLEEP_MS)
    }

    private val idleCheck = Runnable {
        if (current != null && !busy() && idle()) {
            thread(name = "zeta-phone-idle", isDaemon = true) {
                // 等锁的时候可能又用上了
                synchronized(lock) { if (idle()) current?.sleep() }
            }
        }
    }

    private fun idle() = System.currentTimeMillis() - lastUse >= IDLE_SLEEP_MS - 1000

    /** 对话的进程结束了：屏和上面的应用留着，只让它睡下（马上就睡，不再等闲置检查）。[also] 在同一把锁里接着做。 */
    fun detach(also: () -> Unit) {
        main.removeCallbacks(idleCheck)
        thread(name = "zeta-phone-detach", isDaemon = true) {
            synchronized(lock) {
                if (destroyed) return@thread
                current?.sleep()
                also()
            }
        }
    }

    /** 彻底释放：收掉后台屏幕，之后也不再建。[also] 在同一把锁里接着做。 */
    fun destroy(also: () -> Unit) {
        destroyed = true
        main.removeCallbacks(idleCheck)
        thread(name = "zeta-phone-stop", isDaemon = true) {
            synchronized(lock) {
                release()
                also()
            }
        }
    }

    private companion object {
        const val IDLE_SLEEP_MS = 10 * 60_000L
    }
}
