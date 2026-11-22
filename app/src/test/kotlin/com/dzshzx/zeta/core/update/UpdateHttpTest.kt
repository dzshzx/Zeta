package com.dzshzx.zeta.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 取清单、下载 APK 的镜像回退：JVM 里起两个本地 HTTP 服务器，一个当 GitHub，一个当 gh-proxy（路径是完整的原地址， 和 gh-proxy.com 一样）。GitHub
 * 那个可以换成超时不应答、连不上（端口没人听）。
 */
class UpdateHttpTest {
    /** 一个假服务器：路径 → (状态码, 内容)；没登记的路径 404。记下收到的每个请求路径。 */
    private class Fake {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val routes = HashMap<String, Pair<Int, ByteArray>>()
        val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
        val base
            get() = "http://127.0.0.1:${server.address.port}/"

        init {
            server.createContext("/") { exchange: HttpExchange ->
                val path = exchange.requestURI.rawPath
                hits += path
                val (code, body) = routes[path] ?: (404 to "not found".toByteArray())
                exchange.sendResponseHeaders(code, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }
    }

    private lateinit var github: Fake
    private lateinit var proxy: Fake
    private val closers = ArrayList<() -> Unit>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        github = Fake()
        proxy = Fake()
        dir = Files.createTempDirectory("zeta-update").toFile()
    }

    @After
    fun tearDown() {
        github.server.stop(0)
        proxy.server.stop(0)
        closers.forEach { it() }
        dir.deleteRecursively()
    }

    private fun http(mirrored: String = github.base) =
        UpdateHttp(
            userAgent = "Zeta/test",
            mirrors = Mirrors(mirrored = mirrored, proxy = proxy.base),
            connectTimeoutMs = 1_000,
            readTimeoutMs = 500,
        )

    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val apkSha = UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(apk))

    private fun manifestJson(base: String, sha: String = apkSha, size: Int = apk.size) =
        """
        {"versionCode": 905, "versionName": "0.7.2", "notes": "- 新的", "apk": "Zeta-v0.7.2.apk",
         "url": "${base}releases/download/v0.7.2/Zeta-v0.7.2.apk", "size": $size, "sha256": "$sha"}
    """
            .trimIndent()

    private val manifestPath = "/releases/latest/download/update.json"
    private val apkPath = "/releases/download/v0.7.2/Zeta-v0.7.2.apk"

    /** 同一个文件在 gh-proxy 上的路径：前面是完整的原地址。 */
    private fun viaProxy(path: String) = "/" + github.base + path.removePrefix("/")

    private fun reason(block: suspend () -> Unit): String =
        try {
            runBlocking { block() }
            fail("应当失败")
            ""
        } catch (e: UpdateException) {
            e.reason
        }

    @Test
    fun githubAddressesFallBackToTheProxyNothingElseDoes() {
        val mirrors = Mirrors()
        assertEquals(
            listOf(
                "https://github.com/dzshzx/Zeta/releases/latest/download/update.json",
                "https://gh-proxy.com/https://github.com/dzshzx/Zeta/releases/latest/download/update.json",
            ),
            mirrors.candidates(
                "https://github.com/dzshzx/Zeta/releases/latest/download/update.json",
            ),
        )
        assertEquals(
            listOf("http://10.0.2.2:8765/update.json"),
            mirrors.candidates("http://10.0.2.2:8765/update.json"),
        )
    }

    @Test
    fun manifestComesStraightFromGithubWhenItAnswers() {
        github.routes[manifestPath] = 200 to manifestJson(github.base).toByteArray()
        val m = runBlocking { http().manifest(github.base + manifestPath.removePrefix("/")) }
        assertEquals(905, m.versionCode)
        assertEquals(emptyList<String>(), proxy.hits)
    }

    @Test
    fun manifestFallsBackToTheProxyOn404() {
        proxy.routes[viaProxy(manifestPath)] = 200 to manifestJson(github.base).toByteArray()
        val m = runBlocking { http().manifest(github.base + manifestPath.removePrefix("/")) }
        assertEquals(905, m.versionCode)
        assertEquals(listOf(manifestPath), github.hits)
        assertEquals(listOf(viaProxy(manifestPath)), proxy.hits)
    }

    @Test
    fun manifestFallsBackToTheProxyWhenGithubDoesNotAnswer() {
        // 接受连接、一直不回：读超时（500 毫秒）后换 gh-proxy
        val silent = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val held = Collections.synchronizedList(ArrayList<Socket>())
        thread(isDaemon = true) { runCatching { while (true) held += silent.accept() } }
        closers += {
            silent.close()
            held.forEach { it.close() }
        }
        val base = "http://127.0.0.1:${silent.localPort}/"
        proxy.routes["/" + base + manifestPath.removePrefix("/")] =
            200 to manifestJson(base).toByteArray()
        val m = runBlocking {
            http(mirrored = base).manifest(base + manifestPath.removePrefix("/"))
        }
        assertEquals(905, m.versionCode)
    }

