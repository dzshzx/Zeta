package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.claude.defaults
import com.dzshzx.zeta.core.conv.SessionActivity
import com.dzshzx.zeta.core.ssh.SshLinks
import kotlin.concurrent.thread
import kotlinx.serialization.json.JsonObject

/**
 * 应用级的单例：当前对话和切到后台还在跑的对话（打开、切换、分支、收掉，跟上核心里在跑的），界面前后台，各台主机，以及挂在它上面的几块： 设置 [settings]、会话列表
 * [sessionList]、额度 [quota]、账号 [accounts]。手机和桌面共用这一份；只属于一边的（手机本机的 Linux 环境、 各会话的手机工具、胶囊与提醒、前台服务）在平台
 * [platform] 里，见 [HubPlatform]。
 *
 * hub 是对话事件的入口：Conversation 的事件（一轮就要结束、结束了、等确认、后台任务做完、活动变化）都调 hub 上的 on… 方法， 由 hub
 * 分给上面这几块，Conversation 不直接找它们。只转给一处的（[onTurnEnding]）也照样经 hub 转。
 */
class ConversationHub(val platform: HubPlatform) {
    init {
        platform.attach(this)
    }

    /** Zeta 直连远程主机的 SSH 连接（[RemoteHost.Via.Direct]），和它用的密钥。 */
    val ssh = SshLinks(platform.sshAuth)

    /** 各台主机上对话用的 serve 连接（每台一条），对话经它和主机上的核心说话。 */
    val convLinks = ConvLinks(this)

    val settings: Settings
        get() = platform.settings

    /** Linux 一侧的程序 zeta-helper：APK 里带着，第一次用时装进 Linux（[HelperProgram]）。 */
    val helperProgram =
        HelperProgram(
            platform::asset,
            AppVariant.current.linuxName,
        )

    /**
     * 侧栏正看着的主机（[Backends]）：新对话默认开在它上面，会话列表列它的（[allHosts] 时列全部主机的）。设置里存的那台已经删掉时是手机本机。 可观察。换主机用
     * [selectBackend]。
     */
    val backend: String
        get() =
            settings.backend.takeIf { it in backends } ?: backends.firstOrNull() ?: Backends.LOCAL

    /** 侧栏的会话列表列全部主机的（设备胶囊里选了「全部设备」）；没有远程主机时不算。可观察。用 [selectAllHosts] 开，[selectBackend] 关。 */
    val allHosts: Boolean
        get() = settings.paneAll && settings.remoteHosts.isNotEmpty()

    /** 侧栏的会话列表列哪几台主机的。 */
    val listedBackends: List<String>
        get() = if (allHosts) backends else listOf(backend)

    /** 全部主机：手机本机在前（有这一台时，见 [HubPlatform.local]），然后是各台远程主机。 */
    val backends: List<String>
        get() =
            (if (platform.local != null) listOf(Backends.LOCAL) else emptyList()) +
                settings.remoteHosts.map { it.id }

    /** 远程主机 [id]；手机本机、已经删掉的是 null。 */
    fun remoteHost(id: String): RemoteHost? = settings.remoteHosts.firstOrNull { it.id == id }

    /** 主机 [backend] 要靠手机的 Debian：手机本机，和经 Debian 连的远程主机。 */
    private fun needsDebian(backend: String): Boolean =
        backend == Backends.LOCAL || remoteHost(backend)?.via == RemoteHost.Via.Debian

    /**
     * 主机 [backend] 现在能用：要靠手机 Debian 的，等环境检查完、就绪；Zeta 直连的远程主机随时能用。删掉的主机不能用。 界面据此决定显示对话还是「去配置」，可观察。不建
     * [shell]，重组时调用也便宜。
     */
    fun usable(backend: String): Boolean {
        if (backend !in backends) return false
        return !needsDebian(backend) || (settings.env != null && platform.local?.ready == true)
    }

    /**
     * 在主机 [backend] 上跑命令的入口；没法跑时是 null，原因见 [unavailable]：手机的 Debian 还没配置（手机本机、 经 Debian
     * 连的远程主机），远程主机已经删掉。Zeta 直连的远程主机不靠 Debian。环境还在检查时也给（定时任务等不到界面起来）， 界面上判断能不能用看 [usable]。
     */
    fun shell(backend: String): LinuxShell? {
        if (backend == Backends.LOCAL) return settings.env?.let(::EnvShell)
        val host = remoteHost(backend) ?: return null
        return when (host.via) {
            RemoteHost.Via.Direct -> DirectSshShell(ssh, host)
            RemoteHost.Via.Debian -> settings.env?.let { DebianSshShell(EnvShell(it), host) }
        }
    }

