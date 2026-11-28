package com.dzshzx.zeta.ui.settings

import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.core.SessionIdPattern
import com.dzshzx.zeta.core.SessionSort
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRules
import com.dzshzx.zeta.core.schedule.ScheduleAlarms
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.core.schedule.Schedules
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.app.MAX_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.MIN_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.normalizeInterfaceScale
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.pane.sessionSortLabel
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
    private val labelOf: () -> String,
    val read: (Map<String, *>) -> JsonElement,
    val check: (JsonElement) -> Checked,
    val write: (JsonElement) -> Any?,
    val show: (JsonElement) -> String,
    val nullable: Boolean = false,
) {
    /** 给人看的名字：按界面语言现取。 */
    val label: String
        get() = labelOf()
}

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
        else Checked.Bad(Strings.settingsBackupBadModel())
    }

    /** 存储里有、但不导出的键：都和这台手机绑在一起，搬到别处没有意义或者会出错。 */
    val machineKeys: Map<String, () -> String> =
        mapOf(
            "rootfs" to Strings::settingsBackupMachineRootfs,
            "user" to Strings::settingsBackupMachineUser,
            "uid" to Strings::settingsBackupMachineUser,
            "gid" to Strings::settingsBackupMachineUser,
            "home" to Strings::settingsBackupMachineUser,
            "phoneDisplay" to Strings::settingsBackupMachinePhoneDisplay,
            "updateDevManifest" to Strings::settingsBackupMachineUpdateDevManifest,
            "updateDevCert" to Strings::settingsBackupMachineUpdateDevCert,
            "updateCheckedAt" to Strings::settingsBackupMachineUpdateCheckedAt,
            "updateNotified" to Strings::settingsBackupMachineUpdateNotified,
            "mutedApps" to Strings::settingsBackupMachineMutedApps,
            "remoteClaude" to Strings::settingsBackupMachineRemoteClaude,
            "remoteHosts" to Strings::settingsBackupMachineRemoteHosts,
            "backend" to Strings::settingsBackupMachineBackend,
            "paneAll" to Strings::settingsBackupMachinePaneAll,
            "localName" to Strings::settingsBackupMachineLocalName,
            ScheduleAlarms.ARMED to Strings::settingsBackupMachineAlarms,
        )

    val items: List<BackupItem> =
        listOf(
            BackupItem(
                key = "dark",
                labelOf = Strings::settingsTitleAppearance,
                nullable = true,
                read = { prefs -> (prefs["dark"] as? Boolean)?.let(::JsonPrimitive) ?: JsonNull },
                check = { v -> if (v is JsonNull) Checked.Ok(JsonNull) else bool(v) },
                write = { v -> (v as? JsonPrimitive)?.booleanOrNull },
                show = { v ->
                    when ((v as? JsonPrimitive)?.booleanOrNull) {
                        null -> Strings.commonFollowSystem()
                        true -> Strings.commonThemeDark()
                        false -> Strings.commonThemeLight()
                    }
                },
            ),
            BackupItem(
                key = "interfaceScale",
                labelOf = Strings::settingsAppearanceScale,
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
            boolItem("keepScreenOn", Strings::settingsBackupKeepScreenOn, default = true),
            boolItem("autoLoadRemoteImages", Strings::settingsHomeAutoLoadImages, default = false),
            boolItem("autoReadAnswers", Strings::settingsHomeAutoRead, default = false),
            boolItem("autoTitle", Strings::settingsCompactAutoTitle, default = true),
            boolItem("phoneTools", Strings::settingsTitlePhone, default = true),
            boolItem("backgroundScreen", Strings::settingsPhoneAgentDisplay, default = true),
            boolItem(
                "accessibilityGuard",
                Strings::settingsBackupAccessibilityGuard,
                default = true,
            ),
            optionalItem(
                "assistantHarness",
                Strings::settingsBackupAssistantHarness,
                unset = Strings::commonFollowDeviceDefault,
                labels = { Harnesses.ALL.associateWith(Harnesses::shortTitle) },
            ),
            optionalItem(
                "assistantModel",
                Strings::settingsBackupAssistantModel,
                unset = Strings::commonFollowClaudeCode,
                check = ::modelName,
            ),
            optionalItem(
                "assistantEffort",
                Strings::settingsBackupAssistantEffort,
                unset = Strings::commonFollowClaudeCode,
                labels = { EffortLevels.toMap() },
            ),
            boolItem("autoCompact", Strings::settingsCompactAuto, default = true),
            boolItem("autoUpdateCheck", Strings::settingsAutoCheckUpdates, default = true),
            BackupItem(
                key = "autoCompactPercent",
                labelOf = Strings::settingsCompactTiming,
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
                        else -> Checked.Bad(Strings.settingsBackupBadPercent())
                    }
                },
                write = { v -> (v as? JsonPrimitive)?.intOrNull },
                show = { v -> compactPercentLabel((v as? JsonPrimitive)?.intOrNull) },
            ),
            BackupItem(
                key = "cwd",
                labelOf = Strings::settingsBackupDefaultCwd,
                read = { prefs -> (prefs["cwd"] as? String)?.let(::JsonPrimitive) ?: JsonNull },
                check = { v ->
                    val path = v.text()?.takeIf { it.length <= 4096 }?.let(SharedFolders::normalize)
                    if (path != null) Checked.Ok(JsonPrimitive(path))
                    else Checked.Bad(Strings.settingsBackupBadPath())
                },
                write = { v -> v.text() },
                show = { v -> v.text().orEmpty() },
            ),
            BackupItem(
                key = "pinnedSessions",
                labelOf = Strings::settingsBackupPinned,
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
                show = { v ->
                    (v as? JsonArray)
                        ?.size
                        ?.takeIf { it > 0 }
                        ?.let(Strings::settingsBackupPinnedCount) ?: Strings.commonNone()
                },
            ),
            BackupItem(
                key = "sessionSort",
                labelOf = Strings::settingsBackupSessionSort,
                read = { prefs ->
                    JsonPrimitive(SessionSort.of(prefs["sessionSort"] as? String).key)
                },
                check = { v -> choice(v, SessionSort.entries.map { it.key }) },
                write = { v -> v.text() },
                show = { v -> sessionSortLabel(SessionSort.of(v.text())) },
            ),
            BackupItem(
                key = "sharedFolders",
                labelOf = Strings::settingsBackupSharedFolders,
                read = { prefs ->
                    foldersJson(SharedFolders.decode(prefs["sharedFolders"] as? String))
                },
                check = ::folders,
                write = { v -> SharedFolders.encode(foldersOf(v)) },
                show = { v ->
                    foldersOf(v)
                        .joinToString(Strings.commonListSeparator()) { it.name }
                        .ifEmpty { Strings.commonNone() }
                },
            ),
            // 只带名字和内容；id 是这台手机上的，导入时重新生成
            BackupItem(
                key = SavedPrompts.KEY,
                labelOf = Strings::settingsBackupSavedPrompts,
                read = { prefs ->
                    promptsJson(SavedPrompts.decode(prefs[SavedPrompts.KEY] as? String))
                },
                check = ::prompts,
                write = { v ->
                    SavedPrompts.encode(
                        promptsOf(v).map { it.copy(id = UUID.randomUUID().toString()) },
                    )
                },
                show = { v ->
                    (v as? JsonArray)
                        ?.size
                        ?.takeIf { it > 0 }
                        ?.let(Strings::settingsBackupPromptCount) ?: Strings.commonNone()
                },
            ),
            // 定时任务和通知触发只带用户定的那些（[TaskFields]、[RuleFields]）；id、创建时间、运行记录、跳过次数、接着的会话、
            // 冷却和今天的次数都是这台手机上跑出来的，导入时重新生成、从头算。写回时整份换掉，经各自的 store 写，定时任务的闹钟跟着重排
            BackupItem(
                key = Schedules.KEY,
                labelOf = Strings::settingsTitleSchedules,
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
                labelOf = Strings::settingsTitleNotifyTriggers,
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
            "harness",
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
            "harness",
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
                ?: return BackupFile.Invalid(Strings.settingsBackupInvalidJson())
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT)
            return BackupFile.Invalid(Strings.settingsBackupInvalidFormat())
        val version =
            (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return BackupFile.Invalid(Strings.settingsBackupNoVersion())
        if (version < 1) return BackupFile.Invalid(Strings.settingsBackupBadVersion())
        if (version > VERSION)
            return BackupFile.Invalid(Strings.settingsBackupNewerVersion(version))
        val settings =
            root["settings"] as? JsonObject
                ?: return BackupFile.Invalid(Strings.settingsBackupNoSettings())
        val values = LinkedHashMap<String, JsonElement>()
        val skipped = ArrayList<SkippedItem>()
        for (item in items) {
            val raw = settings[item.key] ?: continue
            when (
                val checked =
                    if (raw is JsonNull && !item.nullable)
                        Checked.Bad(Strings.settingsBackupNoValue())
                    else item.check(raw)
            ) {
                is Checked.Ok -> values[item.key] = checked.value
                is Checked.Bad -> skipped += SkippedItem(item.key, item.label, checked.reason)
            }
        }
        for (key in settings.keys) {
            if (key in byKey) continue
            skipped +=
                machineKeys[key]?.let {
                    SkippedItem(key, key, Strings.settingsBackupMachineSkip(it()))
                } ?: SkippedItem(key, key, Strings.settingsBackupUnknownKey())
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

    private fun boolItem(key: String, label: () -> String, default: Boolean) =
        BackupItem(
            key = key,
            labelOf = label,
            read = { prefs -> JsonPrimitive((prefs[key] as? Boolean) ?: default) },
            check = ::bool,
            write = { v -> (v as JsonPrimitive).booleanOrNull },
            show = { v ->
                if ((v as? JsonPrimitive)?.booleanOrNull == true) Strings.settingsBackupOn()
                else Strings.settingsBackupOff()
            },
        )

    /** 可以不设（null，跟随 Claude Code）的文字项；给了 [labels] 时只认其中的值。 */
    private fun optionalItem(
        key: String,
        label: () -> String,
        unset: () -> String,
        labels: () -> Map<String, String> = { emptyMap() },
        check: (JsonElement) -> Checked = { v -> choice(v, labels().keys) },
    ) =
        BackupItem(
            key = key,
            labelOf = label,
            nullable = true,
            read = { prefs -> (prefs[key] as? String)?.let(::JsonPrimitive) ?: JsonNull },
            check = { v -> if (v is JsonNull) Checked.Ok(JsonNull) else check(v) },
            write = { v -> v.text() },
            show = { v -> v.text()?.let { labels()[it] ?: it } ?: unset() },
        )

    private fun bool(v: JsonElement): Checked {
        val p = v as? JsonPrimitive
        val b =
            p?.takeUnless { it.isString }?.booleanOrNull
                ?: return Checked.Bad(Strings.settingsBackupBadBool())
        return Checked.Ok(JsonPrimitive(b))
    }

    private fun choice(v: JsonElement, allowed: Collection<String>): Checked {
        val text = v.text() ?: return Checked.Bad(Strings.settingsBackupBadText())
        return if (text in allowed) Checked.Ok(JsonPrimitive(text))
        else Checked.Bad(Strings.settingsBackupUnknownValue(text.take(40)))
    }

    private fun scale(v: JsonElement): Checked {
        val f =
            (v as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
                ?: return Checked.Bad(Strings.settingsBackupBadNumber())
        // 存的是 Float，0.8 之类的边界值读回来会差一点点
        if (!f.isFinite() || f < MIN_INTERFACE_SCALE - 0.001f || f > MAX_INTERFACE_SCALE + 0.001f) {
            return Checked.Bad(
                Strings.settingsBackupBadScale(
                    (MIN_INTERFACE_SCALE * 100).roundToInt(),
                    (MAX_INTERFACE_SCALE * 100).roundToInt(),
                ),
            )
        }
        return Checked.Ok(JsonPrimitive(normalizeInterfaceScale(f)))
    }

    private fun pinned(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad(Strings.settingsBackupBadPinnedList())
        if (array.size > MAX_PINNED) return Checked.Bad(Strings.settingsBackupTooMany())
        val ids = array.map {
            it.text()?.takeIf(SessionIdPattern::matches)
                ?: return Checked.Bad(Strings.settingsBackupBadSessionId())
        }
        return Checked.Ok(
            buildJsonArray { ids.distinct().sorted().forEach { add(JsonPrimitive(it)) } },
        )
    }

    private fun folders(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad(Strings.settingsBackupBadList())
        if (array.size > SharedFolders.MAX)
            return Checked.Bad(Strings.settingsBackupMaxItems(SharedFolders.MAX))
        val list = ArrayList<SharedFolder>()
        for ((index, element) in array.withIndex()) {
            val item = element as? JsonObject
            val name = item?.get("name")?.text()?.trim()
            val source = item?.get("source")?.text()
            if (name == null || source == null)
                return Checked.Bad(Strings.settingsBackupFolderMissing(index + 1))
            val normalized =
                SharedFolders.normalize(source)
                    ?: return Checked.Bad(Strings.settingsBackupFolderBadPath(index + 1))
            val problem =
                SharedFolders.nameProblem(name, list)
                    ?: SharedFolders.sourceProblem(normalized, list, rootfs = null)
            if (problem != null)
                return Checked.Bad(Strings.settingsBackupItemProblem(index + 1, name, problem))
            list += SharedFolder(name, normalized)
        }
        return Checked.Ok(foldersJson(list))
    }

    private fun prompts(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad(Strings.settingsBackupBadList())
        if (array.size > SavedPrompts.MAX)
            return Checked.Bad(Strings.settingsBackupMaxEntries(SavedPrompts.MAX))
        val list = ArrayList<SavedPrompt>()
        for ((index, element) in array.withIndex()) {
            val item = element as? JsonObject
            val name = item?.get("name")?.let { if (it is JsonNull) "" else it.text() }
            val text = item?.get("text")?.text()
            if (name == null || text == null)
                return Checked.Bad(Strings.settingsBackupPromptMissing(index + 1))
            SavedPrompts.problem(emptyList(), name, text)?.let {
                return Checked.Bad(Strings.settingsBackupEntryProblem(index + 1, it))
            }
            list += SavedPrompt("", name.trim(), text)
        }
        return Checked.Ok(promptsJson(list))
    }

    /** 定时任务、通知触发规则的列表：逐个按存储的写法解码（补一个临时的 id），再用编辑页的规矩校验。 */
    private fun tasks(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad(Strings.settingsBackupBadList())
        if (array.size > Schedules.MAX_TASKS)
            return Checked.Bad(Strings.settingsBackupMaxItems(Schedules.MAX_TASKS))
        val list = ArrayList<ScheduledTask>()
        for ((index, element) in array.withIndex()) {
            val task =
                taskOf(element) ?: return Checked.Bad(Strings.settingsBackupTaskMissing(index + 1))
            // 不看时间：时间已过的单次任务照样导入，和跑过的一样在列表上显示出来
            val problem =
                Schedules.problem(
                    Schedules.draftOf(task),
                    Instant.now(),
                    ZoneId.systemDefault(),
                    checkRule = false,
                ) ?: runProblem(task.model, task.permissionMode)
            if (problem != null)
                return Checked.Bad(Strings.settingsBackupItemProblem(index + 1, task.name, problem))
            list += task
        }
        return Checked.Ok(tasksJson(list))
    }

    private fun rules(v: JsonElement): Checked {
        val array = v as? JsonArray ?: return Checked.Bad(Strings.settingsBackupBadList())
        if (array.size > NotifyRules.MAX_RULES)
            return Checked.Bad(Strings.settingsBackupMaxEntries(NotifyRules.MAX_RULES))
        val list = ArrayList<NotifyRule>()
        for ((index, element) in array.withIndex()) {
            val rule =
                ruleOf(element) ?: return Checked.Bad(Strings.settingsBackupRuleMissing(index + 1))
            val problem =
                NotifyRules.problem(NotifyRules.draftOf(rule))
                    ?: runProblem(rule.model, rule.permissionMode)
            if (problem != null)
                return Checked.Bad(
                    Strings.settingsBackupEntryNamedProblem(index + 1, rule.name, problem),
                )
            list += rule
        }
        return Checked.Ok(rulesJson(list))
    }

    /** 运行用的模型和权限模式：模型是 Claude Code 认的名字（没有表示默认），模式是界面上能选的那几种。 */
    private fun runProblem(model: String?, mode: String): String? =
        when {
            model != null && !ModelPattern.matches(model) -> Strings.settingsBackupBadModel()
            PermissionModes.none { it.first == mode } ->
                Strings.settingsBackupUnknownMode(mode.take(40))
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
            .joinToString(Strings.commonListSeparator())
            .ifEmpty { Strings.commonNone() }

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
