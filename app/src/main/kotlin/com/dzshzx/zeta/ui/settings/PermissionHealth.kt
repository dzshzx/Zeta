package com.dzshzx.zeta.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.phone.PhoneTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 权限页上的一项。 */
internal enum class PermissionId { Root, Linux, Accessibility, Battery, Notifications }

/** Ready 正常；Attention 能用但有问题；Missing 缺了会影响使用；Idle 没开但现在用不到；Checking 还在检查。 */
internal enum class PermissionStatus { Ready, Attention, Missing, Idle, Checking }

internal data class PermissionItem(
    val id: PermissionId,
    val title: String,
    val summary: String?,
    val value: String,
    val status: PermissionStatus,
    /** 一键授予能经 root 处理它。 */
    val rootFixable: Boolean,
) {
    /** 算进“几项需关注”里。 */
    val needsAttention: Boolean get() = status == PermissionStatus.Attention || status == PermissionStatus.Missing
}

/** 不经 root 就能读到的那些状态。 */
internal data class PermissionSnapshot(
    val accessibilityListed: Boolean,
    val accessibilityConnected: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsGranted: Boolean,
    val blockedChannels: List<String>,
)

/**
 * 权限检查与修复。Zeta 默认手机有 root：能经 root 改的就直接改（pm grant、appops、settings、deviceidle），
 * 改不了或没有 root 时再打开对应的系统设置页。
 *
 * Zeta 没有声明通知使用权、使用情况访问、位置，悬浮层用的是无障碍窗口（不要悬浮窗权限），所以这几项不列。
 */
