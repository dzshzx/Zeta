package com.dzshzx.zeta.core

import android.app.Application
import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [Settings]：全进程一份，每项可观察，写了存进设置文件，备份导入后按存下来的值重读。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun prefs() = app.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE)

    @Test
    fun oneInstancePerProcess() {
        assertSame(Settings.of(app), Settings.of(app.applicationContext))
    }

    /** 界面读设置时记下了它读的状态：值变了就会重画。 */
    @Test
    fun readsAreObservable() {
        val settings = Settings.of(app)
        val reads = HashSet<Any>()
        val snapshot = Snapshot.takeSnapshot(readObserver = { reads += it })
        try {
            snapshot.enter { settings.phoneTools; settings.darkTheme; settings.pinnedSessions; settings.env }
        } finally {
            snapshot.dispose()
        }
        assertEquals(4, reads.size)
    }

    @Test
    fun writesGoToTheSettingsFile() {
        val settings = Settings.of(app)
        settings.darkTheme = true
        settings.autoCompactPercent = 70
        settings.pinnedSessions = setOf("s1")
        assertTrue(prefs().getBoolean("dark", false))
        assertEquals(70, prefs().getInt("autoCompactPercent", 0))
        assertEquals(setOf("s1"), prefs().getStringSet("pinnedSessions", null))
        // 写 null 是删掉这一项，回到默认
        settings.darkTheme = null
        settings.autoCompactPercent = null
        assertFalse(prefs().contains("dark"))
        assertFalse(prefs().contains("autoCompactPercent"))
    }

    /** 存着的默认工作目录原样读回；存的环境里没有这一项时用 Linux 用户的家目录。 */
    @Test
    fun defaultCwdFallsBackToHome() {
        val settings = Settings.of(app)
        settings.env = LinuxEnvConfig(LinuxEnv.OWN_ROOTFS, "dzshzx", 5000, 5000, "/home/dzshzx", "/workspace/claude")
        assertEquals("/workspace/claude", settings.env?.defaultCwd)
        assertTrue(settings.restore(mapOf("cwd" to null)))
        assertEquals("/home/dzshzx", settings.env?.defaultCwd)
    }

    /** 各主机的斜杠命令清单存成一个 JSON 对象，重读回来一样；存坏了（不是 JSON、不是对象）读成空的。 */
    @Test
    fun commandsRoundTrip() {
        val settings = Settings.of(app)
        val commands = mapOf(
            Backends.LOCAL to listOf(SlashCommand("compact", "压缩上下文", ""), SlashCommand("review", "", "<pr>")),
            "r-00000001" to listOf(SlashCommand("deploy", "", "")),
        )
        settings.commands = commands
        assertTrue(settings.restore(mapOf("commands" to prefs().getString("commands", null))))
        assertEquals(commands, settings.commands)
        for (bad in listOf("not json", """[{"name":"compact"}]""", """{"local":"compact"}""")) {
            assertTrue(settings.restore(mapOf("commands" to bad)))
            assertEquals(bad, emptyMap<String, List<SlashCommand>>(), settings.commands)
        }
    }

    /** 导入备份：写完存储，各项（连同交给 LinuxEnv 的共享文件夹清单）按导入的值重读。 */
    @Test
    fun restoreRereadsEveryPref() {
        val settings = Settings.of(app)
        settings.keepScreenOn = true
        settings.accent = "coui"
        val folders = listOf(SharedFolder("Download", "/storage/emulated/0/Download"))
        assertTrue(
            settings.restore(
                mapOf(
                    "keepScreenOn" to false,
                    "accent" to "teal",
                    "interfaceScale" to 0.9f,
                    "assistantModel" to "opus",
                    "sharedFolders" to SharedFolders.encode(folders),
                    "hostModes" to setOf("k1=plan"),
                ),
            ),
        )
        assertFalse(settings.keepScreenOn)
        assertEquals("teal", settings.accent)
        assertEquals(0.9f, settings.interfaceScale)
        assertEquals("opus", settings.assistantModel)
        assertEquals(folders, settings.sharedFolders)
        assertEquals(folders, SharedFolders.current)
        assertEquals(mapOf("k1" to "plan"), settings.hostModes)
        assertTrue(settings.restore(mapOf("assistantModel" to null)))
        assertNull(settings.assistantModel)
    }
}
