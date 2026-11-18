package com.dzshzx.zeta.phone

import android.content.AttributionSource
import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.Os
import android.view.Surface
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 后台屏幕的 root 辅助进程。不在 Zeta 的进程里运行：[VirtualScreen] 经 su 用 app_process 启动它，
 * CLASSPATH 指向 Zeta 自己的 APK。做法照 Eta 的 VirtualDisplayCommandMain：
 *
 * 1. 以 root 身份经 Zeta 的 [VdSurfaceProvider] 取回 Zeta 进程里 ImageReader 的 Surface（凭一次性 token）；
 * 2. 降到 system 身份，建一块受信任的虚拟屏，画面直接进那个 Surface：
 *    有自己的焦点、不抢主屏焦点、锁屏时照常可用、不弹输入法（也不退到主屏弹）；
 * 3. 打出 `VD_READY:<displayId>`，然后一直持有它，直到标准输入关闭或收到 `release`。
 *
 * 其间标准输入可以发 `sleep` / `wake`：摘下 / 装回 Surface。摘下后虚拟屏的状态变成 OFF，窗口管理给它加上
 * “Display-off”睡眠令牌，屏上的应用像熄屏一样停到 STOPPED，不再绘制；任务和页面都留在这块屏上。
 * 装回去就恢复 RESUMED，几十毫秒出第一帧。回 `VD_ASLEEP` / `VD_AWAKE`，失败回 `VD_ERR:<原因>`。
 *
 * Zeta 进程没了，管道随之断开，虚拟屏也就释放了；屏上的应用界面随屏销毁，不会被系统挪到主屏前台去打扰用户。
 *
 * 参数：authority token 宽 高 密度
 */
object VdHelper {
    const val READY = "VD_READY:"
    const val ERROR = "VD_ERR:"
    const val ASLEEP = "VD_ASLEEP"
    const val AWAKE = "VD_AWAKE"
    const val NAME = "zeta-background"

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_*。和 Eta 的 TRUSTED_INTERACTIVE_FLAGS 相同，另加 DESTROY_CONTENT_ON_REMOVAL：
    // 不加的话，释放时屏上的应用会被系统挪到主屏最上面
    private const val FLAGS = (1 shl 0) or // PUBLIC
        (1 shl 3) or // OWN_CONTENT_ONLY
        (1 shl 6) or // SUPPORTS_TOUCH
        (1 shl 8) or // DESTROY_CONTENT_ON_REMOVAL
        (1 shl 10) or // TRUSTED
        (1 shl 11) or // OWN_DISPLAY_GROUP
        (1 shl 12) or // ALWAYS_UNLOCKED
        (1 shl 13) or // TOUCH_FEEDBACK_DISABLED
        (1 shl 14) or // OWN_FOCUS
        (1 shl 16) // STEAL_TOP_FOCUS_DISABLED

    // Display.FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_STEAL_TOP_FOCUS_DISABLED：少一个就不能保证不打扰主屏
    private const val REQUIRED_DISPLAY_FLAGS = (1 shl 7) or (1 shl 11) or (1 shl 12)

    private const val IME_POLICY_HIDE = 2

    // 静态强引用：防止等待期间被回收而提前销毁虚拟屏；Surface 休眠时也留着，唤醒时装回去
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var surface: Surface? = null

