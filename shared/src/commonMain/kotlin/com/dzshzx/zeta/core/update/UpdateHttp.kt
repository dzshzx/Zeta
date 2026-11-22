package com.dzshzx.zeta.core.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 一个地址该从哪几处取：GitHub 上的先直连，不通再走 gh-proxy（地址前面加上它），和 assets/zeta_setup_debian.sh 下载 GitHub
 * 文件的做法一样。别的地址（测试版的调试覆盖、测试里的本地服务器）只有它自己。 gh-proxy 是第三方的中转：从它取的清单和安装包照样要核对（大小、sha256 对清单；手机上还核对 APK
 * 的包名、版本、签名对已装的 Zeta，见 app 的 ApkCheck）。
 */
class Mirrors(
    private val mirrored: String = GITHUB,
    private val proxy: String = GH_PROXY,
) {
    fun candidates(url: String): List<String> =
        if (url.startsWith(mirrored)) listOf(url, proxy + url) else listOf(url)

    companion object {
        const val GITHUB = "https://github.com/"
        const val GH_PROXY = "https://gh-proxy.com/"
    }
}

/**
 * 取更新清单、下载安装包（手机的 APK、桌面版的 MSI）：每个地址按 [Mirrors] 给的顺序试，前一个连不上、超时、HTTP 出错或内容不对就换下一个。 都不行时抛
 * [UpdateException]，原因里每个地址各一句。只用 [HttpURLConnection]，走系统的代理设置。
 */
class UpdateHttp(
    private val userAgent: String,
    private val mirrors: Mirrors = Mirrors(),
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) {
    /** 取清单 [url] 并解析（[UpdateManifest.parse]）。 */
    suspend fun manifest(url: String): UpdateManifest =
        withContext(Dispatchers.IO) {
            attempts(url) { candidate ->
                val text = get(candidate) { input -> readLimited(input, UpdateManifest.MAX_BYTES) }
                try {
                    UpdateManifest.parse(text)
                } catch (e: UpdateException) {
                    throw UpdateException("${host(candidate)} 给的${e.reason}")
                }
            }
        }

    /**
     * 下载清单写的安装包 [pkg] 到 [dest]：先写到旁边的 `.part`，大小和 sha256 都对上清单才改名成 [dest]；对不上就删掉，
     * 换下一个地址重下。[onProgress] 报已下载的字节数（在下载的线程上调）。
     */
    suspend fun download(pkg: UpdatePackage, dest: File, onProgress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val part = File(dest.path + ".part")
            try {
                attempts(pkg.url) { candidate ->
                    downloadFrom(candidate, pkg, part, dest, onProgress)
                }
            } finally {
                part.delete()
            }
        }

    /** 从一个地址 [candidate] 下载到 [part]，核对大小和 sha256 后改名成 [dest]；对不上时抛 [UpdateException]。 */
    private suspend fun downloadFrom(
        candidate: String,
        pkg: UpdatePackage,
        part: File,
        dest: File,
        onProgress: (Long) -> Unit,
    ): File {
        part.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val context = coroutineContext
        val total =
            get(candidate) { input ->
                part.outputStream().use { out ->
                    copyChecked(context, input, out, digest, pkg.size, candidate, onProgress)
                }
            }
        if (total != pkg.size)
            throw UpdateException(
                "从 ${host(candidate)} 下的文件只有 $total 字节，清单写的是 ${pkg.size}，已删掉",
            )
        if (hex(digest.digest()) != pkg.sha256)
            throw UpdateException("从 ${host(candidate)} 下的文件 sha256 和清单不一致，已删掉")
        dest.delete()
        if (!part.renameTo(dest)) throw UpdateException("存不下下载的文件")
        return dest
    }

    /** 把 [input] 抄进 [out]，边抄边算 [digest]、报进度；超过清单的 [size] 字节就抛错。返回抄了多少字节。 */
    private fun copyChecked(
        context: CoroutineContext,
        input: InputStream,
        out: OutputStream,
        digest: MessageDigest,
        size: Long,
        candidate: String,
        onProgress: (Long) -> Unit,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            context.ensureActive()
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            if (total > size) throw UpdateException("从 ${host(candidate)} 下的文件比清单写的 $size 字节大，已删掉")
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            onProgress(total)
        }
    }

    /** 按 [Mirrors] 给的顺序对每个地址做 [block]，第一个成功的就是结果；都失败时把每个地址的原因连起来抛出。 */
    private suspend fun <T> attempts(url: String, block: suspend (String) -> T): T {
        val reasons = ArrayList<String>()
        for (candidate in mirrors.candidates(url)) {
            coroutineContext.ensureActive()
            try {
                return block(candidate)
            } catch (e: UpdateException) {
                reasons += e.reason
            } catch (e: IOException) {
                reasons += network(candidate, e)
            }
        }
        throw UpdateException(reasons.joinToString("；"))
    }

    private fun <T> get(url: String, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true // GitHub 的下载地址会跳到 objects.githubusercontent.com
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK)
                throw UpdateException("${host(url)} 回了 HTTP $code")
            return connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw UpdateException("更新清单比 ${limit / 1024} KB 还大，不像是清单")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun network(url: String, e: IOException): String =
        when (e) {
            is SocketTimeoutException -> "连 ${host(url)} 超时"
            is UnknownHostException -> "找不到 ${host(url)}（没有网络？）"
            else -> "连不上 ${host(url)}"
        }

    companion object {
        /** 地址里的主机（带端口），原因里说是哪一处。 */
        fun host(url: String): String =
            runCatching { URL(url).authority }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** 文件的 SHA-256（小写十六进制），边读边算。 */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }
    }
}
