package com.dzshzx.zeta.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.PromptStore
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.of
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.phone.AccessibilityGuard
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SharedPreferences `zeta`
 * 里的每个键，要么进备份（[SettingsBackup.items]），要么是这台手机特有的（[SettingsBackup.machineKeys]）。 用真的 [Settings] 和各个
 * store 把每个设置都写一遍，再看存储里出现了哪些键。 导入时清单类的几项经各自的 store 写回（[restoreBackup]），也在这里真走一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class SettingsKeysTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        // 这几个 store 是全进程一份、带缓存的：先按这次测试的（空的）存储重读
        PromptStore.reload(app)
        ScheduleStore.reload(app)
        NotifyRuleStore.reload(app)
    }

    /** [Settings] 每个 setter 写一个值。新加的设置在这里给一个，测试才会写到它。 */
    private val samples: Map<String, Any?> =
        mapOf(
            "setSharedFolders" to listOf(SharedFolder("Download", "/storage/emulated/0/Download")),
            "setEnv" to
                LinuxEnvConfig(
                    "/data/local/zeta/debian/rootfs",
                    "someone",
                    5000,
                    5000,
                    "/home/someone",
                    "/workspace/claude",
                ),
            "setDarkTheme" to true,
            "setPhoneTools" to false,
            "setAgentDisplay" to false,
            "setMutedApps" to setOf("com.example.music=allow"),
            "setAutoLoadRemoteImages" to true,
            "setAutoReadAnswers" to true,
            "setAutoTitle" to false,
            "setPinnedSessions" to setOf("0a1b2c3d-0000-4000-8000-000000000001"),
            "setSessionSort" to com.dzshzx.zeta.core.SessionSort.Created,
            "setInterfaceScale" to 0.9f,
            "setKeepScreenOn" to false,
            "setAssistantModel" to "opus",
            "setAssistantEffort" to "high",
            "setAutoCompact" to false,
            "setAutoCompactPercent" to 70,
            "setHostModes" to mapOf("k1" to "plan"),
            "setRemoteHosts" to
                listOf(
                    RemoteHost(
                        "r-00000001",
                        "工作站",
                        "dev-box",
                        "/home/someone",
                        "/home/someone/workspace",
                    ),
                ),
            "setBackend" to "r-00000001",
            "setLocalName" to "我的手机",
            "setAutoUpdateCheck" to false,
            "setPaneAll" to true,
            "setRemoteClaude" to mapOf("r-00000001/autoCompact" to "false"),
            "setUpdateCheckedAt" to 1_795_000_300_000L,
            "setUpdateNotified" to 905,
        )

    private val task =
        ScheduledTask(
            id = "t1",
            name = "早报",
            prompt = "汇总今天的日程",
            rule = ScheduleRule.Daily(LocalTime.of(8, 0)),
            lastRun = TaskRun(1_795_000_100_000, RunOutcome.Done, summary = "好了"),
        )

    private val rule =
        NotifyRule(
            id = "r1",
            name = "快递",
            packageName = "com.example.express",
            appLabel = "快递",
            prompt = "记下取件码：{text}",
            lastFired = 1_795_000_200_000,
            firedDay = LocalDate.of(2026, 11, 20),
            firedCount = 3,
        )

    /** 照应用里的各个入口把每个设置写一遍，返回存储里的全部键。 */
    private fun writeEverything(): Set<String> {
        val settings = Settings.of(app)
        val setters =
            Settings::class.java.declaredMethods.filter {
                it.name.startsWith("set") && it.parameterCount == 1
            }
        val unknown = setters.map { it.name }.filter { it !in samples }
        assertTrue("Settings 新加的设置在 samples 里给一个值：$unknown", unknown.isEmpty())
        for (setter in setters) setter.invoke(settings, samples[setter.name])
        AccessibilityGuard.setAutoRestore(app, false)
        PromptStore.update(app) { listOf(SavedPrompt("p1", "简报", "汇总今天的日程")) }
        // 改清单后重排闹钟，记下排过的任务（scheduleArmed）
        ScheduleStore.update(app) { listOf(task) }
        NotifyRuleStore.update(app) { listOf(rule) }
        // 只有调试工具（tools/vd prefs-set）写的：手机工具当作主屏的屏幕编号
        prefs().edit().putInt("phoneDisplay", 7).commit()
        // 同上：测试版调试应用内更新用的清单地址和信任的证书
        prefs()
            .edit()
            .putString("updateDevManifest", "http://10.0.2.2:8765/update.json")
            .putString("updateDevCert", "0".repeat(64))
            .commit()
        return prefs().all.keys
    }

    private fun prefs() = app.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE)

    @Test
    fun everyStoredKeyIsBackedUpOrMachineSpecific() {
        val stored = writeEverything()
        val covered = SettingsBackup.items.map { it.key }.toSet() + SettingsBackup.machineKeys.keys
        assertEquals("既不进备份、也不在本机键表里的键", emptySet<String>(), stored - covered)
        assertEquals("两张表里写不出来的键（去掉了的设置要从表里删掉）", emptySet<String>(), covered - stored)
    }

    /** 导出、清空、再导入：清单经各自的 store 写回，界面看的那份跟着换，定时任务的闹钟按新的 id 重新登记。 */
    @Test
    fun importWritesListsThroughTheirStores() {
        PromptStore.update(app) { listOf(SavedPrompt("p1", "简报", "汇总今天的日程")) }
        ScheduleStore.update(app) { listOf(task) }
        NotifyRuleStore.update(app) { listOf(rule) }
        val settings = Settings.of(app)
        val text =
            SettingsBackup.export(
                settings.snapshot(),
                app = "test",
                exportedAt = "2026-11-20T10:00:00+08:00",
            )

        PromptStore.update(app) { emptyList() }
        ScheduleStore.update(app) { emptyList() }
        NotifyRuleStore.update(app) { emptyList() }
        assertEquals(emptySet<String>(), prefs().getStringSet("scheduleArmed", null))

        val backup = SettingsBackup.parse(text) as BackupFile.Valid
        val plan = SettingsBackup.plan(backup, settings.snapshot())
        assertEquals(
            setOf("savedPrompts", "scheduledTasks", "notifyRules"),
            plan.changes.map { it.key }.toSet(),
        )
        assertTrue(restoreBackup(app, settings, SettingsBackup.writes(backup, plan)))

        assertEquals(listOf("简报" to "汇总今天的日程"), PromptStore.observed.map { it.name to it.text })
        val restored = ScheduleStore.observed.single()
        assertEquals("早报", restored.name)
        assertTrue(restored.id != task.id)
        assertNull(restored.lastRun)
        assertEquals(setOf(restored.id), prefs().getStringSet("scheduleArmed", null))
        val again = NotifyRuleStore.observed.single()
        assertEquals("快递", again.name)
        assertEquals(0, again.firedCount)
        assertNull(again.lastFired)
        // 存储里的和 store 里的是同一份
        assertEquals(ScheduleStore.observed, ScheduleStore.reload(app))
        assertEquals(NotifyRuleStore.observed, NotifyRuleStore.reload(app))
    }

    /**
     * 写设置文件（[Settings.FILE]）的地方都在上面写过一遍；新加了写它的地方，在 [writeEverything] 里补上，再改这里。 文件名只在
     * [Settings.FILE] 写一次，别处不写字面的 "zeta"。
     */
    @Test
    fun everyWriterOfTheSettingsFileIsExercised() {
        val sources =
            listOf(File("src/main/kotlin"), File("../shared/src/commonMain/kotlin"))
                .flatMap { it.walk() }
                .filter { it.isFile && it.extension == "kt" }
                .associate { it.name to it.readText() }
        val opener = Regex("(getSharedPreferences|SharedPrefStore)\\((\\w+, )?(\\w+\\.)?FILE\\b")
        val writers =
            sources
                .filter { (_, text) ->
                    opener.containsMatchIn(text) ||
                        Regex(":\\s*PrefsListStore<").containsMatchIn(text)
                }
                .keys
                .toSortedSet()
        assertEquals(
            sortedSetOf(
                "AccessibilityGuard.kt",
                "NotifyRuleStore.kt",
                "PrefsListStore.kt",
                "PromptStore.kt",
                "ScheduleAlarms.kt",
                "ScheduleStore.kt",
                "SettingsOf.kt",
            ),
            writers,
        )
        assertEquals(
            "直接写了设置文件名的",
            emptyList<String>(),
            sources.filter { (_, text) -> "getSharedPreferences(\"zeta\"" in text }.keys.toList(),
        )
    }
}
