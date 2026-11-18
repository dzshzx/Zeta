package com.dzshzx.zeta.ui.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.dzshzx.zeta.core.ConversationHub

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}

/**
 * “对话进行中保持屏幕常亮”：有对话在回答时给 Activity 的窗口加 FLAG_KEEP_SCREEN_ON。
 * 这个标志只在窗口显示时起作用，切到别的应用或回到桌面后屏幕照常按系统设置熄灭。
 * 放在 [ZetaTheme] 里调用，是因为它是 Activity 界面树里一定会有的一层，不用再去改 MainActivity。
 */
@Composable
internal fun KeepScreenOnWhileRunning(activity: Activity, prefs: UiPrefs) {
    val hub = remember(activity) { ConversationHub.get(activity.application) }
    // 当前对话之外，切走后仍在后台回答的对话也算；isRunning 读的都是可观察的 busy 状态
    val running by remember(hub) {
        derivedStateOf { hub.current.busy || hub.sessions.any { hub.isRunning(it.id) } }
    }
    val keep = prefs.keepScreenOn && running
    DisposableEffect(activity, keep) {
        val window = activity.window
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
