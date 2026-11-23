package com.dzshzx.zeta

import android.app.Application
import com.dzshzx.zeta.i18n.UiLanguage
import com.dzshzx.zeta.ui.components.ProcessHub

/**
 * 单元测试里的 Application（robolectric.properties 指定）。Robolectric 每个用例新建一个，这时清掉上一个用例登记的 hub，
 * `ConversationHub.get` 和界面的 `LocalHub` 拿到的都是这个用例自己的。
 *
 * 界面语言固定成简体中文（源语言），不像 [ZetaApp] 那样跟 Android 的语言走：Robolectric 的系统语言是英语，跟着走的话界面上的字全是英文，
 * 测试照中文断言。要验语言跟随、切换的测试自己调 `AppLanguage.install`。
 */
class ZetaTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessHub.current = null
        UiLanguage.current = UiLanguage.SOURCE
    }
}
