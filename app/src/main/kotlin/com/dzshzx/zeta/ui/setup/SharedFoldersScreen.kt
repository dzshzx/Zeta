package com.dzshzx.zeta.ui.setup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.HelperRun
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.SharedAccess
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.core.fromTreeUri
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogTextField
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.MiuixTheme

/*
 * 共享文件夹在每次进入 Linux 环境时挂载，所以改动对之后的命令和新开的对话生效，已经在进行的对话看不到新加的。
 * 内部存储里的目录实际绑定的是底层的 /data/media，Zeta 会把 Linux 用户加入 media_rw 组以便读写；
 * 在 Linux 里新建的文件属于这个用户，图库等应用要等系统重新扫描后才看得到。
 */

/** 添加共享文件夹时的选择状态：照 Eta，用 root 列出 Android 侧的子文件夹一层层点进去，也可以直接输入路径或用系统选择器。 */
@Stable
private class FolderPicker(start: String, private val existing: List<SharedFolder>) {
    var path by mutableStateOf(start)
    var input by mutableStateOf(start)
    var entries by mutableStateOf<List<String>?>(null)
    var browseError by mutableStateOf<String?>(null)
    var name by mutableStateOf(SharedFolders.defaultName(start, existing))
    var nameTouched = false
    var formError by mutableStateOf<String?>(null)

    fun navigate(target: String) {
        val normalized =
            SharedFolders.normalize(target)
                ?: run {
                    formError = Strings.setupSharedInvalidPath()
                    return
                }
        path = normalized
        input = normalized
        browseError = null
        formError = null
        if (!nameTouched) name = SharedFolders.defaultName(normalized, existing)
    }

    /** 系统选择器选回来的目录：只认内部存储里的。 */
    fun useTreeUri(uri: Uri) {
        val picked = SharedFolders.fromTreeUri(uri)
        if (picked == null) formError = Strings.setupSharedInternalOnly() else navigate(picked)
    }

    /** 列出当前目录下的子文件夹；打不开时记下原因。 */
    suspend fun loadEntries() {
        entries = null
        val found = withContext(Dispatchers.IO) { SharedFolders.subfolders(path) }
        entries = found.orEmpty()
        browseError = if (found == null) Strings.setupSharedCantOpen() else null
    }

    /** 点「添加」时先核对：输入了别的路径就先打开它让用户看清楚；路径、名字有问题时写进 [formError]。都没问题时返回 true。 */
    fun readyToAdd(folders: List<SharedFolder>, rootfs: String?): Boolean {
        // 输入了路径却没按回车：先打开它，看清楚了再添加
        val typed = SharedFolders.normalize(input)
        if (typed == null) {
            formError = Strings.setupSharedInvalidPath()
            return false
        }
        if (typed != path) {
            navigate(typed)
            formError = Strings.setupSharedOpenedTyped()
            return false
        }
        val problem =
            SharedFolders.sourceProblem(path, folders, rootfs)
                ?: SharedFolders.nameProblem(name.trim(), folders)
                ?: browseError
        if (problem != null) {
            formError = problem
            return false
        }
        return true
    }
}

/**
 * 查一遍源目录在不在、Linux 用户能不能读写（阻塞，在后台线程调）。内部存储的目录要靠 media_rw 组：读不了（或 [fixGroup]，刚加了一个）就补上组再查。
 * [changed]（刚增删过）时最后让主机上的核心换一个新进入的（`zeta-helper core-takeover`）：每次进 Debian 各有自己的挂载，常驻的核心 和它之后起的
 * Claude Code 只看得到它启动时挂着的文件夹和组，换了才看得到这次的改动。
 */
private fun checkFolders(
    hub: ConversationHub,
    config: LinuxEnvConfig?,
    list: List<SharedFolder>,
    fixGroup: Boolean,
    changed: Boolean,
): Pair<Map<String, Boolean>, Map<String, SharedAccess>> {
    val sources = SharedFolders.sourcesPresent(list)
    val helper: HelperRun = { s, a -> hub.helper(s, a, backend = Backends.LOCAL) }
    var states = config?.let { SharedFolders.access(helper, list) }.orEmpty()
    val media = list.filter { SharedFolders.isMediaStorage(it.source) }
    if (
        config != null &&
            media.isNotEmpty() &&
            (fixGroup || media.any { states[it.name] == SharedAccess.Denied })
    ) {
        if (SharedFolders.ensureMediaGroup(config))
            states = SharedFolders.access(helper, list).orEmpty()
    }
    if (changed && config != null) hub.helper("core-takeover", backend = Backends.LOCAL)
    return sources to states
}