    /** 主机 [backend] 为什么用不了（[usable] 是 false 的原因）；用得了是 null。 */
    fun unavailable(backend: String): String? {
        if (usable(backend)) return null
        val host = remoteHost(backend)
        return when {
            backend == Backends.LOCAL && platform.local == null -> "还没有添加远程设备，请到设置里添加"
            backend == Backends.LOCAL -> "还没有配置 Linux 环境，请到设置里检查"
            host == null -> "远程设备已经从设置里删掉了"
            else -> "「${host.name}」经手机的 Debian 连，Debian 环境还没就绪"
        }
    }

    /** 加远程主机时默认的连法：手机 Debian 就绪就经它的 OpenSSH（用那边 ~/.ssh 现成的密钥和配置，不用往主机上另加公钥）， 否则 Zeta 直连。可观察。 */
    val defaultVia: RemoteHost.Via
        get() = if (usable(Backends.LOCAL)) RemoteHost.Via.Debian else RemoteHost.Via.Direct

    /** 远程主机 [id] 上有对话在进行：在回答、等你确认，或还有后台任务在跑。这时不改它的连法（[changeVia]）。 */
    fun hostWorking(id: String): Boolean = liveConversations.any { it.backend == id && it.active }

    /**
     * 把远程主机 [id] 改成连法 [via]：地址 [target] 已经按这种连法写好（[RemoteHosts.targetFor]）、检查过能连上， 直连的 [hostKey]
     * 是检查时记下的主机公钥。对话进程是按原来的连法接着的，所以那台上有对话在进行（[hostWorking]）时不改、 返回 false；闲着的收掉进程（内容不丢，下次发消息时用
     * --resume 经新的连法接上）。在主线程上调用。
     */
    fun changeVia(id: String, via: RemoteHost.Via, target: String, hostKey: String?): Boolean {
        val host = remoteHost(id) ?: return false
        if (hostWorking(id)) return false
        val idle = liveConversations.filter { it.backend == id }
        idle.forEach { it.leave() }
        parked.removeAll(idle.toSet())
        platform.prunePhones()
        // 对话的连接是按原来的连法连的，下次按新的连
        convLinks.reset(id)
        updateRemoteHosts(
            settings.remoteHosts.map {
                if (it.id == id)
                    host.copy(
                        via = via,
                        target = target,
                        hostKey = hostKey.takeIf { via == RemoteHost.Via.Direct },
                        key = host.key.takeIf { via == RemoteHost.Via.Direct },
                    )
                else it
            },
        )
        return true
    }

    /** 主机 [backend] 上的命令以 [exitCode] 结束、标准错误是 [stderr]：是远程主机连不上的话给界面一句话，别的情况是 null。 */
    fun linkProblem(backend: String, exitCode: Int, stderr: String): String? =
        if (backend != Backends.LOCAL && exitCode == RemoteShell.CONNECTION_FAILED)
            "连不上「${backendName(backend)}」：" + RemoteHosts.sshProblem(stderr)
        else null

    /**
     * 连一次远程主机 [target] 看看（连法 [via]）：家目录、claude、CPU 架构。直连的只认 [expectedHostKey]，加主机时是 null（记下对方的）；
     * 直连用私钥 [key]（[RemoteHost.key]）。 阻塞，别在主线程上调用。
     */
    fun probeRemote(
        target: String,
        via: RemoteHost.Via,
        expectedHostKey: String? = null,
        key: String? = null,
    ): RemoteHosts.Probe =
        when (via) {
            RemoteHost.Via.Direct -> RemoteHosts.probeDirect(ssh, target, expectedHostKey, key)
            RemoteHost.Via.Debian ->
                shell(Backends.LOCAL)?.let { RemoteHosts.probeDebian(it, target) }
                    ?: RemoteHosts.Probe.Failed("手机里的 Linux 环境还没就绪")
        }

    /** 主机 [backend] 上那个用户的家目录；还不知道时为 null。 */
    fun home(backend: String): String? =
        if (backend == Backends.LOCAL) settings.env?.home else remoteHost(backend)?.home

    /** 界面上设备 [backend] 的名字：用户起的，没起时是它自己的名字（[originalName]）。 */
    fun backendName(backend: String): String =
        if (backend == Backends.LOCAL) settings.localName ?: platform.deviceName
        else remoteHost(backend)?.name ?: "已删除的设备"

    /** 设备 [backend] 自己的名字（用户没起名字时显示它）：手机是系统里的设备名，远程设备是它报的主机名，还没记下时是地址。 */
    fun originalName(backend: String): String {
        val host = remoteHost(backend) ?: return platform.deviceName
        return host.hostname ?: RemoteHosts.displayTarget(host.target)
    }

    /** 用户给设备 [backend] 起的名字；没起是 null。 */
    fun customName(backend: String): String? =
        if (backend == Backends.LOCAL) settings.localName else remoteHost(backend)?.customName

    /** 给设备 [backend] 起名字；[name] 为空是不起，显示它自己的名字（[originalName]）。在主线程上调用。 */
    fun rename(backend: String, name: String?) {
        val clean = name?.trim()?.ifEmpty { null }
        if (backend == Backends.LOCAL) settings.localName = clean
        else
            updateRemoteHosts(
                settings.remoteHosts.map {
                    if (it.id == backend) it.copy(customName = clean) else it
                },
            )
    }

