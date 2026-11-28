package com.dzshzx.zeta.ui.setup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.core.claude.SkillImport
import com.dzshzx.zeta.core.claude.SkillInfo
import com.dzshzx.zeta.core.claude.SkillList
import com.dzshzx.zeta.core.claude.SyncedSkill
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.NoticeDialog
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.RowMoreMenu
import com.dzshzx.zeta.ui.components.miuix.SwitchLoadingPreference
import com.dzshzx.zeta.ui.components.rememberRetained
import java.io.IOException
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

/** ZIP 读进内存再交给那台的核心；Skill 包一般很小，太大的多半选错了文件。 */
private class PickedZip(val name: String, val bytes: ByteArray)

/**
 * “Skills”：~/.claude/skills 下每个含 SKILL.md 的文件夹是一个 Skill。 开关写的是 Claude Code
 * 自己的设置：~/.claude/settings.json 的 skillOverrides（关掉是 "off"），和它的 /skills 菜单一样，文件不动。 账号同步的 Skill 在
 * ~/.claude/skills/synced，由 Claude Code 从 claude.ai 拉下来，这里只列出；标“已停用”的是 skillOverrides 里关掉了。
 */
@Composable
internal fun ClaudeSkillsScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    harness: String = Harnesses.CLAUDE,
) {
    val client = rememberConfigClient(hub, backend, harness)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var list by remember { mutableStateOf<SkillList?>(null) }
    var importing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var pending by remember { mutableStateOf<Pair<PickedZip, SkillImport.Exists>?>(null) }
    var deleting by remember { mutableStateOf<SkillInfo?>(null) }
    var describing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var reloads by remember { mutableStateOf(0) }
    val operating = importing || busy != null

    LaunchedEffect(reloads, harness) {
        scope.runConfig({ client.skills() }) { result ->
            result
                .onSuccess { list = it }
                .onFailure { notice = ConfigNotice(Strings.setupSkillsLoadFailed(), it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    fun install(zip: PickedZip, replace: Boolean) {
        importing = true
        scope.runConfig({ client.importSkill(zip.name, zip.bytes, replace) }) { result ->
            importing = false
            result
                .onSuccess {
                    when (it) {
                        is SkillImport.Exists -> pending = zip to it
                        is SkillImport.Installed -> {
                            notice = installedNotice(it)
                            reload()
                        }
                    }
                }
                .onFailure {
                    notice = ConfigNotice(Strings.setupSkillsInstallFailed(), it.problem())
                }
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            importing = true
            scope.runConfig({ readPickedZip(context, uri) }) { result ->
                importing = false
                result
                    .onSuccess { install(it, replace = false) }
                    .onFailure {
                        notice = ConfigNotice(Strings.setupSkillsInstallFailed(), it.problem())
                    }
            }
        }
    val pick = {
        picker.launch(
            arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
        )
    }

    fun toggle(skill: SkillInfo, enabled: Boolean) {
        busy = skill.name
        scope.runConfig({ client.setSkillEnabled(skill, enabled) }) { result ->
            busy = null
            result.onFailure {
                notice =
                    ConfigNotice(
                        if (enabled) Strings.setupSkillsEnableFailed()
                        else Strings.setupSkillsDisableFailed(),
                        it.problem(),
                    )
            }
            reload()
        }
    }

    fun delete(skill: SkillInfo) {
        busy = skill.name
        scope.runConfig({ client.deleteSkill(skill.name) }) { result ->
            busy = null
            result.onFailure {
                notice = ConfigNotice(Strings.setupClaudeDeleteFailed(), it.problem())
            }
            reload()
        }
    }

    val shownDescription = rememberRetained(describing)
    val shownPending = rememberRetained(pending)
    val shownDeleting = rememberRetained(deleting)
    MiuixPage(
        title = "Skills",
        onBack = onBack,
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            NoticeDialog(
                show = describing != null,
                title = shownDescription?.first,
                summary = shownDescription?.second,
                closeText = Strings.setupClaudeGotIt(),
                onDismissRequest = { describing = null },
            )
            ConfirmDialog(
                show = pending != null,
                title = Strings.setupSkillsReplaceTitle(),
                summary = shownPending?.let { Strings.setupSkillsReplaceSummary(it.second.name) },
                confirmText = Strings.setupSkillsReplace(),
                confirmEnabled = !operating,
                onConfirm = {
                    pending?.let { install(it.first, replace = true) }
                    pending = null
                },
                onDismissRequest = { pending = null },
            )
            ConfirmDialog(
                show = deleting != null,
                title = Strings.setupSkillsDeleteTitle(),
                summary = shownDeleting?.let { Strings.setupSkillsDeleteSummary(it.name) },
                confirmText = Strings.setupClaudeDelete(),
                confirmEnabled = !operating,
                destructive = true,
                onConfirm = {
                    deleting?.let(::delete)
                    deleting = null
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        item(key = "install") { ImportGroup(importing, enabled = !operating, onPick = pick) }
        val l = list
        val skills = l?.skills.orEmpty()
        item(key = "user") {
            UserSkillsGroup(
                loaded = l != null,
                skills = skills,
                isAgy = Harnesses.normalize(harness) == Harnesses.AGY,
                operating = operating,
                busy = busy,
                onToggle = ::toggle,
                onDescribe = { describing = it.name to skillDescription(it) },
                onDelete = { deleting = it },
            )
        }
        val synced = l?.synced.orEmpty()
        if (synced.isNotEmpty()) {
            item(key = "synced") {
                SyncedSkillsGroup(
                    synced,
                    isAgy = Harnesses.normalize(harness) == Harnesses.AGY,
                    onDescribe = {
                        describing =
                            it.name to it.description.ifBlank { Strings.setupSkillsNoDescription() }
                    },
                )
            }
        }
    }
}

/** 读出选中的 ZIP：文件名取不到时叫 skill.zip，超过 [ClaudeConfigCore.MAX_SKILL_ZIP] 或读不了时抛 [IOException]。 */
private fun readPickedZip(context: Context, uri: Uri): PickedZip {
    val resolver = context.contentResolver
    val name =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "skill.zip"
    val bytes =
        resolver.openInputStream(uri)?.use { input ->
            val data = input.readNBytes(ClaudeConfigCore.MAX_SKILL_ZIP + 1)
            if (data.size > ClaudeConfigCore.MAX_SKILL_ZIP)
                throw IOException(
                    Strings.setupSkillsZipTooLarge(ClaudeConfigCore.MAX_SKILL_ZIP shr 20),
                )
            data
        } ?: throw IOException(Strings.setupSkillsZipUnreadable())
    return PickedZip(name, bytes)
}

private fun installedNotice(installed: SkillImport.Installed): ConfigNotice =
    ConfigNotice(
        if (installed.replaced) Strings.setupSkillsReplaced() else Strings.setupSkillsInstalled(),
        // 没启用：settings.json 的 skillOverrides 里早就把这个名字关掉了
        if (installed.enabled) Strings.setupSkillsInstalledOn(installed.name)
        else Strings.setupSkillsInstalledOff(installed.name),
    )

/** 说明对话框里的正文：没写说明时说无描述，不是 Skill 的文件夹说明原因。 */
private fun skillDescription(skill: SkillInfo): String =
    skill.description.ifBlank {
        if (skill.valid) Strings.setupSkillsNoDescription() else Strings.setupSkillsNotSkill()
    }

/** 从 ZIP 导入的入口，导入中显示转圈。 */
@Composable
private fun ImportGroup(importing: Boolean, enabled: Boolean, onPick: () -> Unit) {
    PreferenceGroup {
        item {
            BasicComponent(
                title = Strings.setupSkillsImport(),
                summary =
                    if (importing) Strings.setupSkillsImporting()
                    else Strings.setupSkillsImportHint(),
                endActions = if (importing) ({ InfiniteProgressIndicator(size = 20.dp) }) else null,
                enabled = enabled,
                onClick = onPick,
            )
        }
    }
}

/** 「用户安装」一组：~/.claude/skills 里的 Skill，可以开关、看说明、删除。 */
@Composable
private fun UserSkillsGroup(
    loaded: Boolean,
    skills: List<SkillInfo>,
    isAgy: Boolean,
    operating: Boolean,
    busy: String?,
    onToggle: (SkillInfo, Boolean) -> Unit,
    onDescribe: (SkillInfo) -> Unit,
    onDelete: (SkillInfo) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupSkillsUser(skills.size),
        footer = if (isAgy) Strings.setupSkillsAgyUserFooter() else Strings.setupSkillsUserFooter(),
    ) {
        when {
            !loaded -> item { ConfigLoadingRow() }
            skills.isEmpty() ->
                item {
                    ConfigEmptyRow(
                        Strings.setupSkillsNone(),
                        if (isAgy) Strings.setupSkillsAgyNoneSummary()
                        else Strings.setupSkillsNoneSummary(),
                    )
                }
            else ->
                skills.forEach { skill ->
                    item(key = skill.name) {
                        SkillRow(
                            skill = skill,
                            isAgy = isAgy,
                            enabled = !operating,
                            working = busy == skill.name,
                            onToggle = { onToggle(skill, it) },
                            onDescribe = { onDescribe(skill) },
                            onDelete = { onDelete(skill) },
                        )
                    }
                }
        }
    }
}

/** 「账号同步」一组：claude.ai 同步下来的 Skill，只能看。 */
@Composable
private fun SyncedSkillsGroup(
    synced: List<SyncedSkill>,
    isAgy: Boolean,
    onDescribe: (SyncedSkill) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupSkillsSynced(synced.size),
        footer =
            if (isAgy) Strings.setupSkillsAgySyncedFooter() else Strings.setupClaudeManagedOnline(),
    ) {
        synced.forEach { skill ->
            item(key = skill.name) {
                BasicComponent(
                    title = skill.name,
                    summary = skill.description.clipLine(90).ifBlank { null },
                    endActions =
                        if (skill.enabled) null
                        else ({ PreferenceValue(Strings.setupClaudeDisabled()) }),
                    onClick = { onDescribe(skill) },
                )
            }
        }
    }
}

/** 一个 Skill：点整行或开关启用/停用，“更多”里看说明、删除。照 Eta 的 SkillSwitchRow。 */
@Composable
private fun SkillRow(
    skill: SkillInfo,
    isAgy: Boolean,
    enabled: Boolean,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onDescribe: () -> Unit,
    onDelete: () -> Unit,
) {
    val canToggle = enabled && skill.valid
    val description =
        when {
            !skill.valid -> Strings.setupSkillsMissingFile()
            skill.description.isBlank() -> Strings.setupSkillsNoDescription()
            // 说明往往是一大段，列表里只放开头
            else -> skill.description.clipLine(90)
        }
    val title =
        skill.title
            .takeIf { it.isNotBlank() && it != skill.name }
            ?.let { Strings.setupSkillsTitleWithName(it, skill.name) } ?: skill.name
    val menu: @Composable () -> Unit = {
        RowMoreMenu(
            contentDescription = Strings.setupClaudeMoreFor(skill.name),
            entries =
                listOf(
                    MenuEntry(
                        items =
                            listOf(
                                MenuItem(
                                    text = Strings.setupSkillsShowDescription(),
                                    onClick = onDescribe,
                                ),
                            ),
                    ),
                    MenuEntry(
                        items =
                            listOf(
                                MenuItem(
                                    text = Strings.setupClaudeDelete(),
                                    enabled = enabled,
                                    onClick = { if (enabled) onDelete() },
                                    alert = true,
                                ),
                            ),
                    ),
                ),
        )
    }
    if (isAgy) {
        BasicComponent(
            title = title,
            summary = description,
            endActions = {
                if (working) InfiniteProgressIndicator(size = 20.dp)
                menu()
            },
            onClick = onDescribe,
        )
        return
    }
    SwitchLoadingPreference(
        checked = skill.enabled,
        onCheckedChange = onToggle,
        title = title,
        summary = description,
        isLoading = working,
        enabled = canToggle,
        endActions = { menu() },
    )
}
