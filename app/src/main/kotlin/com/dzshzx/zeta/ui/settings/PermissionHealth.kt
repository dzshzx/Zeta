package com.dzshzx.zeta.ui.settings

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
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
import com.dzshzx.zeta.core.BackgroundStart
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.core.notify.ZetaNotificationListener
import com.dzshzx.zeta.core.schedule.ScheduleAlarms
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.AccessibilityGuard
import com.dzshzx.zeta.phone.tools.HealthReader
import com.dzshzx.zeta.phone.tools.HealthSummary
import com.dzshzx.zeta.phone.tools.PersonalTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 权限页上的一项。 */
internal enum class PermissionId {
    Root,
    Linux,
    Accessibility,
    Battery,
    BackgroundStart,
    Notifications,
    NotificationHistory,
    NotificationListener,
    ExactAlarm,
    AutoStart,
    Microphone,
    Health,
}

/** Ready 正常；Attention 能用但有问题；Missing 缺了会影响使用；Idle 没开但现在用不到；Checking 还在检查。 */
internal enum class PermissionStatus {
    Ready,
    Attention,
    Missing,
    Idle,
    Checking,
}

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
    val needsAttention: Boolean
        get() = status == PermissionStatus.Attention || status == PermissionStatus.Missing
}

/** 不经 root 就能读到的那些状态。 */
internal data class PermissionSnapshot(
    val accessibilityListed: Boolean,
    val accessibilityConnected: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val notificationsGranted: Boolean,
    val blockedChannels: List<String>,
    /** 系统的通知历史开没开；读不到时是 null。 */
    val notificationHistory: Boolean? = null,
    /** MIUI 的“后台弹出界面”给没给；不是 MIUI、读不到时是 null（不列这一项）。 */
    val backgroundStart: Boolean? = null,
    /** 能排精确闹钟（定时任务到点准时）。 */
    val exactAlarms: Boolean = false,
    /** HyperOS / MIUI 的“自启动”；不是小米系统或读不到时是 null。 */
    val autoStart: Boolean? = null,
    /** 有通知使用权（通知触发的任务用）。 */
    val notificationListener: Boolean = false,
    /** 有麦克风权限（按住说话用）。 */
    val microphone: Boolean = false,
    /** 健康连接的读权限都给了；这台手机没有健康连接时是 null（不列这一项）。 */
    val health: Boolean? = null,
)

/**
 * 权限检查与修复。Zeta 默认手机有 root：能经 root 改的就直接改（pm grant、appops、settings、deviceidle）， 改不了或没有 root
 * 时再打开对应的系统设置页。
 *
 * Zeta 没有声明使用情况访问、位置，悬浮层用的是无障碍窗口（不要悬浮窗权限），所以这几项不列。 通知使用权只有通知触发的任务用，见 [notificationListenerItem]。
 * 通知历史不是 Zeta 的权限，是系统设置里的一个开关：手机工具查已划掉的通知要靠它。
 */
internal object PermissionHealth {
    fun snapshot(context: Context): PermissionSnapshot {
        val packageName = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val service = ComponentName.unflattenFromString(AccessibilityGuard.SERVICE)
        // 列表里可能是完整类名，也可能是“包名/.类名”的简写，统一成 ComponentName 再比
        val listed = runCatching {
            Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
                .orEmpty()
                .split(':')
                .any { ComponentName.unflattenFromString(it.trim()) == service }
        }
            .getOrDefault(false)
        val granted =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED &&
                notifications?.areNotificationsEnabled() != false
        // 渠道是第一次发通知时才建的，还没建的不算被关
        val blocked =
            listOf(Notifier.CHANNEL_STATUS, Notifier.CHANNEL_ALERT).mapNotNull { id ->
                notifications
                    ?.getNotificationChannel(id)
                    ?.takeIf { it.importance == NotificationManager.IMPORTANCE_NONE }
                    ?.name
                    ?.toString()
            }
        return PermissionSnapshot(
            accessibilityListed = listed,
            accessibilityConnected = AccessibilityGuard.connected,
            ignoringBatteryOptimizations =
                power?.isIgnoringBatteryOptimizations(packageName) == true,
            backgroundRestricted = activity?.isBackgroundRestricted == true,
            notificationsGranted = granted,
            blockedChannels = blocked,
            notificationHistory = PersonalTools.notificationHistoryEnabled(context),
            backgroundStart = BackgroundStart.allowed(context),
            exactAlarms = ScheduleAlarms.exact(context),
            autoStart = miuiAutoStart(context),
            notificationListener = ZetaNotificationListener.granted(context),
            microphone =
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            health =
                if (HealthReader.available(context))
                    HealthReader.granted(context).containsAll(HealthSummary.PERMISSIONS)
                else null,
        )
    }

