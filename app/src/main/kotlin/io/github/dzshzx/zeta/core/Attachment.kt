package io.github.dzshzx.zeta.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Immutable
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
