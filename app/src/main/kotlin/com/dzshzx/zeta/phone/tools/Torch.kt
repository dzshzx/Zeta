package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 手电筒：用本进程的 CameraManager（setTorchMode 不要相机权限），状态取自系统的手电筒回调。
 * 灯归打开它的进程所有：Zeta 的进程没了灯也会灭，所以这个对象跟着进程一直留着。
 * 系统没有对应的 shell 命令，MIUI 的控制中心也是这样开关的。
 */
internal object Torch {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val states = HashMap<String, Boolean?>()
    private var manager: CameraManager? = null
    private var cameraId: String? = null
    private var started = false

    private fun start(context: Context): Unit = lock.withLock {
        if (started) return
        started = true
        val cameras = context.applicationContext.getSystemService(CameraManager::class.java) ?: return
        manager = cameras
        val withFlash = runCatching {
            cameras.cameraIdList.filter { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrDefault(emptyList())
        cameraId = withFlash.firstOrNull {
            runCatching { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.getOrNull() == CameraCharacteristics.LENS_FACING_BACK
        } ?: withFlash.firstOrNull()
        if (cameraId == null) return
        val thread = HandlerThread("zeta-torch").apply { start() }
        cameras.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) = publish(id, enabled)
            override fun onTorchModeUnavailable(id: String) = publish(id, null)
        }, Handler(thread.looper))
    }

    private fun publish(id: String, value: Boolean?) = lock.withLock {
        states[id] = value
        changed.signalAll()
    }

    /** 等到有状态（注册回调后系统马上报一次），或等到状态变成 [expected]。null 表示没有闪光灯或正被相机占用。 */
    private fun await(expected: Boolean? = null, timeoutMs: Long = 1500): Boolean? = lock.withLock {
        val id = cameraId ?: return null
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (id !in states || (expected != null && states[id] != expected)) {
            val left = end - SystemClock.elapsedRealtime()
            if (left <= 0) break
            changed.await(left, TimeUnit.MILLISECONDS)
        }
        states[id]
    }

    /** `on`、`off`，闪光灯不可用时说明原因。 */
    fun read(context: Context): String {
        start(context)
        if (cameraId == null) return "unavailable (no flash unit)"
        return when (await()) {
            true -> "on"
            false -> "off"
            null -> "unavailable (camera in use)"
        }
    }

    fun set(context: Context, on: Boolean): String {
        start(context)
        val id = cameraId ?: throw ToolError("这台手机没有可用的闪光灯")
        if (await() == on) return if (on) "on" else "off"
        try {
            manager!!.setTorchMode(id, on)
        } catch (error: Exception) {
            throw ToolError("系统没让开关手电筒（相机可能正被占用）：${error.message ?: error.javaClass.simpleName}")
        }
        return when (await(on)) {
            true -> "on"
            false -> "off"
            null -> "unavailable (camera in use)"
        }
    }
}
