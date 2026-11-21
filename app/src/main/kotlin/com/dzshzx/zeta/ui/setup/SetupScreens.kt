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
import com.dzshzx.zeta.core.ConversationHub
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
import com.dzshzx.zeta.core.formatBytes
import com.dzshzx.zeta.core.optionalName
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.DropdownValuePreference
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.InputPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

private fun locationName(rootfs: String?): String =
    when (rootfs) {
        null -> "未选择"
        LinuxEnv.OWN_ROOTFS -> "Zeta 自带的环境"
        else -> "自定义位置"
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

    CouiPage(
        title = "Linux 环境",
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
                title = "卸载 " + optionalName(lastRemoving) + "？",
                summary = removeOptionalSummary(lastRemoving) + "以后可以在这里再装回来。",
                confirmText = "卸载",
                destructive = true,
                onConfirm = {
                    removing?.let(hub.environment::removeOptional)
                    removing = null
                },
                onDismissRequest = { removing = null },
            )
            ConfirmDialog(
                show = confirmLogout,
                title = "退出登录",
                summary = "退出后要重新登录才能继续使用 Claude Code。",
                confirmText = "退出",
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
        title = if (rootfs) "自定义路径" else "默认工作目录",
        summary = if (rootfs) "Debian 根目录在 Android 里的路径" else "新对话默认在这个目录下开始",
        value = draft,
        onValueChange = onDraftChange,
        label = if (rootfs) LinuxEnv.OWN_ROOTFS else home ?: "/workspace",
        confirmText = "确定",
        confirmEnabled = draft.trim().startsWith("/"),
        onConfirm = onConfirm,
        onDismissRequest = onDismiss,
    )
}

/** 卸载一样可选工具前说明会删掉什么、留下什么。 */
private fun removeOptionalSummary(name: String): String =
    when (name) {
        "go" -> "删除 /usr/local/go 和 Zeta 为它写的配置。~/go 里下载过的模块、装过的程序不动。"
        "node" -> "删除 /usr/local/node 和 Zeta 为它写的配置。~/.npm-global 里装过的全局包不动，但没有 Node.js 就跑不起来了。"
        "apk" ->
            "删除 /opt/zeta/apk-analysis 里的 jadx、apktool、smali/baksmali。Java 只有当初是 Zeta 为它装的才会一起卸掉，环境里原有的（比如 Android 构建用的）不动。"
        else ->
            "删除 uv、它管理的各个 Python 版本和用 uv tool 装的命令。项目里的虚拟环境会因为没有了 Python 而用不了；系统自带的 python3 不受影响。"
    }

private fun setupStateText(state: SetupState): String =
    when (state) {
        SetupState.Checking -> "检查中"
        SetupState.NoRoot -> "没有 root 权限"
        SetupState.NoEnvironment -> "未找到环境"
        SetupState.Ready -> "可用"
    }

