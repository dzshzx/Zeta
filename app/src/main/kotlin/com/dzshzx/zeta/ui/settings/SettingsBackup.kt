package com.dzshzx.zeta.ui.settings

import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.core.SessionIdPattern
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.core.schedule.ScheduleAlarms
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.ui.app.AccentChoice
import com.dzshzx.zeta.ui.app.MAX_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.MIN_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.normalizeInterfaceScale
import com.dzshzx.zeta.ui.chat.PermissionModes
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 文件里一项设置的校验结果：合格时是规整后的值，否则是原因。 */
internal sealed interface Checked {
    data class Ok(val value: JsonElement) : Checked

    data class Bad(val reason: String) : Checked
}

/**
 * 一项可以备份的设置。文件里的键和 SharedPreferences `zeta` 里的键相同。 [read] 从存储的快照里取有效值（没存过的取默认值，[JsonNull]
 * 表示“跟随默认”）； [check] 校验文件里的值；[write] 把合格的值换成要写回存储的值（null 表示删掉这一键，回到默认）。 [nullable] 的项可以是 null（跟随系统或
 * Claude Code 的默认值）；别的项没有值时不导出。
 */
internal class BackupItem(
    val key: String,
    val label: String,
    val read: (Map<String, *>) -> JsonElement,
    val check: (JsonElement) -> Checked,
    val write: (JsonElement) -> Any?,
    val show: (JsonElement) -> String,
    val nullable: Boolean = false,
)

/** 导入时没用上的一项，及原因。 */
internal data class SkippedItem(val key: String, val label: String, val reason: String)

/** 导入会改的一项：从什么改成什么（都是给人看的文字）。 */
internal data class ItemChange(val key: String, val label: String, val from: String, val to: String)

/** 读备份文件的结果。 */
internal sealed interface BackupFile {
    data class Invalid(val reason: String) : BackupFile

    /** [values] 是校验通过的项（键 → 规整后的值）；[skipped] 是文件里有、但不导入的项。 */
    data class Valid(
        val version: Int,
        val app: String?,
        val exportedAt: String?,
        val values: Map<String, JsonElement>,
        val skipped: List<SkippedItem>,
    ) : BackupFile
}

/** 导入前给人确认的清单：要改的、和当前一样的、跳过的。 */
internal data class ImportPlan(
    val changes: List<ItemChange>,
    val unchanged: List<String>,
    val skipped: List<SkippedItem>,
)

/**
 * Zeta 设置的备份与恢复：SharedPreferences `zeta` 里 Zeta 自己的设置和一个 JSON 文件互相转换。
 * 这里只有转换和校验；读写文件、写回存储（[restoreBackup]）、刷新界面在 SettingsBackupScreen.kt。
 *
 * 只导出 [items] 列出的项（白名单），Claude Code 的配置、会话、登录状态都不在 Zeta 的设置里，也就不会进备份。 存储里别的键是这台手机特有的，不导出，原因见
 * [machineKeys]；文件里出现它们也不导入。 存储里的每个键都在这两处之一，SettingsKeysTest 核对。
 */
internal object SettingsBackup {
    const val FORMAT = "zeta-settings"
    const val VERSION = 1

    /** 备份文件只有几 KB；比这大的不当它是备份。 */
    const val MAX_BYTES = 256 * 1024

