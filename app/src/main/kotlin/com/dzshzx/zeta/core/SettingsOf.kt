package com.dzshzx.zeta.core

import android.content.Context

/** 本进程的那一份设置（[Settings.shared]），存在 SharedPreferences [Settings.FILE] 里。 */
fun Settings.Companion.of(context: Context): Settings {
    val app = context.applicationContext
    return Settings.shared(app) { SharedPrefStore(app, Settings.FILE) }
}
