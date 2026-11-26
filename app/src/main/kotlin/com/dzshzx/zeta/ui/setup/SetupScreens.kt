package com.dzshzx.zeta.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AccountStatus
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DiskImageStatus
import com.dzshzx.zeta.core.EnvController
import com.dzshzx.zeta.core.EnvHealth
import com.dzshzx.zeta.core.EnvInstaller
import com.dzshzx.zeta.core.EnvStatus
import com.dzshzx.zeta.core.InstallStep
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.LinuxEnvConfig
import com.dzshzx.zeta.core.LoginState
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.SshStatus
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.core.optionalName
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference
import com.dzshzx.zeta.ui.components.miuix.FieldGroup
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.miuix.TextInputDialog
import com.dzshzx.zeta.ui.settings.ValueRow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun locationName(rootfs: String?): String =
    when (rootfs) {
        null -> Strings.setupLocationNone()
        LinuxEnv.OWN_ROOTFS -> Strings.setupLocationOwn()
        else -> Strings.setupLocationCustom()
    }

private enum class LinuxEdit {
    Rootfs,
    Cwd,
}

/** Linux 环境的管理页：用哪个环境、安装、体检与修复、Claude Code 的版本和账号。 */
@Composable
internal fun LinuxScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenLogin: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenImage: () -> Unit,
) {
    val env = hub.settings.env
    val ready = hub.environment.setup == SetupState.Ready && env != null
    var editing by remember { mutableStateOf<LinuxEdit?>(null) }
    var lastEditing by remember { mutableStateOf(LinuxEdit.Rootfs) }
    var draft by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var lastRemoving by remember { mutableStateOf("go") }
    removing?.let { lastRemoving = it }
    var filesPage by rememberSaveable { mutableStateOf<LinuxFilesPage?>(null) }
    val own = hub.environment.ownStatus
    val maintenance = hub.environment.maintenance
    val current = env?.rootfs ?: hub.environment.rootfsHint
    LaunchedEffect(ready, env?.rootfs) { if (ready) loadMissingStatus(hub.environment) }
    val edit: (LinuxEdit, String) -> Unit = { kind, value ->
        draft = value
        lastEditing = kind
        editing = kind
    }

    MiuixPage(
        title = Strings.setupLinuxTitle(),
        onBack = onBack,
        dialogs = {
            LinuxEditDialog(
                show = editing != null,
                kind = lastEditing,
                draft = draft,
                onDraftChange = { draft = it },
                home = env?.home,
                onConfirm = {
                    val value = draft.trim()
                    when (editing) {
                        LinuxEdit.Rootfs -> hub.environment.redetect(value)
                        LinuxEdit.Cwd -> hub.environment.setDefaultCwd(value)
                        null -> Unit
                    }
                    editing = null
                },
                onDismiss = { editing = null },
            )
            ConfirmDialog(
                show = removing != null,
                title = Strings.setupRemoveOptionalTitle(optionalName(lastRemoving)),
                summary = Strings.setupRemoveOptionalSummary(removeOptionalSummary(lastRemoving)),
                confirmText = Strings.setupUninstall(),
                destructive = true,
                onConfirm = {
                    removing?.let(hub.environment::removeOptional)
                    removing = null
                },
                onDismissRequest = { removing = null },
            )
            ConfirmDialog(
                show = confirmLogout,
                title = Strings.setupLogout(),
                summary = Strings.setupLogoutSummary(),
                confirmText = Strings.setupLogoutConfirm(),
                onConfirm = {
                    confirmLogout = false
                    hub.environment.logout()
                },
                onDismissRequest = { confirmLogout = false },
            )
        },
    ) {
        // Zeta 经 root 进入这个 Debian 环境，以其中的普通用户（运行用户）身份运行 Claude Code
        item(key = "env") { EnvGroup(hub, env, current, onEdit = edit) }

        if (own?.complete != true) {
            // 装在 LinuxEnv.OWN_BASE，不在任何应用的数据目录里，卸载 Zeta 或别的应用都不会把它删掉
            item(key = "install") {
                InstallGroup(hub.environment.installer.running, own, onOpenInstall)
            }
        }

        if (ready) {
            readyItems(
                hub = hub,
                maintenanceRunning = maintenance?.running == true,
                onRemove = { removing = it },
                onOpenFiles = { filesPage = it },
                onOpenSsh = onOpenSsh,
                onOpenImage = onOpenImage,
                onOpenLogin = onOpenLogin,
                onLogout = { confirmLogout = true },
            )
        }

        if (maintenance != null) {
            item(key = "maintenance") {
                MaintenanceGroup(maintenance, onDismiss = hub.environment::dismissMaintenance)
            }
        }
    }
    LinuxFilesPages(hub, page = filesPage.takeIf { ready }, onClose = { filesPage = null })
}

