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

/**
 * 随消息内联发给模型看的一张图片：已缩小并编码好，另带一张界面用的小图。用户选的图片另把原文件复制进主机（[FileUpload.inline]）， 这时 [original]
 * 是那个文件的附件说明（核心协议里图的 original）。
 */
@Immutable
class Attachment(
    val id: String,
    val preview: ImageBitmap,
    val mediaType: String,
    val base64: String,
    val original: String? = null,
) {
    fun withOriginal(path: String) = Attachment(id, preview, mediaType, base64, path)
}

object Attachments {
    /** 长边上限：内联的只是让模型先看一眼，要细节时 Claude Code 去读原文件（见 [FileUpload.inline]）。 */
    const val MAX_EDGE = 1568
    const val PREVIEW_EDGE = 360

    /** 已经编码好的一张图（草稿里存下的、会话记录里取回的）重新做成附件：内容原样发送，只另解一张界面用的小图。 解不开返回 null。耗时，别在主线程调用。 */
    fun restore(
        id: String,
        mediaType: String,
        bytes: ByteArray,
        original: String? = null,
    ): Attachment? = runCatching {
        val preview = ImageCodec.current.decodeWithin(bytes, PREVIEW_EDGE)
        Attachment(
            id,
            preview,
            mediaType,
            Base64.getEncoder().encodeToString(bytes),
            original,
        )
    }
        .getOrNull()
}

/**
 * 要随消息送进对话所在主机的一个文件。复制完成后 [path] 才有值。
 *
 * 图片文件另有 [inline]：缩小的一份，随消息内联发给模型先看（带上 original 指明是这个文件的），原文件仍复制进主机，模型看不清时
 * 自己去读。输入栏只画它的缩略图；没有时（草稿里恢复的）缩略图从主机上的 [path] 取，只发文件。
 */
class FileUpload(val id: String, val name: String) {
    /** 这份草稿自己选的、分享来的本机文件：输入栏点开看大图时读它（原图，不经主机）。放回的、草稿里恢复的没有。 */
    var local: PickedFile? = null

    /** 这次复制进主机的文件是这份草稿自己传的、还没随消息发出去：从输入栏移除时把主机上那份删掉（[Uploads.discard]）。放回的、草稿里恢复的 可能是发过的消息里的，不删。 */
    var fresh = false

    /** 用户从输入栏移除了它；还在复制的，复制完由复制的那边删掉主机上那份。 */
    var removed = false
    var path by mutableStateOf<String?>(null)
    var inline by mutableStateOf<Attachment?>(null)
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

    /**
     * 用户从输入栏移除了 [upload]：是这份草稿自己传的（[FileUpload.fresh]）就删掉主机上那次复制的目录（[copyFrom] 建的 `files/<8
     * 位十六进制>`，路径对不上这个样子的不动）。还在复制的只记下，复制完再调一次。阻塞，别在主线程调用。
     */
    fun discard(shell: LinuxShell, upload: FileUpload) {
        upload.removed = true
        val path = upload.path ?: return
        if (!upload.fresh) return
        val directory = path.substringBeforeLast('/')
        val root = "${AppVariant.current.cacheDir(shell.home)}/files/"
        if (!directory.startsWith(root) || !UploadDirectory.matches(directory.removePrefix(root)))
            return
        runCatching { shell.run("rm -rf " + LinuxEnv.quote(directory), plain = true) }
    }

    private val UploadDirectory = Regex("[0-9a-f]{8}")

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

    /** 读成一张缩小、编码好的图片附件；不是图片、读不了是 null。 */
    fun loadImage(): Attachment?
}

/** 文件名里不能有的字符换掉，限长；空了用 "file"。 */
fun Uploads.cleanName(name: String): String =
    name.replace(Regex("[/\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "file" }
