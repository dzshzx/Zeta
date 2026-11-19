package com.dzshzx.zeta.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui.CouiPageBottomSpacer
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.itos.coui.layout.Group
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** “权限健康”：Zeta 需要或能用到的权限和状态，每项点一下就去修；有 root 时可以一键授予。 */
@Composable
internal fun PermissionScreen(hub: ConversationHub, onBack: () -> Unit, onOpenLinux: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = rememberPermissionSnapshot()
    val items = PermissionHealth.items(snapshot, hub)
    var working by remember { mutableStateOf<Set<PermissionId>>(emptySet()) }
    var granting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun openSettings(id: PermissionId) {
        val intent = PermissionHealth.settingsIntent(context, id) ?: return
        runCatching { context.startActivity(intent) }.onFailure {
            // ROM 自带的设置页打不开（比如 MIUI 的权限页）：退回应用详情页
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun onItemClick(item: PermissionItem) {
        when (item.id) {
            // 重新检查时会再调一次 su，没授权过的话 root 管理器会在这时弹出询问
            PermissionId.Root -> hub.checkSetup()
            // 安装环境、登录账号都在 Linux 环境页里
            PermissionId.Linux -> onOpenLinux()
            else -> when {
                item.rootFixable -> {
                    working = working + item.id
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { PermissionHealth.fixWithRoot(context, item.id) }.getOrDefault(false) }
                        working = working - item.id
                        // root 也没改成：退回到系统设置页，让用户自己开
                        if (!ok) openSettings(item.id)
                    }
                }
                else -> openSettings(item.id)
            }
        }
    }

    val fixable = items.filter { it.rootFixable }
    CouiScaffoldPage(title = "权限健康", onBack = onBack) {
        Group(
            title = "权限与状态",
            footer = "对话进行时 Zeta 靠前台服务（通知栏里那一条）保持运行，加进电池优化的白名单后，长时间息屏也不容易被系统限制。" +
                "在最近任务里“全部清理”会把 Zeta 一起关掉，正在进行的对话不受影响，重新打开会接上。",
        ) {
            items.forEach { item ->
                val busy = item.id in working
                val clickable = !busy && !granting && isActionable(item, hub)
                CouiPreference(
                    title = item.title,
                    summary = item.summary,
                    value = if (busy) "正在处理…" else item.value,
                    valueColor = if (busy) Color.Unspecified else statusColor(item.status),
                    onClick = if (clickable) ({ onItemClick(item) }) else null,
                )
            }
        }
        Group(
            footer = "经 root 直接改系统设置：把 Zeta 加进电池优化白名单并解除后台限制，允许 MIUI 的后台弹出界面，授予通知权限，" +
                "在系统的无障碍服务列表末尾加上 Zeta（列表里别的服务原样保留），开启系统的通知历史。没改成的那一项，点它会打开对应的系统设置。",
        ) {
            CouiPreference(
                title = "一键授予",
                summary = when {
                    granting -> "正在授予…"
                    result != null -> result
                    hub.setup == SetupState.NoRoot -> "需要 root 权限"
                    hub.setup == SetupState.Checking -> "正在检查 root 权限"
                    fixable.isEmpty() -> "没有需要补上的权限"
                    else -> fixable.joinToString("、") { it.title }
                },
                enabled = fixable.isNotEmpty() && !granting && working.isEmpty(),
                showChevron = false,
                onClick = {
                    granting = true
                    result = null
                    scope.launch {
                        val failed = withContext(Dispatchers.IO) {
                            fixable.filterNot { item -> runCatching { PermissionHealth.fixWithRoot(context, item.id) }.getOrDefault(false) }
                        }
                        granting = false
                        result = if (failed.isEmpty()) "已全部授予" else "没有改成：" + failed.joinToString("、") { it.title } + "，点它到系统设置里处理"
                    }
                },
            )
        }
        CouiPageBottomSpacer()
    }
}

/** 这一项点了有没有事可做。 */
private fun isActionable(item: PermissionItem, hub: ConversationHub): Boolean = when (item.id) {
    PermissionId.Root -> item.status == PermissionStatus.Missing
    PermissionId.Linux -> hub.setup == SetupState.NoEnvironment || item.status == PermissionStatus.Attention
    else -> item.status != PermissionStatus.Ready && item.status != PermissionStatus.Checking &&
        (item.rootFixable || item.status != PermissionStatus.Idle)
}

@Composable
internal fun statusColor(status: PermissionStatus): Color = when (status) {
    PermissionStatus.Missing -> StatusError
    PermissionStatus.Attention -> StatusWarning
    else -> Color.Unspecified
}