/** 环境可用后，体检结果和 SSH 状态还没有的先查一次。 */
private fun loadMissingStatus(environment: EnvController) {
    if (environment.health == null) environment.checkHealth()
    if (environment.ssh == null) environment.refreshSsh()
}

/** 改 Debian 根目录或默认工作目录的输入框；[kind] 是最近一次打开的那种，关闭动画期间文案不变。 */
@Composable
private fun LinuxEditDialog(
    show: Boolean,
    kind: LinuxEdit,
    draft: String,
    onDraftChange: (String) -> Unit,
    home: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rootfs = kind == LinuxEdit.Rootfs
    TextInputDialog(
        show = show,
        title = if (rootfs) Strings.setupCustomPath() else Strings.setupDefaultCwd(),
        summary = if (rootfs) Strings.setupRootfsHint() else Strings.setupCwdHint(),
        value = draft,
        onValueChange = onDraftChange,
        label = if (rootfs) LinuxEnv.OWN_ROOTFS else home ?: "/workspace",
        confirmText = Strings.setupOk(),
        confirmEnabled = draft.trim().startsWith("/"),
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
    )
}

/** 卸载一样可选工具前说明会删掉什么、留下什么。 */
private fun removeOptionalSummary(name: String): String =
    when (name) {
        "go" -> Strings.setupRemoveGo()
        "node" -> Strings.setupRemoveNode()
        "apk" -> Strings.setupRemoveApk()
        else -> Strings.setupRemovePython()
    }

private fun setupStateText(state: SetupState): String =
    when (state) {
        SetupState.Checking -> Strings.setupStateChecking()
        SetupState.NoRoot -> Strings.setupStateNoRoot()
        SetupState.NoEnvironment -> Strings.setupStateNoEnv()
        SetupState.Ready -> Strings.setupStateReady()
    }

/** 自带环境还没装完时的入口。 */
@Composable
private fun InstallGroup(running: Boolean, own: EnvStatus?, onOpenInstall: () -> Unit) {
    PreferenceGroup(footer = Strings.setupInstallFooter()) {
        item {
            ArrowPreference(
                title =
                    when {
                        running -> Strings.setupInstallRunning()
                        own?.base == true -> Strings.setupInstallContinueDebian()
                        else -> Strings.setupInstallDebian()
                    },
                summary = Strings.setupInstallPitch(),
                onClick = onOpenInstall,
            )
        }
    }
}

/** 「环境」一组：状态、位置、路径、运行用户、默认工作目录、重新检测。 */
@Composable
private fun EnvGroup(
    hub: ConversationHub,
    env: LinuxEnvConfig?,
    current: String,
    onEdit: (LinuxEdit, String) -> Unit,
) {
    PreferenceGroup(title = Strings.setupEnvGroup(), footer = Strings.setupEnvFooter()) {
        item {
            ValueRow(Strings.setupStatus(), setupStateText(hub.environment.setup))
        }
        item {
            val choose: (String) -> Unit = { target ->
                if (target != env?.rootfs) hub.environment.redetect(target)
            }
            DropdownValuePreference(
                title = Strings.setupLocation(),
                value = locationName(current),
                // 有对话正在进行时不能切换
                enabled = hub.environment.canSwitchEnvironment,
                entries =
                    listOf(
                        MenuEntry(
                            items =
                                listOf(
                                    MenuItem(
                                        text = Strings.setupLocationOwn(),
                                        selected = current == LinuxEnv.OWN_ROOTFS,
                                        onClick = { choose(LinuxEnv.OWN_ROOTFS) },
                                    ),
                                    MenuItem(
                                        text = Strings.setupCustomPathMenu(),
                                        selected = current != LinuxEnv.OWN_ROOTFS,
                                        onClick = { onEdit(LinuxEdit.Rootfs, current) },
                                    ),
                                ),
                        ),
                    ),
            )
        }
        item { BasicComponent(title = Strings.setupPath(), summary = current) }
        if (env != null) {
            item {
                ValueRow(Strings.setupRunUser(), "${env.user} (${env.uid})")
            }
            item {
                BasicComponent(
                    title = Strings.setupDefaultCwd(),
                    summary = env.defaultCwd,
                    onClick = { onEdit(LinuxEdit.Cwd, env.defaultCwd) },
                )
            }
        }
        item {
            BasicComponent(
                title = Strings.setupRedetect(),
                summary = Strings.setupRedetectSummary(),
                onClick = { hub.environment.redetect(current) },
            )
        }
    }
}

