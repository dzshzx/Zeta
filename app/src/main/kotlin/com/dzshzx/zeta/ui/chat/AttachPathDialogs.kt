package com.dzshzx.zeta.ui.chat

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EnvShell
import com.dzshzx.zeta.core.PathCheck
import com.dzshzx.zeta.core.PathKind
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.ui.components.MonoFont
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.TextField
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.LinkText
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.appendLink
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 附件菜单里“文件夹”“输入路径”两项各自的对话框开没开。每次打开都从头来（[opens] 加一）。 */
@Stable
internal class PathAttach {
    var folder by mutableStateOf(false)
        private set
    var path by mutableStateOf(false)
        private set
    var opens by mutableIntStateOf(0)
        private set

    fun openFolder() {
        opens++
        folder = true
    }

    fun openPath() {
        opens++
        path = true
    }

    fun dismiss() {
        folder = false
        path = false
    }
}

/**
 * 引用 Linux 里的文件夹或路径。选好后交给 [onAttach] 一条附件说明（见 [AttachedPaths]），随消息发出时 Claude Code 直接读到它。
 * 这里查“看不看得到”都以对话的 Claude Code 进程为准：它启动之后才加的共享文件夹，它看不到，要重启一次。
 * 两个都是 COUI 2 的对话框，要在它的 Scaffold 里才显示。
 */
@Composable
internal fun AttachPathDialogs(state: PathAttach, hub: ConversationHub, conversation: Conversation, onAttach: (String) -> Unit) {
    FolderAttachDialog(state.folder, state.opens, hub, conversation, onAttach, state::dismiss)
    PathAttachDialog(state.path, state.opens, hub, conversation, onAttach, state::dismiss)
}

/** 进程能不能停下来重启：这一轮没在跑、没有等确认、没有后台任务。重启后下次发送用 --resume 接上，对话内容不变。 */
private fun Conversation.canRestart(): Boolean = !active

private sealed interface Resolution {
    data object Checking : Resolution

    /** 对话的 Claude Code 看得到，可以引用。 */
    data class Ready(val linuxPath: String, val folder: Boolean) : Resolution

    /** 在共享文件夹里，但进程启动时还没有这个共享文件夹。 */
    data class NeedsRestart(val linuxPath: String, val folder: Boolean) : Resolution

    /** Linux 里看不到，加为共享文件夹 [name] 后就在 /mnt/shared/[name]；[problem] 不为空时不能加。 */
    data class NeedsShare(val name: String, val problem: String?) : Resolution

    data class Problem(val message: String) : Resolution
}

/**
 * 查对话所在主机上的路径 [linuxPath] 对对话进程是不是可见。手机本机的内部存储读不了时先把 Linux 用户补进 media_rw 组再查一次。
 */
private fun checkLinux(hub: ConversationHub, conversation: Conversation, linuxPath: String, wantFolder: Boolean?): Resolution {
    val shell = hub.shell(conversation.backend) ?: return Resolution.Problem(hub.unavailable(conversation.backend) ?: "用不了这台设备")
    val config = hub.settings.env?.takeIf { conversation.backend == Backends.LOCAL }
    var kind = PathCheck.check(shell, linuxPath, conversation.hostKey)
    if (config != null && kind == PathKind.Denied && linuxPath.startsWith(SharedFolders.LINUX_ROOT + "/") && SharedFolders.ensureMediaGroup(config)) {
        kind = PathCheck.check(shell, linuxPath, conversation.hostKey)
    }
    return when (kind) {
        PathKind.Folder -> if (wantFolder == false) Resolution.Problem("这是一个文件夹") else Resolution.Ready(linuxPath, true)
        PathKind.File -> if (wantFolder == true) Resolution.Problem("这是一个文件，不是文件夹") else Resolution.Ready(linuxPath, false)
        PathKind.FolderNotInProcess -> if (wantFolder == false) Resolution.Problem("这是一个文件夹") else Resolution.NeedsRestart(linuxPath, true)
        PathKind.FileNotInProcess -> if (wantFolder == true) Resolution.Problem("这是一个文件，不是文件夹") else Resolution.NeedsRestart(linuxPath, false)
        PathKind.Missing -> Resolution.Problem(NotThere + linuxPath)
        PathKind.Denied -> Resolution.Problem("Linux 用户没有权限读取 $linuxPath")
        PathKind.Unknown -> Resolution.Problem("查不了：进不去 Linux 环境")
    }
}

