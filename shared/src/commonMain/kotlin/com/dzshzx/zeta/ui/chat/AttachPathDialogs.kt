package com.dzshzx.zeta.ui.chat

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
import androidx.compose.foundation.lazy.LazyListScope
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
import com.dzshzx.zeta.core.HelperResult
import com.dzshzx.zeta.core.PathCheck
import com.dzshzx.zeta.core.PathKind
import com.dzshzx.zeta.core.SharedFolder
import com.dzshzx.zeta.core.SharedFolders
import com.dzshzx.zeta.i18n.Strings
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 附件菜单里“文件夹”“输入路径”两项各自的对话框开没开。每次打开都从头来（[opens] 加一）。 */
@Stable
class PathAttach {
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
 * 这里查“看不看得到”都以对话的 Claude Code 进程为准：它启动之后才加的共享文件夹，它看不到，要重启一次。 两个都是 COUI 2 的对话框，要在它的 Scaffold 里才显示。
 */
@Composable
fun AttachPathDialogs(
    state: PathAttach,
    hub: ConversationHub,
    conversation: Conversation,
    onAttach: (String) -> Unit,
) {
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

    /** 用不了，[message] 是原因；[missing] 是路径不存在（输入路径时再看它是不是手机上能对应过去的目录）。 */
    data class Problem(val message: String, val missing: Boolean = false) : Resolution
}

/** 查对话所在主机上的路径 [linuxPath] 对对话进程是不是可见。手机本机的内部存储读不了时先把 Linux 用户补进 media_rw 组再查一次。 */
private fun checkLinux(
    hub: ConversationHub,
    conversation: Conversation,
    linuxPath: String,
    wantFolder: Boolean?,
): Resolution {
    val shell =
        hub.shell(conversation.backend)
            ?: return Resolution.Problem(
                hub.unavailable(conversation.backend) ?: Strings.composerDeviceUnavailable(),
            )
    val config = hub.settings.env?.takeIf { conversation.backend == Backends.LOCAL }
    // 按对话进程的挂载查看不看得到（加共享文件夹后要不要重启）；还没接着进程时查新进程会看到的
    var kind = PathCheck.check(shell, linuxPath, hostKey = conversation.hostKey)
    if (
        config != null &&
            kind == PathKind.Denied &&
            linuxPath.startsWith(SharedFolders.LINUX_ROOT + "/") &&
            SharedFolders.ensureMediaGroup(config)
    ) {
        kind = PathCheck.check(shell, linuxPath, hostKey = conversation.hostKey)
    }
    return when (kind) {
        PathKind.Folder ->
            if (wantFolder == false) Resolution.Problem(Strings.composerIsFolder())
            else Resolution.Ready(linuxPath, true)
        PathKind.File ->
            if (wantFolder == true) Resolution.Problem(Strings.composerIsFileNotFolder())
            else Resolution.Ready(linuxPath, false)
        PathKind.FolderNotInProcess ->
            if (wantFolder == false) Resolution.Problem(Strings.composerIsFolder())
            else Resolution.NeedsRestart(linuxPath, true)
        PathKind.FileNotInProcess ->
            if (wantFolder == true) Resolution.Problem(Strings.composerIsFileNotFolder())
            else Resolution.NeedsRestart(linuxPath, false)
        PathKind.Missing ->
            Resolution.Problem(Strings.composerPathMissing(linuxPath), missing = true)
        PathKind.Denied -> Resolution.Problem(Strings.composerPathDenied(linuxPath))
        PathKind.Unknown -> Resolution.Problem(Strings.composerPathUnknown())
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
        val normalized =
            SharedFolders.normalize(target)
                ?: run {
                    error = Strings.composerFolderPathInvalid()
                    return
                }
        path = normalized
        input = normalized
        browseError = null
        error = null
    }
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
    val pickTree =
        ChatPlatform.current.rememberFolderTreePicker { path ->
            if (path == null) browser.error = Strings.composerPickerInternalOnly()
            else browser.navigate(path)
        }
    val resolution = browser.resolution
    val idle = conversation.canRestart()
    ConfirmDialog(
        show = visible,
        title = Strings.composerAttachFolderTitle(),
        summary = null,
        confirmText = folderConfirmText(resolution),
        confirmEnabled =
            !browser.working &&
                browser.browseError == null &&
                folderConfirmAllowed(resolution, idle, conversation.isProcessAlive),
        onConfirm = {
            // 输入了路径却没按回车：先打开它，看清楚了再引用
            val typed = SharedFolders.normalize(browser.input)
            if (typed != null && typed != browser.path) {
                browser.navigate(typed)
                return@ConfirmDialog
            }
            confirmFolder(resolution, browser, hub, conversation, scope, onAttach, onDismiss)
        },
        onDismissRequest = onDismiss,
    ) {
        FolderDialogContent(browser, resolution, idle, hub, conversation) {
            pickTree(browser.path)
        }
    }
}

/** 引用文件夹对话框的确认键文字：要先共享或重启时说清楚。 */
private fun folderConfirmText(resolution: Resolution): String =
    when (resolution) {
        is Resolution.NeedsShare -> Strings.composerShareAndAttach()
        is Resolution.NeedsRestart -> Strings.composerRestartAndAttach()
        else -> Strings.composerAttachRef()
    }

/** 按查到的结果能不能引用：要重启的得等这一轮结束；要共享的不能有问题，且（已在跑的进程要重启）得空闲。 */
private fun folderConfirmAllowed(resolution: Resolution, idle: Boolean, running: Boolean): Boolean =
    when (resolution) {
        is Resolution.Ready -> true
        is Resolution.NeedsRestart -> idle
        is Resolution.NeedsShare -> resolution.problem == null && (!running || idle)
        else -> false
    }

/** 按下引用文件夹的确认键：看得到的直接引用，要重启的先停进程、换核心，要共享的先加共享文件夹。 */
private fun confirmFolder(
    resolution: Resolution,
    browser: AndroidFolderBrowser,
    hub: ConversationHub,
    conversation: Conversation,
    scope: CoroutineScope,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (resolution) {
        is Resolution.Ready -> {
            onAttach(AttachedPaths.reference(resolution.linuxPath, folder = true))
            onDismiss()
        }
        is Resolution.NeedsRestart -> {
            browser.working = true
            scope.launch {
                browser.error = restartAndAttach(hub, conversation, resolution, onAttach, onDismiss)
                browser.working = false
            }
        }
        is Resolution.NeedsShare -> {
            browser.working = true
            scope.launch {
                shareAndAttach(resolution.name, browser, hub, conversation, onAttach, onDismiss)
            }
        }
        else -> Unit
    }
}

/** 把选的目录加为共享文件夹 [name] 再引用；加不成时留在对话框里说明原因，并重查一遍。 */
private suspend fun shareAndAttach(
    name: String,
    browser: AndroidFolderBrowser,
    hub: ConversationHub,
    conversation: Conversation,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val failure =
        withContext(Dispatchers.IO) { shareAndCheck(hub, browser.path, name) }
            // 核心和已在跑的进程都看不到新挂的目录：停下进程、换核心，下次发送时带着新的挂载重新起来
            ?: renewCore(hub, conversation, end = conversation.isProcessAlive)
    browser.working = false
    if (failure != null) {
        browser.error = failure
        browser.resolution =
            withContext(Dispatchers.IO) { resolveFolder(hub, conversation, browser.path) }
        return
    }
    AttachedPaths.linuxPathFor(browser.path, hub.settings.sharedFolders, hub.settings.env?.rootfs)
        ?.let { onAttach(AttachedPaths.reference(it, folder = true)) }
    onDismiss()
}

/** 引用文件夹对话框的内容：路径输入框、系统选择器链接、子文件夹列表和 Linux 里看不看得到的说明。 */
@Composable
private fun FolderDialogContent(
    browser: AndroidFolderBrowser,
    resolution: Resolution,
    idle: Boolean,
    hub: ConversationHub,
    conversation: Conversation,
    onPickTree: () -> Unit,
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
        browser.browseError = if (found == null) Strings.composerBrowseFailed() else null
        browser.resolution = resolving.await()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = browser.input,
            onValueChange = {
                browser.input = it
                browser.error = null
            },
            label = Strings.composerPhoneFolderLabel(),
            // 框里一开始就有路径：标题浮在上面，不当占位提示
            useLabelAsPlaceholder = false,
            singleLine = true,
            showClearButton = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { browser.navigate(browser.input) }),
        )
        // 不用对话框的 neutral 按钮：COUI 把它画成红色的危险操作
        LinkText(
            text =
                buildAnnotatedString {
                    appendLink(Strings.composerUseSystemPicker(), onClick = onPickTree)
                },
            style = COUITheme.textStyles.bodyS,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        browser.browseError?.let { Note(it, error = true) }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 4.dp),
        ) {
            folderEntries(browser)
        }
        val status = folderStatus(resolution, idle, conversation.isProcessAlive)
        Text(
            text = status.first,
            style =
                COUITheme.textStyles.bodyS.copy(
                    fontFamily = if (resolution is Resolution.Ready) MonoFont else null,
                ),
            color =
                if (status.second) COUITheme.colorScheme.error
                else COUITheme.colorScheme.labelSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        browser.error?.let { Note(it, error = true) }
    }
}

