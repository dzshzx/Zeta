package com.dzshzx.zeta.phone

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.phone.tools.RootShell
import com.dzshzx.zeta.phone.tools.Shell
import com.dzshzx.zeta.phone.tools.ToolError
import com.dzshzx.zeta.phone.tools.out
import com.dzshzx.zeta.phone.tools.toolCall
import com.dzshzx.zeta.phone.tools.toolText
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/** screen_record 要录哪块屏。 */
internal sealed interface RecordTarget {
    /** 这个对话的后台屏幕：画面本来就进 Zeta 的进程，Zeta 自己编码。 */
    class Background(val screen: VirtualScreen) : RecordTarget

    /** 主屏（[displayId] 是逻辑屏号）：以 root 用系统的 screenrecord 录。 */
    class Main(val displayId: Int) : RecordTarget
}

/**
 * screen_record 的录屏，每个对话一份（[PhoneTools] 持有），同一时间最多录一段。录好的 mp4 放进手机 Linux 环境里 Zeta 的附件目录
 * （`~/.cache/<ZETA_NAME>/files/<随机>/`，和发给 Claude 的附件同一处，七天后由 zeta-helper 清掉），交给 Linux 用户，
 * 结果里说路径，Claude 用自己的工具去读。
 *
 * 系统的 screenrecord（Android 15 上是 v1.3）只认物理屏（`--display-id` 是 SurfaceFlinger 的物理屏号），录不了虚拟屏， 所以后台屏幕由
 * Zeta 自己录：登记看实时画面（[VirtualScreen.watch]，期间不休眠），每来一帧画到 H.264 编码器的输入 Surface 上，
 * 画面不变时编码器自己重复上一帧，MediaMuxer 写成 mp4；主屏用 screenrecord，SIGINT 停下，mp4 才写得完整。
 */
internal class ScreenRecorder(
    private val context: Context,
    private val settings: Settings,
    private val sh: Shell = RootShell,
) {
    private var current: Recording? = null

    /** 开始录 [target]，最长 [limitSeconds] 秒（到点自己停，文件留着等 stop 取）。 */
    @Synchronized
    fun start(target: RecordTarget, limitSeconds: Int?): JsonObject = toolCall {
        current?.let { throw ToolError("已经在录（${it.where}）：先用 screen_record action=stop 停下") }
        val env = settings.env ?: throw ToolError("手机上还没有 Linux 环境，录好的视频没地方交给你")
        val seconds = ScreenRecording.limit(limitSeconds)
        val output =
            ScreenRecording.output(env, AppVariant.current.cacheDir(env.home), LocalDateTime.now())
        val recording =
            when (target) {
                is RecordTarget.Background ->
                    FrameRecording.start(context, target.screen, output, seconds)
                is RecordTarget.Main -> RootRecording.start(sh, target.displayId, output, seconds)
            }
        current = recording
        toolText(
            "开始录屏：${recording.where}，最长 $seconds 秒（到点自己停）。做完要录的操作后用 screen_record action=stop 取视频。",
        )
    }

    /** 停下正在录的（[action] 不是 stop 时说明用法），交出 mp4 的路径。 */
    @Synchronized
    fun stop(action: String?): JsonObject = toolCall {
        if (action != "stop") throw ToolError("action 只能是 start 或 stop")
        val recording = current ?: throw ToolError("现在没有在录屏：先用 screen_record action=start")
        current = null
        toolText(recording.finish(sh))
    }

    /** 对话的手机工具要销毁了：在录的停下、照样交给 Linux（在别的线程上做，不等）。 */
    fun stopQuietly() {
        val recording = synchronized(this) { current.also { current = null } } ?: return
        thread(name = "zeta-screen-record-stop", isDaemon = true) {
            runCatching { recording.finish(sh) }
        }
    }
}

/** 录好的视频交到哪：Linux 里的路径（给 Claude）和 Android 一侧的同一个文件（以 root 写）。 */
internal class RecordOutput(
    val linuxDir: String,
    val linuxPath: String,
    val androidPath: String,
    /** 依次要有的目录（Android 一侧），没有的建出来交给 Linux 用户。 */
    val androidDirs: List<String>,
    val owner: String,
)