    private const val MAX_PINNED = 1000
    private val ModelPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/@\\[\\]-]{0,127}")

    /** 模型名：Claude Code 认的别名或完整 ID。 */
    private fun modelName(v: JsonElement): Checked {
        val text = v.text()
        return if (text != null && ModelPattern.matches(text)) Checked.Ok(JsonPrimitive(text))
        else Checked.Bad("不是有效的模型名")
    }

    /** 存储里有、但不导出的键：都和这台手机绑在一起，搬到别处没有意义或者会出错。 */
    val machineKeys: Map<String, String> =
        mapOf(
            "rootfs" to "Linux 环境装在这台手机的哪个位置，换手机或重装后由 Zeta 重新检测",
            "user" to "Linux 环境里的用户，由 Zeta 检测",
            "uid" to "Linux 环境里的用户，由 Zeta 检测",
            "gid" to "Linux 环境里的用户，由 Zeta 检测",
            "home" to "Linux 环境里的用户，由 Zeta 检测",
            "phoneDisplay" to "调试用的屏幕编号，只对当时那块虚拟屏有效",
            "updateDevManifest" to "测试版调试应用内更新用的清单地址，只对当时的测试有效",
            "updateDevCert" to "测试版调试应用内更新用的信任证书，只对当时的测试有效",
            "updateCheckedAt" to "这台手机上次检查更新的时间",
            "updateNotified" to "这台手机已经提醒过的新版本",
            "mutedApps" to "为后台运行临时静音、还没恢复的应用，是这台手机当下的状态",
            "commands" to "各设备上 Claude Code 斜杠命令的缓存，打开对话时自动更新",
            "contextWindows" to "各模型上下文窗口的缓存，Claude Code 会重新报",
            "hostModes" to "还开着的 Claude Code 进程上的权限模式，进程没了就作废",
            "remoteClaude" to "各台远程设备上的自动压缩、压缩时机和自动标题，跟着远程设备的清单走",
            "remoteHosts" to
                "远程设备靠这台手机的密钥连过去（Debian 里 ~/.ssh 的，或 Zeta 直连用的、存在这台手机里的），换手机后先让那些设备认新手机的公钥再加",
            "backend" to "侧栏正看着哪台设备，跟着远程设备的清单走",
            "paneAll" to "侧栏列不列全部设备的对话，跟着远程设备的清单走",
            "localName" to "给这台手机起的名字，换手机后按新手机的名字显示",
            ScheduleAlarms.ARMED to "定时任务在这台手机上排过的闹钟，重排时据此取消已删掉的任务的闹钟；导入定时任务后会重新排",
        )

    val items: List<BackupItem> =
        listOf(
            BackupItem(
                key = "dark",
                label = "外观与主题",
                nullable = true,
                read = { prefs -> (prefs["dark"] as? Boolean)?.let(::JsonPrimitive) ?: JsonNull },
                check = { v -> if (v is JsonNull) Checked.Ok(JsonNull) else bool(v) },
                write = { v -> (v as? JsonPrimitive)?.booleanOrNull },
                show = { v ->
                    when ((v as? JsonPrimitive)?.booleanOrNull) {
                        null -> "跟随系统"
                        true -> "深色"
                        false -> "浅色"
                    }
                },
            ),
            BackupItem(
                key = "accent",
                label = "强调色",
                read = { prefs ->
                    JsonPrimitive(AccentChoice.from(prefs["accent"] as? String).key)
                },
                check = { v -> choice(v, AccentChoice.entries.map { it.key }) },
                write = { v -> v.text() },
                show = { v ->
                    AccentChoice.entries.firstOrNull { it.key == v.text() }?.label
                        ?: v.text().orEmpty()
                },
            ),
            BackupItem(
                key = "interfaceScale",
                label = "界面缩放",
                read = { prefs ->
                    JsonPrimitive(
                        normalizeInterfaceScale((prefs["interfaceScale"] as? Float) ?: 1f),
                    )
                },
                check = ::scale,
                write = { v -> (v as JsonPrimitive).floatOrNull },
                show = { v ->
                    "${(((v as? JsonPrimitive)?.floatOrNull ?: 1f) * 100).roundToInt()}%"
                },
            ),
            boolItem("keepScreenOn", "对话进行中保持屏幕常亮", default = true),
            boolItem("autoLoadRemoteImages", "自动加载网络图片", default = false),
            boolItem("autoReadAnswers", "回答自动朗读", default = false),
            boolItem("autoTitle", "自动生成对话标题", default = true),
            boolItem("phoneTools", "手机操作", default = true),
            boolItem("backgroundScreen", "在后台屏幕上操作", default = true),
            boolItem("accessibilityGuard", "无障碍服务被系统关掉后自动打开", default = true),
            optionalItem(
                "assistantModel",
                "数字助理的模型",
                unset = "跟随 Claude Code",
                check = ::modelName,
            ),
            optionalItem(
                "assistantEffort",
                "数字助理的思考强度",
                unset = "跟随 Claude Code",
                labels = EffortLevels.toMap(),
            ),
            boolItem("autoCompact", "自动压缩上下文", default = true),
            boolItem("autoUpdateCheck", "自动检查更新", default = true),
            BackupItem(
                key = "autoCompactPercent",
                label = "压缩时机",
                nullable = true,
                read = { prefs ->
                    (prefs["autoCompactPercent"] as? Int)
                        ?.takeIf { it in 1..100 }
                        ?.let(::JsonPrimitive) ?: JsonNull
                },
                check = { v ->
                    val n = (v as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    when {
                        v is JsonNull -> Checked.Ok(JsonNull)
                        n != null && n in 1..100 -> Checked.Ok(JsonPrimitive(n))
                        else -> Checked.Bad("应为 1 到 100 的整数")
                    }
                },
                write = { v -> (v as? JsonPrimitive)?.intOrNull },
                show = { v -> compactPercentLabel((v as? JsonPrimitive)?.intOrNull) },
            ),
            BackupItem(
                key = "cwd",
                label = "默认工作目录",
                read = { prefs -> (prefs["cwd"] as? String)?.let(::JsonPrimitive) ?: JsonNull },
                check = { v ->
                    val path = v.text()?.takeIf { it.length <= 4096 }?.let(SharedFolders::normalize)
                    if (path != null) Checked.Ok(JsonPrimitive(path)) else Checked.Bad("不是有效的绝对路径")
                },
                write = { v -> v.text() },
                show = { v -> v.text().orEmpty() },
            ),
            BackupItem(
                key = "pinnedSessions",
                label = "置顶的对话",
                read = { prefs ->
                    val ids =
                        (prefs["pinnedSessions"] as? Set<*>)
                            .orEmpty()
                            .filterIsInstance<String>()
                            .sorted()
                    buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }
                },
                check = ::pinned,
                write = { v -> (v as JsonArray).mapNotNull { it.text() }.toSet() },
                show = { v -> (v as? JsonArray)?.size?.takeIf { it > 0 }?.let { "$it 个" } ?: "无" },
            ),
            BackupItem(
                key = "sharedFolders",
                label = "共享文件夹",
                read = { prefs ->
                    foldersJson(SharedFolders.decode(prefs["sharedFolders"] as? String))
                },
                check = ::folders,
                write = { v -> SharedFolders.encode(foldersOf(v)) },
                show = { v -> foldersOf(v).joinToString("、") { it.name }.ifEmpty { "无" } },
            ),
            // 只带名字和内容；id 是这台手机上的，导入时重新生成
            BackupItem(
                key = SavedPrompts.KEY,
                label = "常用指令",
                read = { prefs ->
                    promptsJson(SavedPrompts.decode(prefs[SavedPrompts.KEY] as? String))
                },
                check = ::prompts,
                write = { v ->
                    SavedPrompts.encode(
                        promptsOf(v).map { it.copy(id = UUID.randomUUID().toString()) },
                    )
                },
                show = { v -> (v as? JsonArray)?.size?.takeIf { it > 0 }?.let { "$it 条" } ?: "无" },
            ),
            // 定时任务和通知触发只带用户定的那些（[TaskFields]、[RuleFields]）；id、创建时间、运行记录、跳过次数、接着的会话、
            // 冷却和今天的次数都是这台手机上跑出来的，导入时重新生成、从头算。写回时整份换掉，经各自的 store 写，定时任务的闹钟跟着重排
            BackupItem(
                key = Schedules.KEY,
                label = "定时任务",
                read = { prefs -> tasksJson(Schedules.decode(prefs[Schedules.KEY] as? String)) },
                check = ::tasks,
                write = { v ->
                    val now = System.currentTimeMillis()
                    Schedules.encode(
                        tasksOf(v).map { it.copy(id = Schedules.newId(), created = now) },
                    )
                },
                show = ::names,
            ),
            BackupItem(
                key = NotifyRules.KEY,
                label = "通知触发",
                read = { prefs ->
                    rulesJson(NotifyRules.decode(prefs[NotifyRules.KEY] as? String))
                },
                check = ::rules,
                write = { v ->
                    val now = System.currentTimeMillis()
                    NotifyRules.encode(
                        rulesOf(v).map { it.copy(id = NotifyRules.newId(), created = now) },
                    )
                },
                show = ::names,
            ),
        )

    /** 定时任务在备份里带的字段（存储里的写法）：编辑页上用户定的那些。 */
    private val TaskFields =
        setOf(
            "name",
            "prompt",
            "rule",
            "enabled",
            "cwd",
            "model",
            "permissionMode",
            "newSession",
            "onlyOnChange",
        )

    /** 通知触发的规则在备份里带的字段（存储里的写法）：编辑页上用户定的那些。 */
    private val RuleFields =
        setOf(
            "name",
            "package",
            "appLabel",
            "keywords",
            "window",
            "prompt",
            "enabled",
            "cwd",
            "model",
            "permissionMode",
            "cooldown",
            "dailyLimit",
            "onlyOnChange",
        )

    private val byKey = items.associateBy { it.key }

    /** 导出：存储的快照 → 备份文件的文字。只含 [items] 里的项；没存过、也没有默认值的（比如还没有环境时的工作目录）不写。 */
    fun export(prefs: Map<String, *>, app: String, exportedAt: String): String {
        val settings = buildJsonObject {
            for (item in items) {
                val value = item.read(prefs)
                if (value is JsonNull && !item.nullable) continue
                put(item.key, value)
            }
        }
        val file = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("app", app)
            put("exportedAt", exportedAt)
            put("settings", settings)
        }
        return pretty.encodeToString(JsonObject.serializer(), file) + "\n"
    }

    /** 导入第一步：读文件、核对格式和版本，逐项校验。坏的项、这台手机特有的项、不认识的项记进 skipped，不影响别的项。 */
    fun parse(text: String): BackupFile {
        val root =
            runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
                ?: return BackupFile.Invalid("文件不是 JSON")
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT)
            return BackupFile.Invalid("不是 Zeta 的设置备份")
        val version =
            (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return BackupFile.Invalid("备份文件缺少格式版本号")
        if (version < 1) return BackupFile.Invalid("格式版本号无效")
        if (version > VERSION)
            return BackupFile.Invalid("备份来自更新版本的 Zeta（格式版本 $version），请先升级 Zeta 再导入")
        val settings = root["settings"] as? JsonObject ?: return BackupFile.Invalid("备份文件里没有设置")
        val values = LinkedHashMap<String, JsonElement>()
        val skipped = ArrayList<SkippedItem>()
        for (item in items) {
            val raw = settings[item.key] ?: continue
            when (
                val checked =
                    if (raw is JsonNull && !item.nullable) Checked.Bad("没有值") else item.check(raw)
            ) {
                is Checked.Ok -> values[item.key] = checked.value
                is Checked.Bad -> skipped += SkippedItem(item.key, item.label, checked.reason)
            }
        }
        for (key in settings.keys) {
            if (key in byKey) continue
            skipped +=
                machineKeys[key]?.let { SkippedItem(key, key, "这台手机特有的设置，不导入：$it") }
                    ?: SkippedItem(key, key, "这个版本的 Zeta 没有这一项（更新版本加的，或已经去掉了）")
        }
        return BackupFile.Valid(
            version = version,
            app = (root["app"] as? JsonPrimitive)?.contentOrNull,
            exportedAt = (root["exportedAt"] as? JsonPrimitive)?.contentOrNull,
            values = values,
            skipped = skipped,
        )
    }

    /** 导入第二步：和当前设置比一比，列出会改的项。[extraSkips] 是导入前另外查出来用不了的项（键 → 原因）， 比如这台手机的 Linux 环境里没有备份里的工作目录。 */
    fun plan(
        backup: BackupFile.Valid,
        prefs: Map<String, *>,
        extraSkips: Map<String, String> = emptyMap(),
    ): ImportPlan {
        val changes = ArrayList<ItemChange>()
        val unchanged = ArrayList<String>()
        val skipped = ArrayList(backup.skipped)
        for ((key, value) in backup.values) {
            val item = byKey.getValue(key)
            extraSkips[key]?.let {
                skipped += SkippedItem(key, item.label, it)
                continue
            }
            val now = item.read(prefs)
            if (now == value) unchanged += item.label
            else changes += ItemChange(key, item.label, item.show(now), item.show(value))
        }
        return ImportPlan(changes, unchanged, skipped)
    }

    /** 导入第三步：要写回存储的值（键 → 值，null 表示删掉这一键）。只写会改的项。 */
    fun writes(backup: BackupFile.Valid, plan: ImportPlan): Map<String, Any?> =
        plan.changes.associate { change ->
            change.key to byKey.getValue(change.key).write(backup.values.getValue(change.key))
        }

    private val pretty = Json { prettyPrint = true }

    private fun JsonElement.text(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun boolItem(key: String, label: String, default: Boolean) =
        BackupItem(
            key = key,
            label = label,
            read = { prefs -> JsonPrimitive((prefs[key] as? Boolean) ?: default) },
            check = ::bool,
            write = { v -> (v as JsonPrimitive).booleanOrNull },
            show = { v -> if ((v as? JsonPrimitive)?.booleanOrNull == true) "开" else "关" },
        )

    /** 可以不设（null，跟随 Claude Code）的文字项；给了 [labels] 时只认其中的值。 */
    private fun optionalItem(
        key: String,
        label: String,
        unset: String,
        labels: Map<String, String> = emptyMap(),
        check: (JsonElement) -> Checked = { v -> choice(v, labels.keys) },
    ) =
        BackupItem(
            key = key,
            label = label,
            nullable = true,
            read = { prefs -> (prefs[key] as? String)?.let(::JsonPrimitive) ?: JsonNull },
            check = { v -> if (v is JsonNull) Checked.Ok(JsonNull) else check(v) },
            write = { v -> v.text() },
            show = { v -> v.text()?.let { labels[it] ?: it } ?: unset },
        )

    private fun bool(v: JsonElement): Checked {
        val p = v as? JsonPrimitive
        val b =
            p?.takeUnless { it.isString }?.booleanOrNull
                ?: return Checked.Bad("应为开关值（true 或 false）")
        return Checked.Ok(JsonPrimitive(b))
    }

    private fun choice(v: JsonElement, allowed: Collection<String>): Checked {
        val text = v.text() ?: return Checked.Bad("应为文字")
        return if (text in allowed) Checked.Ok(JsonPrimitive(text))
        else Checked.Bad("不认识的取值“${text.take(40)}”")
    }

    private fun scale(v: JsonElement): Checked {
        val f =
            (v as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
                ?: return Checked.Bad("应为数字")
        // 存的是 Float，0.8 之类的边界值读回来会差一点点
        if (!f.isFinite() || f < MIN_INTERFACE_SCALE - 0.001f || f > MAX_INTERFACE_SCALE + 0.001f) {
            return Checked.Bad(
                "应在 ${(MIN_INTERFACE_SCALE * 100).roundToInt()}% 到 ${(MAX_INTERFACE_SCALE * 100).roundToInt()}% 之间",
            )
        }
        return Checked.Ok(JsonPrimitive(normalizeInterfaceScale(f)))
    }

    private fun pinned(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad("应为会话 id 的列表")
        if (array.size > MAX_PINNED) return Checked.Bad("条目太多")
        val ids = array.map {
            it.text()?.takeIf(SessionIdPattern::matches) ?: return Checked.Bad("含有无效的会话 id")
        }
        return Checked.Ok(
            buildJsonArray { ids.distinct().sorted().forEach { add(JsonPrimitive(it)) } },
        )
    }

    private fun folders(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad("应为列表")
        if (array.size > SharedFolders.MAX) return Checked.Bad("最多 ${SharedFolders.MAX} 个")
        val list = ArrayList<SharedFolder>()
        for ((index, element) in array.withIndex()) {
            val item = element as? JsonObject
            val name = item?.get("name")?.text()?.trim()
            val source = item?.get("source")?.text()
            if (name == null || source == null) return Checked.Bad("第 ${index + 1} 个缺少名字或目录")
            val normalized =
                SharedFolders.normalize(source) ?: return Checked.Bad("第 ${index + 1} 个：目录路径无效")
            val problem =
                SharedFolders.nameProblem(name, list)
                    ?: SharedFolders.sourceProblem(normalized, list, rootfs = null)
            if (problem != null) return Checked.Bad("第 ${index + 1} 个（$name）：$problem")
            list += SharedFolder(name, normalized)
        }
        return Checked.Ok(foldersJson(list))
    }

    private fun prompts(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad("应为列表")
        if (array.size > SavedPrompts.MAX) return Checked.Bad("最多 ${SavedPrompts.MAX} 条")
        val list = ArrayList<SavedPrompt>()
        for ((index, element) in array.withIndex()) {
            val item = element as? JsonObject
            val name = item?.get("name")?.let { if (it is JsonNull) "" else it.text() }
            val text = item?.get("text")?.text()
            if (name == null || text == null) return Checked.Bad("第 ${index + 1} 条缺少名字或内容")
            SavedPrompts.problem(emptyList(), name, text)?.let {
                return Checked.Bad("第 ${index + 1} 条：$it")
            }
            list += SavedPrompt("", name.trim(), text)
        }
        return Checked.Ok(promptsJson(list))
    }

    /** 定时任务、通知触发规则的列表：逐个按存储的写法解码（补一个临时的 id），再用编辑页的规矩校验。 */
    private fun tasks(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad("应为列表")
        if (array.size > Schedules.MAX_TASKS) return Checked.Bad("最多 ${Schedules.MAX_TASKS} 个")
        val list = ArrayList<ScheduledTask>()
        for ((index, element) in array.withIndex()) {
            val task = taskOf(element) ?: return Checked.Bad("第 ${index + 1} 个缺少指令或时间")
            // 不看时间：时间已过的单次任务照样导入，和跑过的一样在列表上显示出来
            val problem =
                Schedules.problem(
                    Schedules.draftOf(task),
                    Instant.now(),
                    ZoneId.systemDefault(),
                    checkRule = false,
                ) ?: runProblem(task.model, task.permissionMode)
            if (problem != null) return Checked.Bad("第 ${index + 1} 个（${task.name}）：$problem")
            list += task
        }
        return Checked.Ok(tasksJson(list))
    }

    private fun rules(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad("应为列表")
        if (array.size > NotifyRules.MAX_RULES) return Checked.Bad("最多 ${NotifyRules.MAX_RULES} 条")
        val list = ArrayList<NotifyRule>()
        for ((index, element) in array.withIndex()) {
            val rule = ruleOf(element) ?: return Checked.Bad("第 ${index + 1} 条缺少应用或指令")
            val problem =
                NotifyRules.problem(NotifyRules.draftOf(rule))
                    ?: runProblem(rule.model, rule.permissionMode)
            if (problem != null) return Checked.Bad("第 ${index + 1} 条（${rule.name}）：$problem")
            list += rule
        }
        return Checked.Ok(rulesJson(list))
    }

    /** 运行用的模型和权限模式：模型是 Claude Code 认的名字（没有表示默认），模式是界面上能选的那几种。 */
    private fun runProblem(model: String?, mode: String): String? =
        when {
            model != null && !ModelPattern.matches(model) -> "不是有效的模型名"
            PermissionModes.none { it.first == mode } -> "不认识的权限模式“${mode.take(40)}”"
            else -> null
        }

    private fun JsonObject.only(keys: Set<String>) = JsonObject(filterKeys { it in keys })

    private fun withTempId(element: JsonElement, keys: Set<String>): JsonObject? =
        (element as? JsonObject)?.let {
            JsonObject(it.only(keys) + ("id" to JsonPrimitive("backup")))
        }

    private fun taskOf(element: JsonElement): ScheduledTask? =
        withTempId(element, TaskFields)?.let {
            runCatching { Schedules.decodeTask(it) }.getOrNull()
        }

    private fun ruleOf(element: JsonElement): NotifyRule? =
        withTempId(element, RuleFields)?.let {
            runCatching { NotifyRules.decodeRule(it) }.getOrNull()
        }

    private fun tasksJson(list: List<ScheduledTask>) =
        JsonArray(list.map { Schedules.encodeTask(it).only(TaskFields) })

    private fun rulesJson(list: List<NotifyRule>) =
        JsonArray(list.map { NotifyRules.encodeRule(it).only(RuleFields) })

    private fun tasksOf(v: JsonElement): List<ScheduledTask> =
        (v as? JsonArray).orEmpty().mapNotNull(::taskOf)

    private fun rulesOf(v: JsonElement): List<NotifyRule> =
        (v as? JsonArray).orEmpty().mapNotNull(::ruleOf)

    /** 确认清单上的一项：各个的名字。 */
    private fun names(v: JsonElement): String =
        (v as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.text() }
            .joinToString("、")
            .ifEmpty { "无" }

    private fun promptsJson(list: List<SavedPrompt>): JsonArray = buildJsonArray {
        list.forEach {
            add(
                buildJsonObject {
                    put("name", it.name)
                    put("text", it.text)
                },
            )
        }
    }

    private fun promptsOf(v: JsonElement): List<SavedPrompt> =
        (v as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            SavedPrompt(
                "",
                item["name"]?.text().orEmpty(),
                item["text"]?.text() ?: return@mapNotNull null,
            )
        }

    private fun foldersJson(list: List<SharedFolder>): JsonArray = buildJsonArray {
        list.forEach {
            add(
                buildJsonObject {
                    put("name", it.name)
                    put("source", it.source)
                },
            )
        }
    }

    private fun foldersOf(v: JsonElement): List<SharedFolder> =
        (v as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            SharedFolder(
                item["name"]?.text() ?: return@mapNotNull null,
                item["source"]?.text() ?: return@mapNotNull null,
            )
        }
}
