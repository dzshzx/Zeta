package com.dzshzx.zeta.core

import android.net.Uri
import android.provider.DocumentsContract

/** 选系统文件选择器给的目录：只认内部存储（primary），返回 /storage/emulated/<用户>/... 这样的真实路径。 */
fun SharedFolders.fromTreeUri(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    if (!id.startsWith("primary:")) return null
    val rest = id.removePrefix("primary:").trim('/')
    return normalize("/storage/emulated/${LinuxEnv.androidUser}/$rest")
}
