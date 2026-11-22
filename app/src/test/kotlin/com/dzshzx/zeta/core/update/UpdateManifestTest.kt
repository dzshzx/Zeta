package com.dzshzx.zeta.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateManifestTest {
    /** 和 tools/release 的 package 写出来的一样（字段见 docs/release.md），外加一个以后才有的字段。 */
    private fun json(
        versionCode: String = "905",
        sha256: String = "\"" + "ab".repeat(32) + "\"",
        size: String = "13200000",
        apk: String = "\"Zeta-v0.7.2.apk\"",
        extra: String = ",\n  \"future\": {\"x\": 1}",
    ) =
        """
        {
          "versionCode": $versionCode,
          "versionName": "0.7.2",
          "tag": "v0.7.2",
          "publishedAt": "2026-11-21T08:00:00Z",
          "notes": "- feat(update): 应用内更新\n- fix(chat): 修一处",
          "apk": $apk,
          "url": "https://github.com/dzshzx/Zeta/releases/download/v0.7.2/Zeta-v0.7.2.apk",
          "size": $size,
          "sha256": $sha256,
          "certificateSha256": "${"cd".repeat(32)}",
          "sourceCommit": "${"1".repeat(40)}"$extra
        }
    """
            .trimIndent()

    private fun reason(text: String): String =
        try {
            UpdateManifest.parse(text)
            fail("应当解析失败：$text")
            ""
        } catch (e: UpdateException) {
            e.reason
        }

    @Test
    fun parsesTheReleaseManifestAndIgnoresUnknownFields() {
        val m = UpdateManifest.parse(json())
        assertEquals(905, m.versionCode)
        assertEquals("0.7.2", m.versionName)
        assertEquals("Zeta-v0.7.2.apk", m.apk)
        assertEquals(
            "https://github.com/dzshzx/Zeta/releases/download/v0.7.2/Zeta-v0.7.2.apk",
            m.url,
        )
        assertEquals(13_200_000L, m.size)
        assertEquals("ab".repeat(32), m.sha256)
        assertEquals("- feat(update): 应用内更新\n- fix(chat): 修一处", m.notes)
        assertEquals("2026-11-21T08:00:00Z", m.publishedAt)
    }

    @Test
    fun badFieldsAreNamed() {
        assertEquals("更新清单格式不对：不是 JSON 对象", reason("<html>captive portal</html>"))
        assertEquals("更新清单格式不对：不是 JSON 对象", reason("[1, 2]"))
        assertTrue(reason(json(versionCode = "\"905\"")).contains("versionCode"))
        assertTrue(reason(json(versionCode = "0")).contains("versionCode"))
        assertTrue(reason(json(versionCode = "9999999999")).contains("versionCode"))
        assertTrue(reason(json(sha256 = "\"" + "AB".repeat(32) + "\"")).contains("sha256"))
        assertTrue(reason(json(sha256 = "\"abc\"")).contains("sha256"))
        assertTrue(reason(json(size = "0")).contains("size"))
        assertTrue(reason(json(size = "-1")).contains("size"))
        // 文件名会拼进本地路径，带目录的不收
        assertTrue(reason(json(apk = "\"../Zeta.apk\"")).contains("apk"))
        assertTrue(reason(json(apk = "\"Zeta.zip\"")).contains("apk"))
    }

    /** 清单里多了桌面版的四项：手机读到的照旧，那几项坏了也不影响手机。 */
    @Test
    fun desktopFieldsLeaveWhatThePhoneReadsAlone() {
        val desktop =
            ",\n  \"msi\": \"Zeta-v0.7.2.msi\",\n" +
                "  \"msiUrl\": \"https://github.com/dzshzx/Zeta/releases/download/v0.7.2/Zeta-v0.7.2.msi\",\n" +
                "  \"msiSize\": 115000000,\n  \"msiSha256\": \"${"ef".repeat(32)}\""
        val old = UpdateManifest.parse(json(extra = ""))
        val withDesktop = UpdateManifest.parse(json(extra = desktop))
        assertEquals(old, withDesktop.copy(msi = null))
        assertEquals(old.apkPackage, withDesktop.apkPackage)
        assertEquals(115_000_000L, withDesktop.msi?.size)
        val broken = UpdateManifest.parse(json(extra = desktop.replace("115000000", "\"big\"")))
        assertEquals(old, broken)
    }

    @Test
    fun onlyABiggerVersionCodeIsNewer() {
        val m = UpdateManifest.parse(json(versionCode = "905"))
        assertTrue(m.isNewerThan(904))
        assertFalse(m.isNewerThan(905))
        assertFalse(m.isNewerThan(906))
    }
}
