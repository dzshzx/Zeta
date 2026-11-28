package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.diag.DiagEvents
import com.dzshzx.zeta.i18n.Strings
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

enum class SetupState {
    Checking,
    NoRoot,
    NoEnvironment,
    Ready,
}

/**
 * Linux 环境这一摊：检查 root 和环境、识别 Linux 用户、安装、修复与可选工具、SSH 服务、Claude 账号的登录状态。
 * 状态都可观察，在主线程上读写；查询和命令在后台线程上跑，结果投递回主线程。 和对话有关的事交回 [hub]：环境就绪后读会话列表、接回进程，换环境前收掉对话。
 */
class EnvController constructor(private val hub: ConversationHub, assets: (String) -> String) :
    LocalEnvironment {
    override val ready: Boolean
        get() = setup == SetupState.Ready

    override val loggedOut: Boolean
        get() = account?.loggedIn == false

    override fun showAccount(email: String, plan: String) {
        account = AccountStatus(loggedIn = true, email = email, plan = plan)
    }

    override fun showRelay(name: String, baseUrl: String) {
        account = AccountStatus.relay(name, baseUrl)
    }

    override fun showLoggedOut() {
        account = AccountStatus(loggedIn = false, email = null, plan = null)
    }

    private val settings
        get() = hub.settings

    private val main = MainHandler()

    var setup by mutableStateOf(SetupState.Checking)

    /** 还没识别出环境时，设置页里显示和下次检测要用的 rootfs 路径。 */
    var rootfsHint by mutableStateOf(LinuxEnv.OWN_ROOTFS)
        private set

    /** 重新检测前设着的默认工作目录：新识别出的环境里还有这个目录就接着用，见 [checkSetup]。 */
    private var keptCwd: String? = null

    /** 安装 Debian 环境、登录 Claude 账号。 */
    val installer = EnvInstaller(hub, assets)
    private val sshService = SshService(installer, assets)

    /** Zeta 自带环境的磁盘镜像（设置 › Linux 环境 › 磁盘镜像）。 */
    val image = DiskImageController(hub, assets)

    /** 当前环境里 SSH 服务的状态；还没查过是 null。 */
    var ssh by mutableStateOf<SshStatus?>(null)

    var sshWorking by mutableStateOf(false)
        private set

    /** Zeta 自带环境那个位置的安装情况；null 表示还没查过。 */
    var ownStatus by mutableStateOf<EnvStatus?>(null)
        private set

    /** 当前环境里 Claude Code 的登录状态；null 表示还没查过。 */
    var account by mutableStateOf<AccountStatus?>(null)

    /** 当前环境里 Antigravity CLI 的登录状态；null 表示还没查过。 */
    var agyAccount by mutableStateOf<AccountStatus?>(null)

    fun account(harness: String): AccountStatus? =
        if (Harnesses.normalize(harness) == Harnesses.AGY) agyAccount else account

    var health by mutableStateOf<EnvHealth?>(null)

    var healthChecking by mutableStateOf(false)
        private set

    /** 正在进行或刚做完的一项维护（修复、更新 Claude Code）。 */
    data class Maintenance(
        val title: String,
        val running: Boolean,
        val detail: String,
        val ok: Boolean = true,
    )

    var maintenance by mutableStateOf<Maintenance?>(null)

    /** 在装环境或做维护：前台服务据此保持。 */
    override val working: Boolean
        get() = installer.running || maintenance?.running == true

    override fun checkSetup() {
        setup = SetupState.Checking
        thread(name = "zeta-setup", isDaemon = true) {
            val saved = settings.env
            val state = detectSetupState(saved)
            val own = installer.status()
            DiagEvents.setupChecked(
                state.name,
                settings.env?.rootfs ?: saved?.rootfs,
                settings.env?.user,
            )
            main.post {
                setup = state
                ownStatus = own
                if (state == SetupState.Ready) onSetupReady(own)
            }
        }
    }

    private fun detectSetupState(saved: LinuxEnvConfig?): SetupState =
        when {
            !LinuxEnv.hasRoot() -> SetupState.NoRoot
            // 环境可能已经不在了（比如被删掉），不能只凭存下来的设置就当它可用
            saved != null ->
                if (LinuxEnv.exists(saved.rootfs)) SetupState.Ready else SetupState.NoEnvironment
            else -> detectFreshEnv()
        }

    private fun detectFreshEnv(): SetupState {
        val detected = LinuxEnv.detect(rootfsHint) ?: return SetupState.NoEnvironment
        // 默认工作目录：第一次配环境时是用户的家目录（detect 给的）；重新检测前设过的，新环境里还有这个目录就不变
        val kept = keptCwd?.takeIf { cwd ->
            cwd != detected.defaultCwd &&
                runCatching {
                        LinuxEnv.run(detected, "[ -d ${LinuxEnv.quote(cwd)} ]").exitCode == 0
                    }
                    .getOrDefault(false)
        }
        settings.env = if (kept != null) detected.copy(defaultCwd = kept) else detected
        return SetupState.Ready
    }

    private fun onSetupReady(own: EnvStatus) {
        if (own.agy && !own.claude && !settings.hasDefaultHarness(Backends.LOCAL)) {
            settings.setDefaultHarness(Backends.LOCAL, Harnesses.AGY)
        }
        // 顺带把磁盘上的镜像脚本换成这个版本的
        image.refresh()
        refreshAccount()
        hub.onEnvironmentReady()
    }

    /** 换一个 rootfs（或原地重来）：丢掉已保存的环境，重新检查 root 并识别用户，默认工作目录在那边还有就留着。有对话在进行时只记下路径。 */
    fun redetect(rootfs: String) {
        rootfsHint = rootfs
        if (hub.conversationsWorking) return
        settings.env?.let { keptCwd = it.defaultCwd }
        settings.env = null
        account = null
        agyAccount = null
        health = null
        ssh = null
        hub.dropConversations()
        checkSetup()
    }

    /** 有对话正在进行（或还有后台任务在跑）时不能换环境。 */
    val canSwitchEnvironment: Boolean
        get() = !hub.conversationsWorking && !installer.running

    /** 环境装好了：还没有可用环境就直接用它，已经在用别的环境则由用户自己决定要不要切过来。 */
    fun onEnvironmentInstalled() {
        thread(isDaemon = true) {
            val own = installer.status()
            main.post {
                ownStatus = own
                if (setup != SetupState.Ready || settings.env?.rootfs == LinuxEnv.OWN_ROOTFS)
                    redetect(LinuxEnv.OWN_ROOTFS)
            }
        }
    }

    override fun refreshAccount() {
        val config = settings.env ?: return
        thread(name = "zeta-account", isDaemon = true) {
            val shown = readClaudeAccount(config)
            main.post {
                if (settings.env?.rootfs == config.rootfs && shown != null) account = shown
            }
        }
        thread(name = "zeta-agy-account", isDaemon = true) {
            val agyShown = readAgyAccount()
            main.post {
                if (settings.env?.rootfs == config.rootfs && agyShown != null) {
                    agyAccount = agyShown
                }
            }
        }
    }

    private fun readClaudeAccount(config: LinuxEnvConfig): AccountStatus? {
        val accounts = hub.accounts(Backends.LOCAL, Harnesses.CLAUDE)
        val result =
            runCatching { LinuxEnv.run(config, "exec claude auth status", timeoutMs = 60_000) }
                .getOrNull() ?: return null
        val o =
            runCatching { json.parseToJsonElement(result.stdout) as? JsonObject }.getOrNull()
                ?: return null
        val listed = runCatching { accounts.core.list() }.getOrNull()
        val status = AccountStatus.fromAuthStatus(o, listed?.current)
        val relay = if (!status.loggedIn) listed?.relay else null
        return relay?.let { AccountStatus.relay(it.name, it.baseUrl) } ?: status
    }

    private fun readAgyAccount(): AccountStatus? {
        val state =
            runCatching { hub.accounts(Backends.LOCAL, Harnesses.AGY).core.list() }.getOrNull()
                ?: return null
        return state.relay?.let { AccountStatus.relay(it.name, it.baseUrl) }
            ?: AccountStatus(
                loggedIn = state.loggedIn,
                email = state.current?.email?.ifBlank { null },
                plan = state.current?.plan?.ifBlank { null },
            )
    }

    fun checkHealth() {
        val config = settings.env ?: return
        if (healthChecking) return
        healthChecking = true
        thread(name = "zeta-health", isDaemon = true) {
            val result = installer.health(config)
            main.post {
                healthChecking = false
                health = result
            }
        }
    }

    private fun runMaintenance(
        title: String,
        asRoot: Boolean,
        command: String,
        after: () -> Unit = {},
    ) {
        val config = settings.env ?: return
        if (maintenance?.running == true) return
        maintenance = Maintenance(title, running = true, detail = "")
        hub.onActivityChanged()
        thread(name = "zeta-maintain", isDaemon = true) {
            val (ok, output) =
                installer.maintain(config, asRoot, command) { line ->
                    main.post { maintenance = Maintenance(title, running = true, detail = line) }
                }
            main.post {
                maintenance =
                    Maintenance(
                        title,
                        running = false,
                        detail = output.lines().takeLast(4).joinToString("\n"),
                        ok = ok,
                    )
                hub.onActivityChanged()
                after()
            }
        }
    }

    /** 修复软件包并补齐常用工具。 */
    fun repairEnvironment() =
        runMaintenance(
            Strings.coreMaintainRepair(),
            asRoot = true,
            command = installer.repairCommand,
        ) {
            checkHealth()
        }

    fun updateClaude() =
        runMaintenance(
            Strings.coreMaintainUpdateClaude(),
            asRoot = false,
            command = "exec claude update",
        ) {
            checkHealth()
        }

    fun installClaude() =
        runMaintenance(
            Strings.coreMaintainInstallClaude(),
            asRoot = false,
            command = installer.optionalCommand("claude"),
        ) {
            refreshOwnStatus()
            checkHealth()
            refreshAccount()
        }

    fun installAgy() =
        runMaintenance(
            Strings.coreMaintainInstallAgy(),
            asRoot = false,
            command = installer.optionalCommand("install-agy"),
        ) {
            refreshOwnStatus()
            checkHealth()
            refreshAccount()
        }

    fun updateAgy() =
        runMaintenance(
            Strings.coreMaintainUpdateAgy(),
            asRoot = false,
            command = installer.optionalCommand("install-agy"),
        ) {
            checkHealth()
        }

    private fun refreshOwnStatus() {
        thread(isDaemon = true) {
            val own = installer.status()
            main.post { ownStatus = own }
        }
    }

    fun refreshSsh() {
        val config = settings.env ?: return
        thread(name = "zeta-ssh", isDaemon = true) {
            val status = sshService.status(config)
            main.post { if (settings.env?.rootfs == config.rootfs) ssh = status }
        }
    }

    /** 启动、停止、开机自启这类很快的操作；不成功时把原因放进维护卡片。 */
    private fun sshAction(title: String, action: (LinuxEnvConfig) -> String?) {
        val config = settings.env ?: return
        if (sshWorking) return
        sshWorking = true
        thread(name = "zeta-ssh", isDaemon = true) {
            val problem = runCatching {
                action(config)
            }
                .getOrElse { it.message ?: Strings.coreFailed() }
            val status = sshService.status(config)
            main.post {
                sshWorking = false
                ssh = status
                if (problem != null)
                    maintenance = Maintenance(title, running = false, detail = problem, ok = false)
            }
        }
    }

    fun startSsh() = sshAction(Strings.coreSshStart()) { sshService.start(it) }

    fun stopSsh() = sshAction(Strings.coreSshStop()) { sshService.stop(it) }

    fun setSshAutostart(on: Boolean) =
        sshAction(Strings.coreSshAutostart()) { sshService.autostart(it, on) }

    fun setupSsh() {
        val user = settings.env?.user ?: return
        runMaintenance(
            Strings.coreSshSetup(),
            asRoot = true,
            command = installer.optionalCommand("ssh-setup") + " " + LinuxEnv.quote(user),
        ) {
            refreshSsh()
        }
    }

    fun addSshKey(key: String) {
        val user = settings.env?.user ?: return
        val line = key.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return
        runMaintenance(
            Strings.coreSshAddKey(),
            asRoot = true,
            command =
                installer.optionalCommand("ssh-addkey") +
                    " " +
                    LinuxEnv.quote(user) +
                    " " +
                    LinuxEnv.quote(line),
        ) {
            refreshSsh()
        }
    }

    /** 安装一样可选工具（go / node / python）；已经是 Zeta 装的则更新到最新版。 */
    fun installOptional(name: String) =
        runMaintenance(
            title =
                if (name in health?.zetaOwned.orEmpty())
                    Strings.coreOptionalUpdate(optionalName(name))
                else Strings.coreOptionalInstall(optionalName(name)),
            asRoot = name != "python",
            command = installer.optionalCommand(name),
        ) {
            checkHealth()
        }

    /** 卸载一样 Zeta 装的可选工具。别处装的脚本会拒绝。 */
    fun removeOptional(name: String) =
        runMaintenance(
            title = Strings.coreOptionalRemove(optionalName(name)),
            asRoot = name != "python",
            command = installer.optionalCommand("remove") + " " + name,
        ) {
            checkHealth()
        }

    fun logout() =
        runMaintenance(
            Strings.coreMaintainLogout(),
            asRoot = false,
            command = "exec claude auth logout",
        ) {
            refreshAccount()
        }

    fun dismissMaintenance() {
        if (maintenance?.running == false) maintenance = null
    }

    /** 改默认工作目录：还没开始的新对话跟着换过去。 */
    fun setDefaultCwd(cwd: String) {
        val updated = settings.env?.copy(defaultCwd = cwd) ?: return
        settings.env = updated
        hub.renewBlank()
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

/** 可选工具在界面上的名字。 */
fun optionalName(name: String): String =
    when (name) {
        "go" -> "Go"
        "node" -> "Node.js"
        "apk" -> Strings.coreOptionalApk()
        else -> "Python / uv"
    }
