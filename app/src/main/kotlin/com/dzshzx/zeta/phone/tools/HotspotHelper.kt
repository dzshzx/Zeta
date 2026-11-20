package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.net.TetheringManager
import android.os.Looper
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * 开关个人热点的 root 辅助进程。没有 shell 命令能开「带网络共享」的热点（`cmd wifi start-softap` 只开 AP，不共享网络），
 * 要用 TetheringManager，而它要 TETHER_PRIVILEGED。所以像 [com.dzshzx.zeta.phone.VdHelper] 那样经 su 用 app_process 起本类，
 * CLASSPATH 指向 Zeta 自己的 APK，以 root 身份拿系统的 Context 调用。做法照 Eta 的 PhoneCommandMain 与 NativeSystemOperations。
 *
 * 参数：`on` / `off`。用现有的热点名称和密码，不改配置。输出一行 `ZETA_HOTSPOT:ok` 或 `ZETA_HOTSPOT:error <原因>`。
 */
object HotspotHelper {
    const val MARKER = "ZETA_HOTSPOT:"

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        val result = try {
            if (Process.myUid() != 0) throw IllegalStateException("needs root")
            val on = when (args.getOrNull(0)) {
                "on" -> true
                "off" -> false
                "status" -> null
                else -> throw IllegalArgumentException("usage: on|off|status")
            }
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
            if (on == null) status(context) else set(context, on)
        } catch (error: Throwable) {
            "error " + (error.message ?: error.javaClass.simpleName)
        }
        println(MARKER + result)
        exitProcess(0)
    }

    /** 只读：注册回调后系统马上报一次当前共享中的网口。排查用（`ok on` / `ok off`）。 */
    private fun status(context: Context): String {
        val manager = context.getSystemService(TetheringManager::class.java) ?: return "error tethering service unavailable"
        val state = AtomicReference<Boolean?>(null)
        val first = CountDownLatch(1)
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<android.net.TetheringInterface>) {
                state.set(interfaces.any { it.type == TetheringManager.TETHERING_WIFI })
                first.countDown()
            }
        }
        manager.registerTetheringEventCallback(Executor { it.run() }, callback)
        try {
            if (!first.await(3, TimeUnit.SECONDS)) return "error no answer from the tethering service"
        } finally {
            manager.unregisterTetheringEventCallback(callback)
        }
        return if (state.get() == true) "ok on" else "ok off"
    }

    private fun set(context: Context, on: Boolean): String {
        val manager = context.getSystemService(TetheringManager::class.java) ?: return "error tethering service unavailable"
        val direct = Executor { it.run() }
        val request = TetheringManager.TetheringRequest.Builder(TetheringManager.TETHERING_WIFI).build()
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        if (on) {
            manager.startTethering(request, direct, object : TetheringManager.StartTetheringCallback {
                override fun onTetheringStarted() = done.countDown()
                override fun onTetheringFailed(error: Int) {
                    failure.set("start failed, error $error")
                    done.countDown()
                }
            })
        } else {
            // 别的应用（控制中心）开的热点，按请求停不一定对得上；先用按类型停的老接口，没有再按请求停
            val legacy = runCatching {
                TetheringManager::class.java.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, TetheringManager.TETHERING_WIFI)
            }
            if (legacy.isSuccess) done.countDown()
            else manager.stopTethering(request, direct, object : TetheringManager.StopTetheringCallback {
                override fun onStopTetheringSucceeded() = done.countDown()
                override fun onStopTetheringFailed(error: Int) {
                    failure.set("stop failed, error $error")
                    done.countDown()
                }
            })
        }
        if (!done.await(8, TimeUnit.SECONDS)) return "error no answer from the tethering service"
        return failure.get()?.let { "error $it" } ?: "ok"
    }

    /** Zeta 进程里调用：经 su 起辅助进程，返回问题，成功返回 null。 */
    fun run(context: Context, on: Boolean): String? {
        val result = rootShell(appProcessCommand(context.applicationInfo.sourceDir, HotspotHelper::class.java.name, if (on) "on" else "off"), 20_000)
        val line = result.stdout.lineSequence().firstOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)
            ?: return "热点辅助进程没有回应：" + (result.stderr.ifBlank { result.stdout }.trim().takeLast(300).ifBlank { "退出码 ${result.exitCode}" })
        return if (line == "ok") null else line.removePrefix("error ")
    }
}
