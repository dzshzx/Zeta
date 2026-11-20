package com.dzshzx.zeta.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshTarget
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.settings.DangerRow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DirectFooter = "Zeta 用自己的密钥直接连，不需要手机里的 Debian。先把上面的公钥加进那台主机的 ~/.ssh/authorized_keys，" +
    "再输入地址。加主机时记下对方的主机指纹，以后只认它。"

private const val DebianFooter = "经手机 Debian 里的 OpenSSH 连：主机别名、密钥、跳板都用那边 ~/.ssh 的配置。"

private const val HostingFooter = "那台主机要是 arm64 或 x86-64 的 Linux，装好 Claude Code。对话由那边托管，手机断网或 Zeta 被关掉时照样跑完，连上后接着看。"

/** 加一台主机时连接检查的结果，给对话框显示。 */
private class ProbeNotice(val title: String, val message: String)

/**
 * 远程主机：上面的 Claude Code 也能在 Zeta 里用（侧栏顶部切换）。两种加法：输入地址，Zeta 用自己的密钥直连（不要 Debian）；
 * 或者从手机 Debian 的 ~/.ssh/config 里选，经那边的 OpenSSH 连。加之前先连一次，查家目录、claude、系统和 CPU。
 * 点一台进去改名、改默认工作目录、重新检查或删掉。
 */
