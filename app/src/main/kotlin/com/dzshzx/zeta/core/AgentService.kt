package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import com.dzshzx.zeta.R
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.phone.ScreenCapture
import com.dzshzx.zeta.ui.MainActivity

internal object Notifier {
    /** 前台服务那条只为保活的通知：最低重要度，折叠在通知栏底部，不上锁屏、不上状态栏。 */
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_ALERT = "alert"

    /**
     * 已经不用的渠道，还在就删掉：“waiting”“island_waiting”是测试版上等用户时常驻通知临时换过去的；“status”是常驻通知上岛时的
     * 渠道（低重要度，锁屏上也显示），每个对话改成各发一条以后保活的那条换到 [CHANNEL_SERVICE]。
     */
    private val RETIRED_CHANNELS = listOf("waiting", "island_waiting", "status")

    /** 有新版本（core/update/AppUpdates.kt）：同一个版本只发一次。 */
    const val CHANNEL_UPDATE = "update"

    /** 通知 id：各类各一个，同一类里靠 tag 区分（定时任务、通知触发按“来源:id”各一条）。 */
    const val ID_STATUS = 1
    const val ID_ALERT = 2
    const val ID_SCHEDULE = 3
    const val ID_ACCESSIBILITY = 4
    const val ID_UPDATE = 5

    /** 响过的请求号：同一个请求只响一次（离开再回来、对话那条重发时不再响）。在主线程上用。 */
    private val rung = HashSet<String>()

    /** 请求 [requestId] 还没响过就记下、返回 true（这次该响）；响过返回 false。 */
    fun firstRing(requestId: String): Boolean = rung.add(requestId)

    /** 只留还在等的请求号 [waiting]，别的忘掉。 */
    fun forgetRings(waiting: Set<String>) {
        rung.retainAll(waiting)
    }

