package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.i18n.Strings
import kotlin.concurrent.thread

/** 在后台做的一项镜像维护（zeta_image_android.sh 的 start）：做到哪了、做完的结果。 */
data class DiskImageOp(
    /** convert、check、resize、delete-old。 */
    val name: String,
    /** running、ok、error。 */
    val phase: String,
    /**
     * running 时是步骤（stop、measure、copy、check、resize、delete），ok 时是结果（clean、fixed、converted、新的 GB
     * 数…），error 时是原因。
     */
    val message: String,
    /** 复制文件时已拷的、总共的 KB。 */
    val progress: Pair<Long, Long>?,
    /** 状态说在做，却没人在做：做的途中被打断了（比如手机重启）。 */
    val interrupted: Boolean,
    /** 状态最后一次更新的时刻（Unix 秒）。 */
    val time: Long = 0L,
    /** 这项维护开始的时刻（Unix 秒），0 表示不知道（旧版脚本写的状态）。 */
    val started: Long = 0L,
    /** 当前这一步开始的时刻（Unix 秒），0 表示不知道。 */
    val stepStarted: Long = 0L,
) {
    val running: Boolean
        get() = phase == "running" && !interrupted

    /** 这项维护依次要做的几步（脚本写进 msg 的步骤名）。 */
    val steps: List<String>
        get() = STEPS[name] ?: listOf(message)

    /** 正在做第几步（从 0 数）；刚开始（start）算在第一步。 */
    val stepIndex: Int
        get() = steps.indexOf(message).coerceAtLeast(0)

    /** 复制的进度，0 到 1；不在复制时是 null。已用空间里有文件系统自己的开销，所以按 1 封顶。 */
    val fraction: Float?
        get() =
            progress
                ?.takeIf { message == "copy" }
                ?.let { (done, total) -> (done.toFloat() / total).coerceIn(0f, 1f) }

    /** 复制的平均速度（字节每秒），从复制开始算；刚开始几秒还算不准时是 null。 */
    val bytesPerSecond: Long?
        get() {
            val (done, _) = progress?.takeIf { message == "copy" } ?: return null
            val seconds = time - stepStarted
            if (stepStarted <= 0 || seconds < MIN_RATE_SECONDS || done <= 0) return null
            return done * 1024 / seconds
        }

    /** 照平均速度估的复制还要多少秒；算不出速度时是 null。 */
    val secondsLeft: Long?
        get() {
            val rate = bytesPerSecond?.takeIf { it > 0 } ?: return null
            val (done, total) = progress ?: return null
            return ((total - done).coerceAtLeast(0) * 1024 / rate)
        }

    private companion object {
        val STEPS =
            mapOf(
                "convert" to listOf("stop", "measure", "copy", "finish"),
                "check" to listOf("stop", "check"),
                "resize" to listOf("stop", "check", "resize"),
                "delete-old" to listOf("delete"),
            )

        const val MIN_RATE_SECONDS = 4L
    }
}