/** 选手机上的一个文件夹：照“共享文件夹”那样用 root 一层层点进去，也可以输入路径或用系统选择器。 */
@Stable
private class AndroidFolderBrowser(start: String) {
    var path by mutableStateOf(start)
    var input by mutableStateOf(start)
    var entries by mutableStateOf<List<String>?>(null)
    var browseError by mutableStateOf<String?>(null)
    var resolution by mutableStateOf<Resolution>(Resolution.Checking)
    var working by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun navigate(target: String) {
        val normalized = SharedFolders.normalize(target) ?: run {
            error = "目录路径无效"
            return
        }
        path = normalized
        input = normalized
        browseError = null
        error = null
    }
}

/** 系统文件选择器从哪里开始：当前所在的内部存储目录。 */
private fun treeStart(path: String): Uri? {
    val rest = Regex("/data/media/\\d+(/.*)?").matchEntire(SharedFolders.bindSource(path))?.groupValues?.get(1)?.trim('/') ?: return null
    return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$rest")
}

@Composable
private fun FolderAttachDialog(
    visible: Boolean,
    opens: Int,
    hub: ConversationHub,
    conversation: Conversation,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val browser = remember(opens) { AndroidFolderBrowser("/sdcard") }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = SharedFolders.fromTreeUri(uri)
        if (path == null) browser.error = "只支持内部存储里的文件夹，其他位置请输入路径" else browser.navigate(path)
    }
    val resolution = browser.resolution
    val idle = conversation.canRestart()
    ConfirmDialog(
        show = visible,
        title = "引用文件夹",
        summary = null,
        confirmText = when (resolution) {
            is Resolution.NeedsShare -> "加为共享文件夹并引用"
            is Resolution.NeedsRestart -> "重启 Claude 并引用"
            else -> "引用"
        },
        confirmEnabled = !browser.working && browser.browseError == null && when (resolution) {
            is Resolution.Ready -> true
            is Resolution.NeedsRestart -> idle
            is Resolution.NeedsShare -> resolution.problem == null && (conversation.hostKey == null || idle)
            else -> false
        },
        onConfirm = {
            // 输入了路径却没按回车：先打开它，看清楚了再引用
            val typed = SharedFolders.normalize(browser.input)
            if (typed != null && typed != browser.path) {
                browser.navigate(typed)
                return@ConfirmDialog
            }
            when (resolution) {
                is Resolution.Ready -> {
                    onAttach(AttachedPaths.reference(resolution.linuxPath, folder = true))
                    onDismiss()
                }
                is Resolution.NeedsRestart -> {
                    conversation.shutdown()
                    onAttach(AttachedPaths.reference(resolution.linuxPath, folder = true))
                    onDismiss()
                }
                is Resolution.NeedsShare -> {
                    browser.working = true
                    scope.launch {
                        val failure = withContext(Dispatchers.IO) { shareAndCheck(hub, browser.path, resolution.name) }
                        browser.working = false
                        if (failure != null) {
                            browser.error = failure
                            browser.resolution = withContext(Dispatchers.IO) { resolveFolder(hub, conversation, browser.path) }
                            return@launch
                        }
                        // 已在跑的进程看不到新挂的目录：停下它，下次发送时带着新的挂载重新起来
                        if (conversation.hostKey != null) conversation.shutdown()
                        AttachedPaths.linuxPathFor(browser.path, hub.settings.sharedFolders, hub.settings.env?.rootfs)?.let {
                            onAttach(AttachedPaths.reference(it, folder = true))
                        }
                        onDismiss()
                    }
                }
                else -> Unit
            }
        },
        onDismissRequest = onDismiss,
    ) {
        // 换了目录：列子文件夹，同时看它在 Linux 里叫什么、对话的 Claude Code 看不看得到（两件事各起一个 root shell，互不相干）。
        // 放在对话框内容里，内容画出来以后才开始查：COUI 在 Scaffold 的另一块子组合里画对话框，结果要是赶在内容第一次画出的那一帧到，
        // 外面这层重组后，Compose 给对话框内容的重画请求会被丢掉，状态就一直停在“正在查看”。
        LaunchedEffect(browser, browser.path) {
            browser.entries = null
            browser.resolution = Resolution.Checking
            val path = browser.path
            val resolving = async(Dispatchers.IO) { resolveFolder(hub, conversation, path) }
            val found = withContext(Dispatchers.IO) { SharedFolders.subfolders(path) }
            browser.entries = found.orEmpty()
            browser.browseError = if (found == null) "无法打开该目录" else null
            browser.resolution = resolving.await()
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            TextField(
                value = browser.input,
                onValueChange = {
                    browser.input = it
                    browser.error = null
                },
                label = "手机上的文件夹",
                // 框里一开始就有路径：标题浮在上面，不当占位提示
                useLabelAsPlaceholder = false,
                singleLine = true,
                showClearButton = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { browser.navigate(browser.input) }),
            )
            // 不用对话框的 neutral 按钮：COUI 把它画成红色的危险操作
            LinkText(
                text = buildAnnotatedString {
                    appendLink("用系统文件选择器选择", onClick = { runCatching { treeLauncher.launch(treeStart(browser.path)) } })
                },
                style = COUITheme.textStyles.bodyS,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            browser.browseError?.let { Note(it, error = true) }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp)) {
                if (browser.path != "/") {
                    item(key = "..") { FolderRow("../") { browser.navigate(browser.path.substringBeforeLast('/').ifEmpty { "/" }) } }
                }
                val entries = browser.entries
                when {
                    entries == null -> item(key = "loading") {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                            InfiniteProgressIndicator(size = 20.dp)
                        }
                    }
                    entries.isEmpty() && browser.browseError == null -> item(key = "empty") { Note("此处没有子文件夹") }
                    else -> items(entries, key = { it }) { entry -> FolderRow(entry) { browser.navigate(browser.path.trimEnd('/') + "/" + entry) } }
                }
            }
            val status = when (resolution) {
                Resolution.Checking -> "正在查看 Linux 里能不能看到它…" to false
                is Resolution.Ready -> "在 Linux 里是 ${resolution.linuxPath}" to false
                is Resolution.NeedsRestart -> "在 Linux 里是 ${resolution.linuxPath}，但这个对话的 Claude Code 是在加这个共享文件夹之前启动的，" +
                    (if (idle) "要重启一次才看得到（对话内容不变）" else "要等这一轮结束、重启一次才看得到") to !idle
                is Resolution.NeedsShare -> (
                    resolution.problem?.let { "Linux 里看不到这个文件夹，也不能共享：$it" }
                        ?: ("Linux 里看不到这个文件夹。加为共享文件夹后，Claude 在 ${SharedFolders.LINUX_ROOT}/${resolution.name} 读到它" +
                            when {
                                conversation.hostKey == null -> "。"
                                idle -> "；这个对话的 Claude Code 会重启一次（对话内容不变）。"
                                else -> "；要等这一轮结束，这个对话的 Claude Code 重启一次才看得到。"
                            })
                    ) to (resolution.problem != null || (conversation.hostKey != null && !idle))
                is Resolution.Problem -> resolution.message to true
            }
            Text(
                text = status.first,
                style = COUITheme.textStyles.bodyS.copy(fontFamily = if (resolution is Resolution.Ready) MonoFont else null),
                color = if (status.second) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            browser.error?.let { Note(it, error = true) }
        }
    }
}

