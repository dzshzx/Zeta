package com.dzshzx.zeta.core.notify

import android.content.Context
import com.dzshzx.zeta.core.PrefsListStore

/** 通知触发的规则清单（见 [PrefsListStore]）。界面、通知监听（主线程）都会读写。 */
internal object NotifyRuleStore :
    PrefsListStore<NotifyRule>(NotifyRules.KEY, NotifyRules::decode, NotifyRules::encode) {
    fun find(context: Context, id: String): NotifyRule? = load(context).firstOrNull { it.id == id }

    /** 只改一条；它已经被删掉了就什么都不做。 */
    fun updateRule(context: Context, id: String, change: (NotifyRule) -> NotifyRule) {
        update(context) { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
