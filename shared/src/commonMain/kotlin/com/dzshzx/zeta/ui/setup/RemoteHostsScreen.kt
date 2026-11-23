package com.dzshzx.zeta.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.CwdProblem
import com.dzshzx.zeta.core.RemoteHost
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.NoticeDialog
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import com.dzshzx.zeta.ui.components.rememberRetained
import com.dzshzx.zeta.ui.settings.DangerRow
import com.dzshzx.zeta.ui.settings.DeviceNameDialog
import com.dzshzx.zeta.ui.settings.DeviceNameRow
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「加主机」下面的说明：两种连法各要什么、多花什么，默认用哪种。数是真机上量的（每条命令：tools/phone-tests/remote-latency.sh；ssh 进程）。 */
private const val ViaFooter =
    "两种连法：经手机 Debian，用那边 ~/.ssh 的密钥和配置（别名、跳板），不用往设备上另加公钥，" +
        "每条命令多约 50 毫秒、手机上多两个 ssh 进程；Zeta 直连，不要 root 和 Debian，开销更小，但要先把下面的公钥加进那台设备。" +
        "有 Debian 时默认经 Debian，加好以后在设备页里能改。"

private const val DirectOnlyFooter = "手机上没有可用的 Debian，只能 Zeta 直连：先把下面的公钥加进那台设备，再输入地址。"

/** 那台设备要什么、对话在哪跑。 */
private const val HostingFooter =
    "那台设备要是 arm64 或 x86-64 的 Linux，装好 Claude Code。对话由那边托管，手机断网或 Zeta 被关掉时照样跑完，连上后接着看。"

/** 手机上直连用的密钥：私钥在手机的密钥库里。 */
private const val KeyFooter =
    "Zeta 直连时用这把密钥登录：复制公钥那一行，加进那台设备的 ~/.ssh/authorized_keys。私钥在手机的密钥库里，取不出来。" +
        "加设备时记下对方的主机指纹，以后只认它。"

private const val BusyMessage = "这台设备上有对话在进行（在回答、等你确认或有后台任务），等它做完再改。"

/** 断开时这台设备上有对话在进行：它们在那边接着跑。 */
private const val DisconnectBusyMessage =
    "这台设备上有对话在进行（在回答、等你确认或有后台任务）。断开后它们在那边接着跑完，打开连接后接着看；等你确认的要连上后才能答。"

private val Vias = listOf(RemoteHost.Via.Debian, RemoteHost.Via.Direct)

/** 加一台主机时连接检查的结果，给对话框显示。 */
private class ProbeNotice(val title: String, val message: String)

/** 正在连的地址，和是从哪一行加的（那一行显示「正在连接」）。同一时间只连一个。 */
private class Probing(val target: String, val fromConfig: Boolean)

/**
 * 添加远程主机：上面的 Claude Code 也能在 Zeta 里用（侧栏顶部切换）。两种连法（[RemoteHost.via]）：经手机 Debian 的 OpenSSH， 或 Zeta
 * 用自己的密钥直连。加法：从手机 Debian 的 ~/.ssh/config 里选（经 Debian），或输入地址（两种连法都行， 默认
 * [ConversationHub.defaultVia]）。加之前先连一次，查家目录、claude、系统和 CPU。加好的主机列在设置首页，
 * 点进去是它的主机页（[RemoteHostScreen]）。
 */