/** 录屏的纯逻辑：参数、路径、命令、编码尺寸。单元测试在 ScreenRecordingTest。 */
internal object ScreenRecording {
    const val MAX_SECONDS = 180
    const val DEFAULT_SECONDS = 60

    /** 编码的长边不超过它：够看清界面，文件也不大。 */
    const val MAX_EDGE = 1280

    fun limit(seconds: Int?): Int = (seconds ?: DEFAULT_SECONDS).coerceIn(1, MAX_SECONDS)

    /** 这一段放在 Linux 用户 [env] 的 Zeta 缓存目录 [cacheDir]（Linux 路径）下的 files/<随机>/screen-<时刻>.mp4。 */
    fun output(
        env: LinuxEnvConfig,
        cacheDir: String,
        now: LocalDateTime,
        id: String = UUID.randomUUID().toString().take(8),
    ): RecordOutput {
        val dir = "$cacheDir/files/$id"
        val path = "$dir/screen-" + now.format(STAMP) + ".mp4"
        val root = env.rootfs.trimEnd('/')
        val dirs =
            listOf(cacheDir.substringBeforeLast('/'), cacheDir, "$cacheDir/files", dir).map {
                root + it
            }
        return RecordOutput(dir, path, root + path, dirs, "${env.uid}:${env.gid}")
    }

    /** 建出 [output] 还没有的目录，交给 Linux 用户。 */
    fun prepareCommand(output: RecordOutput): String =
        output.androidDirs.joinToString(" && ") {
            val d = LinuxEnv.quote(it)
            "{ [ -d $d ] || { mkdir $d && chown ${output.owner} $d; }; }"
        }

    /**
     * 在后台起 screenrecord 录 [displayId]（只认 0，即默认的物理屏）到 [file]，打出它的进程号；它的报错写进 [log]。 [bitRate] 每秒比特数。
     */
    fun screenrecordCommand(
        file: String,
        seconds: Int,
        log: String,
        bitRate: Int = 6_000_000,
    ): String =
        "screenrecord --time-limit $seconds --bit-rate $bitRate ${LinuxEnv.quote(file)} " +
            "</dev/null >/dev/null 2>${LinuxEnv.quote(log)} & echo \$!"

    /** 让 [pid]（还是 screenrecord 时）照 Ctrl-C 那样收尾，最多等 5 秒；还不退就杀掉。 */
    fun stopCommand(pid: Int): String =
        "p=$pid; is() { [ -d /proc/\$p ] && grep -q screenrecord /proc/\$p/cmdline 2>/dev/null; }; " +
            "if is; then kill -INT \$p; i=0; while is && [ \$i -lt 50 ]; do sleep 0.1; i=\$((i+1)); done; " +
            "if is; then kill -9 \$p; echo killed; fi; fi; true"

    /** [pid] 还是不是在跑的 screenrecord：是就打出 yes。 */
    fun aliveCommand(pid: Int): String =
        "grep -q screenrecord /proc/$pid/cmdline 2>/dev/null && echo yes || true"

    /** 把 [file] 交给 Linux 用户、所有人可读，打出大小（字节）。[source] 不为空时先把它抄成 [file]。 */
    fun handoverCommand(file: String, owner: String, source: String? = null): String {
        val f = LinuxEnv.quote(file)
        val copy = source?.let { "/system/bin/cat ${LinuxEnv.quote(it)} > $f && " }.orEmpty()
        return "$copy[ -s $f ] && chown $owner $f && chmod 644 $f && stat -c %s $f"
    }

    /** 编码尺寸：等比缩到长边不超过 [maxEdge]，两边都取 16 的倍数（编码器按 16×16 的块编码）。 */
    fun encodeSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val scale = minOf(1f, maxEdge / max(width, height).toFloat())
        fun fit(v: Int) = max(16, (v * scale).roundToInt() / 16 * 16)
        return fit(width) to fit(height)
    }

    /** 录了多长，「12.3 秒」。 */
    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f 秒", ms / 1000.0)

    /** 文件多大，「1.2 MB」。 */
    fun size(bytes: Long): String =
        if (bytes < 1_000_000) "${max(1, bytes / 1000)} KB"
        else String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
}