/** 子文件夹列表：不在根目录时先是返回上一层，然后是载入中、空目录的说明或各个子文件夹。 */
private fun LazyListScope.folderEntries(browser: AndroidFolderBrowser) {
    if (browser.path != "/") {
        item(key = "..") {
            FolderRow("../") {
                browser.navigate(browser.path.substringBeforeLast('/').ifEmpty { "/" })
            }
        }
    }
    val entries = browser.entries
    when {
        entries == null ->
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 20.dp)
                }
            }
        entries.isEmpty() && browser.browseError == null ->
            item(key = "empty") { Note(Strings.composerNoSubfolders()) }
        else ->
            items(entries, key = { it }) { entry ->
                FolderRow(entry) { browser.navigate(browser.path.trimEnd('/') + "/" + entry) }
            }
    }
}

/** 选中的目录在 Linux 里看不看得到的说明，和它算不算问题（标红）。 */
private fun folderStatus(
    resolution: Resolution,
    idle: Boolean,
    running: Boolean,
): Pair<String, Boolean> =
    when (resolution) {
        Resolution.Checking -> Strings.composerLinuxChecking() to false
        is Resolution.Ready -> Strings.composerLinuxPathIs(resolution.linuxPath) to false
        is Resolution.NeedsRestart ->
            Strings.composerLinuxPathNeedsRestart(resolution.linuxPath, restartHint(idle)) to !idle
        is Resolution.NeedsShare ->
            (resolution.problem?.let { Strings.composerNotVisibleCantShare(it) }
                ?: needsShareText(
                    "${SharedFolders.LINUX_ROOT}/${resolution.name}",
                    idle,
                    running,
                )) to (resolution.problem != null || (running && !idle))
        is Resolution.Problem -> resolution.message to true
    }

