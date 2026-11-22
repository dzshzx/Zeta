package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlatformTest {
    private val dir = Files.createTempDirectory("zeta-desktop-test").toFile()

    @Test
    fun prefsKeepTheirTypesAcrossReopening() {
        val file = dir.resolve("p.json")
        FilePrefStore(file)
            .edit()
            .putString("s", "文字")
            .putInt("i", 7)
            .putLong("l", 1L shl 40)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putStringSet("set", setOf("a", "b"))
            .commit()
            .let(::assertTrue)
        val again = FilePrefStore(file)
        assertEquals("文字", again.getString("s", null))
        assertEquals(7, again.getInt("i", 0))
        assertEquals(1L shl 40, again.getLong("l", 0))
        assertEquals(1.5f, again.getFloat("f", 0f))
        assertTrue(again.getBoolean("b", false))
        assertEquals(setOf("a", "b"), again.getStringSet("set", null))
        // 类型不对的按没有算，给默认值
        assertEquals(3, again.getInt("s", 3))
        again.edit().remove("s").commit()
        assertFalse(FilePrefStore(file).contains("s"))
    }

    @Test
    fun hubHasOnlyRemoteHosts() {
        val platform = DesktopHubPlatform(dir)
        val hub = ConversationHub(platform)
        assertEquals(emptyList<String>(), hub.backends)
        assertFalse(hub.usable(hub.backend))
        assertEquals("还没有添加远程设备，请到设置里添加", hub.unavailable(Backends.LOCAL))
        assertNull(hub.current.phoneSession)
        // 和手机正式版同一个目录：电脑、手机同时接着同一个托管进程
        assertEquals("zeta", AppVariant.current.linuxName)
        // 托管脚本从 jar 里的 assets/ 读
        assertTrue(hub.hostScript.startsWith("# Zeta "))
    }
}
