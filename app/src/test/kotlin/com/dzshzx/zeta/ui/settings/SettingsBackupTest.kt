package com.dzshzx.zeta.ui.settings

import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.core.schedule.RunOutcome
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.core.schedule.TaskRun
import com.dzshzx.zeta.phone.AccessibilityGuard
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupTest {
    /** 跑过几次的定时任务：id、接着的会话、运行记录、跳过次数都是这台手机上的。 */
    private val task =
        ScheduledTask(
            id = "t1",
            name = "早报",
            prompt = "汇总今天的日程",
            rule = ScheduleRule.Daily(LocalTime.of(8, 0)),
            model = "opus",
            newSession = false,
            sessionId = "0a1b2c3d-0000-4000-8000-000000000002",
            created = 1_795_000_000_000,
            lastRun =
                TaskRun(
                    1_795_000_100_000,
                    RunOutcome.Done,
                    "0a1b2c3d-0000-4000-8000-000000000002",
                    "好了",
                ),
            skips = 2,
            lastSkip = 1_795_000_200_000,
        )

    /** 触发过的通知规则：冷却、今天的次数、运行记录都是这台手机上的。 */
    private val rule =
        NotifyRule(
            id = "r1",
            name = "快递",
            packageName = "com.example.express",
            appLabel = "快递",
            keywords = listOf("取件"),
            prompt = "记下取件码：{text}",
            cooldownMinutes = 10,
            created = 1_795_000_000_000,
            lastRun = TaskRun(1_795_000_100_000, RunOutcome.NoChange, summary = "没有变化"),
            lastFired = 1_795_000_100_000,
            firedDay = LocalDate.of(2026, 11, 20),
            firedCount = 3,
        )

    /** 一台用着的手机上 zeta.xml 的样子（值都是假的）。 */
    private val phone: Map<String, Any?> =
        mapOf(
            "rootfs" to "/data/local/zeta/debian/rootfs",
            "user" to "someone",
            "uid" to 5000,
            "gid" to 5000,
            "home" to "/home/someone",
            "cwd" to "/workspace/claude",
            "assistantModel" to "opus",
            // 以前的 Zeta 存的新对话默认值，现在用 Claude Code 自己的设置：还留在存储里也不导出
            "model" to "opus",
            "permissionMode" to "acceptEdits",
            // 这个版本没有的强调色设置：留在存储里也不导出
            "accent" to "teal",
            "commands" to "[{\"name\":\"compact\"}]",
            "contextWindows" to setOf("opus=200000"),
            "mutedApps" to setOf("com.example.music=allow"),
            "phoneDisplay" to 7,
            "dark" to true,
            "interfaceScale" to 0.9f,
            "pinnedSessions" to setOf("0a1b2c3d-0000-4000-8000-000000000001"),
            "sharedFolders" to
                "[{\"name\":\"Download\",\"source\":\"/storage/emulated/0/Download\"}]",
            "keepScreenOn" to false,
            "autoCompact" to false,
            "autoCompactPercent" to 70,
            "savedPrompts" to
                "[{\"id\":\"a\",\"name\":\"\",\"text\":\"检查 Git 状态\"},{\"id\":\"b\",\"name\":\"简报\",\"text\":\"汇总今天的日程\\n三行说完\"}]",
            "accessibilityGuard" to false,
            "scheduledTasks" to Schedules.encode(listOf(task)),
            "notifyRules" to NotifyRules.encode(listOf(rule)),
            "scheduleArmed" to setOf("t1"),
            // 万一以后有人往这里存了凭据，也不能被导出去
            "apiToken" to "fake-token-1",
        )

    private fun export(prefs: Map<String, Any?>) =
        SettingsBackup.export(prefs, app = "0.6.12", exportedAt = "2026-11-19T10:00:00+08:00")

    private fun valid(text: String): BackupFile.Valid {
        val parsed = SettingsBackup.parse(text)
        assertTrue("应能读出：$parsed", parsed is BackupFile.Valid)
        return parsed as BackupFile.Valid
    }

    private fun file(settings: JsonObject, version: Int = SettingsBackup.VERSION) =
        buildJsonObject {
            put("format", SettingsBackup.FORMAT)
            put("version", version)
            put("settings", settings)
        }
        .toString()

    @Test
    fun exportHasOnlyAllowlistedKeys() {
        val root = Json.parseToJsonElement(export(phone)).jsonObject
        assertEquals(SettingsBackup.FORMAT, root["format"]!!.jsonPrimitive.content)
        assertEquals(SettingsBackup.VERSION, root["version"]!!.jsonPrimitive.int)
        assertEquals("0.6.12", root["app"]!!.jsonPrimitive.content)
        val keys = root["settings"]!!.jsonObject.keys
        assertEquals(SettingsBackup.items.map { it.key }.toSet(), keys)
        for (machine in SettingsBackup.machineKeys.keys) assertFalse(
            "$machine 不该导出",
            machine in keys,
        )
        assertFalse("model" in keys)
        assertFalse("permissionMode" in keys)
        assertFalse("accent" in keys)
        assertFalse("apiToken" in keys)
        assertFalse(export(phone).contains("fake-token-1"))
        // 常用指令导出时不带这台手机上的 id
        val prompts = root["settings"]!!.jsonObject["savedPrompts"]!!.toString()
        assertEquals(
            """[{"name":"","text":"检查 Git 状态"},{"name":"简报","text":"汇总今天的日程\n三行说完"}]""",
            prompts,
        )
        // 定时任务、通知触发只带用户定的那些，不带 id、接着的会话、运行记录、跳过次数、冷却和今天的次数
        val task = root["settings"]!!.jsonObject["scheduledTasks"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("name", "prompt", "rule", "enabled", "model", "permissionMode", "newSession"),
            task.keys,
        )
        val rule = root["settings"]!!.jsonObject["notifyRules"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf(
                "name",
                "package",
                "appLabel",
                "keywords",
                "prompt",
                "enabled",
                "permissionMode",
                "cooldown",
                "dailyLimit",
            ),
            rule.keys,
        )
        assertFalse(export(phone).contains("0a1b2c3d-0000-4000-8000-000000000002"))
    }

    @Test
    fun exportFillsDefaultsAndSkipsUnsetCwd() {
        val settings =
            Json.parseToJsonElement(export(emptyMap<String, Any?>()))
                .jsonObject["settings"]!!
                .jsonObject
        assertEquals(JsonNull, settings["dark"])
        assertEquals(JsonNull, settings["assistantModel"])
        assertEquals(JsonPrimitive(true), settings["phoneTools"])
        assertEquals(JsonPrimitive(false), settings["autoLoadRemoteImages"])
        assertEquals(JsonPrimitive(1f), settings["interfaceScale"])
        assertEquals(JsonPrimitive(true), settings["autoCompact"])
        assertEquals(JsonNull, settings["autoCompactPercent"])
        assertEquals("[]", settings["savedPrompts"].toString())
        assertEquals("[]", settings["scheduledTasks"].toString())
        assertEquals("[]", settings["notifyRules"].toString())
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
        val writes = SettingsBackup.writes(backup, plan)
        assertEquals(true, writes["dark"])
        assertEquals(0.9f, writes["interfaceScale"])
        assertEquals(false, writes["keepScreenOn"])
        assertEquals(false, writes["autoCompact"])
        assertEquals(70, writes["autoCompactPercent"])
        // 常用指令只带名字和内容，顺序不变，id 在这台手机上重新生成
        val prompts = SavedPrompts.decode(writes["savedPrompts"] as String)
        assertEquals(
            listOf("" to "检查 Git 状态", "简报" to "汇总今天的日程\n三行说完"),
            prompts.map { it.name to it.text },
        )
        assertTrue(prompts.none { it.id == "a" || it.id == "b" })
        assertEquals(false, writes["accessibilityGuard"])
        assertEquals("opus", writes["assistantModel"])
        assertEquals("/workspace/claude", writes["cwd"])
        assertEquals(setOf("0a1b2c3d-0000-4000-8000-000000000001"), writes["pinnedSessions"])
        assertEquals(
            "[{\"name\":\"Download\",\"source\":\"/storage/emulated/0/Download\"}]",
            writes["sharedFolders"],
        )
        // 和默认值一样的不写
        assertFalse("phoneTools" in writes)
        // 写回的只有白名单里的键
        assertTrue(writes.keys.all { key -> SettingsBackup.items.any { it.key == key } })
    }

    /** 定时任务和通知触发：确认清单列出名字；写回的是新的 id，运行记录从头算；运行留下的状态不同不算改动。 */
    @Test
    fun scheduledTasksAndNotifyRulesTravelWithoutRunState() {
        val backup = valid(export(phone))
        val plan = SettingsBackup.plan(backup, mapOf("user" to "someone"))
        val changed = plan.changes.associateBy { it.key }
        assertEquals(ItemChange("scheduledTasks", "定时任务", "无", "早报"), changed["scheduledTasks"])
        assertEquals(ItemChange("notifyRules", "通知触发", "无", "快递"), changed["notifyRules"])
        val writes = SettingsBackup.writes(backup, plan)
        val tasks = Schedules.decode(writes["scheduledTasks"] as String)
        assertEquals(listOf(Schedules.draftOf(task)), tasks.map(Schedules::draftOf))
        val restored = tasks.single()
        assertTrue(restored.id != "t1" && restored.id.isNotBlank())
        assertNull(restored.sessionId)
        assertNull(restored.lastRun)
        assertEquals(0, restored.skips)
        assertNull(restored.lastSkip)
        assertTrue(restored.created > task.created)
        val rules = NotifyRules.decode(writes["notifyRules"] as String)
        assertEquals(listOf(NotifyRules.draftOf(rule)), rules.map(NotifyRules::draftOf))
        val fresh = rules.single()
        assertTrue(fresh.id != "r1" && fresh.id.isNotBlank())
        assertNull(fresh.lastRun)
        assertNull(fresh.lastFired)
        assertNull(fresh.firedDay)
        assertEquals(0, fresh.firedCount)

        // 这台手机上同样的任务和规则，只是又跑过几次：不算改动
        val ranAgain =
            phone +
                mapOf(
                    "scheduledTasks" to
                        Schedules.encode(
                            listOf(
                                task.copy(id = "t9", lastRun = null, skips = 0, sessionId = null),
                            ),
                        ),
                    "notifyRules" to
                        NotifyRules.encode(listOf(rule.copy(id = "r9", firedCount = 7))),
                )
        val again = SettingsBackup.plan(backup, ranAgain)
        assertTrue(
            again.changes.toString(),
            again.changes.none { it.key == "scheduledTasks" || it.key == "notifyRules" },
        )
    }

    @Test
    fun badTasksAndRulesAreSkippedWithReasons() {
        fun reason(key: String, item: JsonObject): String =
            valid(file(buildJsonObject { put(key, buildJsonArray { add(item) }) }))
                .skipped
                .single()
                .reason
        val daily = buildJsonObject {
            put("kind", "daily")
            put("time", "08:00")
        }
        assertEquals(
            "第 1 个缺少指令或时间",
            reason(
                "scheduledTasks",
                buildJsonObject {
                    put("name", "坏的")
                    put("prompt", "做点事")
                    put(
                        "rule",
                        buildJsonObject {
                            put("kind", "daily")
                            put("time", "25:00")
                        },
                    )
                },
            ),
        )
        assertEquals(
            "第 1 个缺少指令或时间",
            reason(
                "scheduledTasks",
                buildJsonObject {
                    put("name", "空的")
                    put("rule", daily)
                },
            ),
        )
        assertEquals(
            "第 1 个（早报）：不认识的权限模式“yolo”",
            reason(
                "scheduledTasks",
                buildJsonObject {
                    put("name", "早报")
                    put("prompt", "做点事")
                    put("rule", daily)
                    put("permissionMode", "yolo")
                },
            ),
        )
        assertEquals(
            "第 1 个（早报）：不是有效的模型名",
            reason(
                "scheduledTasks",
                buildJsonObject {
                    put("name", "早报")
                    put("prompt", "做点事")
                    put("rule", daily)
                    put("model", "--dangerous")
                },
            ),
        )
        assertEquals(
            "第 1 个（早报）：工作目录要写 Linux 环境里的绝对路径",
            reason(
                "scheduledTasks",
                buildJsonObject {
                    put("name", "早报")
                    put("prompt", "做点事")
                    put("rule", daily)
                    put("cwd", "relative")
                },
            ),
        )
        assertEquals(
            "第 1 条缺少应用或指令",
            reason(
                "notifyRules",
                buildJsonObject {
                    put("name", "坏的")
                    put("package", "not a package")
                    put("prompt", "做点事")
                },
            ),
        )
        assertEquals(
            "第 1 条（自己）：Zeta 自己的通知不会触发任务",
            reason(
                "notifyRules",
                buildJsonObject {
                    put("name", "自己")
                    put("package", "com.dzshzx.zeta")
                    put("prompt", "做点事")
                },
            ),
        )
        // 备份里带了运行状态（比如手改过的文件）：不认，不导入
        val stale =
            valid(
                file(
                    buildJsonObject {
                        put(
                            "scheduledTasks",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", "t1")
                                        put("name", "早报")
                                        put("prompt", "做点事")
                                        put("rule", daily)
                                        put("skips", 5)
                                    },
                                )
                            },
                        )
                    },
                ),
            )
        assertEquals(
            """[{"name":"早报","prompt":"做点事","rule":{"kind":"daily","time":"08:00"},"enabled":true,"permissionMode":"auto","newSession":true}]""",
            stale.values.getValue("scheduledTasks").toString(),
        )
    }

    @Test
    fun nullMeansBackToDefault() {
        val backup =
            valid(
                file(
                    buildJsonObject {
                        put("dark", JsonNull)
                        put("assistantModel", JsonNull)
                    },
                ),
            )
        val plan = SettingsBackup.plan(backup, phone)
        assertEquals(setOf("dark", "assistantModel"), plan.changes.map { it.key }.toSet())
        val writes = SettingsBackup.writes(backup, plan)
        assertTrue("dark" in writes && writes["dark"] == null)
        assertTrue("assistantModel" in writes && writes["assistantModel"] == null)
    }

    @Test
    fun badFilesAreRejected() {
        fun reason(text: String) = (SettingsBackup.parse(text) as BackupFile.Invalid).reason
        reason("not json")
        reason("[1, 2]")
        assertEquals(
            "不是 Zeta 的设置备份",
            reason("{\"format\":\"other\",\"version\":1,\"settings\":{}}"),
        )
        assertEquals("备份文件缺少格式版本号", reason("{\"format\":\"zeta-settings\",\"settings\":{}}"))
        assertEquals(
            "备份文件缺少格式版本号",
            reason("{\"format\":\"zeta-settings\",\"version\":\"1\",\"settings\":{}}"),
        )
        assertTrue(reason(file(buildJsonObject {}, version = 2)).contains("更新版本"))
        assertEquals("备份文件里没有设置", reason("{\"format\":\"zeta-settings\",\"version\":1}"))
    }

    @Test
    fun badItemsAreSkippedWithReasons() {
        val backup =
            valid(
                file(
                    buildJsonObject {
                        put("phoneTools", "yes")
                        put("autoLoadRemoteImages", true)
                        // 去掉了的设置项（旧版的纯黑背景开关）：跳过，不报错
                        put("pureBlack", true)
                        // 去掉了的强调色设置，带的是当时有效的值：同样跳过
                        put("accent", "teal")
                        put("interfaceScale", 5.0)
                        put("assistantModel", "opus; rm -rf /")
                        put("assistantEffort", "xhigh")
                        // 以前的 Zeta 导出的新对话默认值：同样是去掉了的
                        put("permissionMode", "acceptEdits")
                        put("cwd", "relative/dir")
                        put("keepScreenOn", JsonNull)
                        put("pinnedSessions", buildJsonArray { add(JsonPrimitive("../../etc")) })
                        put(
                            "sharedFolders",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "sys")
                                        put("source", "/system/etc")
                                    },
                                )
                            },
                        )
                        put("autoCompact", "off")
                        put("autoCompactPercent", 150)
                        put(
                            "savedPrompts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "空的")
                                        put("text", " ")
                                    },
                                )
                            },
                        )
                        put("uid", 0)
                        put("rootfs", "/")
                        put("somethingNew", 1)
                    },
                ),
            )
        assertEquals(
            mapOf(
                "autoLoadRemoteImages" to JsonPrimitive(true),
                "assistantEffort" to JsonPrimitive("xhigh"),
            ),
            backup.values,
        )
        val skipped = backup.skipped.associateBy { it.key }
        assertEquals(
            setOf(
                "phoneTools",
                "accent",
                "interfaceScale",
                "assistantModel",
                "permissionMode",
                "cwd",
                "keepScreenOn",
                "pinnedSessions",
                "sharedFolders",
                "uid",
                "rootfs",
                "somethingNew",
                "pureBlack",
                "autoCompact",
                "autoCompactPercent",
                "savedPrompts",
            ),
            skipped.keys,
        )
        assertTrue(skipped.getValue("uid").reason.startsWith("这台手机特有的设置"))
        for (gone in listOf("somethingNew", "pureBlack", "permissionMode", "accent")) {
            assertTrue(skipped.getValue(gone).reason.startsWith("这个版本的 Zeta 没有这一项"))
        }
        assertTrue(skipped.getValue("sharedFolders").reason.contains("不能共享"))
        assertEquals("第 1 条：内容不能为空", skipped.getValue("savedPrompts").reason)
        // 跳过的项原样带进确认清单
        val plan = SettingsBackup.plan(backup, emptyMap<String, Any?>())
        assertEquals(backup.skipped, plan.skipped)
        assertEquals(
            setOf("autoLoadRemoteImages", "assistantEffort"),
            plan.changes.map { it.key }.toSet(),
        )
    }

    @Test
    fun sharedFoldersAreValidatedAsAWhole() {
        val duplicate =
            valid(
                file(
                    buildJsonObject {
                        put(
                            "sharedFolders",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "a")
                                        put("source", "/storage/emulated/0/Download")
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put("name", "a")
                                        put("source", "/storage/emulated/0/DCIM")
                                    },
                                )
                            },
                        )
                    },
                ),
            )
        assertTrue(duplicate.values.isEmpty())
        assertTrue(duplicate.skipped.single().reason.contains("挂载名已被使用"))
        val normalized =
            valid(
                file(
                    buildJsonObject {
                        put(
                            "sharedFolders",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("name", "dl")
                                        put("source", "/storage/emulated/0//Download/")
                                    },
                                )
                            },
                        )
                    },
                ),
            )
        assertEquals(
            "/storage/emulated/0/Download",
            normalized.values.getValue("sharedFolders").toString().let {
                Regex("\"source\":\"([^\"]+)\"").find(it)!!.groupValues[1]
            },
        )
    }

    @Test
    fun extraSkipsDropAnItemFromThePlan() {
        val backup = valid(export(phone))
        val plan =
            SettingsBackup.plan(
                backup,
                mapOf("cwd" to "/workspace"),
                extraSkips = mapOf("cwd" to "这台手机的 Linux 环境里没有这个目录"),
            )
        assertNull(plan.changes.firstOrNull { it.key == "cwd" })
        assertEquals("这台手机的 Linux 环境里没有这个目录", plan.skipped.single { it.key == "cwd" }.reason)
        assertFalse("cwd" in SettingsBackup.writes(backup, plan))
    }

    @Test
    fun accessibilityGuardSwitchIsBackedUpUnderItsOwnKey() {
        // 守护读写的就是这个键；改名时两边要一起改
        assertTrue(SettingsBackup.items.any { it.key == AccessibilityGuard.PREF_AUTO_RESTORE })
        val settings =
            Json.parseToJsonElement(export(emptyMap<String, Any?>()))
                .jsonObject["settings"]!!
                .jsonObject
        assertEquals("默认开着", JsonPrimitive(true), settings[AccessibilityGuard.PREF_AUTO_RESTORE])
    }

    @Test
    fun scaleBoundsSurviveFloatRounding() {
        val low = valid(file(buildJsonObject { put("interfaceScale", 0.8f) }))
        assertEquals(
            0.8f,
            SettingsBackup.writes(low, SettingsBackup.plan(low, emptyMap<String, Any?>()))[
                    "interfaceScale"],
        )
        val tooLow = valid(file(buildJsonObject { put("interfaceScale", 0.5) }))
        assertTrue(tooLow.values.isEmpty())
    }
}