@Composable
internal fun RemoteHostsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hosts = hub.settings.remoteHosts
    val local = hub.shell(Backends.LOCAL)?.takeIf { hub.usable(Backends.LOCAL) }
    // ~/.ssh/config 里的别名：读之前是 null，读不到时是原因
    var aliases by remember { mutableStateOf<Result<List<String>>?>(null) }
    // Zeta 的密钥：第一次打开时从 Keystore 取（没有就生成），取不到时是原因
    var key by remember { mutableStateOf<Result<SshKey>?>(null) }
    // 正在连的地址和连法：同一时间只连一个
    var probing by remember { mutableStateOf<Pair<String, RemoteHost.Via>?>(null) }
    var notice by remember { mutableStateOf<ProbeNotice?>(null) }
    val lastNotice = rememberRetained(notice)
    var typing by remember { mutableStateOf(false) }
    var showingKey by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        key = withContext(Dispatchers.IO) { runCatching { hub.ssh.key } }
    }
    LaunchedEffect(local != null) {
        if (local != null) aliases = withContext(Dispatchers.IO) { runCatching { RemoteHosts.readAliases(local) } }
    }

    fun add(target: String, via: RemoteHost.Via) {
        if (probing != null) return
        probing = target to via
        scope.launch {
            val probe = withContext(Dispatchers.IO) { hub.probeRemote(target, via) }
            probing = null
            when (probe) {
                is RemoteHosts.Probe.Failed -> notice = ProbeNotice("没有加上「$target」", probe.reason)
                is RemoteHosts.Probe.HostKeyChanged -> notice = ProbeNotice("没有加上「$target」", "主机指纹不对")
                is RemoteHosts.Probe.Ok -> {
                    val current = hub.settings.remoteHosts
                    val name = generateSequence(target) { it + "'" }.first { RemoteHosts.nameProblem(it, current) == null }
                    val host = RemoteHost(Backends.newId(), name, target, probe.home, probe.home, via, probe.hostKey)
                    hub.updateRemoteHosts(current + host)
                    // 手机本机用不了时，加的第一台直接用上
                    if (!hub.usable(Backends.LOCAL) && hub.backend == Backends.LOCAL) hub.selectBackend(host.id)
                    val fingerprint = host.fingerprint?.let { "\n主机指纹 $it" }.orEmpty()
                    notice = ProbeNotice("已加上「$name」", probeSummary(probe) + fingerprint + "\n在侧栏顶部切到它，就能看它上面的会话、在它上面开新对话。")
                }
            }
        }
    }

    CouiPage(
        title = "远程主机",
        onBack = onBack,
        dialogs = {
            val parsed = SshTarget.parse(draft)
            TextInputDialog(
                show = typing,
                title = "输入地址",
                summary = (if (draft.isNotBlank() && parsed == null) "" else "Zeta 用自己的密钥直连，") + RemoteHosts.DIRECT_TARGET_HINT,
                value = draft,
                onValueChange = { draft = it },
                label = "user@example.com",
                confirmText = "连接并添加",
                confirmEnabled = parsed != null && probing == null,
                onConfirm = {
                    typing = false
                    parsed?.let { add(it.toString(), RemoteHost.Via.Direct) }
                },
                onDismissRequest = { typing = false },
            )
            val authorized = key?.getOrNull()?.authorizedKey
            ConfirmDialog(
                show = showingKey,
                title = "Zeta 的公钥",
                summary = authorized,
                confirmText = "复制",
                onConfirm = {
                    showingKey = false
                    authorized?.let { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ssh", it)) }
                },
                onDismissRequest = { showingKey = false },
            )
            NoticeDialog(show = notice != null, title = lastNotice?.title, summary = lastNotice?.message, onDismissRequest = { notice = null })
        },
    ) {
        if (hosts.isNotEmpty()) {
            item(key = "hosts") {
                PreferenceGroup(title = "已添加") {
                    hosts.forEach { host ->
                        item {
                            ArrowPreference(
                                title = host.name,
                                summary = host.target + " · " + host.via.label,
                                endActions = { if (host.id == hub.backend) PreferenceValue("正在使用") },
                                onClick = { editing = host.id },
                            )
                        }
                    }
                }
            }
        }
        item(key = "direct") {
            PreferenceGroup(title = "Zeta 直连", footer = if (local == null) DirectFooter + HostingFooter else DirectFooter) {
                item {
                    val loaded = key
                    BasicComponent(
                        title = "Zeta 的公钥",
                        summary = when {
                            loaded == null -> "正在读…"
                            loaded.isFailure -> "读不到 Zeta 的密钥：" + loaded.exceptionOrNull()?.message.orEmpty().take(120)
                            else -> SshKey.ALGORITHM + " · " + loaded.getOrNull()?.fingerprint
                        },
                        enabled = loaded?.isSuccess == true,
                        onClick = { showingKey = true },
                    )
                }
                item {
                    BasicComponent(
                        title = "输入地址",
                        summary = probing?.takeIf { it.second == RemoteHost.Via.Direct }?.let { "正在连接 ${it.first}…" },
                        enabled = probing == null && key?.isSuccess == true,
                        onClick = {
                            draft = ""
                            typing = true
                        },
                    )
                }
            }
        }
        if (local != null) {
            item(key = "debian") {
                val known = hosts.mapTo(HashSet()) { it.target }
                val read = aliases
                val choices = read?.getOrNull()?.filter { it !in known && it != "localhost" }
                PreferenceGroup(title = "从手机 Debian 的 ~/.ssh/config 选", footer = DebianFooter + HostingFooter) {
                    when {
                        read == null -> item { BasicComponent(title = "正在读 ~/.ssh/config…", enabled = false) }
                        choices == null -> item {
                            BasicComponent(title = "读不到 ~/.ssh/config", summary = read.exceptionOrNull()?.message, enabled = false)
                        }
                        choices.isEmpty() -> item { BasicComponent(title = "~/.ssh/config 里没有别的主机", enabled = false) }
                        else -> choices.forEach { alias ->
                            item {
                                BasicComponent(
                                    title = alias,
                                    summary = if (probing == alias to RemoteHost.Via.Debian) "正在连接…" else null,
                                    enabled = probing == null,
                                    onClick = { add(alias, RemoteHost.Via.Debian) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    SlideInPage(editing, onBack = { editing = null }) { id ->
        RemoteHostScreen(hub, id, onBack = { editing = null })
    }
}

private fun probeSummary(probe: RemoteHosts.Probe.Ok): String =
    listOf("Claude Code ${probe.claude}", probe.system).filter { it.isNotBlank() }.joinToString(" · ")

/** 一台远程主机：名字、地址、连接方式、默认工作目录，重新检查连接（直连的主机指纹变了可以改认新的），删掉。 */
@Composable
private fun RemoteHostScreen(hub: ConversationHub, id: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val host = hub.settings.remoteHosts.firstOrNull { it.id == id }
    val shown = rememberRetained(host) ?: return
    var renaming by remember { mutableStateOf(false) }
    var cwdEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    // 直连时对方现在的主机公钥（和记下的不一样），等用户决定认不认
    var changedKey by remember { mutableStateOf<String?>(null) }
    val lastChangedKey = rememberRetained(changedKey)
    // 删掉以后退场动画期间 host 已经是 null：用最后一份画
    LaunchedEffect(host) { if (host == null) onBack() }

    fun save(change: (RemoteHost) -> RemoteHost) {
        hub.updateRemoteHosts(hub.settings.remoteHosts.map { if (it.id == id) change(it) else it })
    }

    val others = hub.settings.remoteHosts.filter { it.id != id }
    val cwd = AttachedPaths.normalize(draft, shown.home)
    CouiPage(
        title = shown.name,
        onBack = onBack,
        dialogs = {
            TextInputDialog(
                show = renaming,
                title = "名字",
                summary = RemoteHosts.nameProblem(draft, others).takeIf { draft.isNotBlank() },
                value = draft,
                onValueChange = { draft = it },
                label = shown.target,
                confirmText = "确定",
                confirmEnabled = RemoteHosts.nameProblem(draft, others) == null,
                onConfirm = {
                    renaming = false
                    save { it.copy(name = draft.trim()) }
                },
                onDismissRequest = { renaming = false },
            )
            TextInputDialog(
                show = cwdEditing,
                title = "默认工作目录",
                summary = "在这台主机上开新对话时的工作目录，~ 是 ${shown.home}",
                value = draft,
                onValueChange = { draft = it },
                label = shown.home,
                confirmText = "确定",
                confirmEnabled = cwd != null,
                onConfirm = {
                    cwdEditing = false
                    cwd?.let { path -> save { it.copy(defaultCwd = path) } }
                },
                onDismissRequest = { cwdEditing = false },
            )
            ConfirmDialog(
                show = changedKey != null,
                title = "主机指纹变了",
                summary = "记下的：${shown.fingerprint}\n现在的：${lastChangedKey?.let(RemoteHosts::fingerprint)}\n" +
                    "确认是那台主机换过密钥（比如重装过系统）再改认新的；不确定的话可能连到了别的机器上。",
                confirmText = "改认新的",
                destructive = true,
                onConfirm = {
                    val fresh = changedKey
                    changedKey = null
                    if (fresh != null) {
                        save { it.copy(hostKey = fresh) }
                        check = "已改认新的主机指纹，再检查一次连接"
                    }
                },
                onDismissRequest = { changedKey = null },
            )
            ConfirmDialog(
                show = removing,
                title = "删掉「${shown.name}」？",
                summary = "只是 Zeta 不再连它。那台主机上的会话记录、Claude Code 的配置都不动；它上面正开着的对话会断开。",
                confirmText = "删掉",
                destructive = true,
                onConfirm = {
                    removing = false
                    hub.updateRemoteHosts(hub.settings.remoteHosts.filter { it.id != id })
                },
                onDismissRequest = { removing = false },
            )
        },
    ) {
        item(key = "host") {
            PreferenceGroup {
                item {
                    ArrowPreference(
                        title = "名字",
                        endActions = { PreferenceValue(shown.name) },
                        onClick = {
                            draft = shown.name
                            renaming = true
                        },
                    )
                }
                item { BasicComponent(title = "地址", endActions = { PreferenceValue(shown.target) }) }
                item { BasicComponent(title = "连接方式", endActions = { PreferenceValue(shown.via.label) }) }
                shown.fingerprint?.let { fingerprint -> item { BasicComponent(title = "主机指纹", summary = fingerprint) } }
                item { BasicComponent(title = "家目录", endActions = { PreferenceValue(shown.home) }) }
                item {
                    ArrowPreference(
                        title = "默认工作目录",
                        summary = shown.defaultCwd,
                        onClick = {
                            draft = shown.defaultCwd
                            cwdEditing = true
                        },
                    )
                }
            }
        }
        item(key = "check") {
            PreferenceGroup(footer = check) {
                item {
                    BasicComponent(
                        title = if (checking) "正在连接…" else "检查连接",
                        enabled = !checking,
                        onClick = {
                            checking = true
                            check = null
                            scope.launch {
                                val probe = withContext(Dispatchers.IO) { hub.probeRemote(shown.target, shown.via, shown.hostKey) }
                                checking = false
                                check = when (probe) {
                                    is RemoteHosts.Probe.Failed -> "没连上：" + probe.reason
                                    is RemoteHosts.Probe.HostKeyChanged -> {
                                        changedKey = probe.hostKey
                                        "没连上：主机指纹和记下的不一样"
                                    }
                                    is RemoteHosts.Probe.Ok -> {
                                        // 家目录变了（换了用户）就跟着改；默认工作目录还在原来家目录下的一起换
                                        if (probe.home != shown.home) save {
                                            it.copy(home = probe.home, defaultCwd = if (it.defaultCwd == it.home) probe.home else it.defaultCwd)
                                        }
                                        "连上了：" + probeSummary(probe)
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        item(key = "remove") {
            PreferenceGroup { item { DangerRow("删掉这台主机") { removing = true } } }
        }
    }
}
