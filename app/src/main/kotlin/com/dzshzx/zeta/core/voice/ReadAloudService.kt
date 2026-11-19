package com.dzshzx.zeta.core.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import com.dzshzx.zeta.R
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.Notifier
import com.dzshzx.zeta.core.diag.DiagLog
import com.dzshzx.zeta.core.diag.DiagTopic

/**
 * 朗读期间的前台服务（媒体播放类）：只在出声期间存在，Zeta 退到后台、锁屏后朗读不被系统冻结。
 * 朗读本身由 [ReadAloud] 管，通知上的“停止”交回给它。
 */
class ReadAloudService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReadAloud.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
            .onFailure {
                DiagLog.warn(DiagTopic.App, "朗读：开不了前台服务：${it.javaClass.simpleName}")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "朗读", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ReadAloudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zeta)
            .setContentTitle(AppVariant.current.notificationTitle("正在朗读回答"))
            .setContentIntent(Notifier.openAppIntent(this))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_zeta), "停止", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "read_aloud"

        /** 和 [Notifier] 里的各类通知错开。 */
        private const val NOTIFICATION_ID = 41
        private val ACTION_STOP = AppVariant.current.action("STOP_READ_ALOUD")

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, ReadAloudService::class.java)) }
                .onFailure { DiagLog.warn(DiagTopic.App, "朗读：系统不让开前台服务：${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReadAloudService::class.java))
        }
    }
}