/** 进程要重启才看得到时，空闲与否的说法。 */
private fun restartHint(idle: Boolean): String =
    if (idle) Strings.composerRestartHintIdle() else Strings.composerRestartHintBusy()

/** 目录要先共享时的说明：共享后在 Linux 里的路径 [path]，对话的 Claude Code 要不要重启、什么时候重启。 */
private fun needsShareText(path: String, idle: Boolean, running: Boolean): String =
    when {
        !running -> Strings.composerNeedsShare(path)
        idle -> Strings.composerNeedsShareRestart(path)
        else -> Strings.composerNeedsShareWait(path)
    }

/**
 * 让对话的 Claude Code 下次起来时看得到新挂上的共享文件夹：每次进 Debian 各有自己的挂载，常驻的核心和它起的 Claude Code
 * 只看得到核心启动时挂着的（docs/core.md 「换一个看得到新挂载的核心」）。[end]
 * 时先结束对话的进程（conv.end）：交班后的新核心不接闲着的进程，不先结束的话下次发消息会接回这个看不到的； 再经一次新的进入跑 `zeta-helper
 * core-takeover`，让核心换成这次进入拉起的。都在后台线程上跑。成了返回 null，没成返回给人看的原因。
 */
private suspend fun renewCore(
    hub: ConversationHub,
    conversation: Conversation,
    end: Boolean,
): String? {
    val done = CompletableDeferred<HelperResult>()
    val takeover: () -> Unit = {
        done.complete(
            runCatching { hub.helper("core-takeover", backend = conversation.backend) }
                .getOrElse { HelperResult.Failed(it.message ?: Strings.composerNotRun()) },
        )
    }
    if (end) conversation.shutdown(takeover) else withContext(Dispatchers.IO) { takeover() }
    val failed = done.await() as? HelperResult.Failed ?: return null
    return Strings.composerRenewFailed(failed.reason)
}

