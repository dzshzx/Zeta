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
import com.dzshzx.zeta.core.claude.ConfigFile
import com.dzshzx.zeta.core.claude.MemoryEntry
import com.dzshzx.zeta.core.claude.MemoryOverview
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroupScope
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.settings.ValueRow
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 编辑器里打开的是哪个文件。 */
private data class MemoryTarget(
    val path: String,
    val title: String,
    val fileLabel: String,
    val placeholder: String,
    val entry: MemoryEntry? = null,
)

private fun typeLabel(type: String): String =
    when (type) {
        "user" -> Strings.setupMemoryTypeUser()
        "feedback" -> Strings.setupMemoryTypeFeedback()
        "project" -> Strings.setupMemoryTypeProject()
        "reference" -> Strings.setupMemoryTypeReference()
        else -> type
    }

/** 把家目录写成 ~，路径短一些。 */
private fun shortPath(path: String, home: String?): String =
    if (home != null && home.length > 1 && (path == home || path.startsWith("$home/")))
        "~" + path.removePrefix(home)
    else path

/**
 * “记忆”：Claude Code 有两种长期记忆。一是用户写给它的指令 ~/.claude/CLAUDE.md，每次对话都载入；
 * 二是它自己记的自动记忆，每个项目一个目录（~/.claude/projects/<项目>/memory），每条一个文件，MEMORY.md 是索引： 索引每次对话开始时载入，具体内容
 * Claude 按需再读。 Eta 只有一份 MEMORY.md，这里按 Claude Code 的样子分成两块。项目自己的 CLAUDE.md 放在项目目录里，这页不管。 自动记忆的开关写在
 * ~/.claude/settings.json（autoMemoryEnabled），对所有项目生效。
 */
@Composable
internal fun ClaudeMemoryScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val cwd = hub.defaultCwd(backend)
    val home = hub.home(backend)
    var overview by remember { mutableStateOf<MemoryOverview?>(null) }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<ConfigNotice?>(null) }
    var editing by remember { mutableStateOf<MemoryTarget?>(null) }
    var reloads by remember { mutableStateOf(0) }

    LaunchedEffect(project, reloads) {
        scope.runConfig({ client.memory(cwd, project) }) { result ->
            result
                .onSuccess { overview = it }
                .onFailure { notice = ConfigNotice(Strings.setupMemoryLoadFailed(), it.problem()) }
        }
    }
    val reload: () -> Unit = { reloads++ }

    val o = overview
    MiuixPage(
        title = Strings.setupMemoryTitle(),
        onBack = onBack,
        dialogs = { ConfigNoticeDialog(notice) { notice = null } },
    ) {
        item(key = "instructions") { InstructionsGroup(o, onOpen = { editing = it }) }

        item(key = "auto") {
            AutoMemoryGroup(
                o = o,
                home = home,
                working = working,
                onToggle = { on ->
                    working = true
                    scope.runConfig({ client.setAutoMemory(on) }) { result ->
                        working = false
                        result.onFailure {
                            notice = ConfigNotice(Strings.setupClaudeChangeFailed(), it.problem())
                        }
                        reload()
                    }
                },
                onSelectProject = { project = it },
                onOpen = { editing = it },
            )
        }

        if (o != null && o.index != null) {
            item(key = "files") { MemoryFilesGroup(o.files, onOpen = { editing = it }) }
        }
    }

    SlideInPage(editing, onBack = { editing = null }) { t ->
        ConfigDocumentEditor(
            client = client,
            path = t.path,
            title = t.title,
            fileLabel = t.fileLabel,
            placeholder = t.placeholder,
            onBack = { editing = null },
            onSaved = reload,
            deleteTitle = Strings.setupMemoryDeleteTitle(),
            deleteMessage = Strings.setupMemoryDeleteMessage(t.fileLabel),
            onDelete =
                if (t.entry != null) {
                    {
                        scope.runConfig({ client.deleteMemory(t.path) }) { result ->
                            result
                                .onSuccess { editing = null }
                                .onFailure {
                                    notice =
                                        ConfigNotice(
                                            Strings.setupClaudeDeleteFailed(),
                                            it.problem(),
                                        )
                                }
                            reload()
                        }
                    }
                } else {
                    null
                },
        )
    }
}

/** 「用户指令」一组：~/.claude/CLAUDE.md。 */
@Composable
private fun InstructionsGroup(o: MemoryOverview?, onOpen: (MemoryTarget) -> Unit) {
    PreferenceGroup(
        title = Strings.setupMemoryInstructions(),
        footer = Strings.setupMemoryInstructionsFooter(),
    ) {
        item {
            ArrowPreference(
                title = "CLAUDE.md",
                summary =
                    when {
                        o == null -> Strings.setupClaudeLoading()
                        o.instructions.exists -> sizeText(o.instructions.size)
                        else -> Strings.setupMemoryInstructionsMissing()
                    },
                enabled = o != null,
                onClick = {
                    o?.let {
                        onOpen(
                            MemoryTarget(
                                it.instructions.path,
                                Strings.setupMemoryInstructions(),
                                "CLAUDE.md",
                                Strings.setupMemoryInstructionsHint(),
                            ),
                        )
                    }
                },
            )
        }
    }
}

