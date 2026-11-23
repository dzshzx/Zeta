package com.dzshzx.zeta.core

import androidx.collection.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import java.util.Base64
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 聊天里图片的按需载入与缓存。会话记录和 Linux 里的图片从对话所在的主机（[Backends]）取回（[fetch]），缓存按主机分开：两台主机上同一个路径是两张图。 */
object ImageStore {
    /** 显示用的长边上限；原图再大也只解到这么大。 */
    private const val MAX_EDGE = 2048
    private const val MAX_DOWNLOAD = 12L * 1024 * 1024

    private val cache =
        object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ImageBitmap): Int =
                value.width * value.height * 4
        }

    /** 解码前的原始字节，和 [cache] 同一个键：看大图时保存到相册、分享用的就是显示的这一份，不另去取。 */
    private val originals =
        object : LruCache<String, ByteArray>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
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

    fun cached(ref: ImageRef, backend: String): ImageBitmap? = cache.get(cacheKey(ref, backend))

    /** 取到过的这张图的原始字节；没取过、没取到或已被挤出缓存时为 null。 */
    fun original(ref: ImageRef, backend: String): ByteArray? = originals.get(cacheKey(ref, backend))

    /** 会话记录、文件里的图片按主机分开缓存；内嵌的、网络上的哪台主机都一样。 */
    private fun cacheKey(ref: ImageRef, backend: String): String =
        when (ref) {
            is SessionImage,
            is FileImage,
            is CoreImage -> backend + "|" + ref.key
            else -> ref.key
        }

    /** 内存里缓存的图片一共多少字节（存储页显示）：解码后的图加原始字节。 */
    fun memoryBytes(): Long = cache.size().toLong() + originals.size()

    /** 存储页的清理：清空内存里的图片、原始字节和“取不到”的记录。正在显示的图片不受影响，之后需要时重新取。 */
    fun clearMemory() {
        cache.evictAll()
        originals.evictAll()
        failed.clear()
    }

    suspend fun load(hub: ConversationHub, ref: ImageRef, backend: String): ImageBitmap? {
        val key = cacheKey(ref, backend)
        cache.get(key)?.let {
            return it
        }
        if (key in failed) return null
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { fetchBytes(hub, ref, backend)?.let { it to decode(it) } }.getOrNull()
            }
        if (loaded == null) {
            failed.add(key)
            return null
        }
        originals.put(key, loaded.first)
        cache.put(key, loaded.second)
        return loaded.second
    }

    /** 取一张图的原始字节：网络图下载，会话记录和文件里的从主机取（[fetch]），内嵌的直接解 base64。阻塞。 */
    private fun fetchBytes(hub: ConversationHub, ref: ImageRef, backend: String): ByteArray? {
        if (ref is RemoteImage) return download(ref.url)
        val data =
            when (ref) {
                is InlineImage -> ref.base64
                is SessionImage,
                is FileImage,
                is CoreImage -> fetch(hub, backend, ref)?.str("data")
                is RemoteImage -> null
            } ?: return null
        return Base64.getMimeDecoder().decode(data)
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

    /**
     * 让主机 [backend] 取一张会话记录里的（[SessionImage]，核心的 `sessions.image`）、核心里对话条目中只带键的（[CoreImage]，核心的
     * `conv.image`）或主机上文件里的（[FileImage]）图： 应答里有 media_type 和 base64 的 data；没取到、别的种类是 null。阻塞。
     *
     * 手机本机的图片文件仍各起一次 zeta-helper 的 file-image：核心只看得到它启动时挂着的共享文件夹（/mnt/shared 下），增删后才换
     * （core-takeover）；单起的进程进来时现挂，看到的总是现在的（docs/core.md「存储与文件」）。
     */
    fun fetch(hub: ConversationHub, backend: String, ref: ImageRef): JsonObject? =
        when (ref) {
            is SessionImage ->
                fromCore(
                    hub,
                    backend,
                    "sessions.image",
                    buildJsonObject {
                        put("file", ref.file)
                        put("ref", ref.ref)
                    },
                )
            is CoreImage ->
                fromCore(
                    hub,
                    backend,
                    "conv.image",
                    buildJsonObject {
                        put("key", ref.conv)
                        put("image", ref.image)
                    },
                )
            is FileImage ->
                if (backend == Backends.LOCAL) fromHelper(hub, "file-image", listOf(ref.path))
                else
                    fromCore(hub, backend, "images.file", buildJsonObject { put("path", ref.path) })
            else -> null
        }

    private fun fromCore(
        hub: ConversationHub,
        backend: String,
        method: String,
        params: JsonObject,
    ): JsonObject? =
        runCatching { hub.callCore(backend, method, params) }.getOrNull() as? JsonObject

    private fun fromHelper(
        hub: ConversationHub,
        subcommand: String,
        arguments: List<String>,
    ): JsonObject? {
        val output =
            hub.helper(subcommand, arguments, timeoutMs = 60_000, backend = Backends.LOCAL)
                .outputOrNull ?: return null
        val line = output.lineSequence().firstOrNull { it.startsWith("{") } ?: return null
        return json.parseToJsonElement(line) as? JsonObject
    }

    private fun decode(bytes: ByteArray): ImageBitmap =
        ImageCodec.current.decodeWithin(bytes, MAX_EDGE)
}