    /** 主机 [backend] 上新对话的默认工作目录。 */
    fun defaultCwd(backend: String): String =
        if (backend == Backends.LOCAL) settings.env?.defaultCwd ?: "/workspace"
        else remoteHost(backend)?.defaultCwd ?: "/"

    /**
     * 在主机 [backend] 上跑 zeta-helper 的子命令 [subcommand]，参数逐个加引号；那台还没装就先装上（[HelperProgram]）。
     * 跑成了才有输出（[HelperResult]）；没有 [shell] 时原因是 [unavailable]，远程主机连不上时是 [linkProblem]。阻塞，别在主线程上调用。
     */
    fun helper(
        subcommand: String,
        arguments: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
        backend: String,
    ): HelperResult {
        val shell =
            shell(backend) ?: return HelperResult.Failed(unavailable(backend) ?: "还没有可用的 Linux 环境")
        val result = runCatching {
            helperProgram.run(
                { command, stdin -> shell.run(command, stdin, timeoutMs, plain = true) },
                subcommand,
                arguments,
            )
        }
            .getOrNull()
        if (result == null && backend != Backends.LOCAL)
            return HelperResult.Failed("连不上「${backendName(backend)}」")
        return result
            ?.let { linkProblem(backend, it.exitCode, it.stderr) }
            ?.let(HelperResult::Failed) ?: HelperResult.of(result)
    }

    /** 确认 zeta-helper 已装进 [shell] 那台（没装就装上）；要自己起它的进程（边读边出结果）之前用。装好了是 true。阻塞。 */
    fun ensureHelper(shell: LinuxShell): Boolean = runCatching {
        helperProgram
            .ensure { command, stdin -> shell.run(command, stdin, 60_000, plain = true) }
            ?.exitCode == 0
    }
        .getOrDefault(false)

    /**
     * 各台主机上新对话的默认值（主机 → 读到的）：那台 Claude Code 自己的设置（~/.claude/settings.json，见 [ClaudeDefaults]）， Zeta
     * 不另存一份，所以每台主机各是各的。新对话起进程时不带模型、思考强度和权限模式，由那台的 Claude Code 照它定；
     * 对话里选的只管那个对话。设置里各主机的页面读写它；还没读到的主机没有这一项。
     */
    private val defaultsByBackend = mutableStateMapOf<String, ClaudeDefaults>()

    /** 主机 [backend] 上新对话的默认值；还没读到时为 null。 */
    fun claudeDefaults(backend: String): ClaudeDefaults? = defaultsByBackend[backend]

    /**
     * 主机 [backend] 上 Claude Code 的默认权限模式（settings.json 没设时是逐项确认）；还没读到时为 null。
     * 核心还没推来对话的状态（新对话还没打开）、或核心读不到默认值时，输入栏显示它（[Conversation.shownPermissionMode]）。
     */
    fun defaultPermissionMode(backend: String): String? =
        claudeDefaults(backend)?.let { it.permissionMode ?: "default" }

    /** 正在读默认值的主机：同一台同时只读一次（设置首页和主机页可能前后脚要读）。只在主线程上碰。 */
    private val readingDefaults = HashSet<String>()

    /** 读到或改了主机 [backend] 上 Claude Code 的默认值。在主线程上调用。 */
    fun updateClaudeDefaults(backend: String, defaults: ClaudeDefaults) {
        defaultsByBackend[backend] = defaults
    }

    /**
     * 重读主机 [backend] 上 Claude Code 的默认值：侧栏正看着的那台在环境就绪时、换过去时读（输入栏要显示它的默认权限模式），
     * 设置首页上还没读过的主机读一次，打开一台主机的页面时重读那台（默认值可能在终端里改过）。那台正在读就不再读；读不到就照旧。在主线程上调用。
     */
    fun refreshClaudeDefaults(backend: String) {
        if (backend in readingDefaults || shell(backend) == null) return
        readingDefaults.add(backend)
        thread(name = "zeta-defaults", isDaemon = true) {
            val defaults = runCatching {
                ClaudeConfigClient.of(this, backend).defaults()
            }
                .getOrNull()
            main.post { onDefaultsRead(backend, defaults) }
        }
    }

    /** [refreshClaudeDefaults] 读完（在主线程上）：读到的默认值记下。 */
    private fun onDefaultsRead(backend: String, defaults: ClaudeDefaults?) {
        readingDefaults.remove(backend)
        if (defaults != null && backend in backends) updateClaudeDefaults(backend, defaults)
    }

    /**
     * 连上设备 [backend] 上的 zeta-helper serve 时，招呼 zeta_hello 里报了主机名 [hostname]（docs/core.md「协议」）。
     * 远程设备记下它，用户没起名字时界面上就叫它（[originalName]）；空的（那边查不到）不记。在主线程上调用。
     */
    fun hostnameReported(backend: String, hostname: String) {
        val name = hostname.trim().ifEmpty { null } ?: return
        if (remoteHost(backend)?.let { it.hostname != name } != true) return
        settings.remoteHosts =
            settings.remoteHosts.map { if (it.id == backend) it.copy(hostname = name) else it }
    }