/** 一个共享文件夹右边的状态：源目录在不在、Linux 用户能不能读写。 */
private fun folderState(
    folder: SharedFolder,
    present: Map<String, Boolean>,
    access: Map<String, SharedAccess>,
    checking: Boolean,
): String =
    when {
        present[folder.name] == false -> Strings.setupSharedMissing()
        access[folder.name] == SharedAccess.ReadWrite -> Strings.setupSharedReadWrite()
        access[folder.name] == SharedAccess.ReadOnly -> Strings.setupSharedReadOnly()
        access[folder.name] == SharedAccess.Denied -> Strings.setupSharedDenied()
        access[folder.name] == SharedAccess.NotMounted -> Strings.setupSharedNotMounted()
        checking -> Strings.setupStateChecking()
        else -> Strings.setupSharedUnknown()
    }

/** 系统文件选择器从哪里开始：当前选到的内部存储目录。 */
private fun initialTreeUri(path: String): Uri? {
    val bind = SharedFolders.bindSource(path)
    val rest =
        Regex("/data/media/\\d+(/.*)?").matchEntire(bind)?.groupValues?.get(1)?.trim('/')
            ?: return null
    return DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:$rest",
    )
}

/** 共享文件夹：把 Android 侧的目录挂到 Linux 环境的 /mnt/shared/<名字>。 */
@Composable
internal fun SharedFoldersScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(hub.settings.sharedFolders) }
    var present by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var access by remember { mutableStateOf<Map<String, SharedAccess>>(emptyMap()) }
    var checking by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<FolderPicker?>(null) }
    var lastPicker by remember { mutableStateOf<FolderPicker?>(null) }
    picker?.let { lastPicker = it }
    var removing by remember { mutableStateOf<SharedFolder?>(null) }
    var lastRemoving by remember { mutableStateOf<SharedFolder?>(null) }
    removing?.let { lastRemoving = it }
    // 连着增删时先发出的查询可能后回来，只认最后一次
    val generation = remember { intArrayOf(0) }

    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val current = picker ?: return@rememberLauncherForActivityResult
            if (uri == null) return@rememberLauncherForActivityResult
            current.useTreeUri(uri)
        }

    BackHandler(onBack = onBack)

    // 查一遍源目录在不在、Linux 用户能不能读写。内部存储的目录要靠 media_rw 组：读不了（或刚加了一个）就补上组再查
    fun refresh(list: List<SharedFolder>, fixGroup: Boolean = false, changed: Boolean = false) {
        checking = true
        val mine = ++generation[0]
        scope.launch {
            val config = hub.settings.env
            val (sources, states) =
                withContext(Dispatchers.IO) { checkFolders(hub, config, list, fixGroup, changed) }
            if (mine != generation[0]) return@launch
            present = sources
            access = states
            checking = false
        }
    }
    LaunchedEffect(Unit) { refresh(folders) }

    val shownPicker = picker ?: lastPicker
    LaunchedEffect(picker, picker?.path) { picker?.loadEntries() }

    fun add(current: FolderPicker) {
        if (!current.readyToAdd(folders, hub.settings.env?.rootfs)) return
        val name = current.name.trim()
        val source = SharedFolders.normalize(current.path) ?: current.path
        val updated = folders + SharedFolder(name, source)
        hub.settings.sharedFolders = updated
        folders = updated
        picker = null
        refresh(updated, fixGroup = SharedFolders.isMediaStorage(source), changed = true)
    }

    fun remove(target: SharedFolder) {
        removing = null
        val updated = folders.filterNot { it.name == target.name }
        hub.settings.sharedFolders = updated
        folders = updated
        scope.launch {
            withContext(Dispatchers.IO) {
                hub.settings.env?.rootfs?.let { SharedFolders.removeMountPoint(it, target.name) }
            }
            refresh(updated, changed = true)
        }
    }

    MiuixPage(
        title = Strings.setupSharedFolders(),
        onBack = onBack,
        dialogs = {
            ConfirmDialog(
                show = picker != null,
                title = Strings.setupSharedAdd(),
                summary = null,
                confirmText = Strings.setupAdd(),
                onConfirm = { picker?.let(::add) },
                onDismissRequest = { picker = null },
            ) {
                shownPicker?.let { current ->
                    FolderPickerContent(
                        current,
                        onSystemPicker = {
                            runCatching { treeLauncher.launch(initialTreeUri(current.path)) }
                        },
                    )
                }
            }
            ConfirmDialog(
                show = removing != null,
                title = Strings.setupSharedRemove(),
                summary =
                    lastRemoving?.let { Strings.setupSharedRemoveSummary(it.name, it.source) },
                confirmText = Strings.setupSharedRemoveConfirm(),
                destructive = true,
                onConfirm = { removing?.let(::remove) },
                onDismissRequest = { removing = null },
            )
        },
    ) {
        item(key = "folders") {
            PreferenceGroup(footer = Strings.setupSharedFooter()) {
                if (folders.isEmpty()) {
                    item { BasicComponent(title = Strings.setupSharedNone()) }
                }
                folders.forEach { folder ->
                    item(key = folder.name) {
                        DropdownValuePreference(
                            title = folder.name,
                            // 挂载点是 /mnt/shared/<名字>，说明里写了，菜单里也能复制
                            summary = folder.source,
                            value = folderState(folder, present, access, checking),
                            entries =
                                listOf(
                                    DropdownEntry(
                                        items =
                                            listOf(
                                                DropdownItem(
                                                    text = Strings.setupSharedCopyMountPoint(),
                                                    onClick = {
                                                        copyLinuxPath(context, folder.mountPoint)
                                                    },
                                                ),
                                                DropdownItem(
                                                    text = Strings.setupSharedRemoveConfirm(),
                                                    onClick = { removing = folder },
                                                ),
                                            ),
                                    ),
                                ),
                        )
                    }
                }
                item(key = "add") {
                    BasicComponent(
                        title = Strings.setupSharedAdd(),
                        titleColor =
                            BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary,
                            ),
                        enabled = folders.size < SharedFolders.MAX,
                        onClick = { picker = FolderPicker("/sdcard", folders) },
                    )
                }
            }
        }
    }
}

