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
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.max

/** 要随消息发出的一张图片：已缩小并编码好，另带一张界面用的小图。 */
@Immutable
class Attachment(val id: String, val preview: ImageBitmap, val mediaType: String, val base64: String)

object Attachments {
    /** 长边上限。再大模型端也会缩，白占传输和上下文。 */
    private const val MAX_EDGE = 1568
    private const val PREVIEW_EDGE = 360

    /** 读取并压缩一张图片；读不了返回 null。耗时，别在主线程调用。 */
    fun load(context: Context, uri: Uri): Attachment? = runCatching {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / edge
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val edge = max(bitmap.width, bitmap.height)
        val preview = if (edge > PREVIEW_EDGE) {
            val scale = PREVIEW_EDGE.toFloat() / edge
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        Attachment(UUID.randomUUID().toString(), preview.asImageBitmap(), "image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }.getOrNull()
}

/** 要随消息送进 Linux 环境的一个文件。复制完成后 [path] 才有值。 */
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

    /**
     * 把文件复制进 Linux 环境的 ~/.cache/zeta/files/<随机>/<文件名>（测试版是 ~/.cache/zeta-dev，见 [AppVariant]），
     * 返回那里的路径；失败返回 null。
     * 放在各自的随机目录里，同名文件互不覆盖；助手脚本会清掉七天前的。耗时，别在主线程调用。
     */
    fun copy(context: Context, config: LinuxEnvConfig, uri: Uri, name: String): String? = runCatching {
        val directory = "${AppVariant.current.cacheDir(config.home)}/files/${UUID.randomUUID().toString().take(8)}"
        val target = "$directory/$name"
        val process = LinuxEnv.start(config, "mkdir -p " + LinuxEnv.quote(directory) + " && cat > " + LinuxEnv.quote(target))
        val input = context.contentResolver.openInputStream(uri) ?: run { process.destroy(); return@runCatching null }
        input.use { source -> process.outputStream.use { source.copyTo(it, 64 * 1024) } }
        if (process.waitFor() == 0) target else null
    }.getOrNull()
}