/** Zeta 自带环境的磁盘镜像（zeta_image_android.sh status）。 */
data class DiskImageStatus(
    /** 环境存在镜像里。 */
    val image: Boolean,
    /** 还是普通目录、可以转成镜像。 */
    val convertible: Boolean,
    val mounted: Boolean,
    /** 镜像的上限（镜像文件的大小）。 */
    val sizeBytes: Long,
    /** 镜像文件实际占手机的空间。 */
    val allocatedBytes: Long,
    /** Debian 里已用、剩余的空间；没挂上时是 null。 */
    val usedBytes: Long?,
    val availableBytes: Long?,
    /** 转换前的目录（rootfs.old）还留着。 */
    val oldDirectory: Boolean,
    /** 手机 /data 的剩余空间。 */
    val freeBytes: Long?,
    val op: DiskImageOp?,
) {
    val busy: Boolean
        get() = op?.running == true

    companion object {
        const val GB = 1L shl 30

        /** 新建镜像时默认的上限：手机剩余空间的一半，在 8 到 40 GB 之间（镜像是稀疏文件，上限不预先占空间）。 */
        fun defaultSizeGb(freeBytes: Long?): Int =
            ((freeBytes ?: 0L) / 2 / GB).coerceIn(8L, 40L).toInt()

        fun parse(output: String): DiskImageStatus {
            val v =
                output
                    .lineSequence()
                    .filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
            fun long(key: String) = v[key]?.toLongOrNull()
            val op =
                v["op"]
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { name ->
                        DiskImageOp(
                            name = name,
                            phase = v["phase"].orEmpty(),
                            message = v["msg"].orEmpty(),
                            progress =
                                v["progress"]?.split(" ")?.let { parts ->
                                    val done = parts.getOrNull(0)?.toLongOrNull()
                                    val total = parts.getOrNull(1)?.toLongOrNull()
                                    if (done != null && total != null && total > 0) done to total
                                    else null
                                },
                            interrupted = v["interrupted"] == "1",
                            time = long("time") ?: 0L,
                            started = long("started") ?: 0L,
                            stepStarted = long("step") ?: 0L,
                        )
                    }
            return DiskImageStatus(
                image = v["image"] == "1",
                convertible = v["convertible"] == "1",
                mounted = v["mounted"] == "1",
                sizeBytes = long("size") ?: 0L,
                allocatedBytes = long("alloc") ?: 0L,
                usedBytes = long("used"),
                availableBytes = long("avail"),
                oldDirectory = v["old"] == "1",
                freeBytes = long("free"),
                op = op,
            )
        }
    }
}

/**
 * 调 zeta_image_android.sh：每次先把应用里的这份写到 [LinuxEnv.IMAGE_SCRIPT]（内容没变就不动），再执行磁盘上的那份。
 * 开机脚本、进环境前挂镜像用的都是磁盘上的那份，在后台做的维护也从那里起。都是耗时操作，别在主线程调用。
 */
class DiskImageService(assets: (String) -> String) {
    private val script by lazy { assets("zeta_image_android.sh") }

    fun status(): DiskImageStatus? = runCatching {
        call("status")
    }
        .getOrNull()
        ?.takeIf { it.exitCode == 0 }
        ?.let { DiskImageStatus.parse(it.stdout) }

    /** 释放空间（fstrim）。返回放出的字节数，或失败的原因。 */
    fun trim(): Result<Long> = runCatching {
        call("trim", timeoutMs = 300_000)
    }
        .fold(
            { result ->
                problem(result)?.let { Result.failure(IllegalStateException(it)) }
                    ?: Result.success(
                        Regex("trimmed=(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toLong()
                            ?: 0L,
                    )
            },
            { Result.failure(it) },
        )

    /** 在后台开始一项维护（convert、check、resize、delete-old）；成功开始了返回 null，否则是原因。 */
    fun start(op: String, vararg arguments: String): String? = runCatching {
        problem(call("start", op, *arguments))
    }
        .getOrElse { it.message ?: Strings.coreFailed() }

    private fun call(vararg arguments: String, timeoutMs: Long = 30_000): CommandResult =
        LinuxEnv.runRoot(
            LinuxEnv.androidScript(INSTALL_AND_RUN, *arguments),
            timeoutMs,
            stdin = script,
        )

    private fun problem(result: CommandResult): String? {
        if (result.exitCode == 0) return null
        return result.stdout
            .lineSequence()
            .firstOrNull { it.startsWith("@zeta error ") }
            ?.removePrefix("@zeta error ")
            ?: result.stderr.ifBlank { result.stdout }.trim().ifBlank { Strings.coreFailed() }
    }

    private companion object {
        /** 从标准输入收脚本，写到磁盘上（两个包轮流写同一份，内容没变时不动），再执行它。 */
        val INSTALL_AND_RUN =
            """
            BB=${'$'}1; S=${LinuxEnv.IMAGE_SCRIPT}; shift
            "${'$'}BB" mkdir -p "${'$'}{S%/*}" && "${'$'}BB" cat > "${'$'}S.new" || exit 1
            if "${'$'}BB" cmp -s "${'$'}S.new" "${'$'}S"; then "${'$'}BB" rm -f "${'$'}S.new"
            else "${'$'}BB" chmod 0755 "${'$'}S.new" && "${'$'}BB" mv "${'$'}S.new" "${'$'}S" || exit 1; fi
            exec "${'$'}BB" sh "${'$'}S" "${'$'}BB" "${'$'}@"
            """
                .trimIndent()
    }
}

/**
 * 「磁盘镜像」页的状态与操作。状态可观察，在主线程上读写；调脚本在后台线程上做。 转换、检查、调整大小在 Android 一侧的后台进程里做（Zeta
 * 被杀也照样做完），这边在有维护进行时每两秒查一次状态。
 */
class DiskImageController(private val hub: ConversationHub, assets: (String) -> String) {
    private val service = DiskImageService(assets)
    private val main = MainHandler()

