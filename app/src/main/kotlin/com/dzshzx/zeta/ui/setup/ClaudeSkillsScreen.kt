package com.dzshzx.zeta.ui.setup

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
import com.dzshzx.zeta.core.claude.ClaudeConfigException
import com.dzshzx.zeta.core.claude.SkillImport
import com.dzshzx.zeta.core.claude.SkillInfo
import com.dzshzx.zeta.core.claude.SkillList
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.RowMoreMenu
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.preference.SwitchLoadingPreference

/** ZIP 读进内存再交给 Debian 那边；Skill 包一般很小，太大的多半选错了文件。 */
private const val MAX_ZIP_BYTES = 50 * 1024 * 1024

private class PickedZip(val name: String, val bytes: ByteArray)

/**
 * “Skills”：~/.claude/skills 下每个含 SKILL.md 的文件夹是一个 Skill。
 * 开关写的是 Claude Code 自己的设置：~/.claude/settings.json 的 skillOverrides（关掉是 "off"），和它的 /skills 菜单一样，文件不动。
 * 账号同步的 Skill 在 ~/.claude/skills/synced，由 Claude Code 从 claude.ai 拉下来，这里只列出；标“已停用”的是 skillOverrides 里关掉了。
 */
@Composable
internal fun ClaudeSkillsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val client = rememberConfigClient(hub)
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

    LaunchedEffect(reloads) {
        scope.runConfig({ client.skills() }) { result ->
            result.onSuccess { list = it }.onFailure { notice = ConfigNotice("读取 Skills 失败", it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    fun install(zip: PickedZip, replace: Boolean) {
        importing = true
        scope.runConfig({ client.importSkill(zip.name, zip.bytes, replace) }) { result ->
            importing = false
            result.onSuccess {
                when (it) {
                    is SkillImport.Exists -> pending = zip to it
                    is SkillImport.Installed -> {
                        notice = ConfigNotice(
                            if (it.replaced) "已替换" else "已安装",
                            // 没启用：settings.json 的 skillOverrides 里早就把这个名字关掉了
                            "“${it.name}”" + if (it.enabled) "装好了，Claude 会按需调用它。" else "装好了，但它被设成停用，打开开关后才能用。",
                        )
                        reload()
                    }
                }
            }.onFailure { notice = ConfigNotice("没有装上", it.problem()) }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.runConfig({
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "skill.zip"
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val data = input.readNBytes(MAX_ZIP_BYTES + 1)
                if (data.size > MAX_ZIP_BYTES) throw ClaudeConfigException("文件超过 50 MB，不像是一个 Skill 包")
                data
            } ?: throw ClaudeConfigException("读不了这个文件")
            PickedZip(name, bytes)
        }) { result ->
            importing = false
            result.onSuccess { install(it, replace = false) }.onFailure { notice = ConfigNotice("没有装上", it.problem()) }
        }
    }
    val pick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }

    fun toggle(skill: SkillInfo, enabled: Boolean) {
        busy = skill.name
        scope.runConfig({ client.setSkillEnabled(skill, enabled) }) { result ->
            busy = null
            result.onFailure { notice = ConfigNotice(if (enabled) "没有启用" else "没有停用", it.problem()) }
            reload()
        }
    }

    val shownDescription = rememberRetained(describing)
    val shownPending = rememberRetained(pending)
    val shownDeleting = rememberRetained(deleting)
    CouiPage(
        title = "Skills",
        onBack = onBack,
        dialogs = {
            ConfigNoticeDialog(notice) { notice = null }
            NoticeDialog(
                show = describing != null,
                title = shownDescription?.first,
                summary = shownDescription?.second,
                closeText = "知道了",
                onDismissRequest = { describing = null },
            )
            ConfirmDialog(
                show = pending != null,
                title = "替换 Skill？",
                summary = shownPending?.let { "已经有一个叫“${it.second.name}”的 Skill。替换会覆盖它当前的全部文件。" },
                confirmText = "替换",
                confirmEnabled = !operating,
                onConfirm = {
                    pending?.let { install(it.first, replace = true) }
                    pending = null
                },
                onDismissRequest = { pending = null },
            )
            ConfirmDialog(
                show = deleting != null,
                title = "删除 Skill",
                summary = shownDeleting?.let { "删除“${it.name}”的整个文件夹，之后需要重新导入才能恢复。" },
                confirmText = "删除",
                confirmEnabled = !operating,
                destructive = true,
                onConfirm = {
                    deleting?.let { skill ->
                        busy = skill.name
                        scope.runConfig({ client.deleteSkill(skill.name) }) { result ->
                            busy = null
                            result.onFailure { notice = ConfigNotice("删除失败", it.problem()) }
                            reload()
                        }
                    }
                    deleting = null
                },
                onDismissRequest = { deleting = null },
            )
        },
    ) {
        item(key = "install") {
            PreferenceGroup {
                item {
                    BasicComponent(
                        title = "从 ZIP 导入",
                        summary = if (importing) "正在检查并安装…" else "选一个含 SKILL.md 的 Skill 包",
                        endActions = if (importing) ({ InfiniteProgressIndicator(size = 20.dp) }) else null,
                        enabled = !operating,
                        onClick = pick,
                    )
                }
            }
        }
        val l = list
        val skills = l?.skills.orEmpty()
        item(key = "user") {
            PreferenceGroup(
                title = "用户安装 · ${skills.size}",
                footer = "装在 ~/.claude/skills 里。关掉后文件留着，Claude 不再用它；改动对之后新开始的对话生效。",
            ) {
                when {
                    l == null -> item { ConfigLoadingRow() }
                    skills.isEmpty() -> item { ConfigEmptyRow("还没有 Skill", "从 ZIP 导入，或让 Claude 写一个") }
                    else -> skills.forEach { skill ->
                        item(key = skill.name) {
                            SkillRow(
                                skill = skill,
                                enabled = !operating,
                                working = busy == skill.name,
                                onToggle = { toggle(skill, it) },
                                onDescribe = { describing = skill.name to skill.description.ifBlank { if (skill.valid) "无描述" else "这个文件夹里没有 SKILL.md，Claude Code 不会把它当成 Skill。" } },
                                onDelete = { deleting = skill },
                            )
                        }
                    }
                }
            }
        }
        val synced = l?.synced.orEmpty()
        if (synced.isNotEmpty()) {
            item(key = "synced") {
                PreferenceGroup(
                    title = "账号同步 · ${synced.size}",
                    footer = "在 claude.ai 上管理，这里只能看。",
                ) {
                    synced.forEach { skill ->
                        item(key = skill.name) {
                            BasicComponent(
                                title = skill.name,
                                summary = brief(skill.description).ifBlank { null },
                                endActions = if (skill.enabled) null else ({ PreferenceValue("已停用") }),
                                onClick = { describing = skill.name to skill.description.ifBlank { "无描述" } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一个 Skill：点整行或开关启用/停用，“更多”里看说明、删除。照 Eta 的 SkillSwitchRow。 */
@Composable
private fun SkillRow(
    skill: SkillInfo,
    enabled: Boolean,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onDescribe: () -> Unit,
    onDelete: () -> Unit,
) {
    val canToggle = enabled && skill.valid
    val description = when {
        !skill.valid -> "缺少 SKILL.md"
        skill.description.isBlank() -> "无描述"
        // 说明往往是一大段，列表里只放开头
        else -> brief(skill.description)
    }
    SwitchLoadingPreference(
        checked = skill.enabled,
        onCheckedChange = onToggle,
        title = skill.title.takeIf { it.isNotBlank() && it != skill.name }?.let { "$it（${skill.name}）" } ?: skill.name,
        summary = description,
        isLoading = working,
        enabled = canToggle,
        endActions = {
            RowMoreMenu(
                contentDescription = "更多：" + skill.name,
                entries = listOf(
                    DropdownEntry(items = listOf(DropdownItem(text = "查看说明", onClick = onDescribe))),
                    DropdownEntry(items = listOf(DropdownItem(text = "删除", enabled = enabled, alert = true, onClick = { if (enabled) onDelete() }))),
                ),
            )
        },
    )
}