@Composable
fun RemoteHostsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val hosts = hub.settings.remoteHosts
    val debian = hub.usable(Backends.LOCAL)
    // Zeta 的密钥：第一次打开时从 Keystore 取（没有就生成），取不到时是原因
    var key by remember { mutableStateOf<Result<SshKey>?>(null) }
    var probing by remember { mutableStateOf<Probing?>(null) }
    var notice by remember { mutableStateOf<ProbeNotice?>(null) }
    val lastNotice = rememberRetained(notice)
    var typing by remember { mutableStateOf(false) }
    var typedVia by remember { mutableStateOf(hub.defaultVia) }
    var showingKey by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var choosingAlias by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        key =
            withContext(Dispatchers.IO) {
                runCatching { hub.ssh.key }
            }
    }

    fun add(target: String, via: RemoteHost.Via, fromConfig: Boolean) {
        if (probing != null) return
        probing = Probing(target, fromConfig)
        val shownTarget = RemoteHosts.displayTarget(target)
        scope.launch {
            val probe = withContext(Dispatchers.IO) { hub.probeRemote(target, via) }
            probing = null
            notice =
                when (probe) {
                    is RemoteHosts.Probe.Failed -> ProbeNotice("没有加上「$shownTarget」", probe.reason)
                    is RemoteHosts.Probe.HostKeyChanged ->
                        ProbeNotice("没有加上「$shownTarget」", "主机指纹不对")
                    is RemoteHosts.Probe.Ok -> addProbedHost(hub, target, via, probe)
                }
        }
    }

    CouiPage(
        title = "添加远程设备",
        onBack = onBack,
        dialogs = {
            TypeAddressDialog(
                show = typing,
                debian = debian,
                keyReady = key?.isSuccess == true,
                via = typedVia,
                onViaChange = { typedVia = it },
                draft = draft,
                onDraftChange = { draft = it },
                idle = probing == null,
                onConfirm = { target ->
                    typing = false
                    target?.let { add(it, typedVia, fromConfig = false) }
                },
                onDismiss = { typing = false },
            )
            val authorized = key?.getOrNull()?.authorizedKey
            ConfirmDialog(
                show = showingKey,
                title = "Zeta 的公钥",
                summary = authorized,
                confirmText = "复制",
                onConfirm = {
                    showingKey = false
                    authorized?.let { clipboard.setText(AnnotatedString(it)) }
                },
                onDismissRequest = { showingKey = false },
            )
            NoticeDialog(
                show = notice != null,
                title = lastNotice?.title,
                summary = lastNotice?.message,
                onDismissRequest = { notice = null },
            )
        },
    ) {
        item(key = "add") {
            AddHostGroup(
                debian = debian,
                adding = probing,
                onChooseAlias = { choosingAlias = true },
                onType = {
                    draft = ""
                    typedVia = hub.defaultVia
                    typing = true
                },
            )
        }
        item(key = "key") { KeyGroup(key, onShow = { showingKey = true }) }
    }
    SlideInPage(choosingAlias, onBack = { choosingAlias = false }) {
        SshConfigHostsScreen(
            hub = hub,
            known = hosts.mapTo(HashSet()) { it.target },
            onPick = { alias ->
                choosingAlias = false
                add(alias, RemoteHost.Via.Debian, fromConfig = true)
            },
            onBack = { choosingAlias = false },
        )
    }
}

/** 连上了：记下这台主机；当前对话的主机用不了时（手机本机没配好），直接用上加的这台。返回给用户看的结果。 */
private fun addProbedHost(
    hub: ConversationHub,
    target: String,
    via: RemoteHost.Via,
    probe: RemoteHosts.Probe.Ok,
): ProbeNotice {
    val host =
        RemoteHost(
            Backends.newId(),
            null,
            target,
            probe.home,
            probe.home,
            via,
            probe.hostKey,
            probe.hostname,
        )
    hub.updateRemoteHosts(hub.settings.remoteHosts + host)
    if (!hub.usable(hub.current.backend)) hub.selectBackend(host.id)
    val fingerprint = host.fingerprint?.let { "\n主机指纹 $it" }.orEmpty()
    return ProbeNotice(
        "已加上「${host.name}」",
        probeSummary(probe) +
            " · " +
            via.label +
            fingerprint +
            "\n在侧栏顶部切到它，就能看它上面的会话、在它上面开新对话；它的默认模型等设置在设置首页的这台设备里。",
    )
}

