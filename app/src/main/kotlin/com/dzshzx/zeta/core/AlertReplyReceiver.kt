package com.dzshzx.zeta.core

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import com.dzshzx.zeta.R

/**
 * 提醒通知上的按钮（允许、拒绝、单选题的选项）按下以后：交给 [TurnAlerts.reply] 回答那个对话正在等的请求。按钮里带着发提醒时的请求号，
 * 请求已经处理掉或换了一个时不再回答，只把这条过时的提醒撤掉。
 */
class AlertReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        ConversationHub.get(context.applicationContext as Application)
            .alerts
            .reply(key, requestId, index)
    }

    internal companion object {
        private val ACTION = AppVariant.current.action("ALERT_REPLY")
        private const val EXTRA_KEY = "key"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_INDEX = "index"

        /** 对话 [key] 等着的请求 [requestId] 的第 [index] 个按钮。data 各不相同，几个按钮、几个对话的 PendingIntent 才不会互相顶掉。 */
        fun action(
            context: Context,
            key: String,
            requestId: String,
            index: Int,
            reply: AlertReply,
        ): Notification.Action {
            val intent =
                Intent(context, AlertReplyReceiver::class.java)
                    .setAction(ACTION)
                    .setData(Uri.fromParts("zeta-reply", "$key/$requestId/$index", null))
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_REQUEST, requestId)
                    .putExtra(EXTRA_INDEX, index)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stat_zeta),
                    reply.label,
                    pending,
                )
                .setAuthenticationRequired(reply.unlock)
                .build()
        }
    }
}
