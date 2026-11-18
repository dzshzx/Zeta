package com.dzshzx.zeta.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.dzshzx.zeta.R
import com.dzshzx.zeta.ui.MainActivity

internal object Notifier {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_ALERT = "alert"
    private const val ALERT_ID = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "运行状态", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "完成与确认提醒", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 用户回到应用里了，提醒就不必留着。 */
    fun clearAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    fun alert(context: Context, title: String, text: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannels(context)
        val notification = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
    }
}

/** 只为保活：Claude Code 进程是本应用的子进程，应用被回收它也会结束。 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val busy = intent?.getBooleanExtra(EXTRA_BUSY, false) == true
        Notifier.ensureChannels(this)
        val notification = Notification.Builder(this, Notifier.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(if (busy) "Claude 正在工作" else "Claude 会话保持中")
            .setContentIntent(Notifier.openAppIntent(this))
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        private var lastState: Pair<Boolean, Boolean>? = null

        fun update(context: Context, active: Boolean, busy: Boolean) {
            val state = active to busy
            if (state == lastState) return
            lastState = state
            val intent = Intent(context, AgentService::class.java).putExtra(EXTRA_BUSY, busy)
            if (active) {
                runCatching { context.startForegroundService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