/** 输入地址加设备：有 Debian 时能选连法；直连要 Zeta 的密钥读得到。 */
@Composable
private fun TypeAddressDialog(
    show: Boolean,
    debian: Boolean,
    keyReady: Boolean,
    via: RemoteHost.Via,
    onViaChange: (RemoteHost.Via) -> Unit,
    draft: String,
    onDraftChange: (String) -> Unit,
    idle: Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val target = RemoteHosts.targetFor(draft, via)
    val viaDebian = via == RemoteHost.Via.Debian
    ConfirmDialog(
        show = show,
        title = "输入地址",
        summary =
            when {
                viaDebian ->
                    "经手机 Debian 的 OpenSSH 连，用那边 ~/.ssh 的密钥。" + RemoteHosts.DEBIAN_TARGET_HINT
                keyReady -> "Zeta 用自己的密钥直连，先把它的公钥加进那台设备。" + RemoteHosts.DIRECT_TARGET_HINT
                else -> "读不到 Zeta 的密钥，直连用不了。"
            },
        confirmText = "连接并添加",
        confirmEnabled = target != null && idle && (viaDebian || keyReady),
        onConfirm = { onConfirm(target) },
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (debian) {
                SegmentButton(
                    items = Vias.map { it.label },
                    selectedIndex = Vias.indexOf(via),
                    onSelectedIndexChange = { index -> Vias.getOrNull(index)?.let(onViaChange) },
                    modifier = Modifier.fillMaxWidth(),
                    height = SegmentButtonDefaults.HeightSmall,
                    padding = SegmentButtonDefaults.PaddingSmall,
                )
            }
            DialogTextField(
                value = draft,
                onValueChange = onDraftChange,
                hint = if (viaDebian) "user@example.com 或别名" else "user@example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }
    }
}

/** 「加设备」一组：从手机 Debian 的 ~/.ssh/config 选（有 Debian 时）、输入地址；正在连的那一行写着在连谁。 */
@Composable
private fun AddHostGroup(
    debian: Boolean,
    adding: Probing?,
    onChooseAlias: () -> Unit,
    onType: () -> Unit,
) {
    PreferenceGroup(
        title = "加设备",
        footer = (if (debian) ViaFooter else DirectOnlyFooter) + HostingFooter,
    ) {
        if (debian) {
            item {
                ArrowPreference(
                    title = CONFIG_TITLE,
                    summary = connecting(adding, fromConfig = true) ?: RemoteHost.Via.Debian.label,
                    enabled = adding == null,
                    onClick = onChooseAlias,
                )
            }
        }
        item {
            ArrowPreference(
                title = "输入地址",
                summary = connecting(adding, fromConfig = false),
                enabled = adding == null,
                onClick = onType,
            )
        }
    }
}

/** 正在从那一行（[fromConfig]：从 ~/.ssh/config 选的，还是输入的）加的设备：「正在连接 …」；不是这一行时是 null。 */
private fun connecting(adding: Probing?, fromConfig: Boolean): String? =
    adding
        ?.takeIf { it.fromConfig == fromConfig }
        ?.let { "正在连接 ${RemoteHosts.displayTarget(it.target)}…" }

/** 「从 ~/.ssh/config 选」那一行和那一页的标题。 */
private const val CONFIG_TITLE = "从 ~/.ssh/config 选"

/** 「Zeta 直连用的密钥」一组：公钥的指纹，点开看整行、复制。 */
@Composable
private fun KeyGroup(key: Result<SshKey>?, onShow: () -> Unit) {
    PreferenceGroup(title = "Zeta 直连用的密钥", footer = KeyFooter) {
        item {
            BasicComponent(
                title = "Zeta 的公钥",
                summary =
                    when {
                        key == null -> "正在读…"
                        key.isFailure ->
                            "读不到 Zeta 的密钥：" + key.exceptionOrNull()?.message.orEmpty().take(120)
                        else -> SshKey.ALGORITHM + " · " + key.getOrNull()?.fingerprint
                    },
                enabled = key?.isSuccess == true,
                onClick = onShow,
            )
        }
    }
}

/** 手机 Debian 的 ~/.ssh/config 里能选的主机：读之前是 null，读不到时是原因。 */
@Composable
private fun rememberConfigHosts(hub: ConversationHub): Result<List<String>>? {
    val local = hub.shell(Backends.LOCAL)?.takeIf { hub.usable(Backends.LOCAL) }
    var hosts by remember { mutableStateOf<Result<List<String>>?>(null) }
    LaunchedEffect(local != null) {
        hosts =
            withContext(Dispatchers.IO) {
                if (local == null)
                    Result.failure(
                        IllegalStateException(hub.unavailable(Backends.LOCAL) ?: "手机上的 Debian 没就绪"),
                    )
                else runCatching { RemoteHosts.readAliases(local) }
            }
    }
    return hosts
}

/** 从手机 Debian 的 ~/.ssh/config 里选一台（经 Debian 的 OpenSSH 连）：Host 行里的具体主机，[known] 里已经加过的不列。 */
@Composable
private fun SshConfigHostsScreen(
    hub: ConversationHub,
    known: Set<String>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val read = rememberConfigHosts(hub)
    CouiPage(title = CONFIG_TITLE, onBack = onBack) {
        item(key = "aliases") {
            val choices = read?.getOrNull()?.filter { it !in known && it != "localhost" }
            PreferenceGroup(
                footer =
                    "手机 Debian 里 ~/.ssh/config 和 config.local 的 Host 行里的主机，带通配符的、已经加过的不列。" +
                        "选一台，经 Debian 的 OpenSSH 连一次，连上了才加。",
            ) {
                when {
                    read == null ->
                        item { BasicComponent(title = "正在读 ~/.ssh/config…", enabled = false) }
                    choices == null ->
                        item {
                            BasicComponent(
                                title = "读不到 ~/.ssh/config",
                                summary = read.exceptionOrNull()?.message,
                                enabled = false,
                            )
                        }
                    choices.isEmpty() ->
                        item { BasicComponent(title = "~/.ssh/config 里没有别的主机", enabled = false) }
                    else ->
                        choices.forEach { alias ->
                            item { ArrowPreference(title = alias, onClick = { onPick(alias) }) }
                        }
                }
            }
        }
    }
}

private fun probeSummary(probe: RemoteHosts.Probe.Ok): String =
    listOf("Claude Code ${probe.claude}", probe.system)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

/**
 * 设置 › 一台远程主机：上面是 [claude]（这台上新对话的默认值、Claude Code 自己的配置，见
 * [com.dzshzx.zeta.ui.settings.HostSettingsScreen]）， 下面是连接：「连接」开关（关掉时 Zeta
 * 不连它，[ConversationHub.setConnected]），自定义设备名、地址、 连接方式（能改：先按新的连法连一次，连上了才改）、默认工作目录，
 * 重新检查连接（直连的主机指纹变了可以改认新的），删掉。
 */
@Composable
fun RemoteHostScreen(
    hub: ConversationHub,
    id: String,
    onBack: () -> Unit,
    claude: LazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val host = hub.settings.remoteHosts.firstOrNull { it.id == id }
    val shown = rememberRetained(host) ?: return
    var renaming by remember { mutableStateOf(false) }
    var cwdEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var check by remember { mutableStateOf<String?>(null) }
    // 正在试着改成的连法
    var switching by remember { mutableStateOf<RemoteHost.Via?>(null) }
    var notice by remember { mutableStateOf<ProbeNotice?>(null) }
    val lastNotice = rememberRetained(notice)
    // 直连时对方现在的主机公钥（和记下的不一样），等用户决定认不认
    var changedKey by remember { mutableStateOf<String?>(null) }
    val lastChangedKey = rememberRetained(changedKey)
    // 删掉以后退场动画期间 host 已经是 null：用最后一份画
    LaunchedEffect(host) { if (host == null) onBack() }

    fun save(change: (RemoteHost) -> RemoteHost) {
        hub.updateRemoteHosts(hub.settings.remoteHosts.replacing(id, change))
    }

    fun switchTo(via: RemoteHost.Via) {
        val target = RemoteHosts.targetFor(shown.target, via)
        val refused = switchRefusal(hub, id, shown.target, target, via)
        if (refused != null || target == null) {
            notice = ProbeNotice("没有改成${via.label}", refused.orEmpty())
            return
        }
        switching = via
        scope.launch {
            val probe = withContext(Dispatchers.IO) { hub.probeRemote(target, via) }
            switching = null
            notice = switchNotice(hub, id, via, target, probe)
        }
    }

    fun checkConnection() {
        checking = true
        check = null
        scope.launch {
            val probe =
                withContext(Dispatchers.IO) {
                    hub.probeRemote(shown.target, shown.via, shown.hostKey)
                }
            checking = false
            check =
                checkMessage(
                    probe,
                    shown,
                    onKeyChanged = { changedKey = it },
                    onRefresh = { ok -> save { it.refreshedBy(ok) } },
                )
        }
    }

    fun trustChangedKey() {
        val fresh = changedKey
        changedKey = null
        if (fresh != null) {
            save { it.copy(hostKey = fresh) }
            check = "已改认新的主机指纹，再检查一次连接"
        }
    }

    val cwd = AttachedPaths.normalize(draft, shown.home)
    CouiPage(
        title = shown.name,
        onBack = onBack,
        dialogs = {
            DeviceNameDialog(hub, id, show = renaming, onDismiss = { renaming = false })
            TextInputDialog(
                show = cwdEditing,
                title = "默认工作目录",
                summary = "在这台设备上开新对话时的工作目录，~ 是 ${shown.home}",
                value = draft,
                onValueChange = { draft = it },
                label = shown.home,
                confirmText = "确定",
                confirmEnabled = cwd != null,
                onConfirm = {
                    // 先看那台上进不进得去：进不去就留着对话框让用户改，不存一个开不了对话的目录
                    cwd?.let { path ->
                        scope.launch {
                            val problem =
                                withContext(Dispatchers.IO) { CwdProblem.check(hub, id, path) }
                            if (problem == null) {
                                cwdEditing = false
                                save { it.copy(defaultCwd = path) }
                            } else hub.platform.toast(problem)
                        }
                    }
                },
                onDismissRequest = { cwdEditing = false },
            )
            ConfirmDialog(
                show = changedKey != null,
                title = "主机指纹变了",
                summary =
                    "记下的：${shown.fingerprint}\n现在的：${lastChangedKey?.let(RemoteHosts::fingerprint)}\n" +
                        "确认是那台设备换过密钥（比如重装过系统）再改认新的；不确定的话可能连到了别的机器上。",
                confirmText = "改认新的",
                destructive = true,
                onConfirm = ::trustChangedKey,
                onDismissRequest = { changedKey = null },
            )
            DisconnectDialog(hub, shown, show = disconnecting) { disconnecting = false }
            ConfirmDialog(
                show = removing,
                title = "删掉「${shown.name}」？",
                summary = "只是 Zeta 不再连它。那台设备上的会话记录、Claude Code 的配置都不动；它上面正开着的对话会断开。",
                confirmText = "删掉",
                destructive = true,
                onConfirm = {
                    removing = false
                    hub.updateRemoteHosts(hub.settings.remoteHosts.filter { it.id != id })
                },
                onDismissRequest = { removing = false },
            )
            NoticeDialog(
                show = notice != null,
                title = lastNotice?.title,
                summary = lastNotice?.message,
                onDismissRequest = { notice = null },
            )
        },
    ) {
        claude()
        item(key = "host") {
            ConnectionGroup(
                hub = hub,
                host = shown,
                switching = switching,
                checking = checking,
                onConnect = { on -> connectOrAsk(hub, id, on) { disconnecting = true } },
                onRename = { renaming = true },
                onSwitch = ::switchTo,
                onEditCwd = {
                    draft = shown.defaultCwd
                    cwdEditing = true
                },
            )
        }
        item(key = "check") {
            CheckGroup(
                check = check,
                checking = checking,
                switching = switching != null,
                onCheck = ::checkConnection,
            )
        }
        item(key = "remove") {
            PreferenceGroup { item { DangerRow("删掉这台设备") { removing = true } } }
        }
    }
}

/** 「连接」开关拨了：打开、或那台上没有对话在进行时直接照做；有对话在进行时先 [ask] 确认再断开。 */
private fun connectOrAsk(hub: ConversationHub, id: String, on: Boolean, ask: () -> Unit) {
    if (!on && hub.hostWorking(id)) ask() else hub.setConnected(id, on)
}

/** 断开有对话在进行的主机前的确认：它们在那边接着跑。 */
@Composable
private fun DisconnectDialog(
    hub: ConversationHub,
    host: RemoteHost,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        show = show,
        title = "断开「${host.name}」？",
        summary = DisconnectBusyMessage,
        confirmText = "断开",
        onConfirm = {
            onDismiss()
            hub.setConnected(host.id, false)
        },
        onDismissRequest = onDismiss,
    )
}

