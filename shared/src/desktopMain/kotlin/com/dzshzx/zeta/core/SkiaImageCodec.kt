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
    override fun decodeWithin(bytes: ByteArray, edge: Int): ImageBitmap {
        val image = Image.makeFromEncoded(bytes)
        val (width, height) = ImageCodec.fitWithin(image.width, image.height, edge)
        val bitmap = Bitmap().apply { allocN32Pixels(width, height) }
        Canvas(bitmap)
            .drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(width.toFloat(), height.toFloat()),
                SamplingMode.LINEAR,
                null,
                true,
            )
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }
}