    // prepareMainLooper、setuid 在应用里不该用，但这里正是在应用之外以 root 运行
    @Suppress("DEPRECATION")
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            if (args.size < 5) fail("usage: authority token width height dpi")
            val uid = Process.myUid()
            if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) fail("needs root, uid=$uid")
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val width = args[2].toInt().coerceIn(320, 4096)
            val height = args[3].toInt().coerceIn(320, 4096)
            val dpi = args[4].toInt().coerceIn(120, 960)

            val target = acquireSurface(args[0], args[1]) ?: fail("surface unavailable")
            surface = target
            // system 身份才过得了 ADD_TRUSTED_DISPLAY 的校验
            if (Process.myUid() == Process.ROOT_UID) Os.setuid(Process.SYSTEM_UID)
            val created = create(target, width, height, dpi)
            display = created
            val id = created.display.displayId
            val flags = created.display.flags
            if (id <= 0 || flags and REQUIRED_DISPLAY_FLAGS != REQUIRED_DISPLAY_FLAGS) {
                release()
                fail("display $id flags=0x${Integer.toHexString(flags)} lacks trusted/own-focus/no-steal")
            }
            hideIme(id)?.let { problem ->
                release()
                fail(problem)
            }
            println(READY + id)
            System.out.flush()
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.cause ?: t
            println(ERROR + cause)
            System.out.flush()
            exitProcess(1)
        }

        val main = Handler(Looper.getMainLooper())
        thread(name = "vd-stdin", isDaemon = true) {
            try {
                val input = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
                while (true) {
                    val line = input.readLine() ?: break
                    when (line.trim()) {
                        "release" -> break
                        "sleep" -> main.post { reply(ASLEEP) { setSurface(null) } }
                        // 先在外面取出留着的那份：lambda 里的 surface 会解析成 VirtualDisplay.getSurface()，休眠后是 null
                        "wake" -> main.post {
                            val retained = surface
                            reply(AWAKE) { setSurface(retained ?: fail("no surface")) }
                        }
                    }
                }
            } catch (_: Throwable) {
                // 管道断开按释放处理
            }
            main.post {
                release()
                exitProcess(0)
            }
        }
        Looper.loop()
    }

    private fun fail(message: String): Nothing = throw IllegalStateException(message)

    private fun reply(ok: String, action: VirtualDisplay.() -> Unit) {
        val error = runCatching { (display ?: fail("no display")).action() }.exceptionOrNull()
        println(if (error == null) ok else ERROR + error)
        System.out.flush()
    }

    private fun release() {
        runCatching { display?.release() }
        runCatching { surface?.release() }
        display = null
        surface = null
    }

    /** 以 root 身份连上 Zeta 的 [VdSurfaceProvider]，凭 token 取回 Surface。 */
    private fun acquireSurface(authority: String, token: String): Surface? {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null) ?: return null
        val iam = Class.forName("android.app.IActivityManager")
        val binder: IBinder = Binder()
        val holder = iam.getMethod(
            "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java,
        ).invoke(am, authority, 0, binder, "ZetaBackgroundScreen") ?: return null
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return null
            val call = Class.forName("android.content.IContentProvider").methods.firstOrNull { it.name == "call" && it.parameterCount == 5 }
                ?: return null
            val attribution = AttributionSource.Builder(Process.myUid()).setPackageName("root").build()
            val reply = call.invoke(provider, attribution, authority, VdSurfaceProvider.METHOD, token, null) as? Bundle ?: return null
            reply.classLoader = Surface::class.java.classLoader
            return reply.getParcelable(VdSurfaceProvider.EXTRA_SURFACE, Surface::class.java)
        } finally {
            runCatching {
                iam.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(am, authority, binder, 0)
            }
        }
    }

    private fun create(surface: Surface, width: Int, height: Int, dpi: Int): VirtualDisplay {
        val activityThread = Class.forName("android.app.ActivityThread")
        val thread = activityThread.getMethod("systemMain").invoke(null)
        val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
        val config = VirtualDisplayConfig.Builder(NAME, width, height, dpi).setSurface(surface).setFlags(FLAGS).build()
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val method = globalClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" && it.parameterCount >= 3 &&
                it.parameterTypes[0] == Context::class.java && it.parameterTypes[2] == VirtualDisplayConfig::class.java
        } ?: fail("no DisplayManagerGlobal.createVirtualDisplay(Context, MediaProjection, VirtualDisplayConfig, ...)")
        val arguments = arrayOfNulls<Any?>(method.parameterCount)
        arguments[0] = context
        arguments[2] = config
        return method.invoke(global, *arguments) as? VirtualDisplay ?: fail("createVirtualDisplay returned null")
    }

    /** 这块屏上不弹输入法，也不退到主屏去弹。刚建好的屏在窗口管理那边可能还没就绪，稍等重试几次。返回失败原因。 */
    private fun hideIme(displayId: Int): String? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as? IBinder
            ?: return "no window service"
        val wms = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: return "no window manager"
        val set = wms.javaClass.methods.firstOrNull { it.name == "setDisplayImePolicy" && it.parameterCount == 2 } ?: return "no setDisplayImePolicy"
        val get = wms.javaClass.methods.firstOrNull { it.name == "getDisplayImePolicy" && it.parameterCount == 1 } ?: return "no getDisplayImePolicy"
        var actual: Any? = null
        for (delay in longArrayOf(0, 15, 30, 50)) {
            if (delay > 0) Thread.sleep(delay)
            set.invoke(wms, displayId, IME_POLICY_HIDE)
            actual = get.invoke(wms, displayId)
            if (actual == IME_POLICY_HIDE) return null
        }
        return "ime policy=$actual"
    }
}
