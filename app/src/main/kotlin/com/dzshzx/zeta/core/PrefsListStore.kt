package com.dzshzx.zeta.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 存在 Zeta 自己的设置（shared_prefs/zeta.xml）里 [key] 下的一份清单（JSON 文本，由 [decode]、[encode] 换算）。
 * 界面、闹钟、通知监听、手机工具（工具线程）都会读写，用一把锁串起来；清单真改了以后在调用的线程上做 [onChanged]。
 * 定时任务、通知触发规则、常用指令各是一份。
 */
internal open class PrefsListStore<T>(
    val key: String,
    private val decode: (String?) -> List<T>,
    private val encode: (List<T>) -> String,
    private val onChanged: (Context, List<T>) -> Unit = { _, _ -> },
) {
    private val lock = Any()
    private var cache: List<T>? = null
    private val main = Handler(Looper.getMainLooper())

    /** 界面看的那份（可观察），在主线程上更新。先 [load] 过才有内容。 */
    var observed by mutableStateOf<List<T>>(emptyList())
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE)

    fun load(context: Context): List<T> = synchronized(lock) {
        cache ?: decode(prefs(context).getString(key, null)).also {
            cache = it
            publish(it)
        }
    }

    /** 丢掉内存里的那份，从存储重读。 */
    fun reload(context: Context): List<T> = synchronized(lock) {
        cache = null
        load(context)
    }

    /** 改清单并存盘。返回改后的清单。 */
    fun update(context: Context, change: (List<T>) -> List<T>): List<T> {
        val next = synchronized(lock) {
            val before = load(context)
            val after = change(before)
            if (after == before) return before
            cache = after
            prefs(context).edit().putString(key, encode(after)).apply()
            publish(after)
            after
        }
        onChanged(context, next)
        return next
    }

    /** 设置备份导入：整份换成存储写法 [text] 解出来的清单，和别的改动一样存盘、更新界面、做 [onChanged]（定时任务据此重排闹钟）。 */
    fun replace(context: Context, text: String?): List<T> = decode(text).let { list -> update(context) { list } }

    /** 从工具线程改的，到主线程上再取最新的那份：先后几次改动排队到达时不会拿旧的盖掉新的。 */
    private fun publish(list: List<T>) {
        if (Looper.myLooper() == Looper.getMainLooper()) observed = list else main.post { observed = synchronized(lock) { cache } ?: list }
    }
}
