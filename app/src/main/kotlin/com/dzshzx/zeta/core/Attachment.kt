package com.dzshzx.zeta.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.util.UUID

/** 要随消息发出的一张图片：已缩小并编码好，另带一张界面用的小图。 */
@Immutable
class Attachment(val id: String, val preview: ImageBitmap, val mediaType: String, val base64: String)

object Attachments {
    /** 长边上限。再大模型端也会缩，白占传输和上下文。 */
    private const val MAX_EDGE = 1568
    private const val PREVIEW_EDGE = 360

    /** 读取并压缩一张图片；读不了返回 null。耗时，别在主线程调用。 */
    fun load(context: Context, uri: Uri): Attachment? = runCatching {
        encode(Images.decodeWithin(ImageDecoder.createSource(context.contentResolver, uri), MAX_EDGE).bitmap)
    }.getOrNull()

    /** 已经在内存里的一张图（截屏）：缩小、编码成附件。耗时，别在主线程调用。 */
    fun fromBitmap(source: Bitmap): Attachment? = runCatching { encode(Images.scaleWithin(source, MAX_EDGE)) }.getOrNull()

    /**
     * 已经编码好的一张图（草稿里存下的、会话记录里取回的）重新做成附件：内容原样发送，只另解一张界面用的小图。
     * 解不开返回 null。耗时，别在主线程调用。
     */
    fun restore(id: String, mediaType: String, bytes: ByteArray): Attachment? = runCatching {
        val preview = Images.decodeWithin(bytes, PREVIEW_EDGE).bitmap
        Attachment(id, preview.asImageBitmap(), mediaType, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }.getOrNull()

    private fun encode(bitmap: Bitmap): Attachment {
        val bytes = Images.jpeg(bitmap, 85)
        val preview = Images.scaleWithin(bitmap, PREVIEW_EDGE)
        return Attachment(UUID.randomUUID().toString(), preview.asImageBitmap(), "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}

/** 要随消息送进对话所在主机的一个文件。复制完成后 [path] 才有值。 */
class FileUpload(val id: String, val name: String) {
    var path by mutableStateOf<String?>(null)
    var failed by mutableStateOf(false)
    val uploading: Boolean get() = path == null && !failed
}

object Uploads {
    /** 文件名和类型；拿不到名字时用地址的最后一段。 */
    fun describe(context: Context, uri: Uri): Pair<String, String?> {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val clean = name.replace(Regex("[/\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "file" }
        // file:// 这类地址问不到类型，按扩展名猜
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(clean.substringAfterLast('.', "").lowercase())
        return clean to type
    }

    /** 文件有多大；问不到（有的应用不给）时为 null。 */
    fun size(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") {
            return@runCatching uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }?.length()
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()?.takeIf { it >= 0 }

    /** 复制的结果：成功时 [path] 是 Linux 里的路径；[tooLarge] 是读到一半超过了上限，复制了一半的已经删掉。 */
    class Copied(val path: String?, val tooLarge: Boolean = false)

    /**
     * 把文件复制进 [shell] 那台机器（对话所在的主机）的 ~/.cache/zeta/files/<随机>/<文件名>（测试版是 ~/.cache/zeta-dev，见 [AppVariant]）。
     * 放在各自的随机目录里，同名文件互不覆盖；助手脚本会清掉七天前的。远程主机经 ssh 的标准输入传过去。
     * 超过 [limit] 字节就停下（事先问不到大小的文件只能边读边数）。耗时，别在主线程调用。
     */
    fun copy(context: Context, shell: LinuxShell, uri: Uri, name: String, limit: Long = Long.MAX_VALUE): Copied =
        copyFrom(shell, name, limit) { context.contentResolver.openInputStream(uri) }

    /** 把内存里的一段内容（太长的文字、放不下的截屏）当作文件复制进 Linux，返回路径；失败返回 null。 */
    fun copyBytes(shell: LinuxShell, name: String, bytes: ByteArray): String? =
        copyFrom(shell, name, Long.MAX_VALUE) { bytes.inputStream() }.path

    private fun copyFrom(shell: LinuxShell, name: String, limit: Long, open: () -> InputStream?): Copied = runCatching {
        val input = open() ?: return@runCatching Copied(null)
        val directory = "${AppVariant.current.cacheDir(shell.home)}/files/${UUID.randomUUID().toString().take(8)}"
        val target = "$directory/$name"
        val process = shell.start("mkdir -p " + LinuxEnv.quote(directory) + " && cat > " + LinuxEnv.quote(target))
        var over = false
        input.use { source ->
            process.outputStream.use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) {
                        over = true
                        break
                    }
                    sink.write(buffer, 0, n)
                }
            }
        }
        // 关掉标准输入 cat 就会结束（app 给不了 su 的子进程发信号，只能这样让它停）
        val ok = process.waitFor() == 0
        if (over) {
            runCatching { shell.run("rm -rf " + LinuxEnv.quote(directory)) }
            return@runCatching Copied(null, tooLarge = true)
        }
        Copied(if (ok) target else null)
    }.getOrElse { Copied(null) }
}