/** 「自动记忆」一组：开关、记忆目录或项目、MEMORY.md 索引。 */
@Composable
private fun AutoMemoryGroup(
    o: MemoryOverview?,
    home: String?,
    working: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectProject: (String) -> Unit,
    onOpen: (MemoryTarget) -> Unit,
) {
    PreferenceGroup(
        title = Strings.setupMemoryAuto(),
        footer = Strings.setupMemoryAutoFooter(),
    ) {
        if (o == null) {
            item { ConfigLoadingRow() }
        } else {
            item {
                SwitchPreference(
                    checked = o.autoMemory && !o.forcedOff,
                    onCheckedChange = onToggle,
                    title = Strings.setupMemoryAutoEnable(),
                    // forcedOff：settings.json 的 env 里设了
                    // CLAUDE_CODE_DISABLE_AUTO_MEMORY，它压过开关
                    summary = if (o.forcedOff) Strings.setupMemoryAutoForcedOff() else null,
                    enabled = !o.forcedOff && !working,
                )
            }
            memoryLocationRows(o, home, onSelectProject)
            memoryIndexRow(o.index, onOpen)
        }
    }
}

/** 记忆放在哪：设了 autoMemoryDirectory 时是那个目录，否则是可选的项目。 */
private fun PreferenceGroupScope.memoryLocationRows(
    o: MemoryOverview,
    home: String?,
    onSelectProject: (String) -> Unit,
) {
    val customDir = o.customDir
    if (customDir != null) {
        // settings.json 里设了 autoMemoryDirectory：所有项目共用这一个目录，没有项目可选
        item {
            ValueRow(
                Strings.setupMemoryDir(),
                Strings.setupMemorySharedDir(),
                summary = shortPath(customDir, home),
            )
        }
    } else if (o.projects.isNotEmpty()) {
        item {
            val current = o.projects.firstOrNull { it.key == o.selected }
            DropdownValuePreference(
                title = Strings.setupMemoryProject(),
                summary =
                    current?.let {
                        shortPath(it.label, home).let { p ->
                            if (it.isDefault) Strings.setupMemoryProjectDefault(p) else p
                        }
                    },
                value = current?.let { Strings.setupMemoryCount(it.count) },
                entries =
                    listOf(
                        MenuEntry(
                            items =
                                o.projects.map {
                                    MenuItem(
                                        text =
                                            shortPath(it.label, home).let { p ->
                                                if (it.isDefault)
                                                    Strings.setupMemoryProjectItemDefault(p)
                                                else Strings.setupMemoryProjectItem(p, it.count)
                                            },
                                        selected = it.key == o.selected,
                                        onClick = { onSelectProject(it.key) },
                                    )
                                },
                        ),
                    ),
            )
        }
    }
}

private fun PreferenceGroupScope.memoryIndexRow(
    index: ConfigFile?,
    onOpen: (MemoryTarget) -> Unit,
) {
    if (index == null) return
    item {
        ArrowPreference(
            title = "MEMORY.md",
            summary =
                if (index.exists) Strings.setupMemoryIndex() else Strings.setupMemoryIndexMissing(),
            endActions = { if (index.exists) PreferenceValue(sizeText(index.size)) },
            onClick = {
                onOpen(
                    MemoryTarget(
                        index.path,
                        "MEMORY.md",
                        "MEMORY.md",
                        Strings.setupMemoryIndexHint(),
                    ),
                )
            },
        )
    }
}

/** 「记忆」一组：这个项目的每条记忆。 */
@Composable
private fun MemoryFilesGroup(files: List<MemoryEntry>, onOpen: (MemoryTarget) -> Unit) {
    PreferenceGroup(title = Strings.setupMemoryFiles(files.size)) {
        if (files.isEmpty()) {
            item { ConfigEmptyRow(Strings.setupMemoryNone(), Strings.setupMemoryNoneSummary()) }
        }
        files.forEach { entry ->
            item(key = entry.file) {
                ArrowPreference(
                    title = entry.name.ifBlank { entry.fileName },
                    summary = entry.description.ifBlank { entry.fileName }.clipLine(90),
                    endActions = {
                        typeLabel(entry.type)
                            .takeIf { it.isNotBlank() }
                            ?.let { PreferenceValue(it) }
                    },
                    onClick = {
                        onOpen(
                            MemoryTarget(
                                entry.file,
                                entry.name.ifBlank { entry.fileName },
                                entry.fileName,
                                "",
                                entry,
                            ),
                        )
                    },
                )
            }
        }
    }
}
