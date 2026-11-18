package com.dzshzx.zeta.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 文件浏览里的一项。 */
@Immutable
data class LinuxEntry(
    val name: String,
    /** 能点进去：目录，或指向目录的符号链接。 */
    val directory: Boolean,
    /** 普通文件，或指向普通文件的符号链接：能预览、能导出。 */
    val regular: Boolean,
    val link: String?,
    val broken: Boolean,
    val size: Long,
    /** 修改时间，秒。 */
    val mtime: Long,
)

sealed interface LinuxListing {
    data class Entries(val path: String, val entries: List<LinuxEntry>, val total: Int, val truncated: Boolean) : LinuxListing
    data class Failed(val message: String) : LinuxListing
}

sealed interface LinuxPreview {
    data class Text(val text: String, val size: Long, val truncated: Boolean) : LinuxPreview
    data class Binary(val size: Long) : LinuxPreview
    data class Failed(val message: String) : LinuxPreview
}

/**
 * 只读的文件浏览：列目录、读文本都经助手脚本、以 Linux 用户的身份做（看到的就是 Claude Code 看到的），输出有上限。
 * 耗时，别在主线程调用。
 */
object LinuxFiles {
    /** 文本预览最多读这么多（与 Eta 相同）。 */
    const val PREVIEW_BYTES = 256 * 1024

    /** 图片经助手的 file-image 整个读进来（转成 base64 再解码，内存要翻几倍），再大的不预览。 */
    const val MAX_PREVIEW_IMAGE = 20L * 1024 * 1024

    private val ImageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")
    private val json = Json { ignoreUnknownKeys = true }

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in ImageExtensions

