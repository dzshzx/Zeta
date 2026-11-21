package com.dzshzx.zeta.ui.settings

import android.content.Context
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
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** “权限健康”：Zeta 需要或能用到的权限和状态，每项点一下就去修；有 root 时可以一键授予。 */
@Composable
internal fun PermissionHealthScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    onOpenLinux: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot = rememberPermissionSnapshot()
    val items = PermissionHealth.items(snapshot, hub)
    var working by remember { mutableStateOf<Set<PermissionId>>(emptySet()) }
    var granting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    fun onItemClick(item: PermissionItem) {
        when (item.id) {
            // 重新检查时会再调一次 su，没授权过的话 root 管理器会在这时弹出询问
            PermissionId.Root -> hub.environment.checkSetup()
            // 安装环境、登录账号都在 Linux 环境页里
            PermissionId.Linux -> onOpenLinux()
            else ->
                fixItem(
                    context,
                    item,
                    scope,
                    onStart = { working = working + item.id },
                    onDone = { working = working - item.id },
                )
        }
    }

    val fixable = items.filter { it.rootFixable }
    CouiPage(title = "权限健康", onBack = onBack) {
        item(key = "items") {
            // 对话进行时 Zeta 靠前台服务（通知栏里那一条）保持运行；对话本身跑在 Linux 环境里，Zeta 被关掉也照样跑完
            PreferenceGroup(title = "权限与状态", footer = "清理最近任务会关掉 Zeta，进行中的对话不受影响，重新打开会接上。") {
                items.forEach { entry ->
                    item(key = entry.id) {
                        val busy = entry.id in working
                        PermissionRow(
                            entry = entry,
                            busy = busy,
                            clickable = !busy && !granting && isActionable(entry, hub),
                            onClick = { onItemClick(entry) },
                        )
                    }
                }
            }
        }
        item(key = "grant") {
            // 一键授予经 root 逐项改系统设置（做法见 PermissionHealth.fixWithRoot）：电池白名单并解除后台限制、MIUI 后台弹出界面、
            // 通知、无障碍（只在系统列表末尾加上 Zeta，别的服务原样保留）、通知历史、精确闹钟、自启动、通知使用权（有开着的通知触发规则时）、
            // 麦克风、健康连接的读权限。摘要只写几项，是哪几项看上面各行的状态
            PreferenceGroup(footer = "经 root 直接改系统设置。没改成的那一项，点它会打开对应的系统设置。") {
                item {
                    BasicComponent(
                        title = "一键授予",
                        summary =
                            grantSummary(granting, result, hub.environment.setup, fixable.size),
                        enabled = fixable.isNotEmpty() && !granting && working.isEmpty(),
                        onClick = {
                            granting = true
                            result = null
                            scope.launch {
                                val failed = grantWithRoot(context, fixable)
                                granting = false
                                result = grantResult(failed)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 修 Root、Linux 以外的一项：能经 root 改的就地改（开始时调 [onStart]，改完调 [onDone]），别的打开系统设置。 */
private fun fixItem(
    context: Context,
    item: PermissionItem,
    scope: CoroutineScope,
    onStart: () -> Unit,
    onDone: () -> Unit,
) {
    if (!item.rootFixable) {
        openPermissionSettings(context, item.id)
        return
    }
    onStart()
    scope.launch {
        fixPermission(context, item.id, tryRoot = true)
        onDone()
    }
}

/** 权限健康的一行；点了打开别的页面（Linux 环境页、系统设置）的带箭头，就地经 root 处理的不带。 */
@Composable
private fun PermissionRow(
    entry: PermissionItem,
    busy: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
) {
    val value: @Composable () -> Unit = {
        PreferenceValue(
            if (busy) "正在处理…" else entry.value,
            color = if (busy) Color.Unspecified else statusColor(entry.status),
        )
    }
    if (clickable && opensPage(entry)) {
        ArrowPreference(
            title = entry.title,
            summary = entry.summary,
            endActions = { value() },
            onClick = onClick,
        )
    } else {
        BasicComponent(
            title = entry.title,
            summary = entry.summary,
            endActions = { value() },
            onClick = if (clickable) onClick else null,
        )
    }
}

/** 一键授予下面的一行说明：正在授予、上次的结果，或者现在能补上几项。 */
private fun grantSummary(
    granting: Boolean,
    result: String?,
    setup: SetupState,
    fixable: Int,
): String =
    when {
        granting -> "正在授予…"
        result != null -> result
        setup == SetupState.NoRoot -> "需要 root 权限"
        setup == SetupState.Checking -> "正在检查 root 权限"
        fixable == 0 -> "没有需要补上的权限"
        else -> "有 $fixable 项可以补上"
    }

private fun grantResult(failed: Int): String = if (failed == 0) "已全部授予" else "有 $failed 项没改成"

/** 经 root 逐项改，返回没改成的项数。 */
private suspend fun grantWithRoot(context: Context, fixable: List<PermissionItem>): Int =
    withContext(Dispatchers.IO) {
        fixable
            .filterNot { entry ->
                runCatching { PermissionHealth.fixWithRoot(context, entry.id) }.getOrDefault(false)
            }
            .size
    }

/** 打开系统设置里管 [id] 的那一页；ROM 自带的设置页打不开（比如 MIUI 的权限页）时退回应用详情页。 */
internal fun openPermissionSettings(context: Context, id: PermissionId) {
    val intent = PermissionHealth.settingsIntent(context, id) ?: return
    runCatching { context.startActivity(intent) }
        .onFailure {
            val details =
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            runCatching { context.startActivity(details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
}

/** 修一项权限：[tryRoot] 时先经 root 直接改，没改成（或不试 root）就打开系统设置让用户自己开。 */
internal suspend fun fixPermission(context: Context, id: PermissionId, tryRoot: Boolean) {
    val ok =
        tryRoot &&
            withContext(Dispatchers.IO) {
                runCatching { PermissionHealth.fixWithRoot(context, id) }.getOrDefault(false)
            }
    if (!ok) openPermissionSettings(context, id)
}

/** 这一项点了有没有事可做。 */
private fun isActionable(item: PermissionItem, hub: ConversationHub): Boolean =
    when (item.id) {
        PermissionId.Root -> item.status == PermissionStatus.Missing
        PermissionId.Linux ->
            hub.environment.setup == SetupState.NoEnvironment ||
                item.status == PermissionStatus.Attention
        else ->
            item.status != PermissionStatus.Ready &&
                item.status != PermissionStatus.Checking &&
                (item.rootFixable || item.status != PermissionStatus.Idle)
    }

/** 点这一项是打开别的页面（Linux 环境页、系统设置），而不是就地经 root 处理（重新检查 root、一键改好）。 */
private fun opensPage(item: PermissionItem): Boolean =
    when (item.id) {
        PermissionId.Linux -> true
        PermissionId.Root -> false
        else -> !item.rootFixable
    }

/** 状态的颜色：缺了是错误色，要注意是警告色，其余 Unspecified（用设置行值的默认颜色）。 */
@Composable
internal fun statusColor(status: PermissionStatus): Color =
    when (status) {
        PermissionStatus.Missing -> COUITheme.colorScheme.error
        PermissionStatus.Attention -> StatusWarning
        else -> Color.Unspecified
    }
