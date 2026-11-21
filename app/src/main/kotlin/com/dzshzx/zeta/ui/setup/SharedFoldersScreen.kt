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
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.DropdownValuePreference
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.BasicComponentDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * 共享文件夹在每次进入 Linux 环境时挂载，所以改动对之后的命令和新开的对话生效，已经在进行的对话看不到新加的。
 * 内部存储里的目录实际绑定的是底层的 /data/media，Zeta 会把 Linux 用户加入 media_rw 组以便读写；
 * 在 Linux 里新建的文件属于这个用户，图库等应用要等系统重新扫描后才看得到。
 */
private const val Footer = "挂载到 Linux 的 /mnt/shared/<挂载名>。改动对新开的对话生效，进行中的对话看不到新加的；经 SSH 连进来的会话不挂载。"

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
                    formError = "目录路径无效"
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
        if (picked == null) formError = "只支持内部存储里的文件夹" else navigate(picked)
    }

    /** 列出当前目录下的子文件夹；打不开时记下原因。 */
    suspend fun loadEntries() {
        entries = null
        val found = withContext(Dispatchers.IO) { SharedFolders.subfolders(path) }
        entries = found.orEmpty()
        browseError = if (found == null) "无法打开该目录" else null
    }

    /** 点「添加」时先核对：输入了别的路径就先打开它让用户看清楚；路径、名字有问题时写进 [formError]。都没问题时返回 true。 */
    fun readyToAdd(folders: List<SharedFolder>, rootfs: String?): Boolean {
        // 输入了路径却没按回车：先打开它，看清楚了再添加
        val typed = SharedFolders.normalize(input)
        if (typed == null) {
            formError = "目录路径无效"
            return false
        }
        if (typed != path) {
            navigate(typed)
            formError = "已打开输入的目录，确认无误后再点“添加”"
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

/** 查一遍源目录在不在、Linux 用户能不能读写（阻塞，在后台线程调）。内部存储的目录要靠 media_rw 组：读不了（或 [fixGroup]，刚加了一个）就补上组再查。 */
private fun checkFolders(
    hub: ConversationHub,
    config: LinuxEnvConfig?,
    list: List<SharedFolder>,
    fixGroup: Boolean,
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
        present[folder.name] == false -> "源目录不存在"
        access[folder.name] == SharedAccess.ReadWrite -> "可读写"
        access[folder.name] == SharedAccess.ReadOnly -> "只读"
        access[folder.name] == SharedAccess.Denied -> "没有权限"
        access[folder.name] == SharedAccess.NotMounted -> "没有挂上"
        checking -> "检查中"
        else -> "未知"
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
    fun refresh(list: List<SharedFolder>, fixGroup: Boolean = false) {
        checking = true
        val mine = ++generation[0]
        scope.launch {
            val config = hub.settings.env
            val (sources, states) =
                withContext(Dispatchers.IO) { checkFolders(hub, config, list, fixGroup) }
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
        refresh(updated, fixGroup = SharedFolders.isMediaStorage(source))
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
            refresh(updated)
        }
    }

    CouiPage(
        title = "共享文件夹",
        onBack = onBack,
        dialogs = {
            ConfirmDialog(
                show = picker != null,
                title = "添加共享文件夹",
                summary = null,
                confirmText = "添加",
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
                title = "移除共享文件夹",
                summary = lastRemoving?.let { "移除“${it.name}”（${it.source}）？源目录中的文件不会被删除。" },
                confirmText = "移除",
                destructive = true,
                onConfirm = { removing?.let(::remove) },
                onDismissRequest = { removing = null },
            )
        },
    ) {
        item(key = "folders") {
            PreferenceGroup(footer = Footer) {
                if (folders.isEmpty()) {
                    item { BasicComponent(title = "还没有共享文件夹") }
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
                                                    text = "复制挂载点路径",
                                                    onClick = {
                                                        copyLinuxPath(context, folder.mountPoint)
                                                    },
                                                ),
                                                DropdownItem(
                                                    text = "移除",
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
                        title = "添加共享文件夹",
                        titleColor =
                            BasicComponentDefaults.titleColor(
                                color = COUITheme.colorScheme.primary,
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
    val colors = COUITheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        DialogTextField(
            value = current.input,
            onValueChange = { current.input = it },
            title = "目录",
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { current.navigate(current.input) }),
        )
        // 不用对话框的 neutral 按钮：COUI 把它画成红色的危险操作，三个按钮竖排还会挤掉下面的挂载名
        Text(
            text = "用系统文件选择器选择",
            style = COUITheme.textStyles.bodyS,
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
                    item(key = "empty") { PickerNote("此处没有子文件夹") }
                else ->
                    items(entries, key = { it }) { entry ->
                        PickerRow(entry) {
                            current.navigate(current.path.trimEnd('/') + "/" + entry)
                        }
                    }
            }
        }
        Text(
            text = "挂载目录：" + current.path,
            style = COUITheme.textStyles.bodyS.copy(fontFamily = MonoFont),
            color = colors.labelSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        DialogTextField(
            value = current.name,
            onValueChange = {
                current.name = it
                current.nameTouched = true
                current.formError = null
            },
            title = "挂载名",
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
            imageVector = COUIIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = COUITheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = COUITheme.textStyles.bodyM.copy(fontFamily = MonoFont),
            color = COUITheme.colorScheme.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS,
        color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