    @Test
    fun manifestFallsBackWhenGithubCannotBeReachedOrSendsSomethingElse() {
        // 端口没人听
        val closed = ServerSocket(0).use { it.localPort }
        val base = "http://127.0.0.1:$closed/"
        proxy.routes["/" + base + manifestPath.removePrefix("/")] =
            200 to manifestJson(base).toByteArray()
        assertEquals(
            905,
            runBlocking { http(mirrored = base).manifest(base + manifestPath.removePrefix("/")) }
                .versionCode,
        )
        // 回了 200 却不是清单（比如被网络劫持成网页）
        github.routes[manifestPath] = 200 to "<html>login</html>".toByteArray()
        proxy.routes[viaProxy(manifestPath)] = 200 to manifestJson(github.base).toByteArray()
        assertEquals(
            905,
            runBlocking { http().manifest(github.base + manifestPath.removePrefix("/")) }
                .versionCode,
        )
    }

    @Test
    fun whenEveryAddressFailsEachOneSaysWhy() {
        proxy.routes[viaProxy(manifestPath)] = 502 to "bad gateway".toByteArray()
        val why = reason { http().manifest(github.base + manifestPath.removePrefix("/")) }
        val g = UpdateHttp.host(github.base)
        val p = UpdateHttp.host(proxy.base)
        assertEquals("$g 回了 HTTP 404；$p 回了 HTTP 502", why)
        // 不是 GitHub 的地址只试它自己
        val other = "http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/update.json"
        assertEquals("连不上 ${UpdateHttp.host(other)}", reason { http().manifest(other) })
        // 内容不对时说是哪一处给的、哪一项不对
        github.routes[manifestPath] = 200 to manifestJson(github.base, sha = "nope").toByteArray()
        proxy.routes.remove(viaProxy(manifestPath))
        assertEquals(
            "$g 给的更新清单格式不对：sha256 缺少或不是 64 位小写十六进制；$p 回了 HTTP 404",
            reason { http().manifest(github.base + manifestPath.removePrefix("/")) },
        )
    }

    @Test
    fun downloadChecksSizeAndSha256AndReportsProgress() {
        github.routes[apkPath] = 200 to apk
        val m = UpdateManifest.parse(manifestJson(github.base))
        val progress = ArrayList<Long>()
        val file = runBlocking {
            http().download(m.apkPackage, File(dir, m.apk)) { progress += it }
        }
        assertTrue(file.readBytes().contentEquals(apk))
        assertEquals(apk.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress)
        assertFalse(File(dir, m.apk + ".part").exists())
        assertEquals(emptyList<String>(), proxy.hits)
    }

    @Test
    fun aCorruptDownloadIsDroppedAndTheProxyTried() {
        github.routes[apkPath] = 200 to apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
        proxy.routes[viaProxy(apkPath)] = 200 to apk
        val m = UpdateManifest.parse(manifestJson(github.base))
        val file = runBlocking { http().download(m.apkPackage, File(dir, m.apk)) {} }
        assertTrue(file.readBytes().contentEquals(apk))
        assertEquals(listOf(viaProxy(apkPath)), proxy.hits)
    }

    @Test
    fun downloadsThatDoNotMatchTheManifestLeaveNothingBehind() {
        val g = UpdateHttp.host(github.base)
        val p = UpdateHttp.host(proxy.base)
        val m = UpdateManifest.parse(manifestJson(github.base))
        val dest = File(dir, m.apk)
        // sha256 不对
        github.routes[apkPath] = 200 to apk.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertEquals(
            "从 $g 下的文件 sha256 和清单不一致，已删掉；$p 回了 HTTP 404",
            reason { http().download(m.apkPackage, dest) {} },
        )
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        // 比清单写的大：下到超出就停
        github.routes[apkPath] = 200 to apk + ByteArray(10)
        assertTrue(
            reason { http().download(m.apkPackage, dest) {} }
                .startsWith("从 $g 下的文件比清单写的 ${apk.size} 字节大，已删掉"),
        )
        // 比清单写的小
        github.routes[apkPath] = 200 to apk.copyOf(1000)
        assertTrue(
            reason { http().download(m.apkPackage, dest) {} }
                .startsWith("从 $g 下的文件只有 1000 字节，清单写的是 ${apk.size}，已删掉"),
        )
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
