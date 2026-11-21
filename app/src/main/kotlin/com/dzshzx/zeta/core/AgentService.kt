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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.dzshzx.zeta.R
import com.dzshzx.zeta.phone.ScreenCapture
import com.dzshzx.zeta.ui.MainActivity

internal object Notifier {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ALERT = "alert"

    /** 测试版上试过、已经不用的渠道（等用户时常驻通知临时换过去的），还在就删掉。 */
    private val RETIRED_CHANNELS = listOf("waiting", "island_waiting")

    /** 有新版本（core/update/AppUpdates.kt）：同一个版本只发一次。 */
    const val CHANNEL_UPDATE = "update"

    /** 通知 id：各类各一个，同一类里靠 tag 区分（定时任务、通知触发按“来源:id”各一条）。 */
    const val ID_STATUS = 1
    const val ID_ALERT = 2
    const val ID_SCHEDULE = 3
    const val ID_ACCESSIBILITY = 4
    const val ID_UPDATE = 5

    /** 响过的请求号：同一个请求不管从实时通知还是提醒发出，只响一次。在主线程上用。 */
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
            NotificationChannel(CHANNEL_STATUS, "运行状态", NotificationManager.IMPORTANCE_LOW),
        )
        // 测试版的提醒不出声：测试时对话跑完、等确认，不该打扰正在用手机的人
        val alert =
            if (AppVariant.current.isDev) NotificationManager.IMPORTANCE_LOW
            else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "完成与确认提醒", alert))
        RETIRED_CHANNELS.filter { manager.getNotificationChannel(it) != null }
            .forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_UPDATE, "版本更新", alert))
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
     * 是通知上的按钮，[silent] 见 [post]。
     */
    fun alert(
        context: Context,
        key: String,
        title: String,
        text: String,
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
        )

    /** 撤掉对话 [key] 的提醒（用户看到了，或等的请求已经处理掉）。 */
    fun clearAlert(context: Context, key: String) = cancel(context, key, ID_ALERT)

    /**
     * 发一条提醒（默认渠道“完成与确认提醒”，[channel] 可换，点开后自动收起）：没有通知权限就不发；测试版的标题前注明“测试版”。 [tag] 和 [id]
     * 一起认出这一条，同一条再发就替换；有 [bigText] 时展开能看到这段全文。 [silent]：这一条不响铃、不振动、不横幅弹出，只进通知栏（屏幕底部已经弹了结果卡片）。
     * [actions] 是通知上的按钮（系统最多画三个）。
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
    ) {
        if (
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        )
            return
        ensureChannels(context)
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
                .build()
        context.getSystemService(NotificationManager::class.java).notify(tag, id, notification)
    }

    fun cancel(context: Context, tag: String?, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, id)
    }
}

/**
 * 只为保活：Claude Code 进程是本应用的子进程，应用被回收它也会结束。 常驻通知在有对话回答时申请提升为实时通知（状态栏、锁屏上的 Live Updates，内容见
 * [LiveStatus]）；系统不允许时照旧。
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
            if (throttle.published?.active != true) {
                if (instance === this) instance = null
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        starting = false
        val state =
            throttle.published
                ?: StatusNotice(active = true, busy = false, running = emptyList(), live = null)
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

    /** 按 [state] 发常驻通知（前台服务的那条），一轮进行中持有唤醒锁。在主线程上调用。 */
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

    /** 常驻通知本身。 */
    internal fun notification(state: StatusNotice): Notification {
        val running = state.running
        Notifier.ensureChannels(this)
        val live = state.live
        val builder = statusBuilder(live)
        if (live != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            promote(builder, live)
            // 实时通知（超级岛展开时也是它）只在停下来等用户时带按钮，是替用户回答的那几个；“停止”“截屏”在岛上用不着
            replyActions(live).forEach(builder::addAction)
            return builder.build()
        } else {
            builder
                .setContentTitle(
                    AppVariant.current.notificationTitle(
                        if (state.busy) "Claude 正在工作" else "Claude 会话保持中",
                    ),
                )
                .setContentIntent(Notifier.openAppIntent(this))
            when (running.size) {
                0 -> Unit
                1 -> builder.setContentText(running.first())
                else -> builder.setContentText("${running.size} 个对话进行中")
            }
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
                        if (running.size > 1) "全部停止" else "停止",
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
                    "截屏",
                    capture,
                )
                .build(),
        )
        return builder.build()
    }

    /**
     * 常驻通知的骨架，在“运行状态”渠道。一轮里要更新很多次，平时只在第一次发出时提醒（用户把这个渠道调成会弹横幅时，不然每次更新都再弹一次）。
     * 实时通知上显示的对话停下来等用户、这个请求第一次出现时提醒一次，弹不弹、响不响照用户给这个渠道的设置。 HyperOS 把实时通知转成超级岛时不让它自己展开（转出来的参数里
     * enableFloat、islandFirstFloat 都是 false），岛弹出来靠这次提醒。 手机上实测：另建一个重要度“高”的渠道，岛不展开；用户调高过的“运行状态”渠道能展开。
     */
    private fun statusBuilder(live: LiveStatus?): Notification.Builder {
        val waiting =
            live?.requestId?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA }
        return Notification.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setOngoing(true)
            .setOnlyAlertOnce(waiting == null || !Notifier.firstRing(waiting))
    }

    /** [live] 上替用户回答的按钮（允许、拒绝、选项），和提醒上的一样经 [AlertReplyReceiver] 回答；没在等用户时是空的。 */
    private fun replyActions(live: LiveStatus): List<Notification.Action> {
        val requestId = live.requestId ?: return emptyList()
        return live.replies.mapIndexed { index, reply ->
            AlertReplyReceiver.action(this, live.key, requestId, index, reply)
        }
    }

    /** 实时通知：标题是对话标题，正文是当前步骤（和顶部胶囊同一句），状态栏小胶囊上一个短词，从这一轮开始走秒； 有没做完的任务清单时用进度条样式。点开回到这个对话。 */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun promote(builder: Notification.Builder, live: LiveStatus) {
        builder
            .setContentTitle(AppVariant.current.notificationTitle(live.title))
            .setContentText(live.text)
            .setSubText(live.note)
            .setContentIntent(Notifier.conversationOpen(this, live.key))
            .setShortCriticalText(live.short)
            .setWhen(live.startedAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
        val progress = live.progress
        if (progress != null) {
            builder.setStyle(
                Notification.ProgressStyle()
                    .setProgressSegments(
                        LiveUpdate.segments(progress.total).map {
                            Notification.ProgressStyle.Segment(it)
                        },
                    )
                    .setProgress(progress.done),
            )
        } else {
            builder.setStyle(Notification.BigTextStyle().bigText(live.detail ?: live.text))
        }
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder.setRequestPromotedOngoing(true)
        } else {
            // 36.0 上还没有这个方法，系统认的是同一个 extra
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        }
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

        /** 实时通知上只是步骤、进度变了时，两次更新至少隔这么久（一轮里步骤一秒变好几次）。 */
        private const val LIVE_INTERVAL_MS = 2_000L

        /** 正在运行的服务；停掉时（[update] 里）就清空，不等 onDestroy。只在主线程上碰，下同。 */
        private var instance: AgentService? = null

        /**
         * 叫了 startForegroundService、服务还没到 onStartCommand：这时不能 stopService，交给 onStartCommand
         * 按最新状态处理。
         */
        private var starting = false
        private val main = Handler(Looper.getMainLooper())
        private val throttle =
            Throttle<StatusNotice>(LIVE_INTERVAL_MS) { old, new -> new.minorAfter(old) }
        private var appContext: Context? = null
        private val flush = Runnable {
            val context = appContext ?: return@Runnable
            throttle.poll(SystemClock.elapsedRealtime())?.let { publish(context, it) }
        }

        /** 实时通知上正显示着、等用户回答的那个请求号（超级岛上能直接回答它）；没有时为 null。在主线程上读。 */
        internal val liveRequestId: String?
            get() = throttle.published?.live?.requestId

        /** 系统允许把常驻通知提升为实时通知（Android 16 起，用户也没在设置里关掉这个应用的实时通知）。 */
        fun canPromote(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
                runCatching {
                        context
                            .getSystemService(NotificationManager::class.java)
                            .canPostPromotedNotifications()
                    }
                    .getOrDefault(false)

        /**
         * [running] 是正在回答的对话标题，非空时通知上带“停止”；[live] 是实时通知的内容，null 时是普通的常驻通知。 在主线程上调用。只是 [live]
         * 的步骤变了时节流（见 [LIVE_INTERVAL_MS]），其余的变化马上更新。
         */
        fun update(
            context: Context,
            active: Boolean,
            busy: Boolean,
            running: List<String>,
            live: LiveStatus? = null,
        ) {
            val now = SystemClock.elapsedRealtime()
            val app = context.applicationContext
            main.removeCallbacks(flush)
            throttle.offer(StatusNotice(active, busy, running, live), now)?.let { publish(app, it) }
            throttle.dueIn(now)?.let { delay ->
                appContext = app
                main.postDelayed(flush, delay)
            }
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
