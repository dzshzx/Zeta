package com.dzshzx.zeta.core

import androidx.compose.ui.graphics.ImageBitmap
import java.util.ServiceLoader
import kotlin.math.roundToInt

/**
 * 把图片文件的字节解成界面能画的图：手机上用 Android 的 ImageDecoder，桌面上用 Skia。各平台的实现登记在
 * META-INF/services/com.dzshzx.zeta.core.ImageCodec 里，第一次用时经 [ServiceLoader] 取。
 */
interface ImageCodec {
    /** 解 [bytes]，长边超过 [edge] 像素时等比缩小到 [edge]。解不开就抛出。 */
    fun decodeWithin(bytes: ByteArray, edge: Int): ImageBitmap

    companion object {
        /** 把 [width]×[height] 等比缩到最长边不超过 [edge]，不放大；四舍五入，至少 1。 */
        fun fitWithin(width: Int, height: Int, edge: Int): Pair<Int, Int> {
            val longest = maxOf(width, height)
            if (longest <= edge || longest <= 0) return width to height
            val scale = edge.toDouble() / longest
            return (width * scale).roundToInt().coerceAtLeast(1) to
                (height * scale).roundToInt().coerceAtLeast(1)
        }

        val current: ImageCodec by lazy {
            ServiceLoader.load(ImageCodec::class.java, ImageCodec::class.java.classLoader)
                .firstOrNull()
                ?: error("没有登记图片解码的实现：META-INF/services/${ImageCodec::class.java.name}")
        }
    }
}