    /** HyperOS / MIUI 自启动的 appop。没有它时开机广播和闹钟都拉不起没在运行的 Zeta。 */
    private const val MIUI_AUTO_START = 10008

    /** 读小米系统自己的那项 appop（隐藏接口，经反射）；别的系统上这一项不存在，返回 null。 */
    private fun miuiAutoStart(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val check =
            AppOpsManager::class
                .java
                .getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
        check.invoke(ops, MIUI_AUTO_START, Process.myUid(), context.packageName) as Int ==
            AppOpsManager.MODE_ALLOWED
    }
        .getOrNull()

    /**
     * 页面上的各项，顺序是先常用后少用：root 和 Linux 环境、手机操作与后台、通知、按住说话， 再是 Claude 查通知历史和健康数据用的，最后是定时任务、通知触发才用的。
     * 摘要只写一行：这一项管什么或现在怎样；为什么需要、怎么修写在下面的注释里。
     */
    fun items(snapshot: PermissionSnapshot?, hub: ConversationHub): List<PermissionItem> {
        val setup = hub.environment.setup
        val root: Boolean? =
            when (setup) {
                SetupState.Checking -> null
                SetupState.NoRoot -> false
                else -> true
            }
        val checking = snapshot == null
        return listOfNotNull(
            // 进 Linux 环境、经 root 执行命令、一键授予都靠 root。没授权时到 KernelSU、Magisk 或 APatch 里允许，点这一项重新检查
            when (root) {
                null ->
                    PermissionItem(
                        PermissionId.Root,
                        Strings.settingsPermRoot(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                true ->
                    PermissionItem(
                        PermissionId.Root,
                        Strings.settingsPermRoot(),
                        Strings.settingsPermRootReadySummary(),
                        Strings.settingsPermGranted(),
                        PermissionStatus.Ready,
                        false,
                    )
                false ->
                    PermissionItem(
                        PermissionId.Root,
                        Strings.settingsPermRoot(),
                        Strings.settingsPermRootMissingSummary(),
                        Strings.settingsPermNotGranted(),
                        PermissionStatus.Missing,
                        false,
                    )
            },
            // Claude Code 运行在 Debian 环境里；安装和登录都在 Linux 环境页
            when (setup) {
                SetupState.Checking ->
                    PermissionItem(
                        PermissionId.Linux,
                        Strings.settingsTitleLinux(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                SetupState.NoRoot ->
                    PermissionItem(
                        PermissionId.Linux,
                        Strings.settingsTitleLinux(),
                        Strings.settingsPermLinuxNeedsRoot(),
                        Strings.settingsPermUnavailable(),
                        PermissionStatus.Idle,
                        false,
                    )
                SetupState.NoEnvironment ->
                    PermissionItem(
                        PermissionId.Linux,
                        Strings.settingsTitleLinux(),
                        Strings.settingsPermLinuxInstall(),
                        Strings.commonNotInstalled(),
                        PermissionStatus.Missing,
                        false,
                    )
                SetupState.Ready -> {
                    val account = hub.environment.account
                    val user = hub.settings.env?.user
                    if (account != null && !account.loggedIn) {
                        PermissionItem(
                            PermissionId.Linux,
                            Strings.settingsTitleLinux(),
                            Strings.settingsPermLinuxSignIn(),
                            Strings.settingsAccountsSignedOut(),
                            PermissionStatus.Attention,
                            false,
                        )
                    } else {
                        PermissionItem(
                            PermissionId.Linux,
                            Strings.settingsTitleLinux(),
                            listOfNotNull(user, account?.email).joinToString(" · ").ifBlank {
                                null
                            },
                            Strings.settingsPermReady(),
                            PermissionStatus.Ready,
                            false,
                        )
                    }
                }
            },
            // 读控件列表、点按、输入中文、做手势都靠无障碍服务。
            // 有 root 时 Claude 用到手机工具会自动打开它，没开不算问题；系统里开着却没连上时，点这一项经 root 拿掉再加回，让系统重新绑定
            when {
                checking ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                snapshot.accessibilityConnected ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        Strings.settingsPermA11yReady(),
                        Strings.settingsPermConnected(),
                        PermissionStatus.Ready,
                        false,
                    )
                !hub.settings.phoneTools ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        Strings.settingsPermA11yPhoneOff(),
                        Strings.commonNotEnabled(),
                        PermissionStatus.Idle,
                        false,
                    )
                snapshot.accessibilityListed ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        Strings.settingsPermA11yReconnect(),
                        Strings.settingsPermNotConnected(),
                        PermissionStatus.Attention,
                        root == true,
                    )
                root == true ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        Strings.settingsPermA11yAuto(),
                        Strings.commonNotEnabled(),
                        PermissionStatus.Idle,
                        true,
                    )
                else ->
                    PermissionItem(
                        PermissionId.Accessibility,
                        Strings.settingsPermAccessibility(),
                        Strings.settingsPermA11yNoRoot(),
                        Strings.commonNotEnabled(),
                        PermissionStatus.Missing,
                        false,
                    )
            },
            // 电池优化白名单：长时间息屏时前台服务不容易被系统限制。电池设置里的“限制后台”另算，经 root 一起放开
            when {
                checking ->
                    PermissionItem(
                        PermissionId.Battery,
                        Strings.settingsPermBattery(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                !snapshot.ignoringBatteryOptimizations ->
                    PermissionItem(
                        PermissionId.Battery,
                        Strings.settingsPermBattery(),
                        Strings.settingsPermBatterySummary(),
                        Strings.commonNotAllowed(),
                        PermissionStatus.Missing,
                        root == true,
                    )
                snapshot.backgroundRestricted ->
                    PermissionItem(
                        PermissionId.Battery,
                        Strings.settingsPermBattery(),
                        Strings.settingsPermBatteryRestricted(),
                        Strings.settingsPermRestricted(),
                        PermissionStatus.Attention,
                        root == true,
                    )
                else ->
                    PermissionItem(
                        PermissionId.Battery,
                        Strings.settingsPermBattery(),
                        Strings.settingsPermBatterySummary(),
                        Strings.commonAllowed(),
                        PermissionStatus.Ready,
                        false,
                    )
            },
            backgroundStartItem(snapshot, root),
            // 两类通知就是两个渠道的名字（AgentService）：运行状态、完成与确认提醒。权限没给和某类被关，都到应用的通知设置里改
            when {
                checking ->
                    PermissionItem(
                        PermissionId.Notifications,
                        Strings.settingsPermNotifications(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                !snapshot.notificationsGranted ->
                    PermissionItem(
                        PermissionId.Notifications,
                        Strings.settingsPermNotifications(),
                        Strings.settingsPermNotificationsSummary(),
                        Strings.commonNotAllowed(),
                        PermissionStatus.Missing,
                        root == true,
                    )
                snapshot.blockedChannels.isNotEmpty() ->
                    PermissionItem(
                        PermissionId.Notifications,
                        Strings.settingsPermNotifications(),
                        Strings.settingsPermNotificationsBlocked(
                            snapshot.blockedChannels.joinToString(Strings.commonListSeparator()),
                        ),
                        Strings.settingsPermPartlyOff(),
                        PermissionStatus.Attention,
                        false,
                    )
                else ->
                    PermissionItem(
                        PermissionId.Notifications,
                        Strings.settingsPermNotifications(),
                        Strings.settingsPermNotificationsSummary(),
                        Strings.commonAllowed(),
                        PermissionStatus.Ready,
                        false,
                    )
            },
            microphoneItem(snapshot, root),
            // 系统自带的功能：开了以后系统保留 24 小时内的通知，Claude 才查得到已经划掉的通知（比如找验证码）
            when {
                checking ->
                    PermissionItem(
                        PermissionId.NotificationHistory,
                        Strings.settingsPermNotificationHistory(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                snapshot.notificationHistory == true ->
                    PermissionItem(
                        PermissionId.NotificationHistory,
                        Strings.settingsPermNotificationHistory(),
                        Strings.settingsPermHistoryOn(),
                        Strings.commonOn(),
                        PermissionStatus.Ready,
                        false,
                    )
                else ->
                    PermissionItem(
                        PermissionId.NotificationHistory,
                        Strings.settingsPermNotificationHistory(),
                        Strings.settingsPermHistoryOff(),
                        Strings.commonNotEnabled(),
                        PermissionStatus.Idle,
                        root == true,
                    )
            },
            healthItem(snapshot, root),
        ) + scheduleItems(snapshot, root) + notificationListenerItem(snapshot, root)
    }

    /**
     * 定时任务要用的两项：有开着的任务时缺了算问题，没有任务时只是提示。 没有精确闹钟时系统可能把到点推迟几分钟到更久；没有自启动时开机后定时任务不会重新排上，Zeta
     * 没在运行时到点也拉不起来。
     */
    private fun scheduleItems(snapshot: PermissionSnapshot?, root: Boolean?): List<PermissionItem> {
        val needed = ScheduleStore.observed.any { it.enabled }
        val missing = if (needed) PermissionStatus.Missing else PermissionStatus.Idle
        if (snapshot == null)
            return listOf(
                PermissionItem(
                    PermissionId.ExactAlarm,
                    Strings.settingsPermExactAlarm(),
                    null,
                    Strings.commonChecking(),
                    PermissionStatus.Checking,
                    false,
                ),
            )
        val exact =
            if (snapshot.exactAlarms) {
                PermissionItem(
                    PermissionId.ExactAlarm,
                    Strings.settingsPermExactAlarm(),
                    Strings.settingsPermExactOn(),
                    Strings.commonAllowed(),
                    PermissionStatus.Ready,
                    false,
                )
            } else {
                PermissionItem(
                    PermissionId.ExactAlarm,
                    Strings.settingsPermExactAlarm(),
                    Strings.settingsPermExactOff(),
                    Strings.commonNotAllowed(),
                    missing,
                    root == true,
                )
            }
        val autoStart =
            when (snapshot.autoStart) {
                null -> null
                true ->
                    PermissionItem(
                        PermissionId.AutoStart,
                        Strings.settingsPermAutoStart(),
                        Strings.settingsPermAutoStartOn(),
                        Strings.commonAllowed(),
                        PermissionStatus.Ready,
                        false,
                    )
                false ->
                    PermissionItem(
                        PermissionId.AutoStart,
                        Strings.settingsPermAutoStart(),
                        Strings.settingsPermAutoStartOff(),
                        Strings.commonNotAllowed(),
                        missing,
                        root == true,
                    )
            }
        return listOfNotNull(exact, autoStart)
    }

    /** 麦克风：只有按住说话用（输入框空着时按住发送键），系统的语音识别服务替 Zeta 录音；没给时不算问题。 */
    private fun microphoneItem(snapshot: PermissionSnapshot?, root: Boolean?): PermissionItem =
        when {
            snapshot == null ->
                PermissionItem(
                    PermissionId.Microphone,
                    Strings.settingsPermMicrophone(),
                    null,
                    Strings.commonChecking(),
                    PermissionStatus.Checking,
                    false,
                )
            snapshot.microphone ->
                PermissionItem(
                    PermissionId.Microphone,
                    Strings.settingsPermMicrophone(),
                    Strings.settingsPermMicSummary(),
                    Strings.commonAllowed(),
                    PermissionStatus.Ready,
                    false,
                )
            else ->
                PermissionItem(
                    PermissionId.Microphone,
                    Strings.settingsPermMicrophone(),
                    Strings.settingsPermMicSummary(),
                    Strings.commonNotAllowed(),
                    PermissionStatus.Idle,
                    root == true,
                )
        }

    /** 健康连接的读权限：Claude 查步数、睡眠、运动、心率、体重、血氧时用（小米运动健康同步进去的，只读）， 没给只是查不到，不算问题。没有健康连接时不列。 */
    private fun healthItem(snapshot: PermissionSnapshot?, root: Boolean?): PermissionItem? =
        when (snapshot?.health) {
            null ->
                if (snapshot == null)
                    PermissionItem(
                        PermissionId.Health,
                        Strings.settingsPermHealth(),
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                else null
            true ->
                PermissionItem(
                    PermissionId.Health,
                    Strings.settingsPermHealth(),
                    Strings.settingsPermHealthOn(),
                    Strings.settingsPermGranted(),
                    PermissionStatus.Ready,
                    false,
                )
            false ->
                PermissionItem(
                    PermissionId.Health,
                    Strings.settingsPermHealth(),
                    Strings.settingsPermHealthOff(),
                    Strings.settingsPermNotGranted(),
                    PermissionStatus.Idle,
                    root == true,
                )
        }

    /**
     * MIUI 的“后台弹出界面”：从胶囊点对话标题回到 Zeta 时要用它。不是 MIUI（读不到）就不列。 没给时 MIUI 拦下 Zeta 在后台打开自己的界面：有 root 时绕道
     * root 拉起，慢一些；没有 root 就回不去。
     */
    private fun backgroundStartItem(
        snapshot: PermissionSnapshot?,
        root: Boolean?,
    ): PermissionItem? {
        val title = Strings.settingsPermBackgroundStart()
        return when (snapshot?.backgroundStart) {
            null ->
                if (snapshot == null)
                    PermissionItem(
                        PermissionId.BackgroundStart,
                        title,
                        null,
                        Strings.commonChecking(),
                        PermissionStatus.Checking,
                        false,
                    )
                else null
            true ->
                PermissionItem(
                    PermissionId.BackgroundStart,
                    title,
                    Strings.settingsPermBgStartOn(),
                    Strings.commonAllowed(),
                    PermissionStatus.Ready,
                    false,
                )
            false ->
                PermissionItem(
                    PermissionId.BackgroundStart,
                    title,
                    if (root == true) Strings.settingsPermBgStartSlow()
                    else Strings.settingsPermBgStartBlocked(),
                    Strings.commonNotAllowed(),
                    if (root == true) PermissionStatus.Attention else PermissionStatus.Missing,
                    root == true,
                )
        }
    }

    /** 经 root 修好 [id]（在后台线程调用）。只改 Zeta 自己的条目： 无障碍列表是在原有内容后面追加，别的应用的服务原样保留。返回执行后它是否好了。 */
    fun fixWithRoot(context: Context, id: PermissionId): Boolean {
        val app = context.applicationContext
        val pkg = app.packageName
        val user = LinuxEnv.androidUser.toString()
        return when (id) {
            PermissionId.Accessibility -> {
                // 先放开“受限设置”：侧载的应用不放开的话，用户在系统设置里也开不了它的无障碍服务
                runScript(
                    "cmd appops set --user \"\$3\" \"\$2\" ACCESS_RESTRICTED_SETTINGS allow",
                    pkg,
                    user,
                )
                if (snapshot(app).accessibilityListed && !AccessibilityGuard.connected) {
                    // 列表里有但没连上：把 Zeta 自己这一项拿掉再加回去，让系统重新绑定
                    AccessibilityGuard.remove(app, "权限健康页重新绑定")
                    Thread.sleep(400)
                }
                AccessibilityGuard.enable(app, "权限健康页")
            }
            PermissionId.Battery -> {
                runScript(
                    "cmd deviceidle whitelist +\"\$2\" >/dev/null; cmd appops set --user \"\$3\" \"\$2\" RUN_ANY_IN_BACKGROUND allow",
                    pkg,
                    user,
                )
                snapshot(app).let { it.ignoringBatteryOptimizations && !it.backgroundRestricted }
            }
            PermissionId.BackgroundStart -> {
                runScript(BackgroundStart.GRANT_SCRIPT, pkg, user)
                BackgroundStart.allowed(app) == true
            }
            PermissionId.Notifications -> {
                runScript(
                    "pm grant --user \"\$3\" \"\$2\" android.permission.POST_NOTIFICATIONS && " +
                        // 清掉“不再询问”，以后需要时还能正常弹出系统的授权框
                        "pm clear-permission-flags --user \"\$3\" \"\$2\" android.permission.POST_NOTIFICATIONS user-fixed",
                    pkg,
                    user,
                )
                snapshot(app).notificationsGranted
            }
            PermissionId.NotificationHistory -> {
                runScript(
                    "settings put --user \"\$3\" secure \"\$2\" 1",
                    PersonalTools.NOTIFICATION_HISTORY_KEY,
                    user,
                )
                PersonalTools.notificationHistoryEnabledOrRoot(app)
            }
            PermissionId.ExactAlarm -> {
                runScript(
                    "cmd appops set --user \"\$3\" \"\$2\" SCHEDULE_EXACT_ALARM allow",
                    pkg,
                    user,
                )
                // 系统给了权限会发广播让应用重排；这里也直接重排一次，不等广播
                ScheduleAlarms.exact(app).also {
                    if (it) ScheduleAlarms.sync(app, ScheduleStore.load(app))
                }
            }
            PermissionId.AutoStart -> {
                runScript("appops set --user \"\$3\" \"\$2\" $MIUI_AUTO_START allow", pkg, user)
                miuiAutoStart(app) == true
            }
            PermissionId.NotificationListener -> {
                runScript(
                    ZetaNotificationListener.GRANT_SCRIPT,
                    ZetaNotificationListener.COMPONENT,
                    user,
                )
                ZetaNotificationListener.granted(app)
            }
            PermissionId.Microphone -> {
                runScript(
                    "pm grant --user \"\$3\" \"\$2\" android.permission.RECORD_AUDIO && " +
                        "pm clear-permission-flags --user \"\$3\" \"\$2\" android.permission.RECORD_AUDIO user-fixed",
                    pkg,
                    user,
                )
                snapshot(app).microphone
            }
            PermissionId.Health -> {
                runScript(
                    "for p in \$4; do pm grant --user \"\$3\" \"\$2\" \"\$p\"; done",
                    pkg,
                    user,
                    HealthSummary.PERMISSIONS.joinToString(" "),
                )
                HealthReader.granted(app).containsAll(HealthSummary.PERMISSIONS)
            }
            PermissionId.Root,
            PermissionId.Linux -> false
        }
    }

    /** 修不了时去的系统设置页。 */
    fun settingsIntent(context: Context, id: PermissionId): Intent? {
        val pkg = context.packageName
        val details =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", pkg, null),
            )
        return when (id) {
            PermissionId.Accessibility -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            // 系统的“电池优化”列表在各家 ROM 上入口不一，应用详情页里都能改
            PermissionId.Battery -> details
            // MIUI 安全中心的“其他权限”页，里面有“后台弹出界面”；打不开时 PermissionHealthScreen 退回应用详情页
            PermissionId.BackgroundStart ->
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg)
            // 权限没给和某类通知被关，都在应用的通知设置页里改
            PermissionId.Notifications ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            PermissionId.NotificationHistory -> Intent("android.settings.NOTIFICATION_HISTORY")
            PermissionId.ExactAlarm ->
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", pkg, null),
                )
            // 小米系统的自启动开关在应用详情页里
            PermissionId.AutoStart -> details
            PermissionId.NotificationListener ->
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            // 运行时权限在应用详情页的“权限”里
            PermissionId.Microphone -> details
            // 健康连接里 Zeta 自己的权限页
            PermissionId.Health ->
                Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            PermissionId.Root,
            PermissionId.Linux -> null
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun runScript(script: String, vararg arguments: String) {
        runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script, *arguments), 15_000) }
    }
}

/** 页面显示期间每秒重新读一次状态；回到前台（比如从系统设置返回）时立即刷新。 都是本地查询，不经 root。 */
@Composable
internal fun rememberPermissionSnapshot(): PermissionSnapshot? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember { mutableStateOf<PermissionSnapshot?>(null) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                snapshot =
                    withContext(Dispatchers.IO) {
                        runCatching { PermissionHealth.snapshot(context.applicationContext) }
                            .getOrNull()
                    }
                delay(1_000)
            }
        }
    }
    return snapshot
}
