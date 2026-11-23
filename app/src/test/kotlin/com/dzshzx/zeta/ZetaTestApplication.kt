package com.dzshzx.zeta

import android.app.Application
import com.dzshzx.zeta.ui.components.ProcessHub

/**
 * 单元测试里的 Application（`app/src/test/resources/robolectric.properties` 指定）。Robolectric
 * 每个用例新建一个，这时清掉上一个用例登记的 hub： `ConversationHub.get` 和界面的 `LocalHub` 拿到的都是这个用例自己的，用例不用各自清。
 */
class ZetaTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ProcessHub.current = null
    }
}
