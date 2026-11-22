package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.update.UpdateException
import com.dzshzx.zeta.core.update.UpdateHttp
import com.dzshzx.zeta.core.update.UpdateManifest
import com.dzshzx.zeta.core.update.UpdatePackage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 桌面版的应用内更新：清单里桌面版那几项怎么读，检查、下载、安装的状态怎么走。下载走真的 [UpdateHttp]（本地 HTTP 服务器），安装器是假的， 只记下要装哪个文件。 */
class DesktopUpdatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val msiBytes = ByteArray(400_000) { (it * 7 % 253).toByte() }
    private val msiSha = UpdateHttp.hex(MessageDigest.getInstance("SHA-256").digest(msiBytes))

    private lateinit var server: HttpServer
    private val routes = HashMap<String, ByteArray>()
    private val base
        get() = "http://127.0.0.1:${server.address.port}"

    private lateinit var settings: Settings
    private lateinit var dir: File
    private val installed = ArrayList<File>()
    private var installResult: String? = null
    private var exits = 0
    private val notices = ArrayList<String>()
    private var clock = 1_800_000_000_000L

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = routes[exchange.requestURI.rawPath]
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        settings = DesktopHubPlatform(tmp.newFolder("files")).settings
        dir = tmp.newFolder("update")
    }

    @After fun tearDown() = server.stop(0)

    private fun manifestJson(
        versionCode: Int = 905,
        desktop: String =
            """, "msi": "Zeta-v0.7.6.msi", "msiUrl": "$base/Zeta-v0.7.6.msi", "msiSize": ${msiBytes.size}, "msiSha256": "$msiSha"""",
    ) =
        """{"versionCode": $versionCode, "versionName": "0.7.6", "notes": "- feat(desktop): 自动更新",
           "publishedAt": "2026-11-22T08:00:00Z", "apk": "Zeta-v0.7.6.apk", "url": "$base/Zeta-v0.7.6.apk",
           "size": 13000000, "sha256": "${"ab".repeat(32)}"$desktop}"""

    private fun updates(
        version: DesktopVersion = DesktopVersion(900, "0.7.5"),
        feed: UpdateFeed = HttpUpdateFeed(version, "$base/update.json"),
    ) =
        DesktopUpdates(
            settings = settings,
            version = version,
            dir = dir,
            feed = feed,
            installer = {
                installed += it
                installResult
            },
            notify = { notices += it },
            exit = { exits++ },
            scope = CoroutineScope(Dispatchers.Unconfined),
            now = { clock },
        )

    private fun publish(json: String = manifestJson(), msi: ByteArray = msiBytes) {
        routes["/update.json"] = json.toByteArray()
        routes["/Zeta-v0.7.6.msi"] = msi
    }

    private fun await(block: () -> kotlinx.coroutines.Job?) = runBlocking {
        withTimeout(10_000) { block()?.join() }
    }

    @Test
    fun theManifestHasADesktopPackageOnlyWhenAllFourFieldsAreRight() {
        val m = UpdateManifest.parse(manifestJson())
        assertEquals(
            UpdatePackage(
                "Zeta-v0.7.6.msi",
                "$base/Zeta-v0.7.6.msi",
                msiBytes.size.toLong(),
                msiSha,
            ),
            m.msi,
        )
        // v0.7.5 及更早的清单没有这几项
        assertNull(UpdateManifest.parse(manifestJson(desktop = "")).msi)
        val good =
            mapOf(
                "msi" to "\"Zeta-v0.7.6.msi\"",
                "msiUrl" to "\"$base/Zeta-v0.7.6.msi\"",
                "msiSize" to "${msiBytes.size}",
                "msiSha256" to "\"$msiSha\"",
            )
        val bad =
            listOf(
                "msi" to "\"../Zeta.msi\"",
                "msi" to "\"Zeta-v0.7.6.exe\"",
                "msiUrl" to "\"file:///C:/Zeta.msi\"",
                "msiSize" to "0",
                "msiSize" to "\"400000\"",
                "msiSha256" to "\"${msiSha.uppercase()}\"",
            )
        for ((key, value) in bad) {
            val fields = good + (key to value)
            val json = fields.entries.joinToString("") { ", \"${it.key}\": ${it.value}" }
            val parsed = UpdateManifest.parse(manifestJson(desktop = json))
            assertNull("$key = $value", parsed.msi)
            assertEquals("手机读到的照旧", 905, parsed.versionCode)
        }
        for (missing in good.keys) {
            val json = (good - missing).entries.joinToString("") { ", \"${it.key}\": ${it.value}" }
            assertNull("缺 $missing", UpdateManifest.parse(manifestJson(desktop = json)).msi)
        }
    }

    @Test
    fun developmentBuildsNeverCheck() {
        publish()
        val dev = updates(DesktopVersion(905, "0.7.6-dev.3+gabc1234"))
        assertFalse(dev.enabled)
        assertNull(dev.onStart())
        assertNull(dev.check())
        assertEquals(DesktopUpdateState.Idle, dev.state)
        assertFalse(DesktopVersion.Unknown.isRelease)
    }

    @Test
    fun startupChecksOncePerDayAndAnnouncesANewVersionOnce() {
        publish()
        val first = updates()
        await { first.onStart() }
        val available = first.state as DesktopUpdateState.Available
        assertEquals(905, available.manifest.versionCode)
        assertEquals(listOf("Zeta 0.7.6 已发布，可在设置中更新"), notices)
        // 同一天再启动不查
        routes.remove("/update.json")
        val again = updates()
        await { again.onStart() }
        assertEquals(DesktopUpdateState.Idle, again.state)
        // 第二天再查，同一个版本不再提示
        publish()
        clock += 24 * 3600 * 1000L
        val nextDay = updates()
        await { nextDay.onStart() }
        assertTrue(nextDay.state is DesktopUpdateState.Available)
        assertEquals(1, notices.size)
        // 关了自动检查就只能手动查
        settings.autoUpdateCheck = false
        clock += 24 * 3600 * 1000L
        val off = updates()
        await { off.onStart() }
        assertEquals(DesktopUpdateState.Idle, off.state)
        await { off.check() }
        assertTrue(off.state is DesktopUpdateState.Available)
    }

    @Test
    fun onlyANewerVersionWithAnMsiCounts() {
        publish(manifestJson(desktop = ""))
        val u = updates()
        await { u.check() }
        assertEquals(DesktopUpdateState.UpToDate, u.state)
        publish(manifestJson(versionCode = 900))
        await { u.check() }
        assertEquals(DesktopUpdateState.UpToDate, u.state)
        routes.clear()
        await { u.check() }
        val failed = u.state as DesktopUpdateState.CheckFailed
        assertEquals("${UpdateHttp.host(base)} 回了 HTTP 404", failed.reason)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun downloadsVerifiesInstallsAndExits() {
        publish()
        val u = updates()
        await { u.check() }
        await { u.start() }
        assertEquals(DesktopUpdateStep.Installing, (u.state as DesktopUpdateState.Available).step)
        assertEquals(listOf(File(dir, "Zeta-v0.7.6.msi")), installed)
        assertTrue(installed.single().readBytes().contentEquals(msiBytes))
        assertEquals(1, exits)
    }

    @Test
    fun aCorruptDownloadIsDeletedAndCanBeRetried() {
        publish(msi = msiBytes.copyOf().also { it[10] = (it[10] + 1).toByte() })
        val u = updates()
        await { u.check() }
        await { u.start() }
        val step = (u.state as DesktopUpdateState.Available).step as DesktopUpdateStep.Failed
        assertEquals("从 ${UpdateHttp.host(base)} 下的文件 sha256 和清单不一致，已删掉", step.reason)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertTrue(installed.isEmpty())
        assertEquals(0, exits)
        // 重试：这次下对了
        publish()
        await { u.start() }
        assertEquals(1, installed.size)
        assertEquals(1, exits)
    }

    @Test
    fun anInstallerThatCannotStartLeavesZetaRunning() {
        publish()
        installResult = "只有 Windows 版能自动安装"
        val u = updates()
        await { u.check() }
        await { u.start() }
        assertEquals(
            DesktopUpdateStep.Failed("只有 Windows 版能自动安装"),
            (u.state as DesktopUpdateState.Available).step,
        )
        assertEquals(0, exits)
        // 已经下好的文件不再下：服务器上没了照样能重试
        routes.remove("/Zeta-v0.7.6.msi")
        installResult = null
        await { u.start() }
        assertEquals(2, installed.size)
        assertEquals(1, exits)
    }

    @Test
    fun cancellingADownloadGoesBackToReady() {
        val manifest = UpdateManifest.parse(manifestJson())
        val halfway = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        val feed =
            object : UpdateFeed {
                override suspend fun manifest() = manifest

                override suspend fun download(
                    pkg: UpdatePackage,
                    dest: File,
                    onProgress: (Long) -> Unit,
                ): File {
                    onProgress(pkg.size / 2)
                    halfway.complete(Unit)
                    hold.await()
                    throw UpdateException("不该走到这里")
                }
            }
        val u = updates(feed = feed)
        await { u.check() }
        assertNotNull(u.start())
        assertNull("正在下载时再点不另起一个", u.start())
        runBlocking { withTimeout(10_000) { halfway.await() } }
        val downloading =
            (u.state as DesktopUpdateState.Available).step as DesktopUpdateStep.Downloading
        assertEquals(msiBytes.size / 2L, downloading.done)
        u.cancel()
        assertEquals(DesktopUpdateStep.Ready, (u.state as DesktopUpdateState.Available).step)
        assertTrue(installed.isEmpty())
    }
}
