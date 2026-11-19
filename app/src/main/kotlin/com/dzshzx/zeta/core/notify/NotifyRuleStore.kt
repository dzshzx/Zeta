package com.dzshzx.zeta.core.notify

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 通知触发的规则清单：存在 Zeta 自己的设置（shared_prefs/zeta.xml）里的一个 JSON 数组。
 * 界面、通知监听（主线程）都会读写，用一把锁串起来。
 */
internal object NotifyRuleStore {
    private const val KEY = "notifyRules"
    private val lock = Any()
    private var cache: List<NotifyRule>? = null
    private val main = Handler(Looper.getMainLooper())

    /** 界面看的那份（可观察），在主线程上更新。先 [load] 过才有内容。 */
    var observed by mutableStateOf<List<NotifyRule>>(emptyList())
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("zeta", Context.MODE_PRIVATE)

    fun load(context: Context): List<NotifyRule> = synchronized(lock) {
        cache ?: NotifyRules.decode(prefs(context).getString(KEY, null)).also {
            cache = it
            publish(it)
        }
    }

    fun find(context: Context, id: String): NotifyRule? = load(context).firstOrNull { it.id == id }

    /** 改清单并存盘。返回改后的清单。 */
    fun update(context: Context, change: (List<NotifyRule>) -> List<NotifyRule>): List<NotifyRule> = synchronized(lock) {
        val before = load(context)
        val after = change(before)
        if (after == before) return before
        cache = after
        prefs(context).edit().putString(KEY, NotifyRules.encode(after)).apply()
        publish(after)
        after
    }

    /** 只改一条；它已经被删掉了就什么都不做。 */
    fun updateRule(context: Context, id: String, change: (NotifyRule) -> NotifyRule) {
        update(context) { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun publish(list: List<NotifyRule>) {
        if (Looper.myLooper() == Looper.getMainLooper()) observed = list else main.post { observed = synchronized(lock) { cache } ?: list }
    }
}