/** 改连法前先看能不能改：别名不能直连，Debian 要就绪，有对话在进行时不改。能改时为 null。 */
private fun switchRefusal(
    hub: ConversationHub,
    id: String,
    current: String,
    target: String?,
    via: RemoteHost.Via,
): String? =
    when {
        target == null ->
            "「$current」是手机 Debian 的 ~/.ssh/config 里的别名，Zeta 直连不读那份配置。" +
                "要直连，回上一页用「输入地址」另加一台，地址写成 用户@主机[:端口]。"
        via == RemoteHost.Via.Debian && !hub.usable(Backends.LOCAL) ->
            hub.unavailable(Backends.LOCAL) ?: "手机上的 Debian 没就绪"
        hub.hostWorking(id) -> BusyMessage
        else -> null
    }

/** 按新连法连过以后：连上了就改（这时又有对话在进行则不改），给用户看结果。 */
private fun switchNotice(
    hub: ConversationHub,
    id: String,
    via: RemoteHost.Via,
    target: String,
    probe: RemoteHosts.Probe,
): ProbeNotice =
    when (probe) {
        is RemoteHosts.Probe.Ok ->
            if (hub.changeVia(id, via, target, probe.hostKey)) {
                ProbeNotice(
                    "已改成${via.label}",
                    probeSummary(probe) +
                        probe.hostKey?.let { "\n主机指纹 " + RemoteHosts.fingerprint(it) }.orEmpty() +
                        "\n这台设备上闲着的对话，下次发消息时经新的连法接上。",
                )
            } else {
                ProbeNotice("没有改成${via.label}", BusyMessage)
            }
        is RemoteHosts.Probe.Failed -> ProbeNotice("没有改成${via.label}", "按新的连法没连上：" + probe.reason)
        is RemoteHosts.Probe.HostKeyChanged -> ProbeNotice("没有改成${via.label}", "主机指纹不对")
    }