    /** 不提醒的那几条通知归的组，见 [post] 的 silent。 */
    private const val SILENT_GROUP = "silent"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                Strings.coreChannelService(),
                NotificationManager.IMPORTANCE_MIN,
            ),
        )
        // 测试版的提醒不出声：测试时对话跑完、等确认，不该打扰正在用手机的人
        val alert =
            if (AppVariant.current.isDev) NotificationManager.IMPORTANCE_LOW
            else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, Strings.coreChannelAlert(), alert),
        )
        RETIRED_CHANNELS.filter { manager.getNotificationChannel(it) != null }
            .forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATE, Strings.coreChannelUpdate(), alert),
        )
    }

    fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 点开切到对话 [key]（会话号，新对话是它的 key）。各对话的 Intent 带着不同的 data，不会互相覆盖。 */
    fun conversationOpen(context: Context, key: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            MainActivity.conversationIntent(context, key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * 对话 [key] 的提醒（跑完、等你处理）：每个对话一条，tag 就是 [key]，同一个对话再发就替换；点开切到这个对话。[detail] 是展开后的全文，[actions]
     * 是通知上的按钮，[silent] 见 [post]。也上超级岛（[FocusParam.alert]，[short]、[right] 是大岛左右两边）：不静音的弹出来停几秒。
     */
    fun alert(
        context: Context,
        key: String,
        title: String,
        text: String,
        short: String,
        right: String,
        detail: String? = null,
        actions: List<Notification.Action> = emptyList(),
        silent: Boolean = false,
    ) =
        post(
            context,
            key,
            ID_ALERT,
            title,
            text,
            conversationOpen(context, key),
            bigText = detail,
            silent = silent,
            actions = actions,
            island =
                FocusParam.alert(
                    title,
                    text,
                    short,
                    right,
                    actions.map { it.title.toString() },
                    silent,
                ),
        )

    /**
     * 把超级岛的焦点参数（见 [FocusParam]）放进 [builder] 的 extras：JSON、图标，按钮是通知上同一批 [actions]，顺序同
     * [IslandContent.replies]。
     */
    fun island(
        context: Context,
        builder: Notification.Builder,
        content: IslandContent,
        actions: List<Notification.Action>,
    ) {
        val pics =
            Bundle().apply {
                putParcelable(
                    FocusParam.PIC_APP,
                    Icon.createWithResource(context, R.drawable.ic_island_icon),
                )
            }
        val buttons =
            Bundle().apply {
                actions.forEachIndexed { i, action ->
                    putParcelable(FocusParam.actionKey(i), action)
                }
            }
        builder.extras.putString(FocusParam.EXTRA, FocusParam.build(content))
        builder.extras.putBundle(FocusParam.PICS, pics)
        builder.extras.putBundle(FocusParam.ACTIONS, buttons)
    }

    /**
     * 对话 [key] 那条通知写成“进行中”（[live]）：标题是对话标题，正文是当前步骤，从这一轮开始走秒；有没做完的任务清单时用进度条样式。
     * 不响不弹（一轮里要更新很多次），上超级岛（[FocusParam.of]）。和完成、等你的提醒是同一条（同一个 tag），照小爱在原地更新。
     */
    fun progress(context: Context, live: LiveStatus) =
        post(
            context,
            live.key,
            ID_ALERT,
            live.title,
            live.text,
            conversationOpen(context, live.key),
            silent = true,
            island = FocusParam.of(live),
        ) {
            setWhen(live.startedAt).setShowWhen(true).setUsesChronometer(true)
            val progress = live.progress
            if (progress != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(
                            LiveUpdate.segments(progress.total).map {
                                Notification.ProgressStyle.Segment(it)
                            },
                        )
                        .setProgress(progress.done),
                )
        }

    /** 撤掉对话 [key] 的提醒（用户看到了，或等的请求已经处理掉）。 */
    fun clearAlert(context: Context, key: String) = cancel(context, key, ID_ALERT)

    /**
     * 发一条提醒（默认渠道“完成与确认提醒”，[channel] 可换，点开后自动收起）：没有通知权限就不发；测试版的标题前注明“测试版”。 [tag] 和 [id]
     * 一起认出这一条，同一条再发就替换；有 [bigText] 时展开能看到这段全文。 [silent]：这一条不响铃、不振动、不横幅弹出，只进通知栏（屏幕底部已经弹了结果卡片）。
     * [actions] 是通知上的按钮（系统最多画三个）。[island] 不为 null 时也上超级岛（见 [island]）。[extra] 在发出前再改一下通知（[progress]
     * 的走秒、进度条）。
     */
    fun post(
        context: Context,
        tag: String?,
        id: Int,
        title: String,
        text: String,
        open: PendingIntent,
        bigText: String? = null,
        silent: Boolean = false,
        channel: String = CHANNEL_ALERT,
        actions: List<Notification.Action> = emptyList(),
        island: IslandContent? = null,
        extra: Notification.Builder.() -> Unit = {},
    ) {
        if (
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        val manager = context.getSystemService(NotificationManager::class.java)
        // 进行中的那条一轮里要发很多次：渠道已经在就不再一遍遍建（建渠道、改名在前台服务那条通知里照常做）
        if (manager.getNotificationChannel(channel) == null) ensureChannels(context)
        val notification =
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_zeta)
                .setContentTitle(AppVariant.current.notificationTitle(title))
                .setContentText(text)
                .apply {
                    if (!bigText.isNullOrBlank())
                        setStyle(Notification.BigTextStyle().bigText(bigText))
                }
                // 框架的 Notification.Builder 没有 setSilent：照 NotificationCompat
                // 的做法，当成组里的一条、只让（不存在的）摘要提醒
                .apply {
                    if (silent)
                        setGroup(SILENT_GROUP)
                            .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
                .apply { actions.forEach(::addAction) }
                .setContentIntent(open)
                .setAutoCancel(true)
                .apply { island?.let { island(context, this, it, actions) } }
                .apply(extra)
                .build()
        manager.notify(tag, id, notification)
    }

    fun cancel(context: Context, tag: String?, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, id)
    }
}

/**
 * 只为保活：Claude Code 进程是本应用的子进程，应用被回收它也会结束。前台服务的那条通知在最低重要度的渠道里，只写“Claude 正在工作”和“停止”“截屏”；
 * 对话的进度、等你、已完成照小爱各自一条（[Notifier.progress]、[Notifier.alert]，由 [TurnAlerts] 发），上小米超级岛。
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConversationHub.get(application as Application).interruptAll()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAPTURE) {
            ScreenCapture.start(this)
            // 没有对话进程时（只是被这个按钮叫起来）不留着
            if (published?.active != true) {
                if (instance === this) instance = null
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        starting = false
        val state = published ?: StatusNotice(active = true, busy = false, running = emptyList())
        // 叫起服务以后、到这里以前对话又都结束了（比如远程主机连不上，进程一起来就退出）：
        // 系统要求 startForegroundService 叫起的服务先进前台，所以照样进一下前台再停掉，否则整个应用被系统结束
        show(state.takeIf { it.active } ?: state.copy(active = true, busy = false))
        if (!state.active) {
            if (instance === this) instance = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** 按 [state] 发前台服务那条保活的通知，一轮进行中持有唤醒锁。在主线程上调用。 */
    private fun show(state: StatusNotice) {
        startForeground(
            Notifier.ID_STATUS,
            notification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        // 只在一轮进行中持有唤醒锁：息屏后设备休眠会把进程一起挂起
        if (state.busy) {
            if (wakeLock == null) {
                wakeLock =
                    getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeta:turn")
                        .apply { acquire(60 * 60 * 1000L) }
            }
        } else {
            releaseWakeLock()
        }
    }

    /** 前台服务那条通知本身。 */
    internal fun notification(state: StatusNotice): Notification {
        val running = state.running
        Notifier.ensureChannels(this)
        val builder =
            Notification.Builder(this, Notifier.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_zeta)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentTitle(
                    AppVariant.current.notificationTitle(
                        if (state.busy) Strings.coreStatusWorking()
                        else Strings.coreStatusHolding(),
                    ),
                )
                .setContentIntent(Notifier.openAppIntent(this))
        when (running.size) {
            0 -> Unit
            1 -> builder.setContentText(running.first())
            else -> builder.setContentText(Strings.coreStatusRunningCount(running.size))
        }
        // 只有对话在跑时才给“停止”；安装、维护脚本不从这里打断
        if (running.isNotEmpty()) {
            val stop =
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, AgentService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            builder.addAction(
                Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_zeta),
                        if (running.size > 1) Strings.coreStatusStopAll()
                        else Strings.coreStatusStop(),
                        stop,
                    )
                    .build(),
            )
        }
        // 截下当前屏幕放进 Zeta 对话的输入框（和快速设置里的磁贴一样）
        val capture =
            PendingIntent.getService(
                this,
                2,
                Intent(this, AgentService::class.java).setAction(ACTION_CAPTURE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        builder.addAction(
            Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_tile_capture),
                    Strings.coreStatusCapture(),
                    capture,
                )
                .build(),
        )
        return builder.build()
    }

    override fun onDestroy() {
        starting = false
        if (instance === this) instance = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private val ACTION_STOP = AppVariant.current.action("STOP")
        private val ACTION_CAPTURE = AppVariant.current.action("CAPTURE")

        /** 正在运行的服务；停掉时（[update] 里）就清空，不等 onDestroy。只在主线程上碰，下同。 */
        private var instance: AgentService? = null

        /**
         * 叫了 startForegroundService、服务还没到 onStartCommand：这时不能 stopService，交给 onStartCommand
         * 按最新状态处理。
         */
        private var starting = false

        /** 最近发出的状态。 */
        private var published: StatusNotice? = null

        /** [running] 是正在回答的对话标题，非空时通知上带“停止”。和上次一样就不动。在主线程上调用。 */
        fun update(context: Context, active: Boolean, busy: Boolean, running: List<String>) {
            val state = StatusNotice(active, busy, running)
            if (state == published) return
            published = state
            publish(context.applicationContext, state)
        }

        private fun publish(context: Context, state: StatusNotice) {
            if (!state.active) {
                instance = null
                if (!starting) context.stopService(Intent(context, AgentService::class.java))
                return
            }
            // 服务已经在跑就直接换通知，不再一次次 startForegroundService
            instance?.let { service ->
                service.show(state)
                return
            }
            if (starting) return
            starting =
                runCatching {
                    context.startForegroundService(Intent(context, AgentService::class.java))
                }
                    .isSuccess
        }
    }
}