    /** 词法归一化成绝对路径，不解析符号链接；不合法的退回 /。 */
    fun normalize(path: String): String {
        val segments = ArrayList<String>()
        for (segment in path.trim().split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun parent(path: String): String? = if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }

    fun child(directory: String, name: String): String = if (directory == "/") "/$name" else "$directory/$name"

    fun list(config: LinuxEnvConfig, helper: String, path: String): LinuxListing {
        val result = runCatching {
            LinuxEnv.run(config, "exec python3 - ls " + LinuxEnv.quote(path), stdin = helper, timeoutMs = 30_000)
        }.getOrNull() ?: return LinuxListing.Failed("进不了 Linux 环境")
        val lines = result.stdout.lineSequence().filter { it.startsWith("{") }.mapNotNull { parse(it) }.toList()
        val head = lines.firstOrNull()?.takeIf { it.str("type") == "zeta_ls" }
            ?: return LinuxListing.Failed(result.stderr.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: "无法读取该路径")
        head.str("error")?.let { return LinuxListing.Failed(problem(it)) }
        val entries = lines.drop(1).mapNotNull { item ->
            val name = item.str("name") ?: return@mapNotNull null
            val kind = item.str("kind")
            val broken = item.bool("broken") == true
            LinuxEntry(
                name = name,
                directory = item.bool("dir") == true,
                regular = kind == "file" || (kind == "link" && !broken && item.bool("dir") != true),
                link = item.str("target")?.takeIf { kind == "link" },
                broken = broken,
                size = item.long("size") ?: 0L,
                mtime = item.long("mtime") ?: 0L,
            )
        }
        return LinuxListing.Entries(head.str("path") ?: path, entries, head.int("total") ?: entries.size, head.bool("truncated") == true)
    }

    fun read(config: LinuxEnvConfig, helper: String, path: String): LinuxPreview {
        val result = runCatching {
            LinuxEnv.run(config, "exec python3 - read " + LinuxEnv.quote(path) + " " + PREVIEW_BYTES, stdin = helper, timeoutMs = 30_000)
        }.getOrNull() ?: return LinuxPreview.Failed("进不了 Linux 环境")
        val item = result.stdout.lineSequence().firstOrNull { it.startsWith("{") }?.let(::parse)
            ?: return LinuxPreview.Failed("无法读取该文件")
        item.str("error")?.let { return LinuxPreview.Failed(problem(it)) }
        val size = item.long("size") ?: 0L
        if (item.bool("binary") == true) return LinuxPreview.Binary(size)
        return LinuxPreview.Text(item.str("text").orEmpty(), size, item.bool("truncated") == true)
    }

    private fun problem(error: String): String = when (error) {
        "missing" -> "路径不存在"
        "not_dir" -> "不是目录"
        "not_file" -> "不是普通文件"
        "denied" -> "没有权限读取（Linux 用户看不了这里）"
        else -> "无法读取该路径"
    }

    private fun parse(line: String): JsonObject? = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/** 一次导入或导出的进度；[message] 不为空表示已经结束。 */
@Immutable
data class TransferState(
    val export: Boolean,
    val name: String,
    val index: Int,
    val count: Int,
    val done: Long,
    /** 当前这个文件的大小；不知道是 null。 */
    val total: Long?,
    val message: String? = null,
    val ok: Boolean = true,
) {
    val running: Boolean get() = message == null
}

/**
 * 手机和 Linux 环境之间搬文件。都以 Linux 用户的身份读写，所以导入的文件属于这个用户（共享文件夹里还会继承 media_rw 组），
 * 读不了、写不进的地方和 Claude Code 一样被挡住。数据经进程管道一块一块地流过去，不整个读进内存。
 * 放在后台线程里，离开页面也照常进行；同一时间只做一件。
 */
object LinuxTransfers {
    var state by mutableStateOf<TransferState?>(null)
        private set

    /** 每结束一次导入加一：文件浏览据此刷新当前目录。 */
    var imports by mutableIntStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var cancelled = false
    @Volatile private var lastPost = 0L

    val running: Boolean get() = state?.running == true

    // 参数：$1 目标目录，$2 文件名，$3 $4 重名时拼“名字 (n).扩展名”用，$5 临时文件名。
    // 先写到隐藏的临时文件，写完再改名：中途失败或取消不会留下半个文件冒充完整的
    private val ImportScript = """
        cd -- "${'$'}1" 2>/dev/null || exit 3
        [ -w . ] || exit 4
        trap 'rm -f -- "${'$'}5"' EXIT
        cat > "${'$'}5" || exit 5
        t=${'$'}2; i=1
        while [ -e "${'$'}t" ] || [ -L "${'$'}t" ]; do t="${'$'}3 (${'$'}i)${'$'}4"; i=${'$'}((i + 1)); done
        mv -- "${'$'}5" "${'$'}t" || exit 6
        printf '%s\n' "${'$'}t"
    """.trimIndent()

    /** 把手机上的这些文件复制进 Linux 的 [directory]，重名的不覆盖，改叫“名字 (1).扩展名”。 */
    fun import(context: Context, config: LinuxEnvConfig, uris: List<Uri>, directory: String) {
        if (running || uris.isEmpty()) return
        val app = context.applicationContext
        begin(TransferState(export = false, name = "", index = 0, count = uris.size, done = 0, total = null))
        thread(name = "zeta-import", isDaemon = true) {
            var imported = 0
            var lastName = ""
            var failure: String? = null
            for ((index, uri) in uris.withIndex()) {
                if (cancelled) break
                val name = Uploads.describe(app, uri).first
                val total = size(app, uri)
                post(TransferState(false, name, index + 1, uris.size, 0, total), force = true)
                val problem = runCatching { importOne(app, config, uri, directory, name, index + 1, uris.size, total) { lastName = it } }
                    .getOrElse { it.message ?: "导入中断" }
                if (problem == null) {
                    imported++
                } else if (!cancelled) {
                    failure = "“$name”没有导入：$problem"
                    break
                }
            }
            val message = when {
                cancelled -> "已取消" + if (imported > 0) "，已导入 $imported 个文件" else ""
                failure != null -> (if (imported > 0) "已导入 $imported 个文件；" else "") + failure
                imported == 1 -> "已导入 $lastName"
                else -> "已导入 $imported 个文件"
            }
            finish(message, ok = failure == null && !cancelled, imported = true)
        }
    }

    /** 返回 null 表示成功（[onSaved] 收到最终的文件名，重名时会改），否则是原因。 */
    private fun importOne(
        context: Context,
        config: LinuxEnvConfig,
        uri: Uri,
        directory: String,
        name: String,
        index: Int,
        count: Int,
        total: Long?,
        onSaved: (String) -> Unit,
    ): String? {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        val temp = ".zeta-import-" + UUID.randomUUID().toString().take(8)
        val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return "读取不了这个文件"
        input.use { source ->
            val command = "exec bash -c " + LinuxEnv.quote(ImportScript) + " zeta-import " +
                listOf(directory, name, stem, extension, temp).joinToString(" ") { LinuxEnv.quote(it) }
            val started = LinuxEnv.start(config, command)
            process = started
            val errors = Drain(started.errorStream)
            val output = Drain(started.inputStream)
            var done = 0L
            // 写不进去多半是脚本已经退出了（目录不对、没有权限），原因看退出码
            val readFailed = runCatching {
                started.outputStream.use { sink ->
                    pump(source, sink::write) { n ->
                        done += n
                        post(TransferState(false, name, index, count, done, total))
                    }
                }
            }.exceptionOrNull() is ReadFailure
            val code = if (cancelled || readFailed) {
                LinuxEnv.kill(started)
                -1
            } else {
                started.waitFor()
            }
            process = null
            if (code == 0) {
                onSaved(output.text().lineSequence().lastOrNull { it.isNotBlank() } ?: name)
                return null
            }
            // 被杀掉的脚本来不及删临时文件
            runCatching { LinuxEnv.run(config, "rm -f -- " + LinuxEnv.quote(LinuxFiles.child(directory, temp))) }
            return when {
                cancelled -> "已取消"
                readFailed -> "读取不了这个文件"
                code == 3 -> "进不去目录 $directory"
                code == 4 -> "没有权限写入 $directory"
                code == 5 -> "写入失败，可能是存储空间不足"
                else -> errors.text().lineSequence().lastOrNull { it.isNotBlank() }?.trim()
                    ?: output.text().trim().ifBlank { "退出码 $code" }
            }
        }
    }

    /** 把 Linux 里的文件 [path] 写到用户在系统文件选择器里建好的 [destination]。 */
    fun export(context: Context, config: LinuxEnvConfig, path: String, size: Long?, destination: Uri) {
        if (running) return
        val app = context.applicationContext
        val name = path.substringAfterLast('/')
        begin(TransferState(export = true, name = name, index = 1, count = 1, done = 0, total = size))
        thread(name = "zeta-export", isDaemon = true) {
            val problem = runCatching { exportOne(app, config, path, name, size, destination) }.getOrElse { it.message ?: "导出中断" }
            if (problem != null) runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }
            finish(
                when {
                    problem == null -> "已导出 $name"
                    cancelled -> "已取消"
                    else -> "“$name”没有导出：$problem"
                },
                ok = problem == null,
                imported = false,
            )
        }
    }

    private fun exportOne(context: Context, config: LinuxEnvConfig, path: String, name: String, size: Long?, destination: Uri): String? {
        val resolver = context.contentResolver
        // "wt" 清空已有内容；有的文档提供方不认 t
        val sink = runCatching { resolver.openOutputStream(destination, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(destination, "w") }.getOrNull()
            ?: return "写不了选好的位置"
        sink.use { out ->
            // 文件内容走标准输出。登录 shell 的配置文件万一往标准输出打了字，会混进导出的文件里：
            // 先打一行记号，记号之前的都丢掉
            val marker = "ZETA-EXPORT-" + UUID.randomUUID().toString().take(8)
            val started = LinuxEnv.start(config, "printf '%s\\n' $marker; exec cat -- " + LinuxEnv.quote(path))
            process = started
            runCatching { started.outputStream.close() }
            val errors = Drain(started.errorStream)
            var done = 0L
            val failed = runCatching {
                BufferedInputStream(started.inputStream, 256 * 1024).use { source ->
                    if (!skipPast(source, marker)) throw ReadFailure(java.io.IOException("no marker"))
                    pump(source, out::write) { n ->
                        done += n
                        post(TransferState(true, name, 1, 1, done, size))
                    }
                }
            }.exceptionOrNull()
            // 读的一侧断了多半是进程已经退出（比如进不了环境），等它一下好拿到退出码和报错
            val code = when {
                cancelled || (failed != null && failed !is ReadFailure) -> -1
                started.waitFor(10, TimeUnit.SECONDS) -> started.exitValue()
                else -> -1
            }
            if (code == -1) LinuxEnv.kill(started)
            process = null
            val reason = errors.text().lineSequence().lastOrNull { it.isNotBlank() }?.trim()?.let { line ->
                if ("Permission denied" in line) "没有权限读取" else line
            }
            return when {
                cancelled -> "已取消"
                failed != null && failed !is ReadFailure -> "写入失败：" + (failed.message ?: "未知原因")
                code == 0 && failed == null -> null
                else -> reason ?: if (code > 0) "退出码 $code" else "读取中断"
            }
        }
    }

    fun cancel() {
        if (!running) return
        cancelled = true
        process?.let(LinuxEnv::kill)
    }

    /** 收起已经结束的那条结果。 */
    fun dismiss() {
        if (!running) state = null
    }

    private fun begin(initial: TransferState) {
        cancelled = false
        lastPost = 0L
        state = initial
    }

    /** 进度最多每 150 毫秒报一次，免得每块数据都重组一次界面。 */
    private fun post(next: TransferState, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPost < 150) return
        lastPost = now
        main.post { if (state?.running == true) state = next }
    }

    private fun finish(message: String, ok: Boolean, imported: Boolean) = main.post {
        state = (state ?: TransferState(!imported, "", 0, 0, 0, null)).copy(message = message, ok = ok)
        if (imported) imports++
    }

    /** 读的一侧出错（区别于写的一侧：写不进多半是对面进程已经退出）。 */
    private class ReadFailure(cause: Throwable) : Exception(cause)

    private fun pump(source: InputStream, write: (ByteArray, Int, Int) -> Unit, onChunk: (Int) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        while (!cancelled) {
            val n = try {
                source.read(buffer)
            } catch (error: java.io.IOException) {
                throw ReadFailure(error)
            }
            if (n < 0) break
            write(buffer, 0, n)
            onChunk(n)
        }
    }

    /** 读到单独一行的 [marker] 为止；前面最多容忍 1 MB 的杂讯。 */
    private fun skipPast(source: InputStream, marker: String): Boolean {
        val expected = marker.toByteArray()
        val line = java.io.ByteArrayOutputStream()
        var skipped = 0
        while (skipped < 1_048_576) {
            val b = source.read()
            if (b < 0) return false
            skipped++
            if (b == '\n'.code) {
                if (line.toByteArray().contentEquals(expected)) return true
                line.reset()
            } else if (line.size() <= expected.size) {
                line.write(b)
            }
        }
        return false
    }

    private fun size(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it >= 0 }

    /** 在后台把一个输出流读完（只留最后 16 KB），免得管道写满把对面卡住。 */
    private class Drain(stream: InputStream) {
        private val text = StringBuilder()
        private val worker = thread(isDaemon = true) {
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    synchronized(text) {
                        text.append(line).append('\n')
                        if (text.length > 16_384) text.delete(0, text.length - 16_384)
                    }
                }
            }
        }

        fun text(): String {
            worker.join(2_000)
            return synchronized(text) { text.toString() }
        }
    }
}