private fun List<RemoteHost>.replacing(id: String, change: (RemoteHost) -> RemoteHost) = map {
    if (it.id == id) change(it) else it
}

/** 检查连接的结果，显示在那一组下面。指纹变了交给 [onKeyChanged] 等用户决定；连上了而家目录或主机名变了交给 [onRefresh] 跟着改。 */
private fun checkMessage(
    probe: RemoteHosts.Probe,
    host: RemoteHost,
    onKeyChanged: (String) -> Unit,
    onRefresh: (RemoteHosts.Probe.Ok) -> Unit,
): String =
    when (probe) {
        is RemoteHosts.Probe.Failed -> "没连上：" + probe.reason
        is RemoteHosts.Probe.HostKeyChanged -> {
            onKeyChanged(probe.hostKey)
            "没连上：主机指纹和记下的不一样"
        }
        is RemoteHosts.Probe.Ok -> {
            // 家目录变了（换了用户）就跟着改，默认工作目录还在原来家目录下的一起换；主机名变了（改过名、重装过）也跟着改
            if (probe.home != host.home || probe.hostname != host.hostname) onRefresh(probe)
            "连上了：" + probeSummary(probe)
        }
    }

/** 「检查连接」一组；上次检查的结果写在下面。 */
@Composable
private fun CheckGroup(check: String?, checking: Boolean, switching: Boolean, onCheck: () -> Unit) {
    PreferenceGroup(footer = check) {
        item {
            BasicComponent(
                title = if (checking) "正在连接…" else "检查连接",
                enabled = !checking && !switching,
                onClick = onCheck,
            )
        }
    }
}