/** 手机上的目录 [path] 在 Linux 里能不能被对话看到；看不到时给出共享它要用的名字。 */
private fun resolveFolder(hub: ConversationHub, conversation: Conversation, path: String): Resolution {
    val config = hub.settings.env ?: return Resolution.Problem("还没有配置 Linux 环境")
    val shares = hub.settings.sharedFolders
    val linux = AttachedPaths.linuxPathFor(path, shares, config.rootfs)
        ?: return Resolution.NeedsShare(SharedFolders.defaultName(path, shares), SharedFolders.sourceProblem(path, shares, config.rootfs))
    return checkLinux(hub, conversation, linux, wantFolder = true)
}

/** 把 [path] 加为共享文件夹 [name]，再以 Linux 用户的身份（新进入的环境）确认读得到。成功返回 null，否则是原因。 */
private fun shareAndCheck(hub: ConversationHub, path: String, name: String): String? {
    val config = hub.settings.env ?: return "还没有配置 Linux 环境"
    val shares = hub.settings.sharedFolders
    (SharedFolders.sourceProblem(path, shares, config.rootfs) ?: SharedFolders.nameProblem(name, shares))?.let { return it }
    val updated = shares + SharedFolder(name, path)
    hub.settings.sharedFolders = updated
    if (SharedFolders.isMediaStorage(path)) SharedFolders.ensureMediaGroup(config)
    return when (PathCheck.check(EnvShell(config), "${SharedFolders.LINUX_ROOT}/$name", null)) {
        PathKind.Folder -> null
        PathKind.Denied -> "已加为共享文件夹，但 Linux 用户读不了它"
        PathKind.Unknown -> "已加为共享文件夹，但查不了它能不能读"
        else -> "已加为共享文件夹，但没有挂上"
    }
}

