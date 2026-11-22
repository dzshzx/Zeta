package com.dzshzx.zeta.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 看大图时要保存或分享的一张图：查看器显示它用的那份原始字节（[ImageStore.original]），按字节认出的类型和据此起的文件名。 怎么存、怎么分享看平台
 * （[com.dzshzx.zeta.ui.chat.ChatPlatform.rememberImageExporter]）。
 */
class ImageExport(val bytes: ByteArray, val mime: String, val fileName: String) {
    companion object {
        private val Stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** 拿不到字节（还没取到、取不到）或认不出是哪种图时为 null，查看器的保存、分享按钮置灰。 */
        fun of(ref: ImageRef, bytes: ByteArray?, now: LocalDateTime): ImageExport? {
            if (bytes == null || bytes.isEmpty()) return null
            val mime = mimeOf(bytes) ?: return null
            return ImageExport(bytes, mime, fileName(ref, mime, now))
        }

        /**
         * 按文件头认图片类型；认不出返回 null。API 也收的四种照 [imageMediaType]，另认 API 不收、相册收的 BMP 和 HEIF 一族（不加进
         * imageMediaType：它的结果当发给 API 的 media type 用）。
         */
        fun mimeOf(bytes: ByteArray): String? =
            imageMediaType(bytes)
                ?: when {
                    bytes.ascii(0, 2) == "BM" -> "image/bmp"
                    bytes.ascii(4, 4) == "ftyp" -> isoImageMime(bytes.ascii(8, 4))
                    else -> null
                }

        /** HEIF 一族（ftyp 盒子）按主品牌分 AVIF 和 HEIC。 */
        private fun isoImageMime(brand: String): String? =
            when (brand) {
                "avif",
                "avis" -> "image/avif"
                "heic",
                "heix",
                "hevc",
                "heim",
                "heis",
                "mif1",
                "msf1" -> "image/heic"
                else -> null
            }

        /** 文件的扩展名，跟着认出的类型走（文件自带的扩展名可能和内容对不上）。 */
        fun extension(mime: String): String =
            when (mime) {
                "image/jpeg" -> "jpg"
                else -> mime.substringAfter('/')
            }

        /**
         * 保存、分享用的文件名：本机或主机上的文件用文件自己的名字，网络图用地址里的文件名，换上按内容认出的扩展名； 取不出名字（内嵌的图、会话记录里的图、地址里没有文件名）时是
         * `Zeta_年月日_时分秒`。
         */
        fun fileName(ref: ImageRef, mime: String, now: LocalDateTime): String {
            val raw =
                when (ref) {
                    is FileImage -> ref.path.substringAfterLast('/')
                    is RemoteImage ->
                        ref.url
                            .substringBefore('#')
                            .substringBefore('?')
                            .substringAfter("://")
                            .substringAfter('/', "")
                            .substringAfterLast('/')
                    else -> ""
                }
            val base =
                raw.substringBeforeLast('.')
                    .replace(Regex("""[\\/:*?"<>|\s\p{Cntrl}]+"""), "_")
                    .trim('_', '.', ' ')
                    .take(80)
                    .ifEmpty { "Zeta_" + now.format(Stamp) }
            return base + "." + extension(mime)
        }

        private fun ByteArray.ascii(from: Int, length: Int): String =
            if (size < from + length) "" else String(this, from, length, Charsets.ISO_8859_1)
    }
}
