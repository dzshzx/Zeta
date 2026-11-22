package com.dzshzx.zeta.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

/** 桌面上的 [ImageCodec]：Skia 解码，太大时画到一张缩小的位图上。登记在 shared 的 desktopMain 资源里。 */
class SkiaImageCodec : ImageCodec {
    override fun decodeWithin(bytes: ByteArray, edge: Int): ImageBitmap =
        decode(bytes, edge).asComposeImageBitmap()

    companion object {
        /** 解码 [bytes]，长边超过 [edge] 时缩小。解不开时抛出。 */
        fun decode(bytes: ByteArray, edge: Int): Bitmap =
            Image.makeFromEncoded(bytes).use { scale(it, edge) }

        /** [image] 画到长边不超过 [edge] 的新位图上（线性采样），本来就不大时原样画一份。 */
        fun scale(image: Image, edge: Int): Bitmap {
            val (width, height) = ImageCodec.fitWithin(image.width, image.height, edge)
            val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
            Canvas(bitmap).use {
                it.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
            }
            bitmap.setImmutable()
            return bitmap
        }
    }
}