internal object PermissionHealth {
    fun snapshot(context: Context): PermissionSnapshot {
        val packageName = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val service = ComponentName.unflattenFromString(PhoneTools.SERVICE)
        // 列表里可能是完整类名，也可能是“包名/.类名”的简写，统一成 ComponentName 再比
        val listed = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').any { ComponentName.unflattenFromString(it.trim()) == service }
        }.getOrDefault(false)
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            notifications?.areNotificationsEnabled() != false
        // 渠道是第一次发通知时才建的，还没建的不算被关
        val blocked = listOf(Notifier.CHANNEL_STATUS, Notifier.CHANNEL_ALERT).mapNotNull { id ->
            notifications?.getNotificationChannel(id)?.takeIf { it.importance == NotificationManager.IMPORTANCE_NONE }?.name?.toString()
        }
        return PermissionSnapshot(
            accessibilityListed = listed,
            accessibilityConnected = PhoneTools.accessibilityConnected,
            ignoringBatteryOptimizations = power?.isIgnoringBatteryOptimizations(packageName) == true,
            backgroundRestricted = activity?.isBackgroundRestricted == true,
            notificationsGranted = granted,
            blockedChannels = blocked,
        )
    }

    fun items(snapshot: PermissionSnapshot?, hub: ConversationHub): List<PermissionItem> {
        val setup = hub.setup
        val root: Boolean? = when (setup) {
            SetupState.Checking -> null
            SetupState.NoRoot -> false
            else -> true
        }
        val checking = snapshot == null
        return listOf(
            when (root) {
                null -> PermissionItem(PermissionId.Root, "Root 权限", null, "检查中", PermissionStatus.Checking, false)
                true -> PermissionItem(PermissionId.Root, "Root 权限", "进入 Linux 环境、经 root 执行命令和授予下面这些权限都靠它", "已授权", PermissionStatus.Ready, false)
                false -> PermissionItem(
                    PermissionId.Root, "Root 权限", "在 KernelSU、Magisk 或 APatch 里允许 Zeta 使用 root，然后点这里重新检查",
                    "未授权", PermissionStatus.Missing, false,
                )
            },
            when (setup) {
                SetupState.Checking -> PermissionItem(PermissionId.Linux, "Linux 环境", null, "检查中", PermissionStatus.Checking, false)
                SetupState.NoRoot -> PermissionItem(PermissionId.Linux, "Linux 环境", "需要先有 root 权限", "不可用", PermissionStatus.Idle, false)
                SetupState.NoEnvironment -> PermissionItem(
                    PermissionId.Linux, "Linux 环境", "Claude Code 运行在 Debian 环境里，点这里去安装", "未安装", PermissionStatus.Missing, false,
                )
                SetupState.Ready -> {
                    val account = hub.account
                    val user = hub.env?.user
                    if (account != null && !account.loggedIn) {
                        PermissionItem(PermissionId.Linux, "Linux 环境", listOfNotNull(user, "还没登录 Claude 账号，点这里去登录").joinToString(" · "), "未登录", PermissionStatus.Attention, false)
                    } else {
                        PermissionItem(PermissionId.Linux, "Linux 环境", listOfNotNull(user, account?.email).joinToString(" · ").ifBlank { null }, "已就绪", PermissionStatus.Ready, false)
                    }
                }
            },
            when {
                checking -> PermissionItem(PermissionId.Accessibility, "无障碍服务", null, "检查中", PermissionStatus.Checking, false)
                snapshot.accessibilityConnected -> PermissionItem(PermissionId.Accessibility, "无障碍服务", "读屏、点按、输入中文、做手势都靠它", "已连接", PermissionStatus.Ready, false)
                !hub.phoneTools -> PermissionItem(PermissionId.Accessibility, "无障碍服务", "手机操作已关闭，现在用不到它", "未开启", PermissionStatus.Idle, false)
                snapshot.accessibilityListed -> PermissionItem(
                    PermissionId.Accessibility, "无障碍服务", "系统里已开启，但服务没有连上。点这里经 root 重新开一次",
                    "未连上", PermissionStatus.Attention, root == true,
                )
                // 有 root 时 Claude 用到手机工具会自动打开它，没开不算问题
                root == true -> PermissionItem(
                    PermissionId.Accessibility, "无障碍服务", "Claude 用到手机工具时会经 root 自动打开；也可以点这里现在就打开",
                    "未开启", PermissionStatus.Idle, true,
                )
                else -> PermissionItem(
                    PermissionId.Accessibility, "无障碍服务", "读屏、点按、输入中文都靠它。没有 root 时要到系统设置里手动开启",
                    "未开启", PermissionStatus.Missing, false,
                )
            },
            when {
                checking -> PermissionItem(PermissionId.Battery, "后台运行", null, "检查中", PermissionStatus.Checking, false)
                !snapshot.ignoringBatteryOptimizations -> PermissionItem(
                    PermissionId.Battery, "后台运行", "加进电池优化的白名单，长时间息屏时对话也不容易被系统限制",
                    "未允许", PermissionStatus.Missing, root == true,
                )
                snapshot.backgroundRestricted -> PermissionItem(
                    PermissionId.Battery, "后台运行", "电池设置里把 Zeta 设成了“限制后台”，切到后台后对话可能被停掉",
                    "受限制", PermissionStatus.Attention, root == true,
                )
                else -> PermissionItem(PermissionId.Battery, "后台运行", "已加入电池优化白名单", "已允许", PermissionStatus.Ready, false)
            },
            when {
                checking -> PermissionItem(PermissionId.Notifications, "通知", null, "检查中", PermissionStatus.Checking, false)
                !snapshot.notificationsGranted -> PermissionItem(
                    PermissionId.Notifications, "通知", "通知栏里的运行状态，以及回答完成、需要你确认时的提醒",
                    "未允许", PermissionStatus.Missing, root == true,
                )
                snapshot.blockedChannels.isNotEmpty() -> PermissionItem(
                    PermissionId.Notifications, "通知", "这几类通知被关掉了：" + snapshot.blockedChannels.joinToString("、") + "。点这里到系统设置里打开",
                    "部分关闭", PermissionStatus.Attention, false,
                )
                else -> PermissionItem(PermissionId.Notifications, "通知", "运行状态，以及回答完成、需要你确认时的提醒", "已允许", PermissionStatus.Ready, false)
            },
        )
    }

    /**
     * 经 root 修好 [id]（在后台线程调用）。只改 Zeta 自己的条目：
     * 无障碍列表是在原有内容后面追加，别的应用的服务原样保留。返回执行后它是否好了。
     */
    fun fixWithRoot(context: Context, id: PermissionId): Boolean {
        val app = context.applicationContext
        val pkg = app.packageName
        val user = (Process.myUid() / 100_000).toString()
        return when (id) {
            PermissionId.Accessibility -> {
                // 先放开“受限设置”：侧载的应用不放开的话，用户在系统设置里也开不了它的无障碍服务
                runScript("cmd appops set --user \"\$3\" \"\$2\" ACCESS_RESTRICTED_SETTINGS allow", pkg, user)
                if (snapshot(app).accessibilityListed && !PhoneTools.accessibilityConnected) {
                    // 列表里有但没连上：把 Zeta 自己这一项拿掉再加回去，让系统重新绑定
                    PhoneTools.disableAccessibility()
                    Thread.sleep(400)
                }
                PhoneTools.enableAccessibility(app)
            }
            PermissionId.Battery -> {
                runScript(
                    "cmd deviceidle whitelist +\"\$2\" >/dev/null; cmd appops set --user \"\$3\" \"\$2\" RUN_ANY_IN_BACKGROUND allow",
                    pkg, user,
                )
                snapshot(app).let { it.ignoringBatteryOptimizations && !it.backgroundRestricted }
            }
            PermissionId.Notifications -> {
                runScript(
                    "pm grant --user \"\$3\" \"\$2\" android.permission.POST_NOTIFICATIONS && " +
                        // 清掉“不再询问”，以后需要时还能正常弹出系统的授权框
                        "pm clear-permission-flags --user \"\$3\" \"\$2\" android.permission.POST_NOTIFICATIONS user-fixed",
                    pkg, user,
                )
                snapshot(app).notificationsGranted
            }
            PermissionId.Root, PermissionId.Linux -> false
        }
    }

    /** 修不了时去的系统设置页。 */
    fun settingsIntent(context: Context, id: PermissionId): Intent? {
        val pkg = context.packageName
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
        return when (id) {
            PermissionId.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            // 系统的“电池优化”列表在各家 ROM 上入口不一，应用详情页里都能改
            PermissionId.Battery -> details
            // 权限没给和某类通知被关，都在应用的通知设置页里改
            PermissionId.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            PermissionId.Root, PermissionId.Linux -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun runScript(script: String, vararg arguments: String) {
        runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script, *arguments), 15_000) }
    }
}

/**
 * 页面显示期间每秒重新读一次状态；回到前台（比如从系统设置返回）时立即刷新。
 * 都是本地查询，不经 root。
 */
@Composable
internal fun rememberPermissionSnapshot(): PermissionSnapshot? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember { mutableStateOf<PermissionSnapshot?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                snapshot = withContext(Dispatchers.IO) { runCatching { PermissionHealth.snapshot(context.applicationContext) }.getOrNull() }
                delay(1_000)
            }
        }
    }
    return snapshot
}