/** 正在录的一段。 */
private abstract class Recording(val where: String, val output: RecordOutput) {
    protected val startedAt = SystemClock.elapsedRealtime()

    /** 停下、交给 Linux 用户，返回给 Claude 的一句（路径、时长、大小）。失败抛 [ToolError]。 */
    abstract fun finish(sh: Shell): String

    /** 交出去以后的那一句。[byLimit]：是到了时限自己停的。 */
    protected fun done(bytes: Long, ms: Long, byLimit: Boolean): String =
        "录屏已结束" +
            (if (byLimit) "（到了时限自己停的）" else "") +
            "：${output.linuxPath}（${ScreenRecording.seconds(ms)}，${ScreenRecording.size(bytes)}，mp4）"

    /** 以 root 交出 [output] 的文件（[source] 不为空时先抄过去），返回大小。 */
    protected fun handover(sh: Shell, source: String? = null): Long {
        val result =
            sh.run(
                ScreenRecording.handoverCommand(output.androidPath, output.owner, source),
                60_000,
            )
        return result.stdout.trim().lines().lastOrNull()?.trim()?.toLongOrNull()?.takeIf {
            result.exitCode == 0
        } ?: throw ToolError("录屏没有留下视频文件（${result.stderr.trim().take(200).ifEmpty { "文件是空的" }}）")
    }
}

/** 主屏：root 起的 screenrecord，进程号 [pid]。 */
private class RootRecording(
    output: RecordOutput,
    private val pid: Int,
    private val limitMs: Long,
    private val log: String,
) : Recording("主屏", output) {
    override fun finish(sh: Shell): String {
        val byLimit = sh.out(ScreenRecording.aliveCommand(pid)).trim() != "yes"
        sh.run(ScreenRecording.stopCommand(pid), 15_000)
        val ms = minOf(SystemClock.elapsedRealtime() - startedAt, limitMs)
        val bytes = handover(sh)
        sh.run("rm -f ${LinuxEnv.quote(log)}", 5_000)
        return done(bytes, ms, byLimit)
    }

    companion object {
        fun start(sh: Shell, displayId: Int, output: RecordOutput, seconds: Int): RootRecording {
            if (displayId != 0)
                throw ToolError("主屏现在指到了 display $displayId（调试用的设置），系统的 screenrecord 只录得了真正的屏幕")
            sh.out(ScreenRecording.prepareCommand(output))
            val log =
                "/data/local/tmp/zeta-screenrecord-${output.linuxDir.substringAfterLast('/')}.log"
            val pid =
                sh.out(ScreenRecording.screenrecordCommand(output.androidPath, seconds, log))
                    .trim()
                    .lines()
                    .lastOrNull()
                    ?.trim()
                    ?.toIntOrNull() ?: throw ToolError("screenrecord 没有起来")
            // 参数不对、编码器不可用时它马上退出：等一下看它还在不在
            Thread.sleep(400)
            if (sh.out(ScreenRecording.aliveCommand(pid)).trim() != "yes") {
                val why = sh.run("cat ${LinuxEnv.quote(log)}", 5_000).stdout.trim().take(300)
                throw ToolError("screenrecord 一起来就退出了" + if (why.isEmpty()) "" else "：$why")
            }
            return RootRecording(output, pid, seconds * 1000L, log)
        }
    }
}

