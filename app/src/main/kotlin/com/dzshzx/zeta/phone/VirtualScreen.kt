package com.dzshzx.zeta.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.phone.tools.appProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * 一块后台屏幕：用户看不见的受信任虚拟屏，Claude 在上面开应用、点按，不占用主屏。
 *
 * 画面进本进程的 [ImageReader]；虚拟屏本身由 root 辅助进程 [VdHelper] 持有，它的标准输入连着这里，
 * Zeta 退出或调用 [release] 时管道关闭，虚拟屏随之释放。
 *
 * 平时只留着最近一帧（缓冲不取走的话合成会停），截图时才转成 Bitmap；
 * 界面要看实时画面时用 [watch] 登记，按要求的间隔把画面放进 [frames]。
 *
 * 闲置时可以 [sleep]：虚拟屏关掉，屏上的应用像熄屏一样停下，任务和页面都留着；要用之前 [wake]。
 */
class VirtualScreen private constructor(
    val displayId: Int,
    val width: Int,
    val height: Int,
    private val reader: ImageReader,
    private val worker: HandlerThread,
    private val handler: Handler,
    private val process: Process,
) {
    /** 给界面画的一帧。[bitmap] 可能比画面宽（行尾有对齐用的空白），只画左边 [width] 那一截。 */
    class Frame(val bitmap: Bitmap, val width: Int, val height: Int, val sequence: Long)

    @Volatile var alive = true
        private set

    /** 辅助进程意外退出（或被用户在别处关掉）时调用；[release] 引起的不算。 */
    @Volatile var onGone: (() -> Unit)? = null

    private val lock = Any()
    private var latest: Image? = null
    private val _frames = MutableStateFlow<Frame?>(null)
    val frames: StateFlow<Frame?> = _frames

    private val _asleep = MutableStateFlow(false)

    /** 在休眠：屏上的应用停着，[frames] 里是休眠前那一帧的缩小版。 */
    val asleep: StateFlow<Boolean> = _asleep

    /** 休眠前最后一帧的缩小版，给界面画；醒来出新帧后丢掉。 */
    private var snapshot: Frame? = null

    /** 发给辅助进程的命令一条一条来；回话从读它输出的线程送到 [replies]。 */
    private val commandLock = Any()
    private val replies = LinkedBlockingQueue<String>()
    @Volatile private var frameWaiter: CountDownLatch? = null

    /** 要它醒着的观看者（全屏查看）。有它们在时休眠推迟到它们都走了再做。 */
    private val awakeWatchers = AtomicInteger()
    private var sleepWhenUnwatched = false

    private val watchers = ConcurrentHashMap<Any, Long>()
    private val pool = arrayOfNulls<Bitmap>(3)
    private var poolIndex = 0
    private var sequence = 0L
    private var lastPublished = 0L
    private var publishPending = false
    private val publishRunnable = Runnable {
        publishPending = false
        publish()
    }

    init {
        reader.setOnImageAvailableListener({ onImage() }, handler)
    }

    private fun onImage() {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        synchronized(lock) {
            if (!alive) {
                image.close()
                return
            }
            latest?.close()
            latest = image
            snapshot = null
        }
        frameWaiter?.countDown()
        schedulePublish()
    }

    /** 有人在看就按最短的间隔推一帧；间隔没到先记下，到点补上，免得画面停下来时预览停在旧帧。 */
    private fun schedulePublish() {
        val interval = watchers.values.minOrNull() ?: return
        val wait = lastPublished + interval - SystemClock.uptimeMillis()
        if (wait <= 0) {
            handler.removeCallbacks(publishRunnable)
            publishPending = false
            publish()
        } else if (!publishPending) {
            publishPending = true
            handler.postDelayed(publishRunnable, wait)
        }
    }

    private fun publish() {
        if (watchers.isEmpty()) return
        lastPublished = SystemClock.uptimeMillis()
        val frame = synchronized(lock) {
            val image = latest
            if (image == null) {
                // 休眠时没有帧，给休眠前那一帧
                snapshot?.let { _frames.value = it }
                return
            }
            val index = poolIndex
            poolIndex = (poolIndex + 1) % pool.size
            val bitmap = copy(image, pool[index]) ?: return
            pool[index] = bitmap
            Frame(bitmap, minOf(width, bitmap.width), bitmap.height, ++sequence)
        }
        _frames.value = frame
    }

    /**
     * 要看实时画面：至少每 [intervalMs] 毫秒一帧（画面不变时不推）。返回的函数用来取消登记。
     * 没人看的时候不做任何转换，也不占那几张 Bitmap。
     *
     * [keepAwake]：看的期间要它醒着（全屏查看）。在休眠就唤醒，走的时候再让它睡回去；
     * 期间到了闲置时间也推迟到走了再睡。缩略图不用：休眠时它显示休眠前那一帧就够了。
     */
    fun watch(intervalMs: Long, keepAwake: Boolean = false): () -> Unit {
        val key = Any()
        watchers[key] = intervalMs
        if (keepAwake && awakeWatchers.incrementAndGet() == 1) offMain {
            if (awakeWatchers.get() > 0 && _asleep.value && wakeLocked(waitFrame = false)) sleepWhenUnwatched = true
        }
        handler.post {
            lastPublished = 0
            publish()
        }
        return {
            if (watchers.remove(key) != null && keepAwake && awakeWatchers.decrementAndGet() == 0) offMain {
                if (awakeWatchers.get() == 0 && sleepWhenUnwatched) sleepLocked()
            }
            if (watchers.isEmpty()) handler.post {
                if (watchers.isEmpty()) {
                    synchronized(lock) { pool.fill(null) }
                    _frames.value = null
                }
            }
        }
    }

    /** 在别的线程上排队执行一条命令（界面线程不能等辅助进程回话）。 */
    private fun offMain(block: () -> Unit) {
        thread(name = "zeta-background-power", isDaemon = true) { synchronized(commandLock) { block() } }
    }

    /**
     * 休眠：摘下画面的 Surface，虚拟屏随之关掉，屏上的应用像熄屏一样停下（不再绘制，几乎不耗电），
     * 任务和页面都留在这块屏上。全屏查看开着时推迟到它关掉。已经在睡、或者屏没了时什么也不做。
     * 要等辅助进程回话，不要在界面线程上调用。
     */
    fun sleep() {
        synchronized(commandLock) {
            if (awakeWatchers.get() > 0) sleepWhenUnwatched = true else sleepLocked()
        }
    }

    private fun sleepLocked() {
        sleepWhenUnwatched = false
        if (!alive || _asleep.value) return
        // 先留一份缩小的画面：睡下后就没有帧了
        val small = synchronized(lock) { latest?.let { shrink(it) } }
        if (command("sleep") != VdHelper.ASLEEP) return
        _asleep.value = true
        synchronized(lock) {
            snapshot = small?.let { Frame(it, it.width, it.height, ++sequence) }
            // 整张的帧和那几张 Bitmap 都不留
            latest?.close()
            latest = null
            pool.fill(null)
        }
        handler.post { publish() }
    }

    /**
     * 唤醒并等到新的一帧（最多约 [WAKE_TIMEOUT_MS]），好让紧接着的截图是当前画面。本来就醒着时立即返回。
     * 返回是否醒着。不要在界面线程上调用。
     */
    fun wake(): Boolean = synchronized(commandLock) {
        sleepWhenUnwatched = false
        wakeLocked(waitFrame = true)
    }

    private fun wakeLocked(waitFrame: Boolean): Boolean {
        if (!alive) return false
        if (!_asleep.value) return true
        val start = SystemClock.uptimeMillis()
        val fresh = CountDownLatch(1)
        frameWaiter = fresh
        try {
            if (command("wake") != VdHelper.AWAKE) return false
            _asleep.value = false
            if (waitFrame) fresh.await(maxOf(0, WAKE_TIMEOUT_MS - (SystemClock.uptimeMillis() - start)), TimeUnit.MILLISECONDS)
            return true
        } finally {
            frameWaiter = null
        }
    }

    /** 给辅助进程发一条命令，等它回话。返回回话；没回、或者进程已经没了返回 null。在 [commandLock] 里调用。 */
    private fun command(name: String): String? {
        if (!alive) return null
        replies.clear()
        val sent = runCatching {
            process.outputStream.write("$name\n".toByteArray())
            process.outputStream.flush()
        }.isSuccess
        return if (sent) replies.poll(WAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS) else null
    }

    private fun onReply(line: String) {
        // 辅助进程报的错只有这里看得到，记进 logcat 方便查
        if (line.startsWith(VdHelper.ERROR)) Log.w(TAG, "display $displayId: $line")
        if (line == VdHelper.ASLEEP || line == VdHelper.AWAKE || line.startsWith(VdHelper.ERROR)) replies.offer(line)
    }

    /** 休眠时留给界面的那一帧：缩到长边 [SNAPSHOT_EDGE]，缩略图和刚打开查看时够用了。 */
    private fun shrink(image: Image): Bitmap? {
        val raw = copy(image, null) ?: return null
        val visible = minOf(width, raw.width)
        val scale = minOf(1f, SNAPSHOT_EDGE / maxOf(visible, raw.height).toFloat())
        val small = Bitmap.createBitmap(
            maxOf(1, (visible * scale).roundToInt()), maxOf(1, (raw.height * scale).roundToInt()), Bitmap.Config.ARGB_8888,
        )
        Canvas(small).drawBitmap(raw, Rect(0, 0, visible, raw.height), Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
        raw.recycle()
        return small
    }

    /** 最近一帧的截图（正好是画面大小）。还没有画面、或在休眠时返回 null。 */
    fun capture(): Bitmap? = synchronized(lock) {
        val image = latest ?: return null
        val raw = copy(image, null) ?: return null
        if (raw.width == width && raw.height == height) raw
        else Bitmap.createBitmap(raw, 0, 0, minOf(width, raw.width), raw.height)
    }

    /** 把一帧拷进 Bitmap（尽量重用 [reuse]）。行跨距可能比画面宽：按跨距建图，画的时候裁掉。 */
    private fun copy(image: Image, reuse: Bitmap?): Bitmap? = runCatching {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride < pixelStride * width) return null
        val paddedWidth = rowStride / pixelStride
        buffer.rewind()
        // 有的 ROM 最后一行不带行尾空白，缓冲比“跨距×高”短一点：少拷一行也比失败好
        val rows = minOf(height, buffer.remaining() / rowStride)
        if (rows <= 0) return null
        val bitmap = reuse?.takeIf { it.width == paddedWidth && it.height == rows && it.isMutable }
            ?: Bitmap.createBitmap(paddedWidth, rows, Bitmap.Config.ARGB_8888)
        buffer.limit(buffer.position() + rowStride * rows)
        bitmap.copyPixelsFromBuffer(buffer)
        bitmap
    }.getOrNull()

    /** 释放虚拟屏（屏上的应用界面随之销毁）。可以重复调用。 */
    fun release() {
        synchronized(lock) {
            if (!alive) return
            alive = false
        }
        onGone = null
        // 正在等回话、等新帧的不用再等了
        replies.offer(VdHelper.ERROR + "released")
        frameWaiter?.countDown()
        runCatching {
            process.outputStream.write("release\n".toByteArray())
            process.outputStream.close()
        }
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) LinuxEnv.kill(process)
        handler.post {
            synchronized(lock) {
                latest?.close()
                latest = null
                snapshot = null
                pool.fill(null)
            }
            watchers.clear()
            _frames.value = null
            runCatching { reader.close() }
            worker.quitSafely()
        }
    }

    private fun onHelperExit() {
        val callback = synchronized(lock) { if (alive) onGone else null } ?: return
        release()
        callback()
    }

    companion object {
        private const val HELPER = "com.dzshzx.zeta.phone.VdHelper"
        private const val TAG = "ZetaBackground"

        /** 唤醒最多等这么久（实测几十毫秒出第一帧）。 */
        private const val WAKE_TIMEOUT_MS = 1500L

        private const val SNAPSHOT_EDGE = 640f

        /**
         * 建一块 [width]×[height]、[dpi] 的后台屏幕。失败时抛出异常，message 是给人看的原因。
         * 要 root；会阻塞一两秒（启动 su 和 app_process）。
         */
        fun create(context: Context, width: Int, height: Int, dpi: Int): VirtualScreen {
            val worker = HandlerThread("zeta-background-frames").apply { start() }
            val handler = Handler(worker.looper)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            val token = VdSurfaceProvider.register(reader.surface)
            var process: Process? = null
            try {
                val script = appProcess(context.applicationInfo.sourceDir, HELPER, listOf(VdSurfaceProvider.AUTHORITY, token, "$width", "$height", "$dpi"))
                val started = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                process = started
                val ready = CountDownLatch(1)
                var displayId: Int? = null
                val output = StringBuilder()
                val screen = AtomicReference<VirtualScreen?>(null)
                thread(name = "zeta-background-helper", isDaemon = true) {
                    runCatching {
                        started.inputStream.bufferedReader().forEachLine { line ->
                            when {
                                line.startsWith(VdHelper.READY) -> {
                                    displayId = line.removePrefix(VdHelper.READY).trim().toIntOrNull()
                                    ready.countDown()
                                }
                                displayId == null -> synchronized(output) { output.appendLine(line) }
                                else -> screen.get()?.onReply(line.trim())
                            }
                        }
                    }
                    ready.countDown()
                    // 读到头说明辅助进程退出了
                    screen.get()?.onHelperExit()
                }
                ready.await(10, TimeUnit.SECONDS)
                val id = displayId
                if (id == null || id <= 0 || !started.isAlive) {
                    val detail = synchronized(output) { output.toString() }.lines()
                        .firstOrNull { it.startsWith(VdHelper.ERROR) }?.removePrefix(VdHelper.ERROR)
                        ?: synchronized(output) { output.toString() }.trim().takeLast(300).ifBlank { "辅助进程没有回应" }
                    throw IllegalStateException(detail)
                }
                val created = VirtualScreen(id, width, height, reader, worker, handler, started)
                screen.set(created)
                if (!started.isAlive) {
                    created.release()
                    throw IllegalStateException("辅助进程刚建好屏就退出了")
                }
                return created
            } catch (error: Throwable) {
                process?.let { LinuxEnv.kill(it) }
                runCatching { reader.close() }
                worker.quitSafely()
                throw error
            } finally {
                VdSurfaceProvider.unregister(token)
            }
        }
    }
}
