package com.dzshzx.zeta.desktop

import androidx.compose.ui.graphics.asComposeImageBitmap
import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.SkiaImageCodec
import com.dzshzx.zeta.core.Uploads
import com.dzshzx.zeta.core.cleanName
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** 桌面上选的一个本机文件。 */
class DesktopFile(val file: File) : PickedFile {
    override fun describe(): Pair<String, String?> {
        val name = Uploads.cleanName(file.name)
        val type =
            runCatching { Files.probeContentType(file.toPath()) }.getOrNull()
                ?: URLConnection.guessContentTypeFromName(name)
        return name to type
    }

    override fun size(): Long? = file.takeIf { it.isFile }?.length()

    override fun open(): InputStream? = runCatching { file.inputStream() }.getOrNull()

    override fun loadImage(): Attachment? = runCatching {
        jpegAttachment(file.readBytes())
    }
        .getOrNull()

    private companion object {
        /** 一张图片的字节缩到 [Attachments.MAX_EDGE] 以内、编码成 JPEG 附件，界面用的小图从缩好的图再缩，不再解一遍 JPEG。解不开时抛出。 */
        fun jpegAttachment(bytes: ByteArray): Attachment {
            val scaled = SkiaImageCodec.decode(bytes, Attachments.MAX_EDGE)
            return Image.makeFromBitmap(scaled).use { image ->
                val jpeg =
                    checkNotNull(image.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)) {
                            "编码 JPEG 失败"
                        }
                        .use { it.bytes }
                val preview = SkiaImageCodec.scale(image, Attachments.PREVIEW_EDGE)
                Attachment(
                    UUID.randomUUID().toString(),
                    preview.asComposeImageBitmap(),
                    "image/jpeg",
                    Base64.getEncoder().encodeToString(jpeg),
                )
            }
        }

        const val JPEG_QUALITY = 85
    }
}