/** 后台屏幕：Zeta 自己把画面编成 mp4，先写在应用缓存里的 [file]，停下后以 root 抄进 Linux。 */
private class FrameRecording
private constructor(
    output: RecordOutput,
    private val screen: VirtualScreen,
    private val file: File,
    private val limitMs: Long,
    private val codec: MediaCodec,
    private val input: Surface,
    private val muxer: MediaMuxer,
    private val size: Pair<Int, Int>,
) : Recording("后台屏幕（display ${screen.displayId}）", output) {
    @Volatile private var stopping = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var byLimit = false
    private val ended = CountDownLatch(1)
    private var track = -1
    private var samples = 0
    private val unwatch = screen.watch(FRAME_MS, keepAwake = true)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        thread(name = "zeta-screen-record", isDaemon = true) { run() }
    }

    override fun finish(sh: Shell): String {
        stopping = true
        ended.await(10, TimeUnit.SECONDS)
        val ms = minOf(SystemClock.elapsedRealtime() - startedAt, limitMs)
        try {
            failure?.let { throw ToolError("录屏出错：" + (it.message ?: it.javaClass.simpleName)) }
            if (samples == 0) throw ToolError("没有录到画面")
            sh.out(ScreenRecording.prepareCommand(output))
            return done(handover(sh, source = file.path), ms, byLimit)
        } finally {
            file.delete()
        }
    }

    /** 录屏线程：有新的一帧就画上去，随时取走编好的数据；停下或到时限后收尾。 */
    private fun run() {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.uptimeMillis() + limitMs
        var last = -1L
        try {
            while (!stopping && SystemClock.uptimeMillis() < deadline) {
                val frame = screen.frames.value
                if (frame != null && frame.sequence != last) {
                    last = frame.sequence
                    draw(frame)
                }
                drain(info, untilEnd = false)
                Thread.sleep(FRAME_MS / 2)
            }
            byLimit = !stopping
            codec.signalEndOfInputStream()
            drain(info, untilEnd = true)
        } catch (e: Exception) {
            failure = e
        } finally {
            release()
            ended.countDown()
        }
    }

    private fun draw(frame: VirtualScreen.Frame) {
        val canvas = input.lockHardwareCanvas()
        try {
            canvas.drawBitmap(
                frame.bitmap,
                Rect(0, 0, frame.width, frame.height),
                Rect(0, 0, size.first, size.second),
                paint,
            )
        } finally {
            input.unlockCanvasAndPost(canvas)
        }
    }

    /** 取走编码器已经编好的数据写进 mp4。[untilEnd]：一直取到流结束（最多等 [END_WAIT_MS]）。 */
    private fun drain(info: MediaCodec.BufferInfo, untilEnd: Boolean) {
        val giveUp = SystemClock.uptimeMillis() + END_WAIT_MS
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> if (write(index, info)) return
                !untilEnd || SystemClock.uptimeMillis() > giveUp -> return
            }
        }
    }

    /** 写一块编好的数据。返回是不是流的结尾。 */
    private fun write(index: Int, info: MediaCodec.BufferInfo): Boolean {
        val buffer = codec.getOutputBuffer(index)
        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        if (buffer != null && !config && info.size > 0 && track >= 0) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            muxer.writeSampleData(track, buffer, info)
            samples++
        }
        codec.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun release() {
        unwatch()
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { input.release() }
        // 一帧都没写时 stop 会抛异常，文件也不完整：照样放掉，finish 报没录到
        if (track >= 0) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    companion object {
        /** 看实时画面的间隔：约 15 帧每秒。 */
        private const val FRAME_MS = 66L
        private const val END_WAIT_MS = 3_000L
        private const val BIT_RATE = 4_000_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        fun start(
            context: Context,
            screen: VirtualScreen,
            output: RecordOutput,
            seconds: Int,
        ): FrameRecording {
            val size = ScreenRecording.encodeSize(screen.width, screen.height)
            val dir = File(context.cacheDir, "screen-record").apply { mkdirs() }
            val file = File(dir, output.linuxPath.substringAfterLast('/'))
            val codec = MediaCodec.createEncoderByType(MIME)
            var input: Surface? = null
            try {
                codec.configure(format(size), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                input = codec.createInputSurface()
                codec.start()
                val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                return FrameRecording(
                    output,
                    screen,
                    file,
                    seconds * 1000L,
                    codec,
                    input,
                    muxer,
                    size,
                )
            } catch (e: Exception) {
                runCatching { codec.release() }
                input?.release()
                file.delete()
                throw ToolError("没能开始录屏：" + (e.message ?: e.javaClass.simpleName))
            }
        }

        private fun format(size: Pair<Int, Int>): MediaFormat =
            MediaFormat.createVideoFormat(MIME, size.first, size.second).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // 画面不变时没有新帧：编码器每 100 毫秒重复上一帧，片子的时长才对
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
    }
}
