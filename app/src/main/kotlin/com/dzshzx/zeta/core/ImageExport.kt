package com.dzshzx.zeta.core

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 看大图时要保存到相册或分享的一张图：查看器显示它用的那份原始字节（[ImageStore.original]），按字节认出的类型和据此起的文件名。 存进系统相册的
 * Pictures/Zeta（Android 10 起应用写自己的条目不用存储权限）；分享经本包的 FileProvider（authority 是 `<包名>.images`，只开放缓存里的
 * [ShareDir]）交给系统分享面板。
 */
class ImageExport(val bytes: ByteArray, val mime: String, val fileName: String) {
    companion object {
        /** 分享时把图写到缓存里的这个目录，FileProvider 只开放它（`res/xml/zeta_shared_images.xml`）。 */
        const val ShareDir = "shared_images"

        /** 相册里放 Zeta 存的图的目录（Environment.DIRECTORY_PICTURES 下的 Zeta）。 */
        const val GalleryDir = "Pictures/Zeta"

        private val Stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** 拿不到字节（还没取到、取不到）或认不出是哪种图时为 null，查看器的保存、分享按钮置灰。 */
        fun of(ref: ImageRef, bytes: ByteArray?, now: LocalDateTime): ImageExport? {
            if (bytes == null || bytes.isEmpty()) return null
            val mime = mimeOf(bytes) ?: return null
            return ImageExport(bytes, mime, fileName(ref, mime, now))
        }

        /** 按文件头认图片类型；认不出返回 null。 */
        fun mimeOf(bytes: ByteArray): String? =
            when {
                bytes.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) -> "image/png"
                bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                bytes.ascii(0, 4) == "GIF8" -> "image/gif"
                bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> "image/webp"
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

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

        private fun ByteArray.ascii(from: Int, length: Int): String =
            if (size < from + length) "" else String(this, from, length, Charsets.ISO_8859_1)
    }

    /** 存进系统相册（Pictures/Zeta），成了返回 true。写到一半失败时删掉建了一半的条目。阻塞。 */
    fun saveToGallery(context: Context): Boolean = runCatching {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, GalleryDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return false
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null &&
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null,
                ) > 0
        }
            .getOrDefault(false)
        if (!written) resolver.delete(uri, null, null)
        written
    }
        .getOrDefault(false)

    /** 写进缓存的 [ShareDir]（先清掉上次分享留下的），返回交给 FileProvider 的文件。阻塞。 */
    fun writeShareFile(context: Context): File {
        val dir = File(context.cacheDir, ShareDir)
        dir.deleteRecursively()
        dir.mkdirs()
        return File(dir, fileName).apply { writeBytes(bytes) }
    }

    /** 用 [file]（[writeShareFile] 写的）打开系统分享面板，成了返回 true。要在主线程调。 */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri =
            FileProvider.getUriForFile(
                context,
                AppVariant.current.authority("images"),
                file,
            )
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(fileName, uri)
        val chooser = Intent.createChooser(send, "分享图片")
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    }
        .getOrDefault(false)
}