/** 添加共享文件夹对话框的内容：输入或一层层点选目录，再起个挂载名。 */
@Composable
private fun FolderPickerContent(current: FolderPicker, onSystemPicker: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        DialogTextField(
            value = current.input,
            onValueChange = { current.input = it },
            title = Strings.setupSharedFolder(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { current.navigate(current.input) }),
        )
        // 次要操作做成链接，不在按钮栏上放第三个按钮：三个按钮挤在一行放不下字
        Text(
            text = Strings.setupSharedSystemPicker(),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.primary,
            modifier =
                Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onSystemPicker)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        current.browseError?.let { PickerNote(it, error = true) }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
            if (current.path != "/") {
                item(key = "..") {
                    PickerRow("../") {
                        current.navigate(current.path.substringBeforeLast('/').ifEmpty { "/" })
                    }
                }
            }
            val entries = current.entries
            when {
                entries == null ->
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator(size = 20.dp)
                        }
                    }
                entries.isEmpty() && current.browseError == null ->
                    item(key = "empty") { PickerNote(Strings.setupSharedNoSubfolders()) }
                else ->
                    items(entries, key = { it }) { entry ->
                        PickerRow(entry) {
                            current.navigate(current.path.trimEnd('/') + "/" + entry)
                        }
                    }
            }
        }
        Text(
            text = Strings.setupSharedMountSource(current.path),
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = MonoFont),
            color = colors.onBackgroundVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        DialogTextField(
            value = current.name,
            onValueChange = {
                current.name = it
                current.nameTouched = true
                current.formError = null
            },
            title = Strings.setupSharedMountName(),
            modifier = Modifier.padding(top = 8.dp),
        )
        current.formError?.let { PickerNote(it, error = true) }
    }
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Icon(
            imageVector = MiuixIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = MonoFont),
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color =
            if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