    /** 给连主机 [backend] 的 [HelperServe] 的 onHello：招呼里的主机名交给 [hostnameReported]（在连接自己的线程上收到，投回主线程）。 */
    fun helloFrom(backend: String): (JsonObject) -> Unit = { hello ->
        hello.str("hostname")?.let { name -> main.post { hostnameReported(backend, name) } }
    }

    /**
     * 当前对话还没开始（没有内容、没有会话）时，按现在的默认工作目录重建它：环境就绪时， 设置从备份导入后（[Settings.restore]
     * 已经让各项按导入的值重读），换了默认工作目录时。在主线程上调用。
     */
    fun renewBlank() {
        if (current.items.isEmpty() && current.sessionId == null) current = blank()
    }

    /** 从外面送进来（分享、打开、截屏）、还等用户选放进哪个对话的东西，见 [offerIntake]。 */
    var pendingIntake by mutableStateOf<Intake?>(null)

    /** 已经定了放进当前对话、等它的输入栏取走的东西。 */
    var incoming by mutableStateOf<Intake?>(null)

    /**
     * 各主机（[Backends]）上 Claude Code 报的模型选项（initialize 的应答）。只放在内存里：别名对应的完整 ID 随 Claude Code 升级而变，
     * 按模型存的默认思考强度要以正开着的进程报的为准。还没起过进程的主机没有，见 [modelOptions]；删主机时一起删。
     */
    var models by mutableStateOf<Map<String, List<ModelOption>>>(emptyMap())

    /** 主机 [backend] 上选择器里的模型：那台的 Claude Code 报的，还没拿到时是几个常用别名（[FallbackModels]）。 */
    fun modelOptions(backend: String): List<ModelOption> =
        models[backend].orEmpty().ifEmpty { FallbackModels }

    /** 各对话没发出的输入草稿，按会话号保管，Zeta 重开后还在。 */
    val drafts =
        ComposerDrafts(
            platform.prefs(ComposerDrafts.PREFS),
            java.io.File(platform.filesDir, "drafts"),
            settings,
            ::shell,
        )

    /** 界面上的当前对话（可观察）。换了就告诉 [alerts]：看着的那个的提醒不必留着。 */
    var current: Conversation
        get() = currentState
        private set(value) {
            currentState = value
            platform.alerts.watchChanged()
        }

    private var currentState by mutableStateOf(blank())

    /** 侧栏的会话列表。 */
    val sessionList = SessionList(this)

    private val quotas = HashMap<String, QuotaService>()
    private val accountServices = HashMap<String, AccountService>()

    /** 主机 [backend] 上登录着的账号的订阅额度（那台上的对话共用）：各台登录的可能是不同的账号，各记各的。在主线程上调用。 */
    fun quota(backend: String): QuotaService =
        quotas.getOrPut(backend) { QuotaService(this, backend) }

    /** 主机 [backend] 上 claude-switch 管着的 Claude 账号，和切换账号。在主线程上调用。 */
    fun accounts(backend: String): AccountService =
        accountServices.getOrPut(backend) { AccountService(this, backend) }

    private var shown by mutableStateOf(false)

    /**
     * 界面是否正在显示（可观察：胶囊据此避开正显示着同一对话的聊天页）。 按实例记：界面重建时新旧实例的 onStart/onStop 会交错，单个布尔值会被覆盖错。
     * 切到后台时开始等当前对话闲下来再收掉进程，回到前台就不等了（[retireCurrentWhenIdle]）；会话列表和额度的订阅在后台断开、回来再接上。
     */
    var foreground: Boolean
        get() = shown
        set(value) {
            shown = value
            if (value) sessionList.resume() else sessionList.pause()
            quotas.values.forEach { if (value) it.resume() else it.pause() }
            retireCurrentWhenIdle()
            platform.alerts.watchChanged()
        }

    /** 用户正看着这个对话（Zeta 在前台、显示的就是它）：完成、确认都不用另外提醒。 */
    fun isWatching(conversation: Conversation): Boolean = foreground && conversation === current

    /** 已经在界面起来时读过 Zeta 直连的主机（[onUiStarted]）。 */
    private var directStarted = false

    /**
     * 界面（主界面、数字助理）起来了：手机的 Linux 环境还没就绪就去检查，就绪后要靠 Debian 的主机由 [onEnvironmentReady] 接着做。 Zeta
     * 直连的主机不用等 Debian：第一次时侧栏正看着其中一台的话就读会话列表和默认值。 和手机本机一样等界面起来才做，系统为无障碍服务、定时任务拉起应用时不去连远程主机。在主线程上调用。
     */
    fun onUiStarted() {
        platform.local?.takeIf { !it.ready }?.checkSetup()
        if (directStarted) return
        directStarted = true
        if (needsDebian(backend)) return
        renewBlank()
        sessionList.refresh()
        refreshClaudeDefaults(backend)
    }