/** 自带环境还没装完时的入口。 */
@Composable
private fun InstallGroup(running: Boolean, own: EnvStatus?, onOpenInstall: () -> Unit) {
    PreferenceGroup(footer = "卸载 Zeta 不会删掉这个环境。") {
        item {
            ArrowPreference(
                title =
                    when {
                        running -> "正在安装…"
                        own?.base == true -> "继续安装 Debian 环境"
                        else -> "安装 Debian 环境"
                    },
                summary = "含常用工具和 Claude Code，约占 1 GB",
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
    PreferenceGroup(title = "环境", footer = "有对话在进行时不能切换位置。") {
        item {
            BasicComponent(
                title = "状态",
                endActions = { PreferenceValue(setupStateText(hub.environment.setup)) },
            )
        }
        item {
            val choose: (String) -> Unit = { target ->
                if (target != env?.rootfs) hub.environment.redetect(target)
            }
            DropdownValuePreference(
                title = "位置",
                value = locationName(current),
                // 有对话正在进行时不能切换
                enabled = hub.environment.canSwitchEnvironment,
                entries =
                    listOf(
                        DropdownEntry(
                            items =
                                listOf(
                                    DropdownItem(
                                        text = "Zeta 自带的环境",
                                        selected = current == LinuxEnv.OWN_ROOTFS,
                                        onClick = { choose(LinuxEnv.OWN_ROOTFS) },
                                    ),
                                    DropdownItem(
                                        text = "自定义路径…",
                                        selected = current != LinuxEnv.OWN_ROOTFS,
                                        onClick = { onEdit(LinuxEdit.Rootfs, current) },
                                    ),
                                ),
                        ),
                    ),
            )
        }
        item { BasicComponent(title = "路径", summary = current) }
        if (env != null) {
            item {
                BasicComponent(
                    title = "运行用户",
                    endActions = { PreferenceValue("${env.user} (${env.uid})") },
                )
            }
            item {
                BasicComponent(
                    title = "默认工作目录",
                    summary = env.defaultCwd,
                    onClick = { onEdit(LinuxEdit.Cwd, env.defaultCwd) },
                )
            }
        }
        item {
            BasicComponent(
                title = "重新检测",
                summary = "重新检查 root 和环境里的用户",
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
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val health = hub.environment.health
    item(key = "health") { HealthGroup(hub, health, maintenanceRunning) }
    // 基础环境里已经有 git、ripgrep、python3 这些常用工具，这里是按需再装的。环境里原来就有的（不是 Zeta 装的）只显示版本。
    // Go 顺带配好 ~/go/bin；Python 是 uv 管理的最新版，带 pip；Node.js 的全局包装在 ~/.npm-global；
    // APK 分析包括 jadx、apktool、smali/baksmali，环境里没有 Java 时一并装上 OpenJDK。
    item(key = "optional") {
        PreferenceGroup(title = "可选工具", footer = "Zeta 只更新、卸载自己装的；环境里原有的只显示版本，不会动。") {
            listOf(
                    "go" to "官方最新稳定版，配好国内模块代理",
                    "python" to "uv 和最新的 Python，设为 python3",
                    "node" to "官方最新 LTS 版，带 npm",
                    "apk" to "jadx、apktool 等，反编译和检查 APK",
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
    item(key = "files") { FilesGroup(hub.environment.ssh, onOpenFiles, onOpenSsh) }
    item(key = "claude") {
        ClaudeGroup(hub, health, maintenanceRunning, onOpenLogin = onOpenLogin, onLogout = onLogout)
    }
}

/** 「体检」一组：检查环境的结果和修复。 */
@Composable
private fun HealthGroup(hub: ConversationHub, health: EnvHealth?, maintenanceRunning: Boolean) {
    PreferenceGroup(title = "体检") {
        item {
            BasicComponent(
                title = "检查环境",
                summary = healthSummary(hub.environment.healthChecking, health),
                onClick = hub.environment::checkHealth,
            )
        }
        item {
            BasicComponent(
                title = "修复",
                summary = "修好软件包数据库，补齐常用工具",
                enabled = !maintenanceRunning,
                onClick = hub.environment::repairEnvironment,
            )
        }
    }
}

private fun healthSummary(checking: Boolean, health: EnvHealth?): String =
    when {
        checking -> "检查中…"
        health == null -> "核心命令、工作目录、网络与剩余空间"
        else -> healthFindings(health).joinToString(" · ")
    }

/** 体检结果的各条：先列问题（没有就是环境正常），再是 Debian 版本和剩余空间。 */
private fun healthFindings(health: EnvHealth): List<String> = buildList {
    if (health.missing.isNotEmpty()) add("缺少 " + health.missing.joinToString("、"))
    if (!health.workspaceWritable) add("默认工作目录不可写")
    if (!health.network) add("解析不了域名，可能没有网络")
    if (isEmpty()) add("环境正常")
    if (health.debian.isNotBlank()) add("Debian ${health.debian}")
    add("剩余 ${formatBytes(health.freeBytes)}")
}

/** 「文件与远程访问」一组：文件浏览、共享文件夹、SSH 服务。 */
@Composable
private fun FilesGroup(
    ssh: SshStatus?,
    onOpenFiles: (LinuxFilesPage) -> Unit,
    onOpenSsh: () -> Unit,
) {
    PreferenceGroup(title = "文件与远程访问") {
        item {
            ArrowPreference(
                title = "文件浏览",
                summary = "浏览、预览文件，和手机之间导入导出",
                onClick = { onOpenFiles(LinuxFilesPage.Browser) },
            )
        }
        item {
            ArrowPreference(
                title = "共享文件夹",
                summary = "把 Android 目录挂载到 Linux 环境",
                onClick = { onOpenFiles(LinuxFilesPage.Shared) },
            )
        }
        item {
            ArrowPreference(title = "SSH 服务", summary = sshSummary(ssh), onClick = onOpenSsh)
        }
    }
}

private fun sshSummary(ssh: SshStatus?): String =
    when {
        ssh == null -> "从终端应用或别的设备连进这个环境"
        !ssh.installed -> "未安装"
        ssh.running -> "运行中 · " + ssh.listen.ifBlank { "端口 " + ssh.port }
        else -> "未运行"
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
            BasicComponent(
                title = "版本",
                endActions = { PreferenceValue(health?.claude?.ifBlank { "未安装" } ?: "…") },
            )
        }
        item {
            BasicComponent(
                title = "更新",
                // 运行 claude update
                summary = "更新到最新版",
                enabled = !maintenanceRunning,
                onClick = hub.environment::updateClaude,
            )
        }
        item {
            ArrowPreference(
                title = "账号",
                summary = accountSummary(account),
                onClick = onOpenLogin,
            )
        }
        if (account?.loggedIn == true) {
            item {
                BasicComponent(
                    title = "退出登录",
                    enabled = !maintenanceRunning,
                    onClick = onLogout,
                )
            }
        }
    }
}

private fun accountSummary(account: AccountStatus?): String =
    when {
        account == null -> "正在查询…"
        !account.loggedIn -> "未登录"
        else -> listOfNotNull(account.email, account.plan).joinToString(" · ").ifBlank { "已登录" }
    }

/** 正在做或刚做完的维护（修复、更新、装卸可选工具、SSH 的操作）：做完了点一下收起。 */
@Composable
internal fun MaintenanceGroup(maintenance: EnvController.Maintenance, onDismiss: () -> Unit) {
    PreferenceGroup(title = maintenance.title) {
        item {
            BasicComponent(
                title =
                    when {
                        maintenance.running -> "进行中…"
                        maintenance.ok -> "已完成"
                        else -> "没有成功"
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

    CouiPage(
        title = "安装 Debian 环境",
        onBack = onBack,
        dialogs = {
            TextInputDialog(
                show = editingName,
                title = "用户名",
                summary = "小写字母开头，只含小写字母、数字、下划线和连字符",
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = "zeta",
                confirmText = "确定",
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
                title = "准备",
                // 装在 LinuxEnv.OWN_BASE
                footer = "先下载约 35 MB 的基础系统，再联网装常用工具和 Claude Code，装完约占 1 GB。要几分钟，可以切到后台。",
            ) {
                item {
                    BasicComponent(
                        title = "用户名",
                        summary = "Claude Code 以这个 Linux 用户的身份运行",
                        endActions = { PreferenceValue(existingUser ?: userName) },
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
            PreferenceGroup(title = "步骤") {
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
        endActions = if (value.isEmpty()) null else ({ PreferenceValue(value) }),
    )
}

private fun installStepValue(
    step: InstallStep,
    installer: EnvInstaller,
    complete: Boolean,
    current: Boolean,
): String =
    when {
        step in installer.done || complete -> "完成"
        current -> installer.progress?.let { "${(it * 100).toInt()}%" } ?: "进行中"
        installer.error != null && installer.step == step -> "失败"
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
                    text = "取消",
                    onClick = installer::cancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            complete -> {
                if (hub.settings.env?.rootfs != LinuxEnv.OWN_ROOTFS) {
                    PrimaryButton(
                        text = "切换到这个环境",
                        onClick = { hub.environment.redetect(LinuxEnv.OWN_ROOTFS) },
                        enabled = hub.environment.canSwitchEnvironment,
                    )
                } else if (hub.environment.account?.loggedIn != true) {
                    PrimaryButton(text = "登录 Claude 账号", onClick = onOpenLogin)
                }
                TextButton(
                    text = "完成",
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else ->
                PrimaryButton(
                    text =
                        if (installer.error != null || hub.environment.ownStatus?.base == true)
                            "继续安装"
                        else "开始安装",
                    onClick = onStart,
                    enabled = startEnabled,
                )
        }
    }
}

/** 登录 Claude 账号：打开授权页，把页面给的授权码贴回来。 */
@Composable
internal fun LoginScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val flow = hub.environment.login
    val state = flow.state
    val account = hub.environment.account
    var code by remember { mutableStateOf("") }
    val leave = {
        flow.cancel()
        onBack()
    }
    CouiPage(title = "登录 Claude 账号", onBack = leave) {
        item(key = "status") { LoginStatusGroup(account) }
        when (state) {
            LoginState.Idle,
            is LoginState.Failed -> {
                if (state is LoginState.Failed)
                    item(key = "failed") { Notice(state.message, error = true) }
                item(key = "start") {
                    Buttons {
                        PrimaryButton(
                            text = if (account?.loggedIn == true) "换一个账号登录" else "开始登录",
                            onClick = {
                                code = ""
                                flow.start()
                            },
                        )
                    }
                }
            }
            LoginState.Starting -> item(key = "starting") { Notice("正在准备登录…") }
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
                item(key = "done") { Notice("登录成功") }
                item(key = "finish") { Buttons { PrimaryButton(text = "完成", onClick = leave) } }
            }
        }
    }
}

/** 「当前状态」一组：是否已登录，登录了就带上邮箱和套餐。 */
@Composable
private fun LoginStatusGroup(account: AccountStatus?) {
    PreferenceGroup(title = "当前状态") {
        item {
            BasicComponent(
                title =
                    when {
                        account == null -> "正在查询…"
                        account.loggedIn -> "已登录"
                        else -> "未登录"
                    },
                summary =
                    account
                        ?.takeIf { it.loggedIn }
                        ?.let {
                            listOfNotNull(it.email, it.plan).joinToString(" · ").ifBlank { null }
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
            title = "第一步",
            footer = "在浏览器里登录并同意授权，页面最后会给出一串授权码。",
        ) {
            item {
                ArrowPreference(
                    title = "打开登录页",
                    enabled = url != null,
                    onClick = { url?.let(onOpen) },
                )
            }
            item {
                BasicComponent(
                    title = "复制链接",
                    summary = "想在别的设备上登录时用",
                    enabled = url != null,
                    onClick = { url?.let(onCopy) },
                )
            }
        }
    }
    item(key = "step2") {
        PreferenceGroup(title = "第二步") {
            item {
                InputPreference(
                    value = code,
                    onValueChange = onCodeChange,
                    hint = "把授权码粘贴到这里",
                    enabled = state is LoginState.WaitingCode,
                )
            }
        }
    }
    (state as? LoginState.WaitingCode)?.notice?.let {
        item(key = "notice") { Notice(it, error = true) }
    }
    item(key = "submit") {
        Buttons {
            PrimaryButton(
                text = if (state is LoginState.Verifying) "正在验证…" else "完成登录",
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
    if (error) PagerFooterPreference(summary = text, summaryColor = COUITheme.colorScheme.error)
    else PagerFooterPreference(summary = text)
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
        health == null ->
            BasicComponent(
                title = title,
                summary = pitch,
                endActions = { PreferenceValue("…") },
                enabled = false,
            )
        version.isBlank() ->
            BasicComponent(
                title = title,
                summary = pitch,
                endActions = { PreferenceValue("安装") },
                enabled = enabled,
                onClick = onInstall,
            )
        name in health.zetaOwned ->
            DropdownValuePreference(
                title = title,
                summary = "$label · Zeta 装的",
                value = "管理",
                enabled = enabled,
                entries =
                    listOf(
                        DropdownEntry(
                            items =
                                listOf(
                                    DropdownItem(text = "更新到最新版", onClick = onInstall),
                                    DropdownItem(text = "卸载", onClick = onRemove),
                                ),
                        ),
                    ),
            )
        else ->
            BasicComponent(
                title = title,
                summary = "$label · 环境里原有的",
                endActions = { PreferenceValue("已安装") },
            )
    }
}
