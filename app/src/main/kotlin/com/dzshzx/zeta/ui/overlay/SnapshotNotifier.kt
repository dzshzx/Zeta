package com.dzshzx.zeta.ui.overlay

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 悬浮层（[OverlayHost]、[EdgeGlowHost]、[ResultCardHost]）用 snapshotFlow 盯 hub 的状态，要有人发快照变更通知才收得到。
 * 平时由 Compose 的 GlobalSnapshotManager 发，但它要等进程里第一个 ComposeView 组合时才启动；无障碍服务可能比任何界面
 * 都先起来（Zeta 被杀后由定时任务、通知触发在后台跑一轮），所以服务连上时在这里补一个。
 * 和 GlobalSnapshotManager 一样只挂一次、进程活多久挂多久：有界面以后两边都发，多发一次无妨。
 */
internal object SnapshotNotifier {
    private val started = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    /** 无障碍服务连上时调用；已经挂上就什么都不做。 */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        val main = Handler(Looper.getMainLooper())
        Snapshot.registerGlobalWriteObserver {
            // 一阵写入只排一次：发出通知之前再来的写入都由这一次带上
            if (pending.compareAndSet(false, true)) main.post {
                pending.set(false)
                Snapshot.sendApplyNotifications()
            }
        }
    }
}