/** 环境可用时才有的几组：体检、可选工具、文件与远程访问、Claude Code。 */
private fun LazyListScope.readyItems(
    hub: ConversationHub,
    maintenanceRunning: Boolean,
    onRemove: (String) -> Unit,
    onOpenFiles: (LinuxFilesPage) -> Unit,
    onOpenSsh: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val health = hub.environment.health
    item(key = "health") { HealthGroup(hub, health, maintenanceRunning) }
    // 基础环境里已经有 git、ripgrep、python3 这些常用工具，这里是按需再装的。环境里原来就有的（不是 Zeta 装的）只显示版本。
    // Go 顺带配好 ~/go/bin；Python 是 uv 管理的最新版，带 pip；Node.js 的全局包装在 ~/.npm-global；
    // APK 分析包括 jadx、apktool、smali/baksmali，环境里没有 Java 时一并装上 OpenJDK。
    item(key = "optional") {
        PreferenceGroup(
            title = Strings.setupOptionalTools(),
            footer = Strings.setupOptionalFooter(),
        ) {
            listOf(
                    "go" to Strings.setupPitchGo(),
                    "python" to Strings.setupPitchPython(),
                    "node" to Strings.setupPitchNode(),
                    "apk" to Strings.setupPitchApk(),
                )
                .forEach { (name, pitch) ->
                    item(key = name) {
                        OptionalToolRow(
                            name = name,
                            pitch = pitch,
                            health = health,
                            enabled = !maintenanceRunning && health != null,
                            onInstall = { hub.environment.installOptional(name) },
                            onRemove = { onRemove(name) },
                        )
                    }
                }
        }
    }
    item(key = "files") {
        FilesGroup(
            ssh = hub.environment.ssh,
            // 磁盘镜像只管 Zeta 自带的环境
            image =
                hub.environment.image.status.takeIf {
                    hub.settings.env?.rootfs == LinuxEnv.OWN_ROOTFS
                },
            onOpenFiles = onOpenFiles,
            onOpenSsh = onOpenSsh,
            onOpenImage = onOpenImage,
        )
    }
    item(key = "claude") {
        ClaudeGroup(hub, health, maintenanceRunning, onOpenLogin = onOpenLogin, onLogout = onLogout)
    }
}

/** 「体检」一组：检查环境的结果和修复。 */
@Composable
private fun HealthGroup(hub: ConversationHub, health: EnvHealth?, maintenanceRunning: Boolean) {
    PreferenceGroup(title = Strings.setupHealth()) {
        item {
            BasicComponent(
                title = Strings.setupCheckEnv(),
                summary = healthSummary(hub.environment.healthChecking, health),
                onClick = hub.environment::checkHealth,
            )
        }
        item {
            BasicComponent(
                title = Strings.setupRepair(),
                summary = Strings.setupRepairSummary(),
                enabled = !maintenanceRunning,
                onClick = hub.environment::repairEnvironment,
            )
        }
    }
}

private fun healthSummary(checking: Boolean, health: EnvHealth?): String =
    when {
        checking -> Strings.setupCheckingEllipsis()
        health == null -> Strings.setupHealthIdle()
        else -> healthFindings(health).joinToString(" · ")
    }