    /**
     * 切走的对话（parked）：不在前台显示、进程还留着的对话，按切走的先后排，最后一个是最近的（[IntakeRouting.pick] 要它）。
     * 切走时还没做完的保留到这一轮结束。可观察，只在主线程上改。按 [Conversation.openKey] 找（[parkedFor]），
     * 会话号后来才到也找得到，从列表打开这个会话时不会另建一个对话对象、和它抢同一份手机工具。
     */
    private val parked = mutableStateListOf<Conversation>()

    /** 切走的对话里会话号（新对话是 key）为 [key] 的那个。 */
    private fun parkedFor(key: String): Conversation? = parked.firstOrNull { it.openKey == key }

    /** Zeta 手上的全部对话：当前对话和切到后台还在跑的，当前的在前（可观察）。后台任务面板、胶囊、通知都按它挑。 */
    val liveConversations: List<Conversation>
        get() = listOf(current) + parkedConversations

    /** 切到后台还在跑的对话（定时任务起的也在这里），不含当前对话，按切走的先后排（可观察）。 */
    val parkedConversations: List<Conversation>
        get() = parked.filter { it !== current }

    private val main = MainHandler()

    private fun blank(backend: String = this.backend, cwd: String = defaultCwd(backend)) =
        Conversation(this, cwd, sessionId = null, title = "", historyFile = null, backend = backend)

    /** 不在眼前时答完了一轮、用户还没点开看的会话；只记在内存里，应用进程结束就没了。 */
    private val unread = mutableStateListOf<String>()

    /** 会话列表里这个会话的状态；只是历史记录的没有。忙闲看主机上的核心报的（[SessionList.activityOf]，别的客户端开的对话也在里面）。 */
    fun sessionStatus(sessionId: String): SessionStatus? {
        val core = sessionList.activityOf(sessionId)
        return SessionStatus.of(
            waiting = core?.activity == SessionStatus.Activity.Waiting,
            busy = core?.activity == SessionStatus.Activity.Answering,
            tasks = core?.tasks ?: 0,
            unread = sessionId in unread,
            alive = core?.activity != null,
        )
    }

    /** 这个会话的对话还在进行：在跑、等确认，或者后台任务没做完（主机上的核心报的）。批量删除跳过这些。 */
    fun isActive(sessionId: String): Boolean =
        sessionList.activityOf(sessionId)?.activity.let {
            it != null && it != SessionStatus.Activity.Idle
        }

    /** 主机上的核心说这几个会话各结束了一轮（可能是别的客户端开的）：不在眼前的记成有新回答。在主线程上调用。 */
    fun onTurnsEnded(sessions: List<String>) {
        for (session in sessions) if (session != current.sessionId && session !in unread)
            unread += session
    }

    /**
     * 环境检查完、就绪了（[EnvController.checkSetup]）：侧栏正看着要靠 Debian 的主机的话读会话列表和 Claude Code 的默认值。 直连的主机由
     * [onUiStarted] 管。在主线程上调用。
     */
    fun onEnvironmentReady() {
        if (needsDebian(backend)) {
            renewBlank()
            sessionList.refresh()
            refreshClaudeDefaults(backend)
        }
    }

    /**
     * 侧栏换到主机 [backend]：Claude Code 的默认值换成它的，会话列表也换成它的（列着全部主机时列表不变），当前对话还没开始的话换成它上面的新对话。
     * 别的主机上正在进行的对话留在后台照常跑完。列不列全部主机不归它管，见 [listOnly]、 [selectAllHosts]。在主线程上调用。
     */
    fun selectBackend(backend: String) {
        if (backend !in backends) return
        // 和存着的比，不和 [this.backend] 比：存着的那台刚删掉时它已经是手机本机，但列表、默认值还是那台的
        if (backend == settings.backend && current.backend == backend) return
        followBackend(backend)
        if (current.backend != backend) startNew()
    }

    /** 侧栏的会话列表只列主机 [backend] 的，侧栏也换到它（[selectBackend]）。在主线程上调用。 */
    fun listOnly(backend: String) {
        if (backend !in backends) return
        val wasAll = allHosts
        settings.paneAll = false
        // 换到别的主机时 followBackend 会换列表；还是这台就在这里收窄
        if (wasAll && backend == settings.backend) relist(backend)
        selectBackend(backend)
    }

    /** 侧栏的会话列表改成列全部主机的。新对话仍开在 [backend] 上，当前对话不动。在主线程上调用。 */
    fun selectAllHosts() {
        if (allHosts || settings.remoteHosts.isEmpty()) return
        val listed = listedBackends
        settings.paneAll = true
        sessionList.refresh(backends - listed)
    }

