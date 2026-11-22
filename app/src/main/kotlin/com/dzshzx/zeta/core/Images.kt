package com.dzshzx.zeta.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * 图片的解码、缩小、压 JPEG：附件、聊天里显示的图、手机工具看照片和聊天图片都走这里。 不碰 Context，root
 * 辅助进程（[com.dzshzx.zeta.phone.tools.ProviderHelper]，同一个 APK 起的 app_process）里也能用。
 */
object Images {
    /** 解出来的位图（软件位图，能直接压缩），[sourceWidth]×[sourceHeight] 是原图的大小。 */
    class Decoded(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)

    /** 把 [width]×[height] 等比缩到最长边不超过 [edge]，不放大；四舍五入，至少 1。 */
    fun fitWithin(width: Int, height: Int, edge: Int): Pair<Int, Int> =
        ImageCodec.fitWithin(width, height, edge)

    /** 解码并缩到最长边不超过 [edge]：解码时就按目标大小解，不先解出整张原图。动图取第一帧。解不开抛出异常。 */
    fun decodeWithin(source: ImageDecoder.Source, edge: Int): Decoded {
        var width = 0
        var height = 0
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                width = info.size.width
                height = info.size.height
                val (w, h) = fitWithin(width, height, edge)
                if (w != width || h != height) decoder.setTargetSize(w, h)
            }
        return Decoded(bitmap, width, height)
    }

    fun decodeWithin(bytes: ByteArray, edge: Int): Decoded =
        decodeWithin(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), edge)

    /** 已经在内存里的位图等比缩到最长边不超过 [edge]；本来就不大的原样返回。 */
    fun scaleWithin(bitmap: Bitmap, edge: Int): Bitmap {
        val (w, h) = fitWithin(bitmap.width, bitmap.height, edge)
        return if (w == bitmap.width && h == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    /** 压成 JPEG。JPEG 没有透明，透明的地方先垫白底，不然会成黑色。 */
    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val opaque =
            if (!bitmap.hasAlpha()) bitmap
            else {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            }
        return ByteArrayOutputStream()
            .also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            .toByteArray()
    }
}

/** 从图片数据的开头认出类型（image/jpeg、image/png、image/gif、image/webp）；认不出返回 null。 */
fun imageMediaType(bytes: ByteArray): String? {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    fun ascii(index: Int, text: String) = text.indices.all { at(index + it) == text[it].code }
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
        ascii(0, "GIF8") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        else -> null
    }
}