/** 体检结果的各条：先列问题（没有就是环境正常），再是 Debian 版本和剩余空间。 */
private fun healthFindings(health: EnvHealth): List<String> = buildList {
    if (health.missing.isNotEmpty())
        add(Strings.setupHealthMissing(health.missing.joinToString(Strings.setupListSeparator())))
    if (!health.workspaceWritable) add(Strings.setupHealthCwdNotWritable())
    if (!health.network) add(Strings.setupHealthNoNetwork())
    if (isEmpty()) add(Strings.setupHealthOk())
    if (health.debian.isNotBlank()) add("Debian ${health.debian}")
    add(Strings.setupHealthFree(formatBytes(health.freeBytes)))
}

/** 「文件与远程访问」一组：文件浏览、共享文件夹、SSH 服务。 */
@Composable
private fun FilesGroup(
    ssh: SshStatus?,
    image: DiskImageStatus?,
    onOpenFiles: (LinuxFilesPage) -> Unit,
    onOpenSsh: () -> Unit,
    onOpenImage: () -> Unit,
) {
    PreferenceGroup(title = Strings.setupFilesGroup()) {
        item {
            ArrowPreference(
                title = Strings.setupFileBrowser(),
                summary = Strings.setupFileBrowserSummary(),
                onClick = { onOpenFiles(LinuxFilesPage.Browser) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.setupSharedFolders(),
                summary = Strings.setupSharedFoldersSummary(),
                onClick = { onOpenFiles(LinuxFilesPage.Shared) },
            )
        }
        item {
            ArrowPreference(
                title = Strings.setupSshTitle(),
                summary = sshSummary(ssh),
                onClick = onOpenSsh,
            )
        }
        if (image != null) {
            item {
                ArrowPreference(
                    title = Strings.setupImageTitle(),
                    summary = diskImageSummary(image),
                    onClick = onOpenImage,
                )
            }
        }
    }
}

private fun sshSummary(ssh: SshStatus?): String =
    when {
        ssh == null -> Strings.setupSshSummaryIdle()
        !ssh.installed -> Strings.setupNotInstalled()
        ssh.running ->
            Strings.setupSshRunningAt(ssh.listen.ifBlank { Strings.setupSshPort(ssh.port) })
        else -> Strings.setupSshNotRunning()
    }

/** 「Claude Code」一组：版本、更新、账号、退出登录。 */
@Composable
private fun ClaudeGroup(
    hub: ConversationHub,
    health: EnvHealth?,
    maintenanceRunning: Boolean,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val account = hub.environment.account
    PreferenceGroup(title = "Claude Code") {
        item {
            ValueRow(
                Strings.setupVersion(),
                health?.claude?.ifBlank { Strings.setupNotInstalled() } ?: "…",
            )
        }
        item {
            BasicComponent(
                title = Strings.setupUpdate(),
                // 运行 claude update
                summary = Strings.setupUpdateLatest(),
                enabled = !maintenanceRunning,
                onClick = hub.environment::updateClaude,
            )
        }
        item {
            ArrowPreference(
                title = Strings.setupAccount(),
                summary = accountSummary(account),
                onClick = onOpenLogin,
            )
        }
        // 在用中转时没有登录着的账号，退出登录无从谈起；换回账号在账号页
        if (account?.loggedIn == true && account.relay == null) {
            item {
                BasicComponent(
                    title = Strings.setupLogout(),
                    enabled = !maintenanceRunning,
                    onClick = onLogout,
                )
            }
        }
    }
}

private fun accountSummary(account: AccountStatus?): String =
    when {
        account == null -> Strings.setupQuerying()
        account.relay != null -> Strings.settingsAccountsRelayEntry(account.relay)
        !account.loggedIn -> Strings.setupNotLoggedIn()
        else ->
            listOfNotNull(account.email, account.plan).joinToString(" · ").ifBlank {
                Strings.setupLoggedIn()
            }
    }

/** 正在做或刚做完的维护（修复、更新、装卸可选工具、SSH 的操作）：做完了点一下收起。 */
@Composable
internal fun MaintenanceGroup(maintenance: EnvController.Maintenance, onDismiss: () -> Unit) {
    PreferenceGroup(title = maintenance.title) {
        item {
            BasicComponent(
                title =
                    when {
                        maintenance.running -> Strings.setupMaintenanceRunning()
                        maintenance.ok -> Strings.setupMaintenanceDone()
                        else -> Strings.setupMaintenanceFailed()
                    },
                summary = maintenance.detail.ifBlank { null },
                onClick = if (maintenance.running) null else onDismiss,
            )
        }
    }
}

/** 安装向导：列出每一步的状态，失败了可以原地重试（接着没做完的那一步）。 */
@Composable
internal fun EnvInstallScreen(hub: ConversationHub, onBack: () -> Unit, onOpenLogin: () -> Unit) {
    val installer = hub.environment.installer
    val own = hub.environment.ownStatus
    var userName by rememberSaveable { mutableStateOf("zeta") }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    val existingUser = own?.user
    val nameValid = Regex("[a-z_][a-z0-9_-]{0,30}").matches(userName)
    val complete = installer.finished || own?.complete == true

    MiuixPage(
        title = Strings.setupInstallDebian(),
        onBack = onBack,
        dialogs = {
            TextInputDialog(
                show = editingName,
                title = Strings.setupUserName(),
                summary = Strings.setupUserNameHint(),
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = "zeta",
                confirmText = Strings.setupOk(),
                confirmEnabled = Regex("[a-z_][a-z0-9_-]{0,30}").matches(nameDraft.trim()),
                onConfirm = {
                    userName = nameDraft.trim()
                    editingName = false
                },
                onDismissRequest = { editingName = false },
            )
        },
    ) {
        item(key = "prepare") {
            PreferenceGroup(
                title = Strings.setupInstallPrepare(),
                // 装在 LinuxEnv.OWN_BASE
                footer = Strings.setupInstallPrepareFooter(),
            ) {
                item {
                    ValueRow(
                        Strings.setupUserName(),
                        existingUser ?: userName,
                        summary = Strings.setupUserNameSummary(),
                        // 已有用户、正在装或装完时只读
                        onClick =
                            if (existingUser == null && !installer.running && !complete) {
                                {
                                    nameDraft = userName
                                    editingName = true
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }
        item(key = "steps") {
            PreferenceGroup(title = Strings.setupInstallSteps()) {
                for (step in InstallStep.entries) {
                    item(key = step) { InstallStepRow(step, installer, complete) }
                }
            }
        }
        installer.error?.let { message ->
            item(key = "error") { Notice(message, error = true) }
        }
        item(key = "actions") {
            InstallActions(
                hub = hub,
                complete = complete,
                startEnabled = nameValid || existingUser != null,
                onStart = { installer.start(existingUser ?: userName) },
                onOpenLogin = onOpenLogin,
                onBack = onBack,
            )
        }
    }
}

/** 安装的一步：完成、进度（正在做的那步）、失败或空着。 */
@Composable
private fun InstallStepRow(step: InstallStep, installer: EnvInstaller, complete: Boolean) {
    val current = installer.running && installer.step == step
    val value = installStepValue(step, installer, complete, current)
    BasicComponent(
        title = step.title,
        summary = if (current) installer.detail.ifBlank { null } else null,
        endActions = if (value.isEmpty()) null else ({ PreferenceValue(value, clickable = false) }),
    )
}

private fun installStepValue(
    step: InstallStep,
    installer: EnvInstaller,
    complete: Boolean,
    current: Boolean,
): String =
    when {
        step in installer.done || complete -> Strings.setupStepDone()
        current ->
            installer.progress?.let { "${(it * 100).toInt()}%" } ?: Strings.setupStepRunning()
        installer.error != null && installer.step == step -> Strings.setupStepFailed()
        else -> ""
    }

/** 安装页底部的按钮：安装中是取消；装完是切换环境或登录，再加完成；其余是开始或继续安装。 */
@Composable
private fun InstallActions(
    hub: ConversationHub,
    complete: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onOpenLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val installer = hub.environment.installer
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            installer.running ->
                TextButton(
                    text = Strings.setupCancel(),
                    onClick = installer::cancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            complete -> {
                if (hub.settings.env?.rootfs != LinuxEnv.OWN_ROOTFS) {
                    PrimaryButton(
                        text = Strings.setupSwitchToEnv(),
                        onClick = { hub.environment.redetect(LinuxEnv.OWN_ROOTFS) },
                        enabled = hub.environment.canSwitchEnvironment,
                    )
                } else if (hub.environment.account?.loggedIn != true) {
                    PrimaryButton(text = Strings.setupSignInClaude(), onClick = onOpenLogin)
                }
                TextButton(
                    text = Strings.setupFinish(),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else ->
                PrimaryButton(
                    text =
                        if (installer.error != null || hub.environment.ownStatus?.base == true)
                            Strings.setupContinueInstall()
                        else Strings.setupStartInstall(),
                    onClick = onStart,
                    enabled = startEnabled,
                )
        }
    }
}

/** 主机 [backend] 上登录 Claude 账号：打开授权页，把页面给的授权码贴回来。登录命令由那台的核心跑（[com.dzshzx.zeta.core.LoginFlow]）。 */
@Composable
internal fun LoginScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val flow = hub.accounts(backend).login
    val state = flow.state
    val account = loginAccount(hub, backend)
    var code by remember { mutableStateOf("") }
    val leave = {
        flow.cancel()
        onBack()
    }
    // 远程设备的名字放在「当前状态」上：标题再加设备名就放不下了
    val statusTitle =
        if (backend == Backends.LOCAL) Strings.setupLoginStatus()
        else Strings.setupLoginStatusOn(hub.backendName(backend))
    MiuixPage(title = Strings.setupSignInClaude(), onBack = leave) {
        item(key = "status") { LoginStatusGroup(account, statusTitle) }
        when (state) {
            LoginState.Idle,
            is LoginState.Failed -> {
                if (state is LoginState.Failed)
                    item(key = "failed") { Notice(state.message, error = true) }
                item(key = "start") {
                    Buttons {
                        PrimaryButton(
                            text =
                                if (account?.loggedIn == true) Strings.setupLoginAnother()
                                else Strings.setupLoginStart(),
                            onClick = {
                                code = ""
                                flow.start()
                            },
                        )
                    }
                }
            }
            LoginState.Starting -> item(key = "starting") { Notice(Strings.setupLoginPreparing()) }
            is LoginState.WaitingCode,
            LoginState.Verifying ->
                loginCodeItems(
                    state = state,
                    code = code,
                    onCodeChange = {
                        code = it
                        flow.clearNotice()
                    },
                    onOpen = { openLoginPage(context, it) },
                    onCopy = { copyLoginLink(context, it) },
                    onSubmit = { flow.submit(code) },
                )
            LoginState.Done -> {
                item(key = "done") { Notice(Strings.setupLoginSuccess()) }
                item(key = "finish") {
                    Buttons { PrimaryButton(text = Strings.setupFinish(), onClick = leave) }
                }
            }
        }
    }
}

/** 登录页顶部「当前状态」读的账号：手机本机的是环境检查时查到的，远程主机的看那台的账号清单（没读过就读一次）。 */
@Composable
private fun loginAccount(hub: ConversationHub, backend: String): AccountStatus? {
    if (backend == Backends.LOCAL) return hub.environment.account
    val service = hub.accounts(backend)
    LaunchedEffect(backend) { if (service.state == null) service.refresh() }
    return service.state?.let { listed ->
        listed.relay?.let { AccountStatus.relay(it.name, it.baseUrl) }
            ?: AccountStatus(
                loggedIn = listed.loggedIn,
                email = listed.current?.email?.ifBlank { null },
                plan = listed.current?.plan?.ifBlank { null },
            )
    }
}

/** 「当前状态」一组：是否已登录，登录了就带上邮箱和套餐；在用中转时写中转名和地址。 */
@Composable
private fun LoginStatusGroup(account: AccountStatus?, title: String) {
    PreferenceGroup(title = title) {
        item {
            BasicComponent(
                title =
                    when {
                        account == null -> Strings.setupQuerying()
                        account.relay != null -> Strings.settingsAccountsRelayEntry(account.relay)
                        account.loggedIn -> Strings.setupLoggedIn()
                        else -> Strings.setupNotLoggedIn()
                    },
                summary =
                    account
                        ?.takeIf { it.loggedIn }
                        ?.let {
                            listOfNotNull(it.email, it.plan, it.relayUrl)
                                .joinToString(" · ")
                                .ifBlank {
                                    null
                                }
                        },
            )
        }
    }
}

/** 等授权码和验证中的两步：打开或复制登录页，贴回授权码后完成登录。 */
private fun LazyListScope.loginCodeItems(
    state: LoginState,
    code: String,
    onCodeChange: (String) -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val url = (state as? LoginState.WaitingCode)?.url
    item(key = "step1") {
        PreferenceGroup(
            title = Strings.setupLoginStep1(),
            footer = Strings.setupLoginStep1Footer(),
        ) {
            item {
                ArrowPreference(
                    title = Strings.setupLoginOpenPage(),
                    enabled = url != null,
                    onClick = { url?.let(onOpen) },
                )
            }
            item {
                BasicComponent(
                    title = Strings.setupLoginCopyLink(),
                    summary = Strings.setupLoginCopyLinkSummary(),
                    enabled = url != null,
                    onClick = { url?.let(onCopy) },
                )
            }
        }
    }
    item(key = "step2") {
        FieldGroup(title = Strings.setupLoginStep2()) {
            TextField(
                value = code,
                onValueChange = onCodeChange,
                label = Strings.setupLoginCodeHint(),
                enabled = state is LoginState.WaitingCode,
                showClearButton = true,
                singleLine = true,
            )
        }
    }
    (state as? LoginState.WaitingCode)?.notice?.let {
        item(key = "notice") { Notice(it, error = true) }
    }
    item(key = "submit") {
        Buttons {
            PrimaryButton(
                text =
                    if (state is LoginState.Verifying) Strings.setupLoginVerifying()
                    else Strings.setupLoginFinish(),
                onClick = onSubmit,
                enabled = state is LoginState.WaitingCode && code.isNotBlank(),
            )
        }
    }
}

private fun openLoginPage(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun copyLoginLink(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
        ClipData.newPlainText("login", url),
    )
}

/** 一段说明或报错，放在卡片之间。 */
@Composable
private fun Notice(text: String, error: Boolean = false) {
    if (error) FooterHint(summary = text, summaryColor = MiuixTheme.colorScheme.error)
    else FooterHint(summary = text)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        content()
    }
}

/** 页面底部的整行主按钮。 */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

/** 一样可选工具的一行。没装：点一下安装。Zeta 装的：弹出菜单，可以更新或卸载。 别处装的（环境里原来就有，或者手动装的）：只显示版本，不提供任何操作。 */
@Composable
private fun OptionalToolRow(
    name: String,
    pitch: String,
    health: EnvHealth?,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val title = optionalName(name)
    val version = health?.version(name).orEmpty()
    val label =
        when (name) {
            "python" -> "uv $version"
            "apk" -> version
            else -> "$title $version"
        }
    when {
        health == null -> ValueRow(title, "…", summary = pitch, enabled = false)
        version.isBlank() ->
            ValueRow(
                title,
                Strings.setupOptionalInstall(),
                summary = pitch,
                enabled = enabled,
                onClick = onInstall,
            )
        name in health.zetaOwned ->
            DropdownValuePreference(
                title = title,
                summary = Strings.setupOptionalZetaOwned(label),
                value = Strings.setupOptionalManage(),
                enabled = enabled,
                entries =
                    listOf(
                        MenuEntry(
                            items =
                                listOf(
                                    MenuItem(
                                        text = Strings.setupUpdateLatest(),
                                        onClick = onInstall,
                                    ),
                                    MenuItem(
                                        text = Strings.setupUninstall(),
                                        onClick = onRemove,
                                        alert = true,
                                    ),
                                ),
                        ),
                    ),
            )
        else ->
            ValueRow(
                title,
                Strings.setupInstalled(),
                summary = Strings.setupOptionalPreinstalled(label),
            )
    }
}
