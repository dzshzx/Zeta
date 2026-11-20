package com.dzshzx.zeta.core

import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 聊天里图片的按需载入与缓存。会话记录和 Linux 环境里的图片经助手脚本取回。 */
object ImageStore {
    /** 显示用的长边上限；原图再大也只解到这么大。 */
    private const val MAX_EDGE = 2048
    private const val MAX_DOWNLOAD = 12L * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** 取不到的不反复去取（每次都要进一趟 Linux 环境）。 */
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val json = Json { ignoreUnknownKeys = true }

    /** 用户点过“加载”的网络图片。 */
    private val allowed = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun isAllowed(ref: ImageRef): Boolean = allowed[ref.key] == true

    fun allow(ref: ImageRef) {
        failed.remove(ref.key)
        allowed[ref.key] = true
    }

    fun cached(ref: ImageRef): ImageBitmap? = cache.get(ref.key)

    /** 内存里缓存的图片一共多少字节（存储页显示）。 */
    fun memoryBytes(): Long = cache.size().toLong()

    /** 存储页的清理：清空内存里的图片和“取不到”的记录。正在显示的图片不受影响，之后需要时重新取。 */
    fun clearMemory() {
        cache.evictAll()
        failed.clear()
    }

    suspend fun load(hub: ConversationHub, ref: ImageRef): ImageBitmap? {
        cache.get(ref.key)?.let { return it }
        if (ref.key in failed) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                if (ref is RemoteImage) return@runCatching download(ref.url)?.let(::decode)
                val data = when (ref) {
                    is InlineImage -> ref.base64
                    is SessionImage -> fetch(hub, "image", listOf(ref.file, ref.ref))
                    is FileImage -> fetch(hub, "file-image", listOf(ref.path))
                    is RemoteImage -> null
                } ?: return@runCatching null
                decode(Base64.decode(data, Base64.DEFAULT))
            }.getOrNull()
        }
        if (bitmap == null) failed.add(ref.key) else cache.put(ref.key, bitmap)
        return bitmap
    }

    /** 下载一张网络图片，太大的不要。 */
    private fun download(url: String): ByteArray? {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Zeta")
            connection.setRequestProperty("Accept", "image/*")
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > MAX_DOWNLOAD) return null
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
            }
            out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun fetch(hub: ConversationHub, subcommand: String, arguments: List<String>): String? {
        val output = hub.helperOutput(subcommand, arguments, timeoutMs = 60_000) ?: return null
        val line = output.lineSequence().firstOrNull { it.startsWith("{") } ?: return null
        return (json.parseToJsonElement(line) as? JsonObject)?.str("data")
    }

    private fun decode(bytes: ByteArray): ImageBitmap = Images.decodeWithin(bytes, MAX_EDGE).bitmap.asImageBitmap()
}
