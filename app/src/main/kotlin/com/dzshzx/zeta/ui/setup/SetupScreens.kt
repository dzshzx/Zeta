package com.dzshzx.zeta.ui.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.InstallStep
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.LoginState
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.coui.CouiButton
import com.dzshzx.zeta.ui.components.coui.CouiButtonRole
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiInputDialog
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.components.coui.CouiTextField
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun bytesText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%d MB".format(bytes shr 20)
}

private fun locationName(rootfs: String?): String = when (rootfs) {
    null -> "未选择"
    LinuxEnv.OWN_ROOTFS -> "Zeta 自带的环境"
    LinuxEnv.ETA_ROOTFS -> "Eta 的环境"
    else -> "自定义位置"
}

private enum class LinuxEdit { Rootfs, Cwd }

/** Linux 环境的管理页：用哪个环境、安装、体检与修复、Claude Code 的版本和账号。 */
@Composable
internal fun LinuxScreen(hub: ConversationHub, onBack: () -> Unit, onOpenInstall: () -> Unit, onOpenLogin: () -> Unit) {
    val env = hub.env
    val ready = hub.setup == SetupState.Ready && env != null
    var editing by remember { mutableStateOf<LinuxEdit?>(null) }
    var lastEditing by remember { mutableStateOf(LinuxEdit.Rootfs) }
    var draft by remember { mutableStateOf("") }
    var locationMenu by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var lastRemoving by remember { mutableStateOf("go") }
    removing?.let { lastRemoving = it }
    val own = hub.ownStatus
    val maintenance = hub.maintenance
    LaunchedEffect(ready, env?.rootfs) { if (ready && hub.health == null) hub.checkHealth() }

    CouiScaffoldPage(title = "Linux 环境", onBack = onBack) {
        Group(
            title = "环境",
            footer = "Zeta 经 root 进入这个 Debian 环境，以其中的普通用户身份运行 Claude Code。有对话正在进行时不能切换。",
        ) {
            CouiPreference(
                title = "状态",
                value = when (hub.setup) {
                    SetupState.Checking -> "检查中"
                    SetupState.NoRoot -> "没有 root 权限"
                    SetupState.NoEnvironment -> "未找到环境"
                    SetupState.Ready -> "可用"
                },
            )
            val current = env?.rootfs ?: hub.rootfsHint
            CouiPopupPreference(
                title = "位置",
                value = locationName(env?.rootfs ?: hub.rootfsHint),
                expanded = locationMenu,
                onExpandedChange = { locationMenu = it && hub.canSwitchEnvironment },
                items = listOf(
                    CouiMenuItem(value = LinuxEnv.OWN_ROOTFS, text = "Zeta 自带的环境", selected = current == LinuxEnv.OWN_ROOTFS),
                    CouiMenuItem(value = LinuxEnv.ETA_ROOTFS, text = "Eta 的环境", selected = current == LinuxEnv.ETA_ROOTFS),
                    CouiMenuItem(value = "", text = "自定义路径…", selected = current !in LinuxEnv.KNOWN_ROOTFS),
                ),
                onItemClick = {
                    locationMenu = false
                    if (it.value.isEmpty()) {
                        draft = current
                        lastEditing = LinuxEdit.Rootfs
                        editing = LinuxEdit.Rootfs
                    } else if (it.value != env?.rootfs) {
                        hub.redetect(it.value)
                    }
                },
            )
            CouiPreference(title = "路径", summary = current)
            if (env != null) {
                CouiPreference(title = "运行用户", value = "${env.user} (${env.uid})")
                CouiPreference(
                    title = "默认工作目录",
                    summary = env.defaultCwd,
                    onClick = {
                        draft = env.defaultCwd
                        lastEditing = LinuxEdit.Cwd
                        editing = LinuxEdit.Cwd
                    },
                )
            }
            CouiPreference(title = "重新检测", summary = "重新检查 root 权限并识别环境里的用户", onClick = { hub.redetect(current) })
        }

        if (own?.complete != true) {
            Group(
                title = "安装",
                footer = "装在 ${LinuxEnv.OWN_BASE}，不在任何应用的数据目录里，卸载 Zeta 或别的应用都不会把它删掉。",
            ) {
                CouiPreference(
                    title = when {
                        hub.installer.running -> "正在安装…"
                        own?.base == true -> "继续安装 Debian 环境"
                        else -> "安装 Debian 环境"
                    },
                    summary = "下载 Debian 13，装好常用工具和 Claude Code，约占 1 GB",
                    onClick = onOpenInstall,
                )
            }
        }

        if (ready) {
            val health = hub.health
            Group(title = "体检") {
                CouiPreference(
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
                            add("剩余 ${bytesText(health.freeBytes)}")
                        }.joinToString(" · ")
                    },
                    onClick = hub::checkHealth,
                )
                CouiPreference(
                    title = "修复",
                    summary = "修复软件包数据库，补齐缺少的常用工具",
                    enabled = maintenance?.running != true,
                    onClick = hub::repairEnvironment,
                )
            }
            Group(title = "可选工具", footer = "基础环境里已经有 git、ripgrep、python3 这些常用工具。这里是按需再装的。") {
                CouiPreference(
                    title = "Go",
                    summary = if (health?.go.isNullOrBlank()) "安装官方发布的最新稳定版，并配好 ~/go/bin 和国内模块代理" else "Go ${health?.go}",
                    value = if (health == null) "…" else if (health.go.isBlank()) "安装" else "已安装",
                    enabled = maintenance?.running != true && health != null,
                    onClick = if (health?.go.isNullOrBlank()) hub::installGo else ({ removing = "go" }),
                )
                CouiPreference(
                    title = "Python / uv",
                    summary = if (health?.uv.isNullOrBlank()) "安装 uv 和它管理的 Python，用来跑脚本、建虚拟环境" else "uv ${health?.uv}",
                    value = if (health == null) "…" else if (health.uv.isBlank()) "安装" else "已安装",
                    enabled = maintenance?.running != true && health != null,
                    onClick = if (health?.uv.isNullOrBlank()) hub::installPython else ({ removing = "python" }),
                )
                CouiPreference(
                    title = "Node.js",
                    summary = if (health?.node.isNullOrBlank()) "安装官方发布的最新 LTS 版，带 npm；全局包装在 ~/.npm-global" else "Node.js ${health?.node}",
                    value = if (health == null) "…" else if (health.node.isBlank()) "安装" else "已安装",
                    enabled = maintenance?.running != true && health != null,
                    onClick = if (health?.node.isNullOrBlank()) hub::installNode else ({ removing = "node" }),
                )
            }
            val account = hub.account
            Group(title = "Claude Code") {
                CouiPreference(title = "版本", value = health?.claude?.ifBlank { "未安装" } ?: "…")
                CouiPreference(
                    title = "更新",
                    summary = "运行 claude update 更新到最新版",
                    enabled = maintenance?.running != true,
                    onClick = hub::updateClaude,
                )
                CouiPreference(
                    title = "账号",
                    summary = when {
                        account == null -> "正在查询…"
                        !account.loggedIn -> "未登录"
                        else -> listOfNotNull(account.email, account.plan).joinToString(" · ").ifBlank { "已登录" }
                    },
                    onClick = onOpenLogin,
                )
                if (account?.loggedIn == true) {
                    CouiPreference(title = "退出登录", enabled = maintenance?.running != true, onClick = { confirmLogout = true })
                }
            }
        }

        if (maintenance != null) {
            Group(title = maintenance.title) {
                CouiPreference(
                    title = when {
                        maintenance.running -> "进行中…"
                        maintenance.ok -> "已完成"
                        else -> "没有成功"
                    },
                    summary = maintenance.detail.ifBlank { null },
                    onClick = if (maintenance.running) null else hub::dismissMaintenance,
                    showChevron = false,
                )
            }
        }
    }

    CouiInputDialog(
        visible = editing != null,
        title = if (lastEditing == LinuxEdit.Rootfs) "rootfs 路径" else "默认工作目录",
        message = if (lastEditing == LinuxEdit.Rootfs) "Android 侧看到的 Debian 根目录" else "新对话默认在这个目录下开始",
        value = draft,
        onValueChange = { draft = it },
        placeholder = if (lastEditing == LinuxEdit.Rootfs) LinuxEnv.OWN_ROOTFS else "/workspace",
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
    CouiConfirmDialog(
        visible = removing != null,
        title = "卸载 " + when (lastRemoving) { "go" -> "Go"; "node" -> "Node.js"; else -> "Python / uv" } + "？",
        message = "只卸载工具本身，你的项目、下载过的依赖都不动。以后可以在这里再装回来。",
        confirmText = "卸载",
        destructive = true,
        onConfirm = {
            removing?.let(hub::removeOptional)
            removing = null
        },
        onDismissRequest = { removing = null },
    )
    CouiConfirmDialog(
        visible = confirmLogout,
        title = "退出登录",
        message = "退出后要重新登录才能继续使用 Claude Code。",
        confirmText = "退出",
        onConfirm = {
            confirmLogout = false
            hub.logout()
        },
        onDismissRequest = { confirmLogout = false },
    )
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

    CouiScaffoldPage(title = "安装 Debian 环境", onBack = onBack) {
        Group(
            title = "准备",
            footer = "下载 Debian 13 的基础系统（约 35 MB），再联网安装常用工具和 Claude Code，装完约占 1 GB。" +
                "放在 ${LinuxEnv.OWN_BASE}。整个过程需要几分钟，可以切到后台。",
        ) {
            CouiPreference(
                title = "用户名",
                summary = "Claude Code 以这个 Linux 用户的身份运行",
                value = existingUser ?: userName,
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
        Group(title = "步骤") {
            for (step in InstallStep.entries) {
                val current = installer.running && installer.step == step
                CouiPreference(
                    title = step.title,
                    summary = if (current) installer.detail.ifBlank { null } else null,
                    value = when {
                        step in installer.done || complete -> "完成"
                        current -> installer.progress?.let { "${(it * 100).toInt()}%" } ?: "进行中"
                        installer.error != null && installer.step == step -> "失败"
                        else -> ""
                    },
                )
            }
        }
        installer.error?.let { message ->
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                installer.running -> CouiButton(text = "取消", onClick = installer::cancel, modifier = Modifier.fillMaxWidth())
                complete -> {
                    if (hub.env?.rootfs != LinuxEnv.OWN_ROOTFS) {
                        CouiButton(
                            text = "切换到这个环境",
                            onClick = { hub.redetect(LinuxEnv.OWN_ROOTFS) },
                            enabled = hub.canSwitchEnvironment,
                            role = CouiButtonRole.Primary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (hub.account?.loggedIn != true) {
                        CouiButton(text = "登录 Claude 账号", onClick = onOpenLogin, role = CouiButtonRole.Primary, modifier = Modifier.fillMaxWidth())
                    }
                    CouiButton(text = "完成", onClick = onBack, modifier = Modifier.fillMaxWidth())
                }
                else -> CouiButton(
                    text = if (installer.error != null || own?.base == true) "继续安装" else "开始安装",
                    onClick = { installer.start(existingUser ?: userName) },
                    enabled = nameValid || existingUser != null,
                    role = CouiButtonRole.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    CouiInputDialog(
        visible = editingName,
        title = "用户名",
        message = "小写字母开头，只含小写字母、数字、下划线和连字符",
        value = nameDraft,
        onValueChange = { nameDraft = it },
        placeholder = "zeta",
        confirmText = "确定",
        confirmEnabled = Regex("[a-z_][a-z0-9_-]{0,30}").matches(nameDraft.trim()),
        onConfirm = {
            userName = nameDraft.trim()
            editingName = false
        },
        onDismissRequest = { editingName = false },
    )
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
    CouiScaffoldPage(title = "登录 Claude 账号", onBack = leave) {
        Group(title = "当前状态") {
            CouiPreference(
                title = when {
                    account == null -> "正在查询…"
                    account.loggedIn -> "已登录"
                    else -> "未登录"
                },
                summary = account?.takeIf { it.loggedIn }?.let { listOfNotNull(it.email, it.plan).joinToString(" · ").ifBlank { null } },
            )
        }
        when (state) {
            LoginState.Idle, is LoginState.Failed -> {
                if (state is LoginState.Failed) Notice(state.message, error = true)
                Buttons {
                    CouiButton(
                        text = if (account?.loggedIn == true) "换一个账号登录" else "开始登录",
                        onClick = {
                            code = ""
                            flow.start()
                        },
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LoginState.Starting -> Notice("正在准备登录…")
            is LoginState.WaitingCode, LoginState.Verifying -> {
                val url = (state as? LoginState.WaitingCode)?.url
                Group(
                    title = "第一步",
                    footer = "在浏览器里登录并同意授权，页面最后会给出一串授权码。",
                ) {
                    CouiPreference(
                        title = "打开登录页",
                        enabled = url != null,
                        onClick = {
                            url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        },
                    )
                    CouiPreference(
                        title = "复制链接",
                        summary = "想在别的设备上登录时用",
                        enabled = url != null,
                        showChevron = false,
                        onClick = {
                            url?.let {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("login", it))
                            }
                        },
                    )
                }
                Group(title = "第二步") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        CouiTextField(value = code, onValueChange = { code = it; flow.clearNotice() }, placeholder = "把授权码粘贴到这里", enabled = state is LoginState.WaitingCode)
                    }
                }
                (state as? LoginState.WaitingCode)?.notice?.let { Notice(it, error = true) }
                Buttons {
                    CouiButton(
                        text = if (state is LoginState.Verifying) "正在验证…" else "完成登录",
                        onClick = { flow.submit(code) },
                        enabled = state is LoginState.WaitingCode && code.isNotBlank(),
                        role = CouiButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LoginState.Done -> {
                Notice("登录成功")
                Buttons { CouiButton(text = "完成", onClick = leave, role = CouiButtonRole.Primary, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
    )
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}
