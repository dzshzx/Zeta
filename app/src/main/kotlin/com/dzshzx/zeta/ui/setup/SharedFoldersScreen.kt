package com.dzshzx.zeta.ui.setup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
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
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SharedAccess
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.ui.components.EtaMonospace
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiIcon
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val Footer = "共享文件夹在每次进入 Linux 环境时挂载到 /mnt/shared/ 下，改动对之后的命令和新开的对话生效，" +
    "已经在进行的对话看不到新加的。内部存储里的目录实际绑定的是底层的 /data/media，Zeta 会把 Linux 用户加入 media_rw 组以便读写；" +
    "在 Linux 里新建的文件属于这个用户，图库等应用要等系统重新扫描后才看得到。经 SSH 连进来的会话不挂载共享文件夹。"

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
        val normalized = SharedFolders.normalize(target) ?: run {
            formError = "目录路径无效"
            return
        }
        path = normalized
        input = normalized
        browseError = null
        formError = null
        if (!nameTouched) name = SharedFolders.defaultName(normalized, existing)
    }
}

/** 系统文件选择器从哪里开始：当前选到的内部存储目录。 */
private fun initialTreeUri(path: String): Uri? {
    val bind = SharedFolders.bindSource(path)
    val rest = Regex("/data/media/\\d+(/.*)?").matchEntire(bind)?.groupValues?.get(1)?.trim('/') ?: return null
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rest")
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
    var menuFor by remember { mutableStateOf<String?>(null) }
    val accent = CouiPageDefaults.themeColors().accent
    // 连着增删时先发出的查询可能后回来，只认最后一次
    val generation = remember { intArrayOf(0) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val current = picker ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SharedFolders.fromTreeUri(uri)
        if (path == null) current.formError = "只支持内部存储里的文件夹" else current.navigate(path)
    }

    BackHandler(onBack = onBack)

    // 查一遍源目录在不在、Linux 用户能不能读写。内部存储的目录要靠 media_rw 组：读不了（或刚加了一个）就补上组再查
    fun refresh(list: List<SharedFolder>, fixGroup: Boolean = false) {
        checking = true
        val mine = ++generation[0]
        scope.launch {
            val config = hub.settings.env
            val (sources, states) = withContext(Dispatchers.IO) {
                val sources = SharedFolders.sourcesPresent(list)
                var states = config?.let { SharedFolders.access(it, hub.helperScript, list) }.orEmpty()
                val media = list.filter { SharedFolders.isMediaStorage(it.source) }
                if (config != null && media.isNotEmpty() && (fixGroup || media.any { states[it.name] == SharedAccess.Denied })) {
                    if (SharedFolders.ensureMediaGroup(config)) states = SharedFolders.access(config, hub.helperScript, list).orEmpty()
                }
                sources to states
            }
            if (mine != generation[0]) return@launch
            present = sources
            access = states
            checking = false
        }
    }
    LaunchedEffect(Unit) { refresh(folders) }

    CouiScaffoldPage(title = "共享文件夹", onBack = onBack) {
        Group(footer = Footer) {
            if (folders.isEmpty()) {
                CouiPreference(title = "还没有共享文件夹", summary = "把 Android 目录挂载到 Linux 环境的 /mnt/shared/ 下")
            }
            folders.forEach { folder ->
                CouiPopupPreference(
                    title = folder.name,
                    summary = folder.source + "\n挂载点：" + folder.mountPoint,
                    value = when {
                        present[folder.name] == false -> "源目录不存在"
                        access[folder.name] == SharedAccess.ReadWrite -> "可读写"
                        access[folder.name] == SharedAccess.ReadOnly -> "只读"
                        access[folder.name] == SharedAccess.Denied -> "没有权限"
                        access[folder.name] == SharedAccess.NotMounted -> "没有挂上"
                        checking -> "检查中"
                        else -> "未知"
                    },
                    expanded = menuFor == folder.name,
                    onExpandedChange = { menuFor = if (it) folder.name else null },
                    items = listOf(
                        CouiMenuItem(value = 0, text = "复制挂载点路径"),
                        CouiMenuItem(value = 1, text = "移除"),
                    ),
                    onItemClick = {
                        menuFor = null
                        if (it.value == 0) copyLinuxPath(context, folder.mountPoint) else removing = folder
                    },
                )
            }
            CouiPreference(
                title = "添加共享文件夹",
                titleColor = accent,
                showChevron = false,
                enabled = folders.size < SharedFolders.MAX,
                onClick = { picker = FolderPicker("/sdcard", folders) },
            )
        }
    }

    val shownPicker = picker ?: lastPicker
    LaunchedEffect(picker, picker?.path) {
        val current = picker ?: return@LaunchedEffect
        current.entries = null
        val found = withContext(Dispatchers.IO) { SharedFolders.subfolders(current.path) }
        current.entries = found.orEmpty()
        current.browseError = if (found == null) "无法打开该目录" else null
    }
    CouiConfirmDialog(
        visible = picker != null,
        title = "添加共享文件夹",
        message = null,
        confirmText = "添加",
        onConfirm = {
            val current = picker ?: return@CouiConfirmDialog
            // 输入了路径却没按回车：先打开它，看清楚了再添加
            val typed = SharedFolders.normalize(current.input)
            if (typed == null) {
                current.formError = "目录路径无效"
                return@CouiConfirmDialog
            }
            if (typed != current.path) {
                current.navigate(typed)
                current.formError = "已打开输入的目录，确认无误后再点“添加”"
                return@CouiConfirmDialog
            }
            val name = current.name.trim()
            val problem = SharedFolders.sourceProblem(current.path, folders, hub.env?.rootfs)
                ?: SharedFolders.nameProblem(name, folders)
                ?: current.browseError
            if (problem != null) {
                current.formError = problem
            } else {
                val source = SharedFolders.normalize(current.path) ?: current.path
                val updated = folders + SharedFolder(name, source)
                hub.settings.sharedFolders = updated
                folders = updated
                picker = null
                refresh(updated, fixGroup = SharedFolders.isMediaStorage(source))
            }
        },
        onDismissRequest = { picker = null },
    ) {
        val current = shownPicker ?: return@CouiConfirmDialog
        CouiTextField(
            value = current.input,
            onValueChange = { current.input = it },
            label = "目录",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { current.navigate(current.input) }),
        )
        // 不用对话框的 neutral 按钮：COUI 把它画成红色的危险操作，三个按钮竖排还会挤掉下面的挂载名
        Text(
            text = "用系统文件选择器选择",
            style = MiuixTheme.textStyles.footnote1,
            color = CouiPageDefaults.themeColors().accent,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { runCatching { treeLauncher.launch(initialTreeUri(current.path)) } }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        current.browseError?.let { PickerNote(it, error = true) }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
            if (current.path != "/") {
                item(key = "..") {
                    PickerRow("../") { current.navigate(current.path.substringBeforeLast('/').ifEmpty { "/" }) }
                }
            }
            val entries = current.entries
            when {
                entries == null -> item(key = "loading") {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        InfiniteProgressIndicator(size = 20.dp)
                    }
                }
                entries.isEmpty() && current.browseError == null -> item(key = "empty") { PickerNote("此处没有子文件夹") }
                else -> items(entries, key = { it }) { entry ->
                    PickerRow(entry) { current.navigate(current.path.trimEnd('/') + "/" + entry) }
                }
            }
        }
        Text(
            text = "挂载目录：" + current.path,
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = EtaMonospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
        CouiTextField(
            value = current.name,
            onValueChange = {
                current.name = it
                current.nameTouched = true
                current.formError = null
            },
            label = "挂载名",
            singleLine = true,
            modifier = Modifier.padding(top = 8.dp),
        )
        current.formError?.let { PickerNote(it, error = true) }
    }

    CouiConfirmDialog(
        visible = removing != null,
        title = "移除共享文件夹",
        message = lastRemoving?.let { "移除“${it.name}”（${it.source}）？源目录中的文件不会被删除。" },
        confirmText = "移除",
        destructive = true,
        onConfirm = {
            val target = removing ?: return@CouiConfirmDialog
            removing = null
            val updated = folders.filterNot { it.name == target.name }
            hub.settings.sharedFolders = updated
            folders = updated
            scope.launch {
                withContext(Dispatchers.IO) { hub.env?.rootfs?.let { SharedFolders.removeMountPoint(it, target.name) } }
                refresh(updated)
            }
        },
        onDismissRequest = { removing = null },
    )
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        CouiIcon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = CouiPageDefaults.themeColors().accent,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
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
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
