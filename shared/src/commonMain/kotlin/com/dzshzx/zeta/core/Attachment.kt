package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/** 要随消息发出的一张图片：已缩小并编码好，另带一张界面用的小图。 */
@Immutable
class Attachment(
    val id: String,
    val preview: ImageBitmap,
    val mediaType: String,
    val base64: String,
)

object Attachments {
    /** 长边上限。再大模型端也会缩，白占传输和上下文。 */
    const val MAX_EDGE = 1568
    const val PREVIEW_EDGE = 360

    /** 已经编码好的一张图（草稿里存下的、会话记录里取回的）重新做成附件：内容原样发送，只另解一张界面用的小图。 解不开返回 null。耗时，别在主线程调用。 */
    fun restore(id: String, mediaType: String, bytes: ByteArray): Attachment? = runCatching {
        val preview = ImageCodec.current.decodeWithin(bytes, PREVIEW_EDGE)
        Attachment(
            id,
            preview,
            mediaType,
            Base64.getEncoder().encodeToString(bytes),
        )
    }
        .getOrNull()
}

/** 要随消息送进对话所在主机的一个文件。复制完成后 [path] 才有值。 */
class FileUpload(val id: String, val name: String) {
    var path by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    val uploading: Boolean
        get() = path == null && !failed
}

object Uploads {
    /** 复制的结果：成功时 [path] 是 Linux 里的路径；[tooLarge] 是读到一半超过了上限，复制了一半的已经删掉。 */
    class Copied(val path: String?, val tooLarge: Boolean = false)

    /** 把内存里的一段内容（太长的文字、放不下的截屏）当作文件复制进 Linux，返回路径；失败返回 null。 */
    fun copyBytes(shell: LinuxShell, name: String, bytes: ByteArray): String? =
        copyFrom(shell, name, Long.MAX_VALUE) { bytes.inputStream() }.path

    fun copyFrom(
        shell: LinuxShell,
        name: String,
        limit: Long,
        open: () -> InputStream?,
    ): Copied = runCatching {
        val input = open() ?: return@runCatching Copied(null)
        val directory =
            "${AppVariant.current.cacheDir(shell.home)}/files/${UUID.randomUUID().toString().take(8)}"
        val target = "$directory/$name"
        val process =
            shell.startPlain(
                "mkdir -p " + LinuxEnv.quote(directory) + " && cat > " + LinuxEnv.quote(target),
            )
        val over = input.use { source ->
            process.outputStream.use { sink -> copyLimited(source, sink, limit) }
        }
        // 关掉标准输入 cat 就会结束（app 给不了 su 的子进程发信号，只能这样让它停）
        val ok = process.waitFor() == 0
        if (over) {
            runCatching { shell.run("rm -rf " + LinuxEnv.quote(directory), plain = true) }
            return@runCatching Copied(null, tooLarge = true)
        }
        Copied(if (ok) target else null)
    }
        .getOrElse { Copied(null) }

    /** 把 [source] 抄进 [sink]，超过 [limit] 字节就停下；返回是不是超了（超了的那一块不写）。 */
    private fun copyLimited(source: InputStream, sink: OutputStream, limit: Long): Boolean {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) return false
            total += n
            if (total > limit) return true
            sink.write(buffer, 0, n)
        }
    }
}

/**
 * 用户选的、分享进来的一个文件：content:// 地址（app 的 core/AttachmentsAndroid.kt）。
 * 输入栏据此决定当图片附件还是复制进对话所在的主机。各项都可能耗时，别在主线程调用。
 */
interface PickedFile {
    /** 文件名（已去掉斜杠和控制字符，拿不到时是 "file"）和类型（问不到是 null）。 */
    fun describe(): Pair<String, String?>

    /** 多大；问不到是 null。 */
    fun size(): Long?

    /** 打开读内容；打不开是 null。 */
    fun open(): InputStream?

    /** 读成一张图片附件（缩小、编码好）；读不了是 null。 */
    fun loadImage(): Attachment?
}

/** 文件名里不能有的字符换掉，限长；空了用 "file"。 */
fun Uploads.cleanName(name: String): String =
    name.replace(Regex("[/\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "file" }