/** 引用要重启才看得到的 [target]：先结束进程、换核心（[renewCore]），成了再引用并关掉对话框；没成返回原因，对话框留着。 */
private suspend fun restartAndAttach(
    hub: ConversationHub,
    conversation: Conversation,
    target: Resolution.NeedsRestart,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
): String? {
    renewCore(hub, conversation, end = true)?.let {
        return it
    }
    onAttach(AttachedPaths.reference(target.linuxPath, target.folder))
    onDismiss()
    return null
}

/** 手机上的目录 [path] 在 Linux 里能不能被对话看到；看不到时给出共享它要用的名字。 */
private fun resolveFolder(
    hub: ConversationHub,
    conversation: Conversation,
    path: String,
): Resolution {
    val config = hub.settings.env ?: return Resolution.Problem(Strings.composerNoLinuxEnv())
    val shares = hub.settings.sharedFolders
    val linux =
        AttachedPaths.linuxPathFor(path, shares, config.rootfs)
            ?: return Resolution.NeedsShare(
                SharedFolders.defaultName(path, shares),
                SharedFolders.sourceProblem(path, shares, config.rootfs),
            )
    return checkLinux(hub, conversation, linux, wantFolder = true)
}

/** 把 [path] 加为共享文件夹 [name]，再以 Linux 用户的身份（新进入的环境）确认读得到。成功返回 null，否则是原因。 */
private fun shareAndCheck(hub: ConversationHub, path: String, name: String): String? {
    val config = hub.settings.env ?: return Strings.composerNoLinuxEnv()
    val shares = hub.settings.sharedFolders
    (SharedFolders.sourceProblem(path, shares, config.rootfs)
            ?: SharedFolders.nameProblem(name, shares))
        ?.let {
            return it
        }
    val updated = shares + SharedFolder(name, path)
    hub.settings.sharedFolders = updated
    if (SharedFolders.isMediaStorage(path)) SharedFolders.ensureMediaGroup(config)
    return when (PathCheck.check(EnvShell(config), "${SharedFolders.LINUX_ROOT}/$name", null)) {
        PathKind.Folder -> null
        PathKind.Denied -> Strings.composerSharedButDenied()
        PathKind.Unknown -> Strings.composerSharedButUnknown()
        else -> Strings.composerSharedButNotMounted()
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
    var working by remember(opens) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
        title =
            if (remote) Strings.composerAttachPathOnTitle(hub.backendName(conversation.backend))
            else Strings.composerAttachLinuxPathTitle(),
        summary =
            if (remote) Strings.composerAttachPathRemoteSummary()
            else Strings.composerAttachPathLocalSummary(SharedFolders.LINUX_ROOT),
        confirmText =
            if (current is Resolution.NeedsRestart) Strings.composerRestartAndAttach()
            else Strings.composerAttachRef(),
        confirmEnabled =
            !working &&
                (current is Resolution.Ready || (current is Resolution.NeedsRestart && idle)),
        onConfirm = {
            confirmPath(current, hub, conversation, scope, { working = it }, onAttach, onDismiss) {
                resolution = Resolution.Problem(it)
            }
        },
        onDismissRequest = onDismiss,
    ) {
        PathDialogContent(current, input, { input = it }, home, idle, conversation.cwd)
    }
}

/**
 * 按下引用路径的确认键：看得到的直接引用；要重启的先结束进程、换核心（[restartAndAttach]），期间 [setWorking] 为真，没成时把原因交给 [onFailed]；
 * 别的情况不动。
 */
