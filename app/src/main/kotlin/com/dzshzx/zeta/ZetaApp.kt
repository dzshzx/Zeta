package com.dzshzx.zeta

import android.app.Application
import com.dzshzx.zeta.i18n.AppLanguage

/** Zeta 的 Application：进程一起来就定下界面语言（[AppLanguage]），任何界面、服务、通知出字之前。 */
open class ZetaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.install(this)
    }
}
