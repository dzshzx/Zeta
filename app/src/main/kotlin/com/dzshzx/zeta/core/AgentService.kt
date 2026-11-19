package com.dzshzx.zeta.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Application
import android.graphics.drawable.Icon
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.dzshzx.zeta.R
import com.dzshzx.zeta.phone.ScreenCapture
import com.dzshzx.zeta.ui.MainActivity

internal object Notifier {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ALERT = "alert"

    /** 通知 id：各类各一个，同一类里靠 tag 区分（定时任务、通知触发按“来源:id”各一条）。 */
    const val ID_STATUS = 1
    const val ID_ALERT = 2
    const val ID_SCHEDULE = 3
    const val ID_ACCESSIBILITY = 4

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "运行状态", NotificationManager.IMPORTANCE_LOW))
        // 测试版的提醒不出声：测试时对话跑完、等确认，不该打扰正在用手机的人
        val alert = if (AppVariant.current.isDev) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_DEFAULT
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "完成与确认提醒", alert))
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 用户回到应用里了，提醒就不必留着。 */
    fun clearAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }

    /** 对话跑完、等确认的提醒，点开回到 Zeta。 */
    fun alert(context: Context, title: String, text: String) = post(context, null, ID_ALERT, title, text, openAppIntent(context))

    /**
     * 发一条提醒（渠道“完成与确认提醒”，点开后自动收起）：没有通知权限就不发；测试版的标题前注明“测试版”。
     * [tag] 和 [id] 一起认出这一条，同一条再发就替换；[bigText] 为 true 时正文展开能看全。
     */
    fun post(context: Context, tag: String?, id: Int, title: String, text: String, open: PendingIntent, bigText: Boolean = false) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels(context)
        val notification = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(AppVariant.current.notificationTitle(title))
            .setContentText(text)
            .apply { if (bigText) setStyle(Notification.BigTextStyle().bigText(text)) }
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(tag, id, notification)
    }

    fun cancel(context: Context, tag: String?, id: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(tag, id)
    }
}

/** 只为保活：Claude Code 进程是本应用的子进程，应用被回收它也会结束。 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ConversationHub.get(application as Application).interruptAll()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CAPTURE) {
            ScreenCapture.start(this)
            // 没有对话进程时（只是被这个按钮叫起来）不留着
            if (lastState?.active != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        val busy = intent?.getBooleanExtra(EXTRA_BUSY, false) == true
        val running = intent?.getStringArrayListExtra(EXTRA_RUNNING).orEmpty()
        Notifier.ensureChannels(this)
        val builder = Notification.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(AppVariant.current.notificationTitle(if (busy) "Claude 正在工作" else "Claude 会话保持中"))
            .setContentIntent(Notifier.openAppIntent(this))
            .setOngoing(true)
        when (running.size) {
            0 -> Unit
            1 -> builder.setContentText(running.first())
            else -> builder.setContentText("${running.size} 个对话进行中")
        }
        // 只有对话在跑时才给“停止”；安装、维护脚本不从这里打断
        if (running.isNotEmpty()) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_zeta), if (running.size > 1) "全部停止" else "停止", stop).build(),
            )
        }
        // 截下当前屏幕放进 Zeta 对话的输入框（和快速设置里的磁贴一样）
        val capture = PendingIntent.getService(
            this, 2, Intent(this, AgentService::class.java).setAction(ACTION_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile_capture), "截屏", capture).build())
        val notification = builder.build()
        startForeground(Notifier.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // 只在一轮进行中持有唤醒锁：息屏后设备休眠会把进程一起挂起
        if (busy) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zeta:turn").apply { acquire(60 * 60 * 1000L) }
            }
        } else {
            releaseWakeLock()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val EXTRA_BUSY = "busy"
        private const val EXTRA_RUNNING = "running"
        private val ACTION_STOP = AppVariant.current.action("STOP")
        private val ACTION_CAPTURE = AppVariant.current.action("CAPTURE")

        private data class State(val active: Boolean, val busy: Boolean, val running: List<String>)
        private var lastState: State? = null

        /** [running] 是正在回答的对话标题，非空时通知上带“停止”。 */
        fun update(context: Context, active: Boolean, busy: Boolean, running: List<String>) {
            val state = State(active, busy, running)
            if (state == lastState) return
            lastState = state
            val intent = Intent(context, AgentService::class.java)
                .putExtra(EXTRA_BUSY, busy)
                .putStringArrayListExtra(EXTRA_RUNNING, ArrayList(running))
            if (active) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
