package com.dzshzx.zeta.phone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.get

/**
 * 叫起 [PhoneBridge]：`zeta-helper phone-mcp` 连之前先以 root 发一条指名的广播到这里。应用没在跑时系统因此拉起进程，进程被冻结着时因此解冻； 这里只要把
 * ConversationHub 建起来（它建时开始等连接）。不导出：只有 root、system 和本应用发得进来。
 */
class PhoneBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConversationHub.get(context.applicationContext as Application)
    }
}