private fun confirmPath(
    current: Resolution?,
    hub: ConversationHub,
    conversation: Conversation,
    scope: CoroutineScope,
    setWorking: (Boolean) -> Unit,
    onAttach: (String) -> Unit,
    onDismiss: () -> Unit,
    onFailed: (String) -> Unit,
) {
    when (current) {
        is Resolution.Ready -> {
            onAttach(AttachedPaths.reference(current.linuxPath, current.folder))
            onDismiss()
        }
        is Resolution.NeedsRestart -> {
            setWorking(true)
            scope.launch {
                restartAndAttach(hub, conversation, current, onAttach, onDismiss)?.let(onFailed)
                setWorking(false)
            }
        }
        else -> Unit
    }
}

/** 引用路径对话框的内容：输入框末尾跟着查到的结果，下面一行说明。 */
@Composable
private fun PathDialogContent(
    current: Resolution?,
    input: String,
    onInput: (String) -> Unit,
    home: String?,
    idle: Boolean,
    cwd: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val kind = pathKindLabel(current)
        val colors = COUITheme.colorScheme
        TextField(
            value = input,
            onValueChange = onInput,
            // 占位提示是当前工作目录；查到的结果（文件、文件夹、不可用）跟在输入框末尾
            label = cwd.trimEnd('/') + "/…",
            singleLine = true,
            showClearButton = true,
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            trailingIcon =
                kind?.let { text ->
                    {
                        Text(
                            text = text,
                            style = COUITheme.textStyles.bodyS,
                            color =
                                when (current) {
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
        pathNote(current, input, home, idle)?.let { Note(it, error = current !is Resolution.Ready) }
    }
}

/** 跟在路径输入框末尾的查询结果；还没输入时没有。 */
private fun pathKindLabel(current: Resolution?): String? =
    when (current) {
        is Resolution.Ready ->
            if (current.folder) Strings.composerKindFolder() else Strings.composerKindFile()
        is Resolution.NeedsRestart -> Strings.composerKindNotVisible()
        is Resolution.Problem -> Strings.composerKindUnavailable()
        Resolution.Checking -> Strings.composerKindChecking()
        else -> null
    }

/** 路径输入框下面的说明：手机路径换成了 Linux 路径、要重启进程或不可用的原因。 */
private fun pathNote(current: Resolution?, input: String, home: String?, idle: Boolean): String? =
    when (current) {
        is Resolution.Ready ->
            if (current.linuxPath != AttachedPaths.normalize(input, home))
                Strings.composerPhonePathMapped(current.linuxPath)
            else null
        is Resolution.NeedsRestart -> Strings.composerStartedBeforeShare(restartHint(idle))
        is Resolution.Problem -> current.message
        else -> null
    }

/** 输入的路径：先当对话所在主机上的路径查；手机本机上没有的话，看它是不是手机上能对应到 Linux 的目录。 */
private fun resolvePath(
    hub: ConversationHub,
    conversation: Conversation,
    input: String,
    home: String?,
): Resolution {
    if (hub.shell(conversation.backend) == null)
        return Resolution.Problem(
            hub.unavailable(conversation.backend) ?: Strings.composerDeviceUnavailable(),
        )
    val path =
        AttachedPaths.normalize(input, home)
            ?: return Resolution.Problem(
                if (input.trim().let { it.startsWith("/") || it.startsWith("~") })
                    Strings.composerPathBadChars()
                else Strings.composerPathNotAbsolute(),
            )
    val direct = checkLinux(hub, conversation, path, wantFolder = null)
    if (direct !is Resolution.Problem || !direct.missing) return direct
    val config =
        hub.settings.env?.takeIf { conversation.backend == Backends.LOCAL } ?: return direct
    val shares = hub.settings.sharedFolders
    val mapped = AttachedPaths.linuxPathFor(path, shares, config.rootfs)?.takeIf { it != path }
    if (mapped != null) return checkLinux(hub, conversation, mapped, wantFolder = null)
    if (SharedFolders.bindSource(path).startsWith("/data/media/")) {
        return Resolution.Problem(Strings.composerPathOnPhoneNotShared())
    }
    return direct
}

/** 目录列表的一行：文件夹图标加名字，点了进去。对话框里的列表，COUI 没有现成的行，自己排。 */
@Composable
private fun FolderRow(label: String, onClick: () -> Unit) {
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
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = COUITheme.textStyles.bodyS,
        color = if (error) COUITheme.colorScheme.error else COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
