package com.dzshzx.zeta.core

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

// 手机上把 [ImageExport] 存进相册、交给系统分享面板。认类型、起文件名在 shared 的 ImageExport.kt。
// 存进系统相册的 Pictures/Zeta（Android 10 起应用写自己的条目不用存储权限）；分享经本包的 FileProvider（authority 是
// `<包名>.images`，只开放缓存里的 [ShareDir]）交给系统分享面板。

/** 分享时把图写到缓存里的这个目录，FileProvider 只开放它（`res/xml/zeta_shared_images.xml`）。 */
private const val ShareDir = "shared_images"

/** 相册里放 Zeta 存的图的目录（Environment.DIRECTORY_PICTURES 下的 Zeta）。 */
private const val GalleryDir = "Pictures/Zeta"

/** 存进系统相册（Pictures/Zeta），成了返回 true。写到一半失败时删掉建了一半的条目。阻塞。 */
fun ImageExport.saveToGallery(context: Context): Boolean = runCatching {
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
fun ImageExport.writeShareFile(context: Context): File {
    val dir = File(context.cacheDir, ShareDir)
    dir.deleteRecursively()
    dir.mkdirs()
    return File(dir, fileName).apply { writeBytes(bytes) }
}

/** 用 [file]（[writeShareFile] 写的）打开系统分享面板，成了返回 true。要在主线程调。 */
fun ImageExport.share(context: Context, file: File): Boolean = runCatching {
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