    /** 会话列表换成只列主机 [backend] 的：已经读到的它的那些先留着，再重读。 */
    private fun relist(backend: String) {
        sessionList.keepOnly(backend)
        sessionList.refresh()
    }

    /** 侧栏换成主机 [backend] 的：Claude Code 的默认值，会话列表换成它的（列着全部主机时列表不用换）。 当前对话不动。 */
    private fun followBackend(backend: String) {
        if (backend == settings.backend) return
        settings.backend = backend
        if (!allHosts) relist(backend)
        refreshClaudeDefaults(backend)
    }

    /** 设置页改了远程主机的清单（加、改名、改默认工作目录、删；改连法走 [changeVia]）。在主线程上调用。 */
    fun updateRemoteHosts(hosts: List<RemoteHost>) {
        settings.remoteHosts = hosts
        onRemoteHostsChanged()
    }

    /**
     * 远程主机清单变了：删掉的主机上的对话收掉，Claude Code 在那台上报的模型、那台上的定时任务和通知触发规则一起删； 正看着的那台删了就回到手机本机；
     * 还没开始的新对话按（可能改过的）默认工作目录重建。在主线程上调用。
     */
    private fun onRemoteHostsChanged() {
        val ids = backends.toSet()
        val gone = liveConversations.filter { it.backend !in ids }
        if (gone.isNotEmpty()) {
            for (conversation in gone) parked.remove(conversation)
            gone.forEach { it.shutdown() }
            if (current.backend !in ids) current = blank()
            platform.prunePhones()
        }
        models = models.filterKeys { it in ids }
        quotas.filterKeys { it !in ids }.values.forEach(QuotaService::close)
        quotas.keys.retainAll(ids)
        accountServices.keys.retainAll(ids)
        defaultsByBackend.keys.retainAll(ids)
        settings.remoteClaude = settings.remoteClaude.filterKeys { HostKeys.hostOf(it) in ids }
        platform.hostsRemoved(ids)
        ssh.retain(backends.filterNotTo(HashSet(), ::needsDebian))
        if (settings.backend !in ids) selectBackend(Backends.LOCAL) else renewBlank()
    }

    /** 已经照核心报的忙闲打开过的会话（[openRunning]）：每个只打开一次。只在主线程上用。 */
    private val openedFromActivity = HashSet<String>()

    /**
     * 核心推来了主机 [backend] 上各会话的忙闲（[SessionList.activityReceived]）。在进行（[SessionActivity.active]）、
     * 这里却还没有对话对象的会话，比如应用被回收重开后核心接着跑的那些，按会话号打开放在后台（conv.open 拿到的就是核心里接着的那个），
     * 提醒和胶囊才跟得上它；当前对话不换。每个会话只打开一次。 这里自己的新对话还在进行、还没拿到会话号时，核心报的忙着的会话可能就是它，这次先不打开，等下一次推来。在主线程上调用。
     */
    fun openRunning(backend: String, sessions: Map<String, SessionActivity>) {
        if (liveConversations.any { it.backend == backend && it.sessionId == null && it.active })
            return
        val running = sessions.filter { (session, activity) ->
            activity.active && session !in openedFromActivity && findConversation(session) == null
        }
        if (running.isEmpty()) return
        for (session in running.keys) {
            openedFromActivity += session
            val summary =
                sessionList.sessions.firstOrNull { it.id == session && it.backend == backend }
            // 列表里还没有它时，工作目录和标题等核心推来
            park(
                summary?.let(::conversationOf)
                    ?: Conversation(
                        this,
                        defaultCwd(backend),
                        session,
                        "",
                        null,
                        backend = backend,
                    ),
            )
        }
        onActivityChanged()
    }

    /** 换上一个新对话，开在侧栏正看着的主机上，工作目录是 [cwd]（默认用那台主机的）。 */
    fun startNew(cwd: String? = null) =
        replaceCurrent(if (cwd == null) blank() else blank(cwd = cwd))

    /**
     * 结束当前对话（[Conversation.shutdown]），界面换成空的新对话。和 [startNew] 的差别只在对话还在进行时：
     * 这里一并停掉，那边留在后台跑完。会话正被别处占着的（[Conversation.blockedByExternal]，比如终端、tmux 里开着的）， 那边的 Claude Code
     * 进程也结束（[SessionList.endElsewhere]）。在主线程上调用。
     */
    fun endCurrent() {
        val leaving = current
        replaceCurrent(blank(), end = true)
        val sessionId = leaving.sessionId
        if (leaving.blockedByExternal && sessionId != null)
            sessionList.endElsewhere(sessionId, leaving.backend)
    }

    /**
     * 换了环境（[EnvController.redetect]）：要靠 Debian 的主机上的会话都不再接着用，进程和后台屏幕一并收掉， 当前对话是其中之一的话换上一个空的新对话。Zeta
     * 直连的主机上的对话不受影响。在主线程上调用。
     */
    fun dropConversations() {
        val dropped = liveConversations.filter { needsDebian(it.backend) }
        dropped.forEach { it.leave() }
        parked.removeAll(dropped)
        if (current in dropped) current = blank()
        platform.dropPhones(liveConversations.mapNotNullTo(HashSet()) { it.phoneSession })
        if (needsDebian(backend)) sessionList.clear()
    }

