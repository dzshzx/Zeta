package com.dzshzx.zeta.ui.settings

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 从通知直接打开设置里的某一页（比如无障碍被关掉时的提醒点开到权限健康页）。 MainActivity 收到带 [EXTRA] 的 Intent 后记在
 * [pending]；主界面据此打开设置页，设置页再切到那一页并清掉它。
 */
internal object SettingsLink {
    const val EXTRA = "com.dzshzx.zeta.extra.SETTINGS_PAGE"

    /** 要打开的设置子页；主线程上读写，可观察。 */
    var pending by mutableStateOf<SettingsSubPage?>(null)

    /** 从 Intent 里取出要打开的页，取完把 extra 去掉，界面重建时不再跳一次。 */
    fun accept(intent: Intent?) {
        val link = intent?.getStringExtra(EXTRA) ?: return
        intent.removeExtra(EXTRA)
        pending = page(link) ?: return
    }

    /** 按 [SettingsSubPage.link] 找页。 */
    fun page(link: String): SettingsSubPage? =
        SettingsSubPage.entries.firstOrNull { it.link == link }
}
