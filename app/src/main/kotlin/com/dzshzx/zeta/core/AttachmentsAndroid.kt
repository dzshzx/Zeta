package com.dzshzx.zeta.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID

// 手机上读附件：从 content:// 地址（[UriFile]）、截屏的 Bitmap 读图，问文件名、类型和大小。数据类和上传在 shared 的 Attachment.kt。

/** 读取并压缩一张图片；读不了返回 null。耗时，别在主线程调用。 */
fun Attachments.load(context: Context, uri: Uri): Attachment? = runCatching {
    encodeJpeg(
        Images.decodeWithin(
                ImageDecoder.createSource(context.contentResolver, uri),
                Attachments.MAX_EDGE,
            )
            .bitmap,
    )
}
    .getOrNull()

/** 已经在内存里的一张图（截屏）：缩小、编码成附件。耗时，别在主线程调用。 */
fun Attachments.fromBitmap(source: Bitmap): Attachment? = runCatching {
    encodeJpeg(Images.scaleWithin(source, Attachments.MAX_EDGE))
}
    .getOrNull()

private fun encodeJpeg(bitmap: Bitmap): Attachment {
    val bytes = Images.jpeg(bitmap, 85)
    val preview = Images.scaleWithin(bitmap, Attachments.PREVIEW_EDGE)
    return Attachment(
        UUID.randomUUID().toString(),
        preview.asImageBitmap(),
        "image/jpeg",
        Base64.getEncoder().encodeToString(bytes),
    )
}

/** 文件名和类型；拿不到名字时用地址的最后一段。 */
fun Uploads.describe(context: Context, uri: Uri): Pair<String, String?> {
    val name =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }
            .getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    val clean = Uploads.cleanName(name)
    // file:// 这类地址问不到类型，按扩展名猜
    val type =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(clean.substringAfterLast('.', "").lowercase())
    return clean to type
}

/** 文件有多大；问不到（有的应用不给）时为 null。 */
fun Uploads.size(context: Context, uri: Uri): Long? = runCatching {
    if (uri.scheme == "file") {
        return@runCatching uri.path?.let(::File)?.takeIf { it.isFile && it.canRead() }?.length()
    }
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
    }
}
    .getOrNull()
    ?.takeIf { it >= 0 }

/** 手机上选的、分享来的一个文件：content://（或 file://）地址 [uri]。 */
class UriFile(private val context: Context, val uri: Uri) : PickedFile {
    override fun describe(): Pair<String, String?> = Uploads.describe(context, uri)

    override fun size(): Long? = Uploads.size(context, uri)

    override fun open(): InputStream? = context.contentResolver.openInputStream(uri)

    override fun loadImage(): Attachment? = Attachments.load(context, uri)
}
