package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.MemoryEntry
import com.dzshzx.zeta.core.claude.MemoryOverview
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DropdownValuePreference
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.rememberRetained
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference

/** 编辑器里打开的是哪个文件。 */
private data class MemoryTarget(val path: String, val title: String, val fileLabel: String, val placeholder: String, val entry: MemoryEntry? = null)

private fun typeLabel(type: String): String = when (type) {
    "user" -> "关于你"
    "feedback" -> "反馈"
    "project" -> "项目"
    "reference" -> "参考"
    else -> type
}

/** 把家目录写成 ~，路径短一些。 */
private fun shortPath(path: String, home: String?): String =
    if (home != null && home.length > 1 && (path == home || path.startsWith("$home/"))) "~" + path.removePrefix(home) else path

/**
 * “记忆”：Claude Code 有两种长期记忆。一是用户写给它的指令 ~/.claude/CLAUDE.md，每次对话都载入；
 * 二是它自己记的自动记忆，每个项目一个目录（~/.claude/projects/<项目>/memory），MEMORY.md 是索引。
 * Eta 只有一份 MEMORY.md，这里按 Claude Code 的样子分成两块。
 */
@Composable
internal fun ClaudeMemoryScreen(hub: ConversationHub, onBack: () -> Unit) {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val env = hub.env
    val cwd = env?.defaultCwd ?: "/"
    var overview by remember { mutableStateOf<MemoryOverview?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var editing by remember { mutableStateOf<MemoryTarget?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(project, reloads) {
        scope.runConfig({ client.memory(cwd, project) }) { result ->
            result.onSuccess { overview = it }.onFailure { notice = ConfigNotice("读取记忆失败", it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    val o = overview
    CouiPage(title = "记忆", onBack = onBack, overlays = { ConfigNoticeDialog(notice) { notice = null } }) {
        item(key = "instructions") {
            PreferenceGroup(
                title = "用户指令",
                footer = "~/.claude/CLAUDE.md：你写给 Claude 的长期指令和偏好，每次对话开始时都会载入，所有项目通用。项目自己的 CLAUDE.md 放在项目目录里，不在这里管。",
            ) {
                item {
                    ArrowPreference(
                        title = "CLAUDE.md",
                        summary = when {
                            o == null -> "正在读取…"
                            o.instructions.exists -> sizeText(o.instructions.size)
                            else -> "还没有创建，点开就能写"
                        },
                        enabled = o != null,
                        onClick = {
                            o?.let {
                                editing = MemoryTarget(it.instructions.path, "用户指令", "CLAUDE.md", "比如：回答用中文；我的主要技术栈是 Go；改代码前先说明打算怎么改")
                            }
                        },
                    )
                }
            }
        }

        item(key = "auto") {
            PreferenceGroup(
                title = "自动记忆",
                footer = "Claude 在对话中会自己记下值得长期保留的东西：你的偏好、给它的纠正、项目的背景。每条一个文件，" +
                    "MEMORY.md 是它们的索引，每次对话开始时载入，具体内容按需再读。开关写在 ~/.claude/settings.json，对所有项目生效；关掉后不再读写，已有的记忆保留。",
            ) {
                if (o == null) {
                    item { ConfigLoadingRow() }
                } else {
                    item {
                        SwitchPreference(
                            checked = o.autoMemory && !o.forcedOff,
                            onCheckedChange = { on ->
                                working = true
                                scope.runConfig({ client.setAutoMemory(on) }) { result ->
                                    working = false
                                    result.onFailure { notice = ConfigNotice("没有改成", it.problem()) }
                                    reload()
                                }
                            },
                            title = "启用自动记忆",
                            summary = if (o.forcedOff) "settings.json 的 env 里设了 CLAUDE_CODE_DISABLE_AUTO_MEMORY，这个开关不起作用" else null,
                            enabled = !o.forcedOff && !working,
                        )
                    }
                    if (o.customDir != null) {
                        item { BasicComponent(title = "记忆目录", summary = shortPath(o.customDir, env?.home) + "（settings.json 里的 autoMemoryDirectory，所有项目共用）") }
                    } else if (o.projects.isNotEmpty()) {
                        item {
                            val current = o.projects.firstOrNull { it.key == o.selected }
                            DropdownValuePreference(
                                title = "项目",
                                summary = current?.let { shortPath(it.label, env?.home) + if (it.isDefault) "（默认工作目录）" else "" },
                                value = current?.let { "${it.count} 条" },
                                entries = listOf(
                                    DropdownEntry(
                                        items = o.projects.map {
                                            DropdownItem(
                                                text = shortPath(it.label, env?.home) + if (it.isDefault) "（默认）" else " · ${it.count} 条",
                                                selected = it.key == o.selected,
                                                onClick = { project = it.key },
                                            )
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                    val index = o.index
                    if (index != null) {
                        item {
                            ArrowPreference(
                                title = "MEMORY.md",
                                summary = "记忆的索引，每次对话开始时载入" + if (index.exists) "" else "；还没有",
                                endActions = { if (index.exists) PreferenceValue(sizeText(index.size)) },
                                onClick = { editing = MemoryTarget(index.path, "MEMORY.md", "MEMORY.md", "一行一条记忆：- [标题](文件名.md) — 一句话说明") },
                            )
                        }
                    }
                }
            }
        }

        if (o != null && o.index != null) {
            item(key = "files") {
                PreferenceGroup(title = "记忆 · ${o.files.size}") {
                    if (o.files.isEmpty()) {
                        item { ConfigEmptyRow("这个项目还没有记忆", "对话里让 Claude“记住”什么，它就会记在这里") }
                    }
                    o.files.forEach { entry ->
                        item(key = entry.file) {
                            ArrowPreference(
                                title = entry.name.ifBlank { entry.fileName },
                                summary = entry.description.ifBlank { entry.fileName }.let { brief(it) },
                                endActions = { typeLabel(entry.type).takeIf { it.isNotBlank() }?.let { PreferenceValue(it) } },
                                onClick = {
                                    editing = MemoryTarget(entry.file, entry.name.ifBlank { entry.fileName }, entry.fileName, "", entry)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    val target = rememberRetained(editing)
    ConfigSubPage(visible = editing != null, onBack = { editing = null }) {
        target?.let { t ->
            ConfigDocumentEditor(
                client = client,
                path = t.path,
                title = t.title,
                fileLabel = t.fileLabel,
                placeholder = t.placeholder,
                onBack = { editing = null },
                onSaved = reload,
                deleteTitle = "删除这条记忆",
                deleteMessage = "删除 ${t.fileLabel}，并从 MEMORY.md 的索引里去掉指向它的那一行。删了就找不回来。",
                onDelete = if (t.entry != null) {
                    {
                        scope.runConfig({ client.deleteMemory(t.path) }) { result ->
                            result.onSuccess { editing = null }.onFailure { notice = ConfigNotice("删除失败", it.problem()) }
                            reload()
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}