    /** null 表示还没查过。 */
    var status by mutableStateOf<DiskImageStatus?>(null)
        private set

    /** 正在发一个请求（查状态之外的：释放空间、开始一项维护）。 */
    var acting by mutableStateOf(false)
        private set

    /** 上一次释放空间、开始维护的结果（给人看的一句话），null 表示没有要说的。 */
    var notice by mutableStateOf<Notice?>(null)
        private set

    data class Notice(val text: String, val ok: Boolean)

    @Volatile private var polling = false

    fun refresh() {
        thread(name = "zeta-image", isDaemon = true) {
            val s = service.status()
            main.post { show(s) }
        }
    }

    private fun show(s: DiskImageStatus?) {
        val wasBusy = status?.busy == true
        status = s
        if (s?.busy == true) poll()
        // 做完一项要停 Debian 的维护：服务的状态跟着变了
        else if (wasBusy) hub.environment.refreshSsh()
    }

    /** 有维护在做时每两秒查一次，直到做完。 */
    private fun poll() {
        if (polling) return
        polling = true
        thread(name = "zeta-image-poll", isDaemon = true) {
            var s: DiskImageStatus?
            do {
                Thread.sleep(POLL_MS)
                s = service.status()
                val shown = s
                main.post { status = shown }
            } while (s?.busy == true)
            polling = false
            main.post { show(s) }
        }
    }

    /** 要停 Debian 的维护只在没有对话在进行时做：停 Debian 会结束正在回答的 Claude Code。 */
    val canStopDebian: Boolean
        get() = !hub.conversationsWorking && status?.busy != true && !acting

    fun trim() = act {
        service
            .trim()
            .fold(
                { Notice(Strings.coreImageTrimmed(formatBytes(it)), ok = true) },
                { Notice(it.message ?: Strings.coreFailed(), ok = false) },
            )
    }

    fun convert(sizeGb: Int) = start("convert", sizeGb.toString())

    fun check() = start("check")

    fun resize(sizeGb: Int) = start("resize", sizeGb.toString())

    fun deleteOld() = start("delete-old")

    private fun start(op: String, vararg arguments: String) = act {
        service.start(op, *arguments)?.let { Notice(it, ok = false) }
    }

    private fun act(block: () -> Notice?) {
        if (acting) return
        acting = true
        notice = null
        thread(name = "zeta-image", isDaemon = true) {
            val result =
                runCatching(block).getOrElse {
                    Notice(it.message ?: Strings.coreFailed(), ok = false)
                }
            val s = service.status()
            main.post {
                acting = false
                notice = result
                show(s)
            }
        }
    }

    fun dismissNotice() {
        notice = null
    }

    private companion object {
        const val POLL_MS = 2_000L
    }
}

/** 安装环境的最后一段：把刚解压的环境转成磁盘镜像，等它做完。返回 null 表示成功，否则是原因。 */
internal fun convertForInstall(
    service: DiskImageService,
    onProgress: (DiskImageOp) -> Unit,
): String? {
    val before = service.status() ?: return Strings.coreImageStatusFailed()
    if (before.image) return null
    service.start("convert", DiskImageStatus.defaultSizeGb(before.freeBytes).toString())?.let {
        return it
    }
    while (true) {
        Thread.sleep(1_000)
        val s = service.status() ?: continue
        val op = s.op ?: continue
        if (op.running) {
            onProgress(op)
            continue
        }
        return when {
            op.interrupted -> Strings.coreImageInterrupted()
            op.phase == "ok" -> null
            else -> op.message.ifBlank { Strings.coreFailed() }
        }
    }
}
