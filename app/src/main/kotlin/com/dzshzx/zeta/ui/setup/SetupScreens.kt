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
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EnvHealth
import com.dzshzx.zeta.core.InstallStep
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.LoginState
import com.dzshzx.zeta.core.SetupState
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

private fun locationName(rootfs: String?): String = when (rootfs) {
    null -> "未选择"
    LinuxEnv.OWN_ROOTFS -> "Zeta 自带的环境"
    LinuxEnv.ETA_ROOTFS -> "Eta 的环境"
    else -> "自定义位置"
}

private enum class LinuxEdit { Rootfs, Cwd }

/** Linux 环境的管理页：用哪个环境、安装、体检与修复、Claude Code 的版本和账号。 */
@Composable
internal fun LinuxScreen(hub: ConversationHub, onBack: () -> Unit, onOpenInstall: () -> Unit, onOpenLogin: () -> Unit, onOpenSsh: () -> Unit) {
    val env = hub.env
    val ready = hub.setup == SetupState.Ready && env != null
    var editing by remember { mutableStateOf<LinuxEdit?>(null) }
    var lastEditing by remember { mutableStateOf(LinuxEdit.Rootfs) }
    var draft by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var lastRemoving by remember { mutableStateOf("go") }
    removing?.let { lastRemoving = it }
    var filesPage by rememberSaveable { mutableStateOf<LinuxFilesPage?>(null) }
    val own = hub.ownStatus
    val maintenance = hub.maintenance
    val current = env?.rootfs ?: hub.rootfsHint
    LaunchedEffect(ready, env?.rootfs) {
        if (ready && hub.health == null) hub.checkHealth()
        if (ready && hub.ssh == null) hub.refreshSsh()
    }

    CouiPage(
        title = "Linux 环境",
        onBack = onBack,
        overlays = {
            TextInputDialog(
                show = editing != null,
                title = if (lastEditing == LinuxEdit.Rootfs) "自定义路径" else "默认工作目录",
                summary = if (lastEditing == LinuxEdit.Rootfs) "Debian 根目录在 Android 里的路径" else "新对话默认在这个目录下开始",
                value = draft,
                onValueChange = { draft = it },
                label = if (lastEditing == LinuxEdit.Rootfs) LinuxEnv.OWN_ROOTFS else "/workspace",
                confirmText = "确定",
                confirmEnabled = draft.trim().startsWith("/"),
                onConfirm = {
                    val value = draft.trim()
                    when (editing) {
                        LinuxEdit.Rootfs -> hub.redetect(value)
                        LinuxEdit.Cwd -> hub.setDefaultCwd(value)
                        null -> Unit
                    }
                    editing = null
                },
                onDismissRequest = { editing = null },
            )
            ConfirmDialog(
                show = removing != null,
                title = "卸载 " + optionalName(lastRemoving) + "？",
                summary = when (lastRemoving) {
                    "go" -> "删除 /usr/local/go 和 Zeta 为它写的配置。~/go 里下载过的模块、装过的程序不动。"
                    "node" -> "删除 /usr/local/node 和 Zeta 为它写的配置。~/.npm-global 里装过的全局包不动，但没有 Node.js 就跑不起来了。"
                    "apk" -> "删除 /opt/zeta/apk-analysis 里的 jadx、apktool、smali/baksmali。Java 只有当初是 Zeta 为它装的才会一起卸掉，环境里原有的（比如 Android 构建用的）不动。"
                    else -> "删除 uv、它管理的各个 Python 版本和用 uv tool 装的命令。项目里的虚拟环境会因为没有了 Python 而用不了；系统自带的 python3 不受影响。"
                } + "以后可以在这里再装回来。",
                confirmText = "卸载",
                destructive = true,
                onConfirm = {
                    removing?.let(hub::removeOptional)
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
                    hub.logout()
                },
                onDismissRequest = { confirmLogout = false },
            )
        },
    ) {
        // Zeta 经 root 进入这个 Debian 环境，以其中的普通用户（运行用户）身份运行 Claude Code
        item(key = "env") {
            PreferenceGroup(title = "环境", footer = "有对话在进行时不能切换位置。") {
                item {
                    BasicComponent(
                        title = "状态",
                        endActions = {
                            PreferenceValue(
                                when (hub.setup) {
                                    SetupState.Checking -> "检查中"
                                    SetupState.NoRoot -> "没有 root 权限"
                                    SetupState.NoEnvironment -> "未找到环境"
                                    SetupState.Ready -> "可用"
                                },
                            )
                        },
                    )
                }
                item {
                    val choose: (String) -> Unit = { target -> if (target != env?.rootfs) hub.redetect(target) }
                    DropdownValuePreference(
                        title = "位置",
                        value = locationName(current),
                        // 有对话正在进行时不能切换
                        enabled = hub.canSwitchEnvironment,
                        entries = listOf(
                            DropdownEntry(
                                items = listOfNotNull(
                                    DropdownItem(
                                        text = "Zeta 自带的环境",
                                        selected = current == LinuxEnv.OWN_ROOTFS,
                                        onClick = { choose(LinuxEnv.OWN_ROOTFS) },
                                    ),
                                    DropdownItem(
                                        text = "Eta 的环境",
                                        selected = current == LinuxEnv.ETA_ROOTFS,
                                        onClick = { choose(LinuxEnv.ETA_ROOTFS) },
                                    ).takeIf { hub.etaPresent || current == LinuxEnv.ETA_ROOTFS },
                                    DropdownItem(
                                        text = "自定义路径…",
                                        selected = current !in LinuxEnv.KNOWN_ROOTFS,
                                        onClick = {
                                            draft = current
                                            lastEditing = LinuxEdit.Rootfs
                                            editing = LinuxEdit.Rootfs
                                        },
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                item { BasicComponent(title = "路径", summary = current) }
                if (env != null) {
                    item { BasicComponent(title = "运行用户", endActions = { PreferenceValue("${env.user} (${env.uid})") }) }
                    item {
                        BasicComponent(
                            title = "默认工作目录",
                            summary = env.defaultCwd,
                            onClick = {
                                draft = env.defaultCwd
                                lastEditing = LinuxEdit.Cwd
                                editing = LinuxEdit.Cwd
                            },
                        )
                    }
                }
                item { BasicComponent(title = "重新检测", summary = "重新检查 root 和环境里的用户", onClick = { hub.redetect(current) }) }
            }
        }

        if (own?.complete != true) {
            // 装在 LinuxEnv.OWN_BASE，不在任何应用的数据目录里，卸载 Zeta 或别的应用都不会把它删掉
            item(key = "install") {
                PreferenceGroup(footer = "卸载 Zeta 不会删掉这个环境。") {
                    item {
                        ArrowPreference(
                            title = when {
                                hub.installer.running -> "正在安装…"
                                own?.base == true -> "继续安装 Debian 环境"
                                else -> "安装 Debian 环境"
                            },
                            summary = "含常用工具和 Claude Code，约占 1 GB",
                            onClick = onOpenInstall,
                        )
                    }
                }
            }
        }

        if (ready) {
            val health = hub.health
            item(key = "health") {
                PreferenceGroup(title = "体检") {
                    item {
                        BasicComponent(
                            title = "检查环境",
                            summary = when {
                                hub.healthChecking -> "检查中…"
                                health == null -> "核心命令、工作目录、网络与剩余空间"
                                else -> buildList {
                                    if (health.missing.isNotEmpty()) add("缺少 " + health.missing.joinToString("、"))
                                    if (!health.workspaceWritable) add("默认工作目录不可写")
                                    if (!health.network) add("解析不了域名，可能没有网络")
                                    if (isEmpty()) add("环境正常")
                                    if (health.debian.isNotBlank()) add("Debian ${health.debian}")
                                    add("剩余 ${formatBytes(health.freeBytes)}")
                                }.joinToString(" · ")
                            },
                            onClick = hub::checkHealth,
                        )
                    }
                    item {
                        BasicComponent(
                            title = "修复",
                            summary = "修好软件包数据库，补齐常用工具",
                            enabled = maintenance?.running != true,
                            onClick = hub::repairEnvironment,
                        )
                    }
                }
            }
            // 基础环境里已经有 git、ripgrep、python3 这些常用工具，这里是按需再装的。环境里原来就有的（比如从 Eta 迁过来的）只显示版本。
            // Go 顺带配好 ~/go/bin；Python 是 uv 管理的最新版，带 pip；Node.js 的全局包装在 ~/.npm-global；
            // APK 分析包括 jadx、apktool、smali/baksmali，环境里没有 Java 时一并装上 OpenJDK。
            item(key = "optional") {
                PreferenceGroup(title = "可选工具", footer = "Zeta 只更新、卸载自己装的；环境里原有的只显示版本，不会动。") {
                    listOf(
                        "go" to "官方最新稳定版，配好国内模块代理",
                        "python" to "uv 和最新的 Python，设为 python3",
                        "node" to "官方最新 LTS 版，带 npm",
                        "apk" to "jadx、apktool 等，反编译和检查 APK",
                    ).forEach { (name, pitch) ->
                        item(key = name) {
                            OptionalToolRow(
                                name = name,
                                pitch = pitch,
                                health = health,
                                enabled = maintenance?.running != true && health != null,
                                onInstall = { hub.installOptional(name) },
                                onRemove = { removing = name },
                            )
                        }
                    }
                }
            }
            item(key = "files") {
                PreferenceGroup(title = "文件与远程访问") {
                    item {
                        ArrowPreference(
                            title = "文件浏览",
                            summary = "浏览、预览文件，和手机之间导入导出",
                            onClick = { filesPage = LinuxFilesPage.Browser },
                        )
                    }
                    item {
                        ArrowPreference(
                            title = "共享文件夹",
                            summary = "把 Android 目录挂载到 Linux 环境",
                            onClick = { filesPage = LinuxFilesPage.Shared },
                        )
                    }
                    item {
                        val ssh = hub.ssh
                        ArrowPreference(
                            title = "SSH 服务",
                            summary = when {
                                ssh == null -> "从终端应用或别的设备连进这个环境"
                                !ssh.installed -> "未安装"
                                ssh.running -> "运行中 · " + ssh.listen.ifBlank { "端口 " + ssh.port }
                                else -> "未运行"
                            },
                            onClick = onOpenSsh,
                        )
                    }
                }
            }
            item(key = "claude") {
                val account = hub.account
                PreferenceGroup(title = "Claude Code") {
                    item { BasicComponent(title = "版本", endActions = { PreferenceValue(health?.claude?.ifBlank { "未安装" } ?: "…") }) }
                    item {
                        BasicComponent(
                            title = "更新",
                            // 运行 claude update
                            summary = "更新到最新版",
                            enabled = maintenance?.running != true,
                            onClick = hub::updateClaude,
                        )
                    }
                    item {
                        ArrowPreference(
                            title = "账号",
                            summary = when {
                                account == null -> "正在查询…"
                                !account.loggedIn -> "未登录"
                                else -> listOfNotNull(account.email, account.plan).joinToString(" · ").ifBlank { "已登录" }
                            },
                            onClick = onOpenLogin,
                        )
                    }
                    if (account?.loggedIn == true) {
                        item { BasicComponent(title = "退出登录", enabled = maintenance?.running != true, onClick = { confirmLogout = true }) }
                    }
                }
            }
        }

        if (maintenance != null) {
            item(key = "maintenance") { MaintenanceGroup(maintenance, onDismiss = hub::dismissMaintenance) }
        }
    }
    LinuxFilesPages(hub, page = filesPage.takeIf { ready }, onClose = { filesPage = null })
}

/** 正在做或刚做完的维护（修复、更新、装卸可选工具、SSH 的操作）：做完了点一下收起。 */
@Composable
internal fun MaintenanceGroup(maintenance: ConversationHub.Maintenance, onDismiss: () -> Unit) {
    PreferenceGroup(title = maintenance.title) {
        item {
            BasicComponent(
                title = when {
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
    val installer = hub.installer
    val own = hub.ownStatus
    var userName by rememberSaveable { mutableStateOf("zeta") }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    val existingUser = own?.user
    val nameValid = Regex("[a-z_][a-z0-9_-]{0,30}").matches(userName)
    val complete = installer.finished || own?.complete == true

    CouiPage(
        title = "安装 Debian 环境",
        onBack = onBack,
        overlays = {
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
                        onClick = if (existingUser == null && !installer.running && !complete) {
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
                    item(key = step) {
                        val current = installer.running && installer.step == step
                        val value = when {
                            step in installer.done || complete -> "完成"
                            current -> installer.progress?.let { "${(it * 100).toInt()}%" } ?: "进行中"
                            installer.error != null && installer.step == step -> "失败"
                            else -> ""
                        }
                        BasicComponent(
                            title = step.title,
                            summary = if (current) installer.detail.ifBlank { null } else null,
                            endActions = if (value.isEmpty()) null else ({ PreferenceValue(value) }),
                        )
                    }
                }
            }
        }
        installer.error?.let { message ->
            item(key = "error") { Notice(message, error = true) }
        }
        item(key = "actions") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    installer.running -> TextButton(text = "取消", onClick = installer::cancel, modifier = Modifier.fillMaxWidth())
                    complete -> {
                        if (hub.env?.rootfs != LinuxEnv.OWN_ROOTFS) {
                            PrimaryButton(text = "切换到这个环境", onClick = { hub.redetect(LinuxEnv.OWN_ROOTFS) }, enabled = hub.canSwitchEnvironment)
                        } else if (hub.account?.loggedIn != true) {
                            PrimaryButton(text = "登录 Claude 账号", onClick = onOpenLogin)
                        }
                        TextButton(text = "完成", onClick = onBack, modifier = Modifier.fillMaxWidth())
                    }
                    else -> PrimaryButton(
                        text = if (installer.error != null || own?.base == true) "继续安装" else "开始安装",
                        onClick = { installer.start(existingUser ?: userName) },
                        enabled = nameValid || existingUser != null,
                    )
                }
            }
        }
    }
}

/** 登录 Claude 账号：打开授权页，把页面给的授权码贴回来。 */
@Composable
internal fun LoginScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val flow = hub.login
    val state = flow.state
    val account = hub.account
    var code by remember { mutableStateOf("") }
    val leave = {
        flow.cancel()
        onBack()
    }
    CouiPage(title = "登录 Claude 账号", onBack = leave) {
        item(key = "status") {
            PreferenceGroup(title = "当前状态") {
                item {
                    BasicComponent(
                        title = when {
                            account == null -> "正在查询…"
                            account.loggedIn -> "已登录"
                            else -> "未登录"
                        },
                        summary = account?.takeIf { it.loggedIn }?.let { listOfNotNull(it.email, it.plan).joinToString(" · ").ifBlank { null } },
                    )
                }
            }
        }
        when (state) {
            LoginState.Idle, is LoginState.Failed -> {
                if (state is LoginState.Failed) item(key = "failed") { Notice(state.message, error = true) }
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
            is LoginState.WaitingCode, LoginState.Verifying -> {
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
                                onClick = {
                                    url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                                },
                            )
                        }
                        item {
                            BasicComponent(
                                title = "复制链接",
                                summary = "想在别的设备上登录时用",
                                enabled = url != null,
                                onClick = {
                                    url?.let {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("login", it))
                                    }
                                },
                            )
                        }
                    }
                }
                item(key = "step2") {
                    PreferenceGroup(title = "第二步") {
                        item {
                            InputPreference(
                                value = code,
                                onValueChange = {
                                    code = it
                                    flow.clearNotice()
                                },
                                hint = "把授权码粘贴到这里",
                                enabled = state is LoginState.WaitingCode,
                            )
                        }
                    }
                }
                (state as? LoginState.WaitingCode)?.notice?.let { item(key = "notice") { Notice(it, error = true) } }
                item(key = "submit") {
                    Buttons {
                        PrimaryButton(
                            text = if (state is LoginState.Verifying) "正在验证…" else "完成登录",
                            onClick = { flow.submit(code) },
                            enabled = state is LoginState.WaitingCode && code.isNotBlank(),
                        )
                    }
                }
            }
            LoginState.Done -> {
                item(key = "done") { Notice("登录成功") }
                item(key = "finish") { Buttons { PrimaryButton(text = "完成", onClick = leave) } }
            }
        }
    }
}

/** 一段说明或报错，放在卡片之间。 */
@Composable
private fun Notice(text: String, error: Boolean = false) {
    if (error) PagerFooterPreference(summary = text, summaryColor = COUITheme.colorScheme.error) else PagerFooterPreference(summary = text)
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

/** 页面底部的整行主按钮。 */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(text = text, onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled, colors = ButtonDefaults.textButtonColorsPrimary())
}

/**
 * 一样可选工具的一行。没装：点一下安装。Zeta 装的：弹出菜单，可以更新或卸载。
 * 别处装的（环境里原来就有，或者手动装的）：只显示版本，不提供任何操作。
 */
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
    val label = when (name) {
        "python" -> "uv $version"
        "apk" -> version
        else -> "$title $version"
    }
    when {
        health == null -> BasicComponent(title = title, summary = pitch, endActions = { PreferenceValue("…") }, enabled = false)
        version.isBlank() -> BasicComponent(title = title, summary = pitch, endActions = { PreferenceValue("安装") }, enabled = enabled, onClick = onInstall)
        name in health.zetaOwned -> DropdownValuePreference(
            title = title,
            summary = "$label · Zeta 装的",
            value = "管理",
            enabled = enabled,
            entries = listOf(
                DropdownEntry(
                    items = listOf(
                        DropdownItem(text = "更新到最新版", onClick = onInstall),
                        DropdownItem(text = "卸载", onClick = onRemove),
                    ),
                ),
            ),
        )
        else -> BasicComponent(title = title, summary = "$label · 环境里原有的", endActions = { PreferenceValue("已安装") })
    }
}