    /** 要靠 Debian 的主机上有对话在进行：在回答、等你确认，或还有后台任务在跑（换环境会把它们一起收掉）。 */
    val conversationsWorking: Boolean
        get() = liveConversations.any { it.active && needsDebian(it.backend) }

    /**
     * 主机 [backend] 上换 Claude 账号前：关掉那台上闲着的对话背后的 Claude Code 进程（内容不丢，下次发消息时用 --resume 接上）。
     * 有对话在进行时什么都不做，返回 false。在主线程上调用。
     */
    fun closeIdleConversations(backend: String): Boolean {
        if (!canChangeLogin(backend)) return false
        if (current.backend == backend) current.shutdown()
        val there = parked.filter { it.backend == backend }
        there.forEach { it.shutdown() }
        parked.removeAll(there)
        platform.prunePhones()
        return true
    }

    /** 主机 [backend] 上没有对话在进行（手机本机还要没在装、换环境），能动那台的登录状态。 */
    fun canChangeLogin(backend: String): Boolean =
        liveConversations.none { it.active && it.backend == backend } &&
            (backend != Backends.LOCAL || platform.local?.canSwitchEnvironment != false)

    fun open(summary: SessionSummary) {
        if (current.sessionId == summary.id) return
        replaceCurrent(parkedFor(summary.id) ?: conversationOf(summary))
        followBackend(current.backend)
    }

    /** 按会话列表里的 [summary] 打开的对话对象（建好就 conv.open）。 */
    private fun conversationOf(summary: SessionSummary) =
        Conversation(
            this,
            summary.cwd.ifBlank { defaultCwd(summary.backend) },
            summary.id,
            summary.title.ifBlank { summary.preview.take(40) },
            summary.file,
            backend = summary.backend,
        )

    /**
     * 从当前对话 [lastItemId] 所在那一轮之后分支：切到一个新对话，先显示到那一轮为止的内容，原对话不动（核心的 conv.fork）。 新会话由 Claude Code
     * 从原会话复制，发出第一条消息后才写出记录、出现在侧栏里。
     */
    fun branch(lastItemId: String) {
        val source = current
        if (source.sessionId == null || !source.canRewind) return
        replaceCurrent(
            Conversation(
                this,
                source.cwd,
                sessionId = null,
                title = "",
                historyFile = null,
                backend = source.backend,
                fork = Conversation.Fork(source, lastItemId),
            ),
        )
    }

    /**
     * 胶囊上的“在 Zeta 中打开”：切到会话 [key]（sessionId，新对话用它的 key）。
     * 还在后台跑的直接接过来；已经收掉的按会话列表重新打开；都找不到就不动。在主线程上调用。
     */
    fun openConversation(key: String) {
        val running = findConversation(key)
        if (running === current) return
        if (running != null) {
            replaceCurrent(running)
            // 别的主机上的对话：侧栏跟过去，接着开的新对话也在那台上
            followBackend(running.backend)
            return
        }
        sessionList.sessions.firstOrNull { it.id == key }?.let(::open)
            ?: sessionList.lookup(key, ::open)
    }

    /** 会话 [key]（sessionId，新对话用它的 key）当前或在后台的那个对话对象；没有就是 null。 */
    fun findConversation(key: String): Conversation? =
        if (current.sessionId == key || current.key == key) current else parkedFor(key)

    /** 放进切走的对话（定时任务在后台起的、切走时还在进行的）：不切换界面上的当前对话，跑完照常收掉。在主线程上调用。 */
    fun park(conversation: Conversation) {
        if (conversation !in parked) parked += conversation
    }

    /** 会话 [ids] 要删了（[SessionList.delete]）：开着的对话收掉，后台屏幕一并收掉。在主线程上调用。 */
    fun closeSessions(ids: Set<String>) {
        if (current.sessionId in ids) replaceCurrent(blank(), end = true)
        for (conversation in parked.filter { it.openKey in ids }) {
            parked.remove(conversation)
            conversation.shutdown()
        }
        unread.removeAll(ids)
        platform.prunePhones(ids)
    }

    /**
     * 换上 [next] 作当前对话。原来那个：[end] 时结束进程；闲着时不再接着（[Conversation.leave]，没有别的客户端接着就结束，省内存）；
     * 还在进行的（包括有后台任务的）留在后台跑完。 在主线程上调用。
     */
    private fun replaceCurrent(next: Conversation, end: Boolean = false) {
        val leaving = current
        if (end || !leaving.active) {
            parked.remove(leaving)
            if (end) leaving.shutdown() else leaving.leave()
        } else {
            park(leaving)
        }
        parked.remove(next)
        next.sessionId?.let(unread::remove)
        current = next
        platform.prunePhones()
    }