/** 直接输入 Linux 里的路径，边输入边查它在不在。手机上的路径能对应到 Linux 里的，换成那边的路径。 */
@Composable
private fun PathAttachDialog(
    visible: Boolean,
    opens: Int,
    hub: ConversationHub,
    conversation: Conversation,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(opens) { mutableStateOf("") }
    var resolution by remember(opens) { mutableStateOf<Resolution?>(null) }
    val home = hub.home(conversation.backend)
    val remote = conversation.backend != Backends.LOCAL
    LaunchedEffect(visible, input) {
        if (!visible) return@LaunchedEffect
        resolution = null
        if (input.isBlank()) return@LaunchedEffect
        delay(350)
        resolution = Resolution.Checking
        resolution = withContext(Dispatchers.IO) { resolvePath(hub, conversation, input, home) }
    }
    val current = resolution
    val idle = conversation.canRestart()
    ConfirmDialog(
        show = visible,
        title = if (remote) "引用「${hub.backendName(conversation.backend)}」上的路径" else "引用 Linux 路径",
        summary = if (remote) "这台设备上一个文件或文件夹的绝对路径，Claude 会直接读到它。"
        else "Linux 环境里一个文件或文件夹的绝对路径，Claude 会直接读到它。手机上的目录要先加为共享文件夹（${SharedFolders.LINUX_ROOT}/<名字>）。",
        confirmText = if (current is Resolution.NeedsRestart) "重启 Claude 并引用" else "引用",
        confirmEnabled = current is Resolution.Ready || (current is Resolution.NeedsRestart && idle),
        onConfirm = {
            when (current) {
                is Resolution.Ready -> onAttach(AttachedPaths.reference(current.linuxPath, current.folder))
                is Resolution.NeedsRestart -> {
                    conversation.shutdown()
                    onAttach(AttachedPaths.reference(current.linuxPath, current.folder))
                }
                else -> return@ConfirmDialog
            }
            onDismiss()
        },
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val kind = when (current) {
                is Resolution.Ready -> if (current.folder) "文件夹" else "文件"
                is Resolution.NeedsRestart -> "进程还看不到"
                is Resolution.Problem -> "不可用"
                Resolution.Checking -> "正在查…"
                else -> null
            }
            val colors = COUITheme.colorScheme
            TextField(
                value = input,
                onValueChange = { input = it },
                // 占位提示是当前工作目录；查到的结果（文件、文件夹、不可用）跟在输入框末尾
                label = conversation.cwd.trimEnd('/') + "/…",
                singleLine = true,
                showClearButton = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                trailingIcon = kind?.let { text ->
                    {
                        Text(
                            text = text,
                            style = COUITheme.textStyles.bodyS,
                            color = when (current) {
                                is Resolution.Ready -> colors.primary
                                Resolution.Checking -> colors.labelSecondary
                                else -> colors.error
                            },
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
            )
            val note = when (current) {
                is Resolution.Ready -> if (current.linuxPath != AttachedPaths.normalize(input, home)) "手机上的路径，引用 Linux 里的 ${current.linuxPath}" else null
                is Resolution.NeedsRestart -> "这个对话的 Claude Code 是在加这个共享文件夹之前启动的，" +
                    if (idle) "要重启一次才看得到（对话内容不变）" else "要等这一轮结束、重启一次才看得到"
                is Resolution.Problem -> current.message
                else -> null
            }
            note?.let { Note(it, error = current !is Resolution.Ready) }
        }
    }
}

/** 查不到路径时说明的开头。 */
private const val NotThere = "没有这个路径："

/** 输入的路径：先当对话所在主机上的路径查；手机本机上没有的话，看它是不是手机上能对应到 Linux 的目录。 */
private fun resolvePath(hub: ConversationHub, conversation: Conversation, input: String, home: String?): Resolution {
    if (hub.shell(conversation.backend) == null) return Resolution.Problem(hub.unavailable(conversation.backend) ?: "用不了这台设备")
    val path = AttachedPaths.normalize(input, home)
        ?: return Resolution.Problem(if (input.trim().let { it.startsWith("/") || it.startsWith("~") }) "路径里不能有双引号或控制字符" else "要输入以 / 开头的绝对路径")
    val direct = checkLinux(hub, conversation, path, wantFolder = null)
    if (direct !is Resolution.Problem || !direct.message.startsWith(NotThere)) return direct
    val config = hub.settings.env?.takeIf { conversation.backend == Backends.LOCAL } ?: return direct
    val shares = hub.settings.sharedFolders
    val mapped = AttachedPaths.linuxPathFor(path, shares, config.rootfs)?.takeIf { it != path }
    if (mapped != null) return checkLinux(hub, conversation, mapped, wantFolder = null)
    if (SharedFolders.bindSource(path).startsWith("/data/media/")) {
        return Resolution.Problem("Linux 里没有这个路径。它是手机内部存储里的目录，要先在设置里把它（或上层目录）加为共享文件夹")
    }
    return direct
}

/** 目录列表的一行：文件夹图标加名字，点了进去。对话框里的列表，COUI 没有现成的行，自己排。 */
@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
    ) {
        Icon(imageVector = COUIIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp), tint = COUITheme.colorScheme.primary)
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
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS,
        color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
