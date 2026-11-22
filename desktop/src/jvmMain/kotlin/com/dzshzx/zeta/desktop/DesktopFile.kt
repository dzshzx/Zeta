package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.Attachment
import com.dzshzx.zeta.core.Attachments
import com.dzshzx.zeta.core.ImageCodec
import com.dzshzx.zeta.core.PickedFile
import com.dzshzx.zeta.core.Uploads
import com.dzshzx.zeta.core.cleanName
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import java.nio.file.Files
import java.util.UUID
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface

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

    companion object {
        /** 一张图片的字节缩到 [Attachments.MAX_EDGE] 以内、编码成 JPEG 附件；解不开时抛出。 */
        fun jpegAttachment(bytes: ByteArray): Attachment? {
            val image = Image.makeFromEncoded(bytes)
            val (width, height) =
                ImageCodec.fitWithin(image.width, image.height, Attachments.MAX_EDGE)
            val surface = Surface.makeRasterN32Premul(width, height)
            surface.canvas.drawImageRect(
                image,
                org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                org.jetbrains.skia.Rect.makeWH(width.toFloat(), height.toFloat()),
            )
            val jpeg =
                checkNotNull(
                    surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 85),
                ) {
                    "编码 JPEG 失败"
                }
            return Attachments.restore(UUID.randomUUID().toString(), "image/jpeg", jpeg.bytes)
        }
    }
}
