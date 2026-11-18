package com.dzshzx.zeta.core

import android.graphics.ImageDecoder
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 聊天里图片的按需载入与缓存。会话记录和 Linux 环境里的图片经助手脚本取回。 */
object ImageStore {
    /** 显示用的长边上限；原图再大也只解到这么大。 */
    private const val MAX_EDGE = 2048

    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** 取不到的不反复去取（每次都要进一趟 Linux 环境）。 */
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val json = Json { ignoreUnknownKeys = true }

    fun cached(ref: ImageRef): ImageBitmap? = cache.get(ref.key)

    suspend fun load(hub: ConversationHub, ref: ImageRef): ImageBitmap? {
        cache.get(ref.key)?.let { return it }
        if (ref.key in failed) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val data = when (ref) {
                    is InlineImage -> ref.base64
                    is SessionImage -> fetch(hub, "image " + LinuxEnv.quote(ref.file) + " " + LinuxEnv.quote(ref.ref))
                    is FileImage -> fetch(hub, "file-image " + LinuxEnv.quote(ref.path))
                } ?: return@runCatching null
                decode(Base64.decode(data, Base64.DEFAULT))
            }.getOrNull()
        }
        if (bitmap == null) failed.add(ref.key) else cache.put(ref.key, bitmap)
        return bitmap
    }

    private fun fetch(hub: ConversationHub, arguments: String): String? {
        val config = hub.settings.env ?: return null
        val result = LinuxEnv.run(config, "exec python3 - $arguments", stdin = hub.helperScript, timeoutMs = 60_000)
        if (result.exitCode != 0) return null
        val line = result.stdout.lineSequence().firstOrNull { it.startsWith("{") } ?: return null
        return (json.parseToJsonElement(line) as? JsonObject)?.str("data")
    }

    private fun decode(bytes: ByteArray): ImageBitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val edge = max(info.size.width, info.size.height)
            if (edge > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / edge
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }.asImageBitmap()
}