/** 检查连接时发现家目录或主机名变了：跟着改，默认工作目录原来是家目录的一起换。 */
private fun RemoteHost.refreshedBy(probe: RemoteHosts.Probe.Ok): RemoteHost =
    copy(
        home = probe.home,
        defaultCwd = if (defaultCwd == home) probe.home else defaultCwd,
        hostname = probe.hostname ?: hostname,
    )

/** 「连接方式」下拉：经手机 Debian 或 Zeta 直连；[switching] 是正在试着改成的。 */
@Composable
private fun ViaChoice(
    host: RemoteHost,
    switching: RemoteHost.Via?,
    enabled: Boolean,
    onSwitch: (RemoteHost.Via) -> Unit,
) {
    OverlayDropdownPreference(
        title = "连接方式",
        summary = switching?.let { "正在${it.label}连接…" },
        items = Vias.map { it.label },
        selectedIndex = Vias.indexOf(host.via),
        enabled = enabled,
        onSelectedIndexChange = { index ->
            Vias.getOrNull(index)?.takeIf { it != host.via }?.let(onSwitch)
        },
    )
}

/** 主机页的「连接」一组：「连接」开关、自定义设备名、地址、连接方式、主机指纹、家目录、默认工作目录。 */
@Composable
private fun ConnectionGroup(
    hub: ConversationHub,
    host: RemoteHost,
    switching: RemoteHost.Via?,
    checking: Boolean,
    onConnect: (Boolean) -> Unit,
    onRename: () -> Unit,
    onSwitch: (RemoteHost.Via) -> Unit,
    onEditCwd: () -> Unit,
) {
    PreferenceGroup(title = "连接") {
        item {
            SwitchPreference(
                title = "连接",
                summary = if (host.connected) "用到这台设备时连上，断了自动重连" else "已断开：" + RemoteHosts.OFF_NOTE,
                checked = host.connected,
                onCheckedChange = onConnect,
            )
        }
        item { DeviceNameRow(hub, host.id) { onRename() } }
        item {
            BasicComponent(
                title = "地址",
                endActions = { PreferenceValue(RemoteHosts.displayTarget(host.target)) },
            )
        }
        item { ViaChoice(host, switching, enabled = switching == null && !checking, onSwitch) }
        host.fingerprint?.let { fingerprint ->
            item { BasicComponent(title = "主机指纹", summary = fingerprint) }
        }
        item { BasicComponent(title = "家目录", endActions = { PreferenceValue(host.home) }) }
        item {
            ArrowPreference(
                title = "默认工作目录",
                summary = host.defaultCwd,
                onClick = onEditCwd,
            )
        }
    }
}
