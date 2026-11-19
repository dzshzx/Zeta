package com.dzshzx.zeta.phone

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Intake
import com.dzshzx.zeta.core.IntakeSource
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.offerIntake
import com.dzshzx.zeta.ui.MainActivity
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 截屏发给 Claude：收起通知栏和控制中心，藏起 Zeta 自己的悬浮层，经 root `screencap` 截下主屏，
 * 做成图片附件交给「放进哪个对话」（[offerIntake]），再把 Zeta 打开到那一步。不自动发送，用户补一句话再发。
 *
 * 入口：快速设置磁贴 [CaptureTileService]，和运行状态通知上的「截屏」。
 * 主屏平时是 0 号屏；调试时是设置里的 phoneDisplay（测试用的虚拟屏），screencap 截不到它，改用无障碍截图。
 */
object ScreenCapture {
    private const val TAG = "ZetaCapture"

    /** 收起通知栏、控制中心的动画要放完，不然截进去的是半收的面板。 */
    private const val PANEL_SETTLE_MS = 650L

    private val running = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    /** 截一次。可以在任意线程上调用；上一次还没截完时忽略。 */
    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext as Application
        thread(name = "zeta-capture", isDaemon = true) {
            try {
                val hub = ConversationHub.get(app)
                val display = hub.settings.phoneDisplay
                val shot = capture(display)
                val attachment = shot?.let(Attachments::fromBitmap)
                Log.i(TAG, "display=$display shot=${shot?.width}x${shot?.height} attached=${attachment != null}")
                if (attachment == null) {
                    main.post { Toast.makeText(app, "截屏没有成功", Toast.LENGTH_SHORT).show() }
                    return@thread
                }
                // 先挂上，再打开界面：主线程上这一条排在界面启动之前
                main.post { hub.offerIntake(Intake(IntakeSource.Screenshot, images = listOf(attachment))) }
                open(display)
            } finally {
                running.set(false)
            }
        }
    }

    /** 收起通知栏和控制中心，等动画放完。 */
    private val COLLAPSE_PANELS = "cmd statusbar collapse >/dev/null 2>&1; sleep ${PANEL_SETTLE_MS / 1000.0}"

    /**
     * 先收起面板。0 号屏经 root screencap（收面板和截屏在同一次 root 调用里），截之前藏起 Zeta 的悬浮层；
     * 截不到时（或调试用的测试屏）走无障碍截图，它自己会跳过 Zeta 的悬浮层。
     */
    private fun capture(display: Int): Bitmap? {
        if (display == 0) {
            val service = ZetaAccessibilityService.instance
            val shoot = { rootScreencap(before = COLLAPSE_PANELS) }
            val shot = if (service != null) service.withOverlaysHidden(shoot) else shoot()
            if (shot != null) return shot
        } else {
            runCatching { LinuxEnv.runRoot(COLLAPSE_PANELS, 5_000) }
        }
        return ZetaAccessibilityService.instance?.screenshot(display)
    }

    /**
     * 经 root `screencap -p` 截 0 号屏并解码；[before] 是在同一次 root 调用里先跑的命令。没有 root、没截到时为 null。
     * 手机工具在没有无障碍服务时也用它截主屏。
     */
    internal fun rootScreencap(before: String? = null): Bitmap? {
        val script = (before?.let { "$it; " } ?: "") + "exec screencap -p"
        val process = runCatching {
            ProcessBuilder("su", "-c", script).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        }.getOrNull() ?: return null
        return try {
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor(10, TimeUnit.SECONDS)
            bytes.takeIf { it.isNotEmpty() }?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) {
            Log.w(TAG, "screencap failed", e)
            null
        }
    }

    /**
     * 把 Zeta 打开到「放进哪个对话」那一步：Zeta 的界面还开着就在它那块屏上，否则在主屏上。
     * 经 root 打开：磁贴、通知按钮都在后台，MIUI 的“后台弹出界面”限制会把普通的启动悄悄拦下。
     */
    private fun open(display: Int) {
        val target = MainActivity.liveDisplay ?: display
        val script = "am start --activity-single-top -n \"\$2\"" + (if (target != 0) " --display $target" else "") + " >/dev/null 2>&1"
        val component = AppVariant.current.component(MainActivity::class.java.name)
        runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script, component), 10_000) }
    }
}

/** 快速设置里的「截屏给 Claude」磁贴：点一下截下当前屏幕，放进 Zeta 对话的输入框。 */
class CaptureTileService : TileService() {
    override fun onClick() {
        if (isLocked) unlockAndRun { ScreenCapture.start(this) } else ScreenCapture.start(this)
    }
}
