package com.dzshzx.zeta.core

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.claude.defaults
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.schedule.ScheduleRunner
import com.dzshzx.zeta.core.ssh.SshKey
import com.dzshzx.zeta.core.ssh.SshLinks
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.PhoneTools
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 应用级的单例：当前对话和切到后台还在跑的对话（打开、切换、分支、收掉、接回进程），各会话的手机工具，界面前后台， 以及挂在它上面的几块：设置 [settings]、Linux 环境
 * [environment]、会话列表 [sessionList]、 胶囊与提醒 [alerts]、额度 [quota]、账号 [accounts]。
 *
 * hub 是对话事件的入口：Conversation 的事件（一轮就要结束、结束了、等确认、后台任务做完、活动变化）都调 hub 上的 on… 方法， 由 hub
 * 分给上面这几块，Conversation 不直接找它们。只转给一处的（[onTurnEnding]）也照样经 hub 转。
 */
class ConversationHub(
    private val app: Application,
    sshKey: () -> SshKey = {
        SshKey.keystore(
                AppVariant.current.linuxName +
                    "@" +
                    Build.MODEL.replace(Regex("[^A-Za-z0-9._-]+"), "-"),
            )
            .also { key ->
                // 公钥那一行也放进应用的私有目录：设备测试（和用 adb 的人）从这里取，不用开设置页
                runCatching {
                    java.io.File(app.filesDir, PUBLIC_KEY_FILE).writeText(key.authorizedKey + "\n")
                }
            }
    },
) {
    /** Zeta 直连远程主机的 SSH 连接（[RemoteHost.Via.Direct]），和它用的密钥。 */
    val ssh = SshLinks(sshKey)

    val context: Application
        get() = app

    val settings = Settings.of(app)
    val hostScript: String by lazy {
        app.assets.open("zeta_host.sh").bufferedReader().use { it.readText() }
    }

    /** Linux 一侧的程序 zeta-helper：APK 里带着，第一次用时装进 Linux（[HelperProgram]）。 */
    val helperProgram =
        HelperProgram(
            { name -> app.assets.open(name).use { it.readBytes() } },
            AppVariant.current.linuxName,
        )

    /** 侧栏正看着的主机（[Backends]）：会话列表列它的，新对话默认开在它上面。设置里存的那台已经删掉时是手机本机。可观察。 换主机用 [selectBackend]。 */
    val backend: String
        get() = settings.backend.takeIf { it in backends } ?: Backends.LOCAL

    /** 全部主机：手机本机在前，然后是各台远程主机。 */
    val backends: List<String>
        get() = listOf(Backends.LOCAL) + settings.remoteHosts.map { it.id }

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
        return !needsDebian(backend) ||
            (settings.env != null && environment.setup == SetupState.Ready)
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
        idle.forEach { it.shutdown() }
        parked.removeAll(idle.toSet())
        prunePhones()
        updateRemoteHosts(
            settings.remoteHosts.map {
                if (it.id == id)
                    host.copy(
                        via = via,
                        target = target,
                        hostKey = hostKey.takeIf { via == RemoteHost.Via.Direct },
                    )
                else it
            },
        )
        // 原来的连法没接成（比如经 Debian 而 Debian 没就绪）时没查过那台上的后台进程，换了连法查一次
        if (usable(id)) reattachHosts(id)
        return true
    }

    /** 主机 [backend] 上的命令以 [exitCode] 结束、标准错误是 [stderr]：是远程主机连不上的话给界面一句话，别的情况是 null。 */
    fun linkProblem(backend: String, exitCode: Int, stderr: String): String? =
        if (backend != Backends.LOCAL && exitCode == RemoteShell.CONNECTION_FAILED)
            "连不上「${backendName(backend)}」：" + RemoteHosts.sshProblem(stderr)
        else null

    /**
     * 连一次远程主机 [target] 看看（连法 [via]）：家目录、claude、CPU 架构。直连的只认 [expectedHostKey]，加主机时是 null（记下对方的）。
     * 阻塞，别在主线程上调用。
     */
    fun probeRemote(
        target: String,
        via: RemoteHost.Via,
        expectedHostKey: String? = null,
    ): RemoteHosts.Probe =
        when (via) {
            RemoteHost.Via.Direct -> RemoteHosts.probeDirect(ssh, target, expectedHostKey)
            RemoteHost.Via.Debian ->
                shell(Backends.LOCAL)?.let { RemoteHosts.probeDebian(it, target) }
                    ?: RemoteHosts.Probe.Failed("手机里的 Linux 环境还没就绪")
        }

    /** 主机 [backend] 上那个用户的家目录；还不知道时为 null。 */
    fun home(backend: String): String? =
        if (backend == Backends.LOCAL) settings.env?.home else remoteHost(backend)?.home

    /** 界面上设备 [backend] 的名字：用户起的，没起时是它自己的名字（[originalName]）。 */
    fun backendName(backend: String): String =
        if (backend == Backends.LOCAL) settings.localName ?: phoneName
        else remoteHost(backend)?.name ?: "已删除的设备"

    /** 手机自己的名字：系统设置里的设备名（「关于手机」里能改），取不到时是型号。Zeta 开着时改了系统里的名字，下次启动才跟上。 */
    private val phoneName: String by lazy {
        android.provider.Settings.Global.getString(
                app.contentResolver,
                android.provider.Settings.Global.DEVICE_NAME,
            )
            ?.trim()
            ?.ifEmpty { null } ?: Build.MODEL
    }

    /** 设备 [backend] 自己的名字（用户没起名字时显示它）：手机是系统里的设备名，远程设备是它报的主机名，还没记下时是地址。 */
    fun originalName(backend: String): String {
        val host = remoteHost(backend) ?: return phoneName
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
     * 对话还没定下模式时输入栏显示它（[Conversation.shownPermissionMode]）。
     */
    fun defaultPermissionMode(backend: String): String? =
        claudeDefaults(backend)?.let { it.permissionMode ?: "default" }

    /** 正在读默认值的主机：同一台同时只读一次（设置首页和主机页可能前后脚要读）。只在主线程上碰。 */
    private val readingDefaults = HashSet<String>()

    /** 读到或改了主机 [backend] 上 Claude Code 的默认值。在主线程上调用。 */
    internal fun updateClaudeDefaults(backend: String, defaults: ClaudeDefaults) {
        defaultsByBackend[backend] = defaults
    }

    /**
     * 还开着的 Claude Code 进程上用户在对话里定的权限模式（进程键 → 模式）。只在进程活着时有意义，和 Claude Code 里
     * 「只用于这个会话」一样：应用被回收后接回进程时照它恢复（[Conversation] 的 attach），进程没了在 [reattachHosts] 里清掉。
     */
    internal fun hostMode(key: String): String? = settings.hostModes[key]

    internal fun rememberHostMode(key: String, mode: String) {
        val modes = settings.hostModes
        if (modes[key] != mode) settings.hostModes = modes + (key to mode)
    }

    /**
     * 重读主机 [backend] 上 Claude Code 的默认值：侧栏正看着的那台在环境就绪时、换过去时读（输入栏要显示它的默认权限模式），
     * 设置首页上还没读过的主机读一次，打开一台主机的页面时重读那台（默认值可能在终端里改过）。那台正在读就不再读；读不到就照旧。
     * 远程设备还没记下自己的主机名（加的时候还不查它）时，读到了就顺带问一句（[RemoteHost.hostname]）。在主线程上调用。
     */
    fun refreshClaudeDefaults(backend: String) {
        if (backend in readingDefaults || shell(backend) == null) return
        readingDefaults.add(backend)
        val askHostname = remoteHost(backend)?.hostname == null && backend != Backends.LOCAL
        thread(name = "zeta-defaults", isDaemon = true) {
            val defaults = runCatching {
                ClaudeConfigClient.of(this, backend).defaults()
            }
                .getOrNull()
            val hostname = if (askHostname && defaults != null) hostnameOf(backend) else null
            main.post { onDefaultsRead(backend, defaults, hostname) }
        }
    }

    /** [refreshClaudeDefaults] 读完（在主线程上）：读到的默认值记下，问到的主机名补进还没记主机名的那台设备。 */
    private fun onDefaultsRead(backend: String, defaults: ClaudeDefaults?, hostname: String?) {
        readingDefaults.remove(backend)
        if (defaults != null && backend in backends) updateClaudeDefaults(backend, defaults)
        if (hostname == null) return
        settings.remoteHosts =
            settings.remoteHosts.map {
                if (it.id == backend && it.hostname == null) it.copy(hostname = hostname) else it
            }
    }

    /** 设备 [backend] 报的主机名（`uname -n`）；问不到是 null。阻塞。 */
    private fun hostnameOf(backend: String): String? = runCatching {
        shell(backend)?.run("uname -n", timeoutMs = 15_000, plain = true)
    }
        .getOrNull()
        ?.takeIf { it.exitCode == 0 }
        ?.stdout
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.ifEmpty { null }

    /**
     * 当前对话还没开始（没有内容、没有会话）时，按现在的默认工作目录重建它：环境就绪时， 设置从备份导入后（[Settings.restore]
     * 已经让各项按导入的值重读），换了默认工作目录时。在主线程上调用。
     */
    internal fun renewBlank() {
        if (current.items.isEmpty() && current.sessionId == null) current = blank()
    }

    /** 从外面送进来（分享、打开、截屏）、还等用户选放进哪个对话的东西，见 [offerIntake]。 */
    var pendingIntake by mutableStateOf<Intake?>(null)

    /** 已经定了放进当前对话、等它的输入栏取走的东西。 */
    var incoming by mutableStateOf<Intake?>(null)

    /** 已经查过、接回了后台进程的主机（[reattachHosts]）；正在查的也算，没查成时去掉。只在主线程上用。 */
    internal val hostsChecked = HashSet<String>()

    /**
     * 各主机（[Backends]）上 Claude Code 报的模型选项（initialize 的应答）。只放在内存里：别名对应的完整 ID 随 Claude Code 升级而变，
     * 按模型存的默认思考强度要以正开着的进程报的为准。还没起过进程的主机没有，见 [modelOptions]；删主机时一起删。
     */
    var models by mutableStateOf<Map<String, List<ModelOption>>>(emptyMap())
        internal set

    /** 主机 [backend] 上选择器里的模型：那台的 Claude Code 报的，还没拿到时是几个常用别名（[FallbackModels]）。 */
    fun modelOptions(backend: String): List<ModelOption> =
        models[backend].orEmpty().ifEmpty { FallbackModels }

    /**
     * 每个会话的手机工具，按 sessionId（新对话还没有时按 key）登记。后台屏幕比 Claude Code 进程活得久：
     * 进程结束、重新打开会话时新建的对话对象从这里拿回原来那份，屏上的应用还是原来的样子。 主线程和工具线程（[makeRoom]）都会访问，用它自己当锁；持锁时不调用会去拿
     * PhoneTools 锁的东西。 要在 [current] 之前初始化：[blank] 建对话时就会来要。
     */
    private val phones = HashMap<String, PhoneTools>()

    /** 各对话没发出的输入草稿，按会话号保管，Zeta 重开后还在。 */
    val drafts = ComposerDrafts(app, settings, ::shell)

    /** 界面上的当前对话（可观察）。换了就告诉 [alerts]：看着的那个的提醒不必留着。 */
    var current: Conversation
        get() = currentState
        private set(value) {
            currentState = value
            alerts.watchChanged()
        }

    private var currentState by mutableStateOf(blank())

    /** Linux 环境：检查、安装、维护、SSH、Claude 账号的登录状态。 */
    val environment =
        EnvController(this) { name -> app.assets.open(name).bufferedReader().use { it.readText() } }

    /** 胶囊、结果卡片、完成与确认提醒、实时通知显示哪个对话。 */
    val alerts = TurnAlerts(this)

    /** 侧栏的会话列表。 */
    val sessionList = SessionList(this)

    /** 当前账号的订阅额度（所有对话共用）。 */
    val quota = QuotaService(this)

    /** claude-switch 管着的 Claude 账号，和切换账号。 */
    val accounts = AccountService(this)

    /** 界面是否在前台；不在前台时一轮结束或需要批准会发通知。 */
    private val startedActivities = HashSet<Activity>()

    private var shown by mutableStateOf(false)

    /**
     * 界面是否正在显示（可观察：胶囊据此避开正显示着同一对话的聊天页）。 按实例记：界面重建时新旧实例的 onStart/onStop 会交错，单个布尔值会被覆盖错。
     * 切到后台时开始等当前对话闲下来再收掉进程，回到前台就不等了（[retireCurrentWhenIdle]）。
     */
    var foreground: Boolean
        get() = shown
        internal set(value) {
            shown = value
            retireCurrentWhenIdle()
            alerts.watchChanged()
        }

    /** 用户正看着这个对话（Zeta 在前台、显示的就是它）：完成、确认都不用另外提醒。 */
    internal fun isWatching(conversation: Conversation): Boolean =
        foreground && conversation === current

    init {
        DiagLog.init(app)
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivities.add(activity)
                    foreground = true
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivities.remove(activity)
                    foreground = startedActivities.isNotEmpty()
                }

                override fun onActivityDestroyed(activity: Activity) {
                    startedActivities.remove(activity)
                    foreground = startedActivities.isNotEmpty()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
                    Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) =
                    Unit
            },
        )
        ScheduleRunner.onAppStart(app)
        AccessibilityGuard.start(app)
        watchNetwork()
    }

    /** 已经在界面起来时接过 Zeta 直连的主机（[onUiStarted]）。 */
    private var directStarted = false

    /**
     * 界面（主界面、数字助理）起来了：手机的 Linux 环境还没就绪就去检查，就绪后要靠 Debian 的主机由 [onEnvironmentReady] 接着做。 Zeta
     * 直连的主机不用等 Debian：第一次时就接回它们上面还在跑的进程，侧栏正看着其中一台的话读会话列表和默认值。
     * 和手机本机一样等界面起来才做，系统为无障碍服务、定时任务拉起应用时不去连远程主机。在主线程上调用。
     */
    fun onUiStarted() {
        if (environment.setup != SetupState.Ready) environment.checkSetup()
        if (directStarted) return
        directStarted = true
        val direct = backends.filterNot(::needsDebian)
        if (backend in direct) {
            renewBlank()
            sessionList.refresh()
            refreshClaudeDefaults(backend)
        }
        direct.forEach(::reattachHosts)
    }

    /**
     * 手机换了网络（Wi-Fi 和移动数据之间）：远程主机的旧连接多半已经死了，两种连法都马上断开（直连的在 Zeta 里， 经 Debian 的关掉 Debian 里 ssh
     * 的主连接），上面的对话随即重连，不等保活包超时（约 45 秒）。 重新有网时，等着重连的对话也马上去连。
     */
    private fun watchNetwork() {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    private var last: Network? = null

                    override fun onAvailable(network: Network) {
                        val previous = last
                        last = network
                        if (previous != null && previous != network) {
                            ssh.dropAll()
                            dropDebianLinks()
                        }
                        // 等着重连的对话不等满退避的时间
                        NetworkWake.signal()
                    }
                },
            )
        }
    }

    /** 关掉手机 Debian 里到各台经 Debian 连的主机的 ssh 主连接（[DebianSshShell.closeMasters]），在后台线程上做。 */
    private fun dropDebianLinks() {
        val targets =
            settings.remoteHosts.filter { it.via == RemoteHost.Via.Debian }.map { it.target }
        if (targets.isEmpty() || !usable(Backends.LOCAL)) return
        val local = shell(Backends.LOCAL) ?: return
        thread(name = "zeta-ssh-drop", isDaemon = true) {
            runCatching { local.run(DebianSshShell.closeMasters(targets), timeoutMs = 15_000) }
        }
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

    private val main = Handler(Looper.getMainLooper())

    private fun blank(backend: String = this.backend, cwd: String = defaultCwd(backend)) =
        Conversation(this, cwd, sessionId = null, title = "", historyFile = null, backend = backend)

    fun isRunning(sessionId: String): Boolean = findConversation(sessionId)?.busy == true

    /** 这个会话的对话还在进行：在跑、等确认，或者后台任务没做完。批量删除跳过这些。 */
    fun isActive(sessionId: String): Boolean = findConversation(sessionId)?.active == true

    /** 侧栏的“等你确认”标记：这个会话的对话正等着用户批准一次工具调用。 */
    fun isWaiting(sessionId: String): Boolean = findConversation(sessionId)?.pending != null

    /**
     * 环境检查完、就绪了（[EnvController.checkSetup]）：侧栏正看着要靠 Debian 的主机的话读会话列表和 Claude Code 的默认值，
     * 第一次就绪时接回这些主机上还在跑的进程（远程主机连不上的，等换到它时再查）。直连的主机由 [onUiStarted] 管。在主线程上调用。
     */
    internal fun onEnvironmentReady() {
        if (needsDebian(backend)) {
            renewBlank()
            sessionList.refresh()
            refreshClaudeDefaults(backend)
        }
        backends.filter(::needsDebian).forEach(::reattachHosts)
    }

    /**
     * 侧栏换到主机 [backend]：会话列表、Claude Code 的默认值都换成它的，当前对话还没开始的话换成它上面的新对话，
     * 那台主机上还在跑的进程接回来（之前没接上的话）。别的主机上正在进行的对话留在后台照常跑完。在主线程上调用。
     */
    fun selectBackend(backend: String) {
        if (backend !in backends) return
        // 和存着的比，不和 [this.backend] 比：存着的那台刚删掉时它已经是手机本机，但列表、默认值还是那台的
        if (backend == settings.backend && current.backend == backend) return
        followBackend(backend)
        if (current.backend != backend) startNew()
    }

    /** 侧栏换成主机 [backend] 的：会话列表、Claude Code 的默认值，没接过的后台进程接回来。当前对话不动。 */
    private fun followBackend(backend: String) {
        if (backend == settings.backend) return
        settings.backend = backend
        sessionList.clear()
        sessionList.refresh()
        refreshClaudeDefaults(backend)
        reattachHosts(backend)
    }

    /** 设置页改了远程主机的清单（加、改名、改默认工作目录、删；改连法走 [changeVia]）。在主线程上调用。 */
    fun updateRemoteHosts(hosts: List<RemoteHost>) {
        settings.remoteHosts = hosts
        onRemoteHostsChanged()
    }

    /**
     * 远程主机清单变了：删掉的主机上的对话收掉，Claude Code 在那台上报的模型和斜杠命令一起删；正看着的那台删了就回到手机本机；
     * 还没开始的新对话按（可能改过的）默认工作目录重建。在主线程上调用。
     */
    private fun onRemoteHostsChanged() {
        val ids = backends.toSet()
        val gone = liveConversations.filter { it.backend !in ids }
        if (gone.isNotEmpty()) {
            for (conversation in gone) parked.remove(conversation)
            gone.forEach { it.shutdown() }
            if (current.backend !in ids) current = blank()
            prunePhones()
        }
        hostsChecked.retainAll(ids)
        models = models.filterKeys { it in ids }
        defaultsByBackend.keys.retainAll(ids)
        settings.commands = settings.commands.filterKeys { it in ids }
        ssh.retain(backends.filterNotTo(HashSet(), ::needsDebian))
        if (settings.backend !in ids) selectBackend(Backends.LOCAL) else renewBlank()
    }

    /**
     * 应用上次是被系统回收或划掉的话，主机 [backend] 上的 Claude Code 进程可能还在后台。
     * 还在干活或等确认的重新接上；闲着的直接结束，省内存。每台主机查成一次就不再查；连不上的下次再查。在主线程上调用。
     */
    private fun reattachHosts(backend: String) {
        if (backend in hostsChecked) return
        val shell = shell(backend) ?: return
        hostsChecked += backend
        thread(name = "zeta-hosts", isDaemon = true) {
            // 没查成不等于没有：什么都不动（不结束进程、不删权限模式），下次再查
            val output = helper("hosts", timeoutMs = 60_000, backend = backend).outputOrNull
            if (output == null) {
                main.post { hostsChecked -= backend }
                return@thread
            }
            val hosts = parseHosts(output, defaultCwd(backend))
            main.post { onHostsListed(backend, shell, hosts) }
        }
    }

    /** zeta-helper hosts 的输出：一行一个托管进程；坏行和没有 key 的跳过，没记工作目录的用 [defaultCwd]。 */
    private fun parseHosts(output: String, defaultCwd: String): List<HostInfo> =
        output
            .lineSequence()
            .mapNotNull { line ->
                runCatching { json.parseToJsonElement(line) as? JsonObject }
                    .getOrNull()
                    ?.let { hostInfo(it, defaultCwd) }
            }
            .toList()

    private fun hostInfo(o: JsonObject, defaultCwd: String): HostInfo? =
        HostInfo(
            key = o.str("key") ?: return null,
            cwd = o.str("cwd").orEmpty().ifBlank { defaultCwd },
            title = o.str("title").orEmpty(),
            sessionId = o.str("session"),
            historyFile = o.str("file"),
            lines = o.int("lines") ?: 0,
            busy = o.bool("busy") == true,
            pending = o.obj("pending"),
            permissionMode = o.str("mode"),
            tasks = o.int("tasks") ?: 0,
            taskEvents = o.arr("task_events").orEmpty().filterIsInstance<JsonObject>(),
        )

    /** 查到了主机 [backend] 上的托管进程（在主线程上）：在干活的接上，闲着的结束，结束了的进程的权限模式不再留。 */
    private fun onHostsListed(backend: String, shell: LinuxShell, hosts: List<HostInfo>) {
        // 这个进程里已经接着的（比如界面打开前定时任务起的对话）不再接一次，也不当闲着收掉；在主线程上看才不漏
        val known = liveConversations.mapNotNullTo(HashSet()) { it.hostKey }
        val others = hosts.filter { it.key !in known }
        // 这一轮结束了但后台任务还在跑的也接上，否则结束进程会把任务一起杀掉
        val active = others.filter { it.active }
        val idle = others - active.toSet()
        if (idle.isNotEmpty()) stopHosts(shell, idle)
        // 用户的权限模式只留给还接着的进程：这台主机上查到的之外的不动（别的主机的进程这次没查）
        val dead = (hosts.map { it.key } - known - active.map { it.key }.toSet()).toSet()
        settings.hostModes
            .takeIf { modes -> modes.keys.any { it in dead } }
            ?.let { modes -> settings.hostModes = modes.filterKeys { it !in dead } }
        if (active.isEmpty()) return
        for (host in active) adoptHost(backend, host)
        onActivityChanged()
    }

    /** 在后台线程上结束闲着的托管进程。 */
    private fun stopHosts(shell: LinuxShell, idle: List<HostInfo>) {
        thread(name = "zeta-hosts", isDaemon = true) {
            for (host in idle) {
                runCatching {
                    shell.run(hostCommandLine(hostScript, listOf("stop", host.key)), plain = true)
                }
            }
        }
    }

    /** 接回一个还在干活的进程：当前对话还空着就换成它，否则放到后台。 */
    private fun adoptHost(backend: String, host: HostInfo) {
        val conversation =
            Conversation(
                this,
                host.cwd,
                host.sessionId,
                host.title,
                host.historyFile,
                attach = host,
                backend = backend,
            )
        if (
            current.backend == backend &&
                current.items.isEmpty() &&
                current.sessionId == null &&
                !current.isProcessAlive
        ) {
            current = conversation
        } else {
            park(conversation)
        }
    }

    /** 换上一个新对话，开在侧栏正看着的主机上，工作目录是 [cwd]（默认用那台主机的）。 */
    fun startNew(cwd: String? = null) =
        replaceCurrent(if (cwd == null) blank() else blank(cwd = cwd))

    /**
     * 结束当前对话（[Conversation.shutdown]），界面换成空的新对话。和 [startNew] 的差别只在对话还在进行时：
     * 这里一并停掉，那边留在后台跑完。打开时会话正被别处占用的（[Conversation.external]，比如终端、tmux 里开着的）， 那边的 Claude Code
     * 进程也结束（[SessionList.endElsewhere]）。在主线程上调用。
     */
    fun endCurrent() {
        val leaving = current
        replaceCurrent(blank(), end = true)
        val sessionId = leaving.sessionId
        if (leaving.external && sessionId != null)
            sessionList.endElsewhere(sessionId, leaving.backend)
    }

    /**
     * 换了环境（[EnvController.redetect]）：要靠 Debian 的主机上的会话都不再接着用，进程和后台屏幕一并收掉， 当前对话是其中之一的话换上一个空的新对话。Zeta
     * 直连的主机上的对话不受影响。在主线程上调用。
     */
    internal fun dropConversations() {
        val dropped = liveConversations.filter { needsDebian(it.backend) }
        dropped.forEach { it.shutdown() }
        parked.removeAll(dropped)
        if (current in dropped) current = blank()
        synchronized(phones) {
                val kept = liveConversations.mapTo(HashSet()) { it.phone }
                phones.values.filter { it !in kept }.also { phones.values.removeAll(it.toSet()) }
            }
            .forEach { it.destroy() }
        if (needsDebian(backend)) sessionList.clear()
    }

    /** 要靠 Debian 的主机上有对话在进行：在回答、等你确认，或还有后台任务在跑（换环境会把它们一起收掉）。 */
    internal val conversationsWorking: Boolean
        get() = liveConversations.any { it.active && needsDebian(it.backend) }

    /**
     * 换 Claude 账号前：关掉闲着的对话背后的 Claude Code 进程（内容不丢，下次发消息时用 --resume 接上）。 有对话在进行时什么都不做，返回
     * false。在主线程上调用。
     */
    internal fun closeIdleConversations(): Boolean {
        if (!environment.canSwitchEnvironment) return false
        current.shutdown()
        parked.toList().forEach { it.shutdown() }
        parked.clear()
        prunePhones()
        return true
    }

    fun open(summary: SessionSummary) {
        if (current.sessionId == summary.id) return
        replaceCurrent(
            parkedFor(summary.id)
                ?: Conversation(
                    this,
                    summary.cwd.ifBlank { defaultCwd(summary.backend) },
                    summary.id,
                    summary.title.ifBlank { summary.preview.take(40) },
                    summary.file,
                    external = summary.external,
                    backend = summary.backend,
                ),
        )
        followBackend(current.backend)
    }

    /**
     * 从当前对话 [lastItemId] 所在那一轮之后分支：切到一个新对话，先显示到那一轮为止的内容，原对话不动。 新会话由 Claude Code
     * 从原会话复制（--fork-session），发出第一条消息后才写出记录、出现在侧栏里。
     */
    fun branch(lastItemId: String) {
        val source = current
        val sourceId = source.sessionId ?: return
        if (!source.canRewind) return
        val plan = branchPlan(source.items, lastItemId) ?: return
        val title = branchTitle(source.title, sessionList.sessions.map { it.title })
        replaceCurrent(
            Conversation(
                this,
                source.cwd,
                sessionId = null,
                title = title,
                historyFile = null,
                backend = source.backend,
                fork =
                    Fork(
                        sourceId,
                        plan.cut,
                        plan.lastSeen,
                        plan.items,
                        source.earlier,
                        source.shownModel,
                        source.effort,
                        source.permissionMode,
                    ),
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
    internal fun findConversation(key: String): Conversation? =
        if (current.sessionId == key || current.key == key) current else parkedFor(key)

    /** 放进切走的对话（定时任务在后台起的、切走时还在进行的）：不切换界面上的当前对话，跑完照常收掉。在主线程上调用。 */
    internal fun park(conversation: Conversation) {
        if (conversation !in parked) parked += conversation
    }

    /** 会话 [ids] 要删了（[SessionList.delete]）：开着的对话收掉，后台屏幕一并收掉。在主线程上调用。 */
    internal fun closeSessions(ids: Set<String>) {
        if (current.sessionId in ids) replaceCurrent(blank(), end = true)
        for (conversation in parked.filter { it.openKey in ids }) {
            parked.remove(conversation)
            conversation.shutdown()
        }
        synchronized(phones) { ids.mapNotNull { phones.remove(it) } }.forEach { it.destroy() }
        prunePhones()
    }

    /** 换上 [next] 作当前对话。原来那个：[end] 时或闲着时收掉进程（省内存），还在进行的（包括有后台任务的）留在后台跑完。 在主线程上调用。 */
    private fun replaceCurrent(next: Conversation, end: Boolean = false) {
        val leaving = current
        if (end || !leaving.active) {
            parked.remove(leaving)
            leaving.shutdown()
        } else {
            park(leaving)
        }
        parked.remove(next)
        current = next
        prunePhones()
    }

    /** 会话 [key]（sessionId，新对话用它的 key）的手机工具：登记过就接着用原来那份。在主线程上调用。 */
    internal fun phoneFor(key: String): PhoneTools =
        synchronized(phones) {
            phones.getOrPut(key) {
                PhoneTools(
                    app,
                    settings,
                    beforeCreate = ::makeRoom,
                    onScreenGone = ::prunePhones,
                    isBusy = ::phoneBusy,
                    onChanged = ::onActivityChanged,
                )
            }
        }

    /**
     * 用手机工具 [phone] 的对话正在进行（后台任务在跑也算）。按 hub 手上的对话查，不让手机工具拿着对话：
     * 对话收掉后有后台屏幕的手机工具还被登记着，拿着对话就连它的聊天内容、图片一起留住；收掉的对话也自然算不忙。 读的都是快照状态，任何线程上都能调用。
     */
    private fun phoneBusy(phone: PhoneTools): Boolean = liveConversations.any {
        it.phone === phone && (it.busy || it.backgroundTasks > 0)
    }

    /**
     * 对话 [conversation] 的会话号从 [from] 换成了 [to]（新对话拿到会话号、/clear 换了新会话；还没有会话号时用对话的 key）。
     * 按会话登记的都在这里换键：手机工具（之后重新打开才找得回那块后台屏幕）、输入栏草稿；切走的对话按 openKey 找，不用换。
     * 新加按会话登记的东西也在这里跟着换（SessionRekeyTest 核对）。在主线程上调用。
     */
    internal fun sessionChanged(conversation: Conversation, from: String, to: String) {
        rekeyPhone(conversation.phone, from, to)
        drafts.rekey(conversation, from, to)
    }

    /** 对话的会话号变了：登记跟着换。新号下面要是还挂着别的一份（不该有），它已经没人找得到，收掉。 */
    private fun rekeyPhone(phone: PhoneTools, from: String, to: String) {
        val stale =
            synchronized(phones) {
                // 已经不在登记里（没人用被丢掉、销毁了）就别再登记回去，否则以后打开这个会话会拿到一份建不了屏的
                if (phones[from] !== phone) return
                phones.remove(from)
                phones.put(to, phone)?.takeIf { it !== phone }
            }
        stale?.destroy()
    }

    /** 丢掉没有后台屏幕、也没有对话在用的那些，免得登记越积越多。在主线程上调用。 */
    private fun prunePhones() {
        val inUse = liveConversations.mapTo(HashSet()) { it.phone }
        val unused =
            synchronized(phones) {
                phones.values
                    .filter { it !in inUse && it.slot.alive == null }
                    .also { phones.values.removeAll(it.toSet()) }
            }
        // 销毁而不只是丢开：还没结束的工具调用就不会再给它建出一块没人管的屏
        unused.forEach { it.destroy() }
    }

    /**
     * [phone] 要新建一块后台屏幕（工具线程上）。全应用已有 [MAX_SCREENS] 块时先腾出来：
     * 只挑对话没在进行的，用户正看着的当前对话放最后，其余最久没用的先收；都在忙就照样建。 收屏另起线程做，这里不会去拿别的 PhoneTools 的锁，两个对话同时建屏也不会互相卡住。
     */
    private fun makeRoom(phone: PhoneTools) {
        val others =
            synchronized(phones) { phones.values.filter { it !== phone && it.slot.alive != null } }
        val excess = others.size - MAX_SCREENS + 1
        if (excess <= 0) return
        val watching = current.phone
        others
            .filter { !it.slot.busy() }
            .sortedWith(compareBy<PhoneTools> { it === watching }.thenBy { it.slot.lastUse })
            .take(excess)
            .forEach { it.slot.releaseAsync() }
    }

    /** 对话的这一轮就要结束（还没清 busy 和手机工具的状态），[text] 是胶囊上停留的那一句（“已完成”）。在主线程上调用。 */
    internal fun onTurnEnding(conversation: Conversation, text: String) =
        alerts.turnEnding(conversation, text)

    /**
     * 一轮结束了：切走的对话闲下来就收掉，提醒交给 [alerts]，会话列表里这个会话重读。[stopped]：这一轮是用户叫停的；[text]
     * 是结束的那一句（“已完成”“出错了”“已中断”）。
     */
    internal fun onTurnFinished(
        conversation: Conversation,
        stopped: Boolean = false,
        text: String = "已完成",
    ) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
        alerts.turnFinished(conversation, stopped, text)
        // 侧栏列的是别的主机的话，这一轮没改到它
        if (conversation.backend != backend) return
        val session = conversation.sessionId
        if (session != null) sessionList.touched(session) else sessionList.refresh()
    }

    /** 闲着的对话最后一个后台任务也结束了（这一轮早已结束）。 */
    internal fun onBackgroundTasksDone(conversation: Conversation) {
        if (conversation !== current) retireWhenIdle(conversation) else retireCurrentWhenIdle()
    }

    /**
     * Zeta 不在前台时，当前对话闲着（[Conversation.active] 为假）满 [IDLE_RETIRE_MS] 就收掉它背后的 Claude Code 进程，
     * 内容不丢，下次发消息时用 --resume 接上。不收的话，这个进程和为它常驻的前台服务（[onActivityChanged]）互相保着， 用过一次 Zeta 就一直占着几百 MB
     * 内存和一条常驻通知。切到后台、一轮结束、后台任务做完时重新计时，回到前台就不等了。 到点时还在进行的不收，等它这一轮结束再计时。
     */
    private val retireCurrent = Runnable { if (!foreground && !current.active) current.shutdown() }

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
                    conversation.shutdown()
                    prunePhones()
                }
            },
            RETIRE_DELAY_MS,
        )
    }

    /** 对话等着用户回答的请求变了（来了、处理掉了、撤回了）：提醒交给 [alerts]，胶囊和实时通知跟着更新。在主线程上调用。 */
    internal fun onPendingChanged(conversation: Conversation) {
        alerts.pendingChanged(conversation)
        onActivityChanged()
    }

    /** 有进程活着就保持前台服务，否则系统会在切到后台后很快回收本应用，连带结束 Claude Code。 */
    internal fun onActivityChanged() {
        val working = environment.working
        val active = working || liveConversations.any { it.isProcessAlive }
        // 后台任务跑着也要持有唤醒锁，否则息屏休眠时任务会被挂起；和远程主机断了连接、正在重连的不算（那边照样在跑）
        val busy = working || liveConversations.any { it.active && !it.offline }
        val answering = liveConversations.filter { it.busy }
        val running = answering.map { conversation ->
            conversation.title.ifBlank { "新对话" } +
                (conversation.phone.agentDisplayApp?.let { " · 在后台操作$it" } ?: "")
        }
        AgentService.update(app, active, busy, running, alerts.liveStatus(answering))
    }

    /** 通知栏的“停止”：打断所有正在回答的对话，和聊天页的停止键一样。 */
    internal fun interruptAll() {
        liveConversations.filter { it.busy }.forEach { it.interrupt() }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
        private const val RETIRE_DELAY_MS = 5_000L

        /** 和 zeta_host.sh 的看门狗一样：没人看着、闲着的进程约十分钟后结束。 */
        internal const val IDLE_RETIRE_MS = 10 * 60_000L

        /** 全应用最多同时留几块后台屏幕：每块都连着一个 root 辅助进程和屏上开着的应用，太多了内存吃不消。 */
        private const val MAX_SCREENS = 3

        @Volatile private var instance: ConversationHub? = null

        /** Zeta 直连用的公钥那一行（authorized_keys 的写法），在应用私有目录 files/ 下；密钥第一次用时写出。 */
        const val PUBLIC_KEY_FILE = "zeta_ssh.pub"

        fun get(app: Application): ConversationHub =
            instance
                ?: synchronized(this) {
                    instance ?: ConversationHub(app).also { instance = it }
                }
    }
}
