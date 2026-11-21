package com.dzshzx.zeta.ui.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** 把 [text]（UTF-8）写进用户在系统“保存到”里选的文件 [uri]。没写成时把系统已经建好的那个空文件删掉。 耗时，别在主线程调用。 */
internal fun Context.writeText(uri: Uri, text: String): Boolean {
    val written = runCatching {
        contentResolver.openOutputStream(uri, "w")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
        } != null
    }
        .getOrDefault(false)
    if (!written) discardDocument(uri)
    return written
}

/** 删掉系统为“保存到”建好、最后没写进东西的文件。 */
internal fun Context.discardDocument(uri: Uri) {
    runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }
}
