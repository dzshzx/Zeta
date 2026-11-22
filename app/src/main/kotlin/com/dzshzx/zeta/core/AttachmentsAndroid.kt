package com.dzshzx.zeta.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.Base64
import java.util.UUID

// 手机上读附件：从 content:// 地址、截屏的 Bitmap 读图，问文件名、类型和大小。数据类和上传在 shared 的 Attachment.kt。

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
    val clean = name.replace(Regex("[/\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "file" }
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

/**
 * 把文件复制进 [shell] 那台机器（对话所在的主机）的 ~/.cache/zeta/files/<随机>/<文件名>（测试版是 ~/.cache/zeta-dev，见
 * [AppVariant]）。 放在各自的随机目录里，同名文件互不覆盖；助手脚本会清掉七天前的。远程主机经 ssh 的标准输入传过去。 超过 [limit]
 * 字节就停下（事先问不到大小的文件只能边读边数）。耗时，别在主线程调用。
 */
fun Uploads.copy(
    context: Context,
    shell: LinuxShell,
    uri: Uri,
    name: String,
    limit: Long = Long.MAX_VALUE,
): Uploads.Copied = copyFrom(shell, name, limit) { context.contentResolver.openInputStream(uri) }