    /**
     * 对话 [conversation] 的会话号从 [from] 换成了 [to]（新对话拿到会话号、/clear 换了新会话；还没有会话号时用对话的 key）。
     * 按会话登记的都在这里换键：手机工具（之后重新打开才找得回那块后台屏幕）、输入栏草稿；切走的对话按 openKey 找，不用换。
     * 新加按会话登记的东西也在这里跟着换（SessionRekeyTest 核对）。在主线程上调用。
     */
    fun sessionChanged(conversation: Conversation, from: String, to: String) {
        conversation.phoneSession?.let { platform.rekeyPhone(it, from, to) }
        drafts.rekey(conversation, from, to)
        if (unread.remove(from)) unread += to
    }

    /** 对话的这一轮就要结束（还没清 busy 和手机工具的状态），[text] 是胶囊上停留的那一句（“已完成”）。在主线程上调用。 */
    fun onTurnEnding(conversation: Conversation, text: String) =
        platform.alerts.turnEnding(conversation, text)

    /**
     * 一轮结束了：切走的对话闲下来就收掉，提醒交给 [alerts]，会话列表里这个会话重读。[stopped]：这一轮是用户叫停的；[text]
     * 是结束的那一句（“已完成”“出错了”“已中断”）。
     */
    fun onTurnFinished(
        conversation: Conversation,
        stopped: Boolean = false,
        text: String = "已完成",
    ) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
        if (conversation !== current && !stopped) markUnread(conversation)
        platform.alerts.turnFinished(conversation, stopped, text)
        // 侧栏列的是别的主机的话，这一轮没改到它
        if (conversation.backend !in listedBackends) return
        val session = conversation.sessionId
        if (session != null) sessionList.touched(session, conversation.backend)
        else sessionList.refresh()
    }

    private fun markUnread(conversation: Conversation) {
        val session = conversation.sessionId ?: return
        if (session !in unread) unread += session
    }

    /** 闲着的对话最后一个后台任务也结束了（这一轮早已结束）。 */
    fun onBackgroundTasksDone(conversation: Conversation) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
    }

    /**
     * Zeta 不在前台时，当前对话闲着（[Conversation.active] 为假）满 [IDLE_RETIRE_MS] 就不再接着它背后的 Claude Code 进程
     * （[Conversation.leave]：没有别的客户端接着时进程随之结束），内容不丢，下次发消息时用 --resume
     * 接上。不收的话，这个进程和为它常驻的前台服务（[onActivityChanged]）互相保着， 用过一次 Zeta 就一直占着几百 MB
     * 内存和一条常驻通知。切到后台、一轮结束、后台任务做完时重新计时，回到前台就不等了。 到点时还在进行的不收，等它这一轮结束再计时。
     */
    private val retireCurrent = Runnable { if (!foreground && !current.active) current.leave() }

    private fun retireCurrentWhenIdle() {
        main.removeCallbacks(retireCurrent)
        if (!foreground) main.postDelayed(retireCurrent, IDLE_RETIRE_MS)
    }

    /**
     * 切走的对话没事了（没有进行中的一轮、没有后台任务、没有等确认的调用）就收掉进程。 稍等一下再看：后台任务跑完后 Claude Code 会紧接着自己另起一轮汇报结果（实测不到 0.2
     * 秒）， 立刻收掉就把这一轮掐掉了；起了新的一轮的话等它结束再说。
     */
    private fun retireWhenIdle(conversation: Conversation) {
        main.postDelayed(
            {
                if (conversation !== current && !conversation.active) {
                    parked.remove(conversation)
                    conversation.leave()
                    platform.prunePhones()
                }
            },
            RETIRE_DELAY_MS,
        )
    }

    /** 对话等着用户回答的请求变了（来了、处理掉了、撤回了）：提醒交给 [alerts]，胶囊和实时通知跟着更新。在主线程上调用。 */
    fun onPendingChanged(conversation: Conversation) {
        // 先更新实时通知：提醒要据此判断超级岛上是不是已经能回答这个请求
        onActivityChanged()
        platform.alerts.pendingChanged(conversation)
    }

    /** 对话的活动变了：交给平台（手机上据此保持前台服务、更新常驻通知和胶囊）。 */
    fun onActivityChanged() = platform.activityChanged()

    init {
        // 放在各项之后：平台这时才开始监听网络、生命周期，接上外部入口，它们可能马上回调 hub
        platform.started()
    }

    /** 通知栏的“停止”：打断所有正在回答的对话，和聊天页的停止键一样。 */
    fun interruptAll() {
        liveConversations.filter { it.busy }.forEach { it.interrupt() }
    }

    companion object {
        private const val RETIRE_DELAY_MS = 5_000L

        /** 和 zeta_host.sh 的看门狗一样：没人看着、闲着的进程约十分钟后结束。 */
        const val IDLE_RETIRE_MS = 10 * 60_000L
    }
}
