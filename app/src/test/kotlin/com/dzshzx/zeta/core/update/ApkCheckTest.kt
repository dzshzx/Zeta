package com.dzshzx.zeta.core.update

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.of
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 下载的包装之前的核对，以及哪个包检查更新、去哪取清单。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ApkCheckTest {
    private val debug = "b350fad0e6e35e4e717875cc3131e47b96d98482a3f1c9451295217435f91746"
    private val release = "1b89b1ab1d2274b98847c466b19bbf51f9ef997cb08956d9ed4b4caa7fba2403"
    private val stranger = "9".repeat(64)
    private val pkg = "com.dzshzx.zeta"

    private val manifest =
        UpdateManifest(
            versionCode = 905,
            versionName = "0.7.2",
            notes = "",
            apk = "Zeta-v0.7.2.apk",
            url = "https://github.com/dzshzx/Zeta/releases/download/v0.7.2/Zeta-v0.7.2.apk",
            size = 1,
            sha256 = "0".repeat(64),
        )

    private fun apk(
        signer: String,
        history: List<String> = listOf(signer),
        packageName: String = pkg,
        versionCode: Long = 905,
    ) = ApkFacts(packageName, versionCode, listOf(signer), history)

    @Test
    fun sameSignerOrARotationFromTheInstalledOneIsAccepted() {
        // 正式发版证书签的装着，新版本也是它签的
        assertNull(
            ApkCheck.verdict(
                apk(release, listOf(debug, release)),
                manifest,
                apk(release, listOf(debug, release), versionCode = 900),
            ),
        )
        // v0.7.0 及更早（调试证书）装着，新版本带「调试证书 → 正式发版证书」的 lineage
        assertNull(
            ApkCheck.verdict(
                apk(release, listOf(debug, release)),
                manifest,
                apk(debug, versionCode = 800),
            ),
        )
    }

    @Test
    fun everyMismatchIsRejectedWithAReason() {
        val installed = apk(release, listOf(debug, release), versionCode = 900)
        assertEquals("读不出下载的安装包（文件坏了，或不是 APK）", ApkCheck.verdict(null, manifest, installed))
        assertEquals(
            "安装包的包名是 com.example.other，不是本应用（com.dzshzx.zeta）",
            ApkCheck.verdict(
                apk(release, listOf(debug, release), packageName = "com.example.other"),
                manifest,
                installed,
            ),
        )
        assertEquals(
            "安装包的 versionCode 是 906，清单写的是 905",
            ApkCheck.verdict(
                apk(release, listOf(debug, release), versionCode = 906),
                manifest,
                installed,
            ),
        )
        // 别人的证书；只有调试证书、没有 lineage 的（旧证书已经作废，系统也装不上）
        assertEquals("安装包的签名和已装的 Zeta 不一致", ApkCheck.verdict(apk(stranger), manifest, installed))
        assertEquals("安装包的签名和已装的 Zeta 不一致", ApkCheck.verdict(apk(debug), manifest, installed))
        // 自称从别人的证书轮换过来、却不含已装的证书
        assertEquals(
            "安装包的签名和已装的 Zeta 不一致",
            ApkCheck.verdict(
                apk(release, listOf(stranger, release)),
                manifest,
                apk(debug, versionCode = 800),
            ),
        )
        // 读不出已装的签名时不放行
        assertEquals(
            "安装包的签名和已装的 Zeta 不一致",
            ApkCheck.verdict(apk(release), manifest, ApkFacts(pkg, 900, emptyList(), emptyList())),
        )
    }

    @Test
    fun onlyPackagesSignedByTheTrustedCertificateCheck() {
        assertTrue(UpdateTrust.shouldCheck(apk(release, listOf(debug, release)), release))
        assertFalse(UpdateTrust.shouldCheck(apk(debug), release))
        assertFalse(UpdateTrust.shouldCheck(apk(stranger), release))
        assertFalse(UpdateTrust.shouldCheck(null, release))
    }

    /** 构建时写进 BuildConfig 的正式发版证书就是 release/signing-certs.txt 的最后一行。 */
    @Test
    fun releaseCertificateComesFromTheRegistry() {
        val last =
            File("../release/signing-certs.txt")
                .readLines()
                .map { it.trim() }
                .last { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(last, BuildConfig.RELEASE_CERT_SHA256)
    }

    /** 调试覆盖只在测试版里生效：单元测试跑的是 debug 包（同正式版不读覆盖），设了也照样用默认的地址和证书。 */
    @Test
    fun devOverridesApplyOnlyToTheTestBuild() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("updateDevManifest", "http://10.0.2.2:8765/update.json")
            .putString("updateDevCert", debug)
            .commit()
        val settings = Settings.of(app)
        assertEquals("http://10.0.2.2:8765/update.json", settings.updateDevManifest)
        // 正式版（开关是常量 false）不理会覆盖；单元测试跑在测试版上，开关是 true，覆盖生效
        assertEquals(
            UpdateManifest.LATEST_URL,
            UpdateTrust.manifestUrl(settings, devOverride = false),
        )
        assertEquals(
            BuildConfig.RELEASE_CERT_SHA256,
            UpdateTrust.trustedCert(settings, devOverride = false),
        )
        assertTrue(BuildConfig.UPDATE_DEV_OVERRIDE)
        assertEquals("http://10.0.2.2:8765/update.json", UpdateTrust.manifestUrl(settings))
        assertEquals(debug, UpdateTrust.trustedCert(settings))
    }
}
