package com.dzshzx.zeta.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.dzshzx.zeta.core.SavedPrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    /** 一台用着的手机上 zeta.xml 的样子（值都是假的）。 */
    private val phone: Map<String, Any?> = mapOf(
        "rootfs" to "/data/local/zeta/debian/rootfs",
        "user" to "someone",
        "uid" to 5000,
        "gid" to 5000,
        "home" to "/home/someone",
        "cwd" to "/workspace/claude",
        "model" to "opus",
        "permissionMode" to "acceptEdits",
        "commands" to "[{\"name\":\"compact\"}]",
        "contextWindows" to setOf("opus=200000"),
        "mutedApps" to setOf("com.example.music=allow"),
        "phoneDisplay" to 7,
        "dark" to true,
        "accent" to "teal",
        "interfaceScale" to 0.9f,
        "pinnedSessions" to setOf("0a1b2c3d-0000-4000-8000-000000000001"),
        "sharedFolders" to "[{\"name\":\"Download\",\"source\":\"/storage/emulated/0/Download\"}]",
        "keepScreenOn" to false,
        "autoCompact" to false,
        "autoCompactPercent" to 70,
        "savedPrompts" to "[{\"id\":\"a\",\"name\":\"\",\"text\":\"检查 Git 状态\"},{\"id\":\"b\",\"name\":\"简报\",\"text\":\"汇总今天的日程\\n三行说完\"}]",
        // 万一以后有人往这里存了凭据，也不能被导出去
        "apiToken" to "fake-token-1",
    )

    private fun export(prefs: Map<String, Any?>) = SettingsBackup.export(prefs, app = "0.6.12", exportedAt = "2026-11-19T10:00:00+08:00")

    private fun valid(text: String): BackupFile.Valid {
        val parsed = SettingsBackup.parse(text)
        assertTrue("应能读出：$parsed", parsed is BackupFile.Valid)
        return parsed as BackupFile.Valid
    }

    private fun file(settings: JsonObject, version: Int = SettingsBackup.VERSION) = buildJsonObject {
        put("format", SettingsBackup.FORMAT)
        put("version", version)
        put("settings", settings)
    }.toString()

    @Test
    fun exportHasOnlyAllowlistedKeys() {
        val root = Json.parseToJsonElement(export(phone)).jsonObject
        assertEquals(SettingsBackup.FORMAT, root["format"]!!.jsonPrimitive.content)
        assertEquals(SettingsBackup.VERSION, root["version"]!!.jsonPrimitive.int)
        assertEquals("0.6.12", root["app"]!!.jsonPrimitive.content)
        val keys = root["settings"]!!.jsonObject.keys
        assertEquals(SettingsBackup.items.map { it.key }.toSet(), keys)
        for (machine in SettingsBackup.machineKeys.keys) assertFalse("$machine 不该导出", machine in keys)
        assertFalse("apiToken" in keys)
        assertFalse(export(phone).contains("fake-token-1"))
        // 常用指令导出时不带这台手机上的 id
        val prompts = root["settings"]!!.jsonObject["savedPrompts"]!!.toString()
        assertEquals("""[{"name":"","text":"检查 Git 状态"},{"name":"简报","text":"汇总今天的日程\n三行说完"}]""", prompts)
    }

    @Test
    fun exportFillsDefaultsAndSkipsUnsetCwd() {
        val settings = Json.parseToJsonElement(export(emptyMap<String, Any?>())).jsonObject["settings"]!!.jsonObject
        assertEquals(JsonNull, settings["dark"])
        assertEquals(JsonNull, settings["model"])
        assertEquals(JsonPrimitive(true), settings["phoneTools"])
        assertEquals(JsonPrimitive(false), settings["pureBlack"])
        assertEquals(JsonPrimitive("coui"), settings["accent"])
        assertEquals(JsonPrimitive(1f), settings["interfaceScale"])
        assertEquals(JsonPrimitive(true), settings["autoCompact"])
        assertEquals(JsonNull, settings["autoCompactPercent"])
        assertEquals("[]", settings["savedPrompts"].toString())
        assertFalse("没有环境时没有工作目录", "cwd" in settings)
    }

    @Test
    fun roundTripChangesNothing() {
        val backup = valid(export(phone))
        assertTrue(backup.skipped.isEmpty())
        val plan = SettingsBackup.plan(backup, phone)
        assertTrue("同一份设置导回来不该有改动：${plan.changes}", plan.changes.isEmpty())
        assertEquals(SettingsBackup.items.size, plan.unchanged.size)
        assertTrue(SettingsBackup.writes(backup, plan).isEmpty())
    }

    @Test
    fun importIntoFreshInstallWritesTypedValues() {
        val backup = valid(export(phone))
        // 刚装好、什么都没设过的 Zeta；环境已检测出来，工作目录是默认的 /workspace
        val fresh = mapOf("user" to "someone", "cwd" to "/workspace")
        val plan = SettingsBackup.plan(backup, fresh)
        val changed = plan.changes.associateBy { it.key }
        assertEquals("跟随系统", changed.getValue("dark").from)
        assertEquals("深色", changed.getValue("dark").to)
        assertEquals("100%", changed.getValue("interfaceScale").from)
        assertEquals("90%", changed.getValue("interfaceScale").to)
        assertEquals("自动接受编辑", changed.getValue("permissionMode").to)
        val writes = SettingsBackup.writes(backup, plan)
        assertEquals(true, writes["dark"])
        assertEquals("teal", writes["accent"])
        assertEquals(0.9f, writes["interfaceScale"])
        assertEquals(false, writes["keepScreenOn"])
        assertEquals(false, writes["autoCompact"])
        assertEquals(70, writes["autoCompactPercent"])
        // 常用指令只带名字和内容，顺序不变，id 在这台手机上重新生成
        val prompts = SavedPrompts.decode(writes["savedPrompts"] as String)
        assertEquals(listOf("" to "检查 Git 状态", "简报" to "汇总今天的日程\n三行说完"), prompts.map { it.name to it.text })
        assertTrue(prompts.none { it.id == "a" || it.id == "b" })
        assertEquals("opus", writes["model"])
        assertEquals("/workspace/claude", writes["cwd"])
        assertEquals(setOf("0a1b2c3d-0000-4000-8000-000000000001"), writes["pinnedSessions"])
        assertEquals("[{\"name\":\"Download\",\"source\":\"/storage/emulated/0/Download\"}]", writes["sharedFolders"])
        // 和默认值一样的不写
        assertFalse("phoneTools" in writes)
        // 写回的只有白名单里的键
        assertTrue(writes.keys.all { key -> SettingsBackup.items.any { it.key == key } })
    }

    @Test
    fun nullMeansBackToDefault() {
        val backup = valid(file(buildJsonObject { put("dark", JsonNull); put("model", JsonNull) }))
        val plan = SettingsBackup.plan(backup, phone)
        assertEquals(setOf("dark", "model"), plan.changes.map { it.key }.toSet())
        val writes = SettingsBackup.writes(backup, plan)
        assertTrue("dark" in writes && writes["dark"] == null)
        assertTrue("model" in writes && writes["model"] == null)
    }

    @Test
    fun badFilesAreRejected() {
        fun reason(text: String) = (SettingsBackup.parse(text) as BackupFile.Invalid).reason
        reason("not json")
        reason("[1, 2]")
        assertEquals("不是 Zeta 的设置备份", reason("{\"format\":\"other\",\"version\":1,\"settings\":{}}"))
        assertEquals("备份文件缺少格式版本号", reason("{\"format\":\"zeta-settings\",\"settings\":{}}"))
        assertEquals("备份文件缺少格式版本号", reason("{\"format\":\"zeta-settings\",\"version\":\"1\",\"settings\":{}}"))
        assertTrue(reason(file(buildJsonObject {}, version = 2)).contains("更新版本"))
        assertEquals("备份文件里没有设置", reason("{\"format\":\"zeta-settings\",\"version\":1}"))
    }

    @Test
    fun badItemsAreSkippedWithReasons() {
        val backup = valid(
            file(
                buildJsonObject {
                    put("phoneTools", "yes")
                    put("pureBlack", true)
                    put("accent", "yellow")
                    put("interfaceScale", 5.0)
                    put("model", "opus; rm -rf /")
                    put("effort", "xhigh")
                    put("permissionMode", "yolo")
                    put("cwd", "relative/dir")
                    put("keepScreenOn", JsonNull)
                    put("pinnedSessions", buildJsonArray { add(JsonPrimitive("../../etc")) })
                    put(
                        "sharedFolders",
                        buildJsonArray {
                            add(buildJsonObject { put("name", "sys"); put("source", "/system/etc") })
                        },
                    )
                    put("autoCompact", "off")
                    put("autoCompactPercent", 150)
                    put("savedPrompts", buildJsonArray { add(buildJsonObject { put("name", "空的"); put("text", " ") }) })
                    put("uid", 0)
                    put("rootfs", "/")
                    put("somethingNew", 1)
                },
            ),
        )
        assertEquals(mapOf("pureBlack" to JsonPrimitive(true), "effort" to JsonPrimitive("xhigh")), backup.values)
        val skipped = backup.skipped.associateBy { it.key }
        assertEquals(
            setOf(
                "phoneTools", "accent", "interfaceScale", "model", "permissionMode", "cwd", "keepScreenOn",
                "pinnedSessions", "sharedFolders", "uid", "rootfs", "somethingNew",
                "autoCompact", "autoCompactPercent", "savedPrompts",
            ),
            skipped.keys,
        )
        assertTrue(skipped.getValue("uid").reason.startsWith("这台手机特有的设置"))
        assertTrue(skipped.getValue("somethingNew").reason.startsWith("不认识的设置项"))
        assertTrue(skipped.getValue("sharedFolders").reason.contains("不能共享"))
        assertEquals("第 1 条：内容不能为空", skipped.getValue("savedPrompts").reason)
        // 跳过的项原样带进确认清单
        val plan = SettingsBackup.plan(backup, emptyMap<String, Any?>())
        assertEquals(backup.skipped, plan.skipped)
        assertEquals(setOf("pureBlack", "effort"), plan.changes.map { it.key }.toSet())
    }

    @Test
    fun sharedFoldersAreValidatedAsAWhole() {
        val duplicate = valid(
            file(
                buildJsonObject {
                    put(
                        "sharedFolders",
                        buildJsonArray {
                            add(buildJsonObject { put("name", "a"); put("source", "/storage/emulated/0/Download") })
                            add(buildJsonObject { put("name", "a"); put("source", "/storage/emulated/0/DCIM") })
                        },
                    )
                },
            ),
        )
        assertTrue(duplicate.values.isEmpty())
        assertTrue(duplicate.skipped.single().reason.contains("挂载名已被使用"))
        val normalized = valid(
            file(
                buildJsonObject {
                    put("sharedFolders", buildJsonArray { add(buildJsonObject { put("name", "dl"); put("source", "/storage/emulated/0//Download/") }) })
                },
            ),
        )
        assertEquals(
            "/storage/emulated/0/Download",
            normalized.values.getValue("sharedFolders").toString().let { Regex("\"source\":\"([^\"]+)\"").find(it)!!.groupValues[1] },
        )
    }

    @Test
    fun extraSkipsDropAnItemFromThePlan() {
        val backup = valid(export(phone))
        val plan = SettingsBackup.plan(backup, mapOf("cwd" to "/workspace"), extraSkips = mapOf("cwd" to "这台手机的 Linux 环境里没有这个目录"))
        assertNull(plan.changes.firstOrNull { it.key == "cwd" })
        assertEquals("这台手机的 Linux 环境里没有这个目录", plan.skipped.single { it.key == "cwd" }.reason)
        assertFalse("cwd" in SettingsBackup.writes(backup, plan))
    }

    @Test
    fun scaleBoundsSurviveFloatRounding() {
        val low = valid(file(buildJsonObject { put("interfaceScale", 0.8f) }))
        assertEquals(0.8f, SettingsBackup.writes(low, SettingsBackup.plan(low, emptyMap<String, Any?>()))["interfaceScale"])
        val tooLow = valid(file(buildJsonObject { put("interfaceScale", 0.5) }))
        assertTrue(tooLow.values.isEmpty())
    }
}
