package com.dzshzx.zeta.core.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 定时任务清单：存在 Zeta 自己的设置（shared_prefs/zeta.xml）里的一个 JSON 数组。
 * 界面、闹钟、手机工具（工具线程）都会读写，用一把锁串起来；每次改完重新排一遍闹钟。
 */
internal object ScheduleStore {
    private const val KEY = "scheduledTasks"
    private val lock = Any()
    private var cache: List<ScheduledTask>? = null
    private val main = Handler(Looper.getMainLooper())

    /** 界面看的那份（可观察），在主线程上更新。先 [load] 过才有内容。 */
    var observed by mutableStateOf<List<ScheduledTask>>(emptyList())
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("zeta", Context.MODE_PRIVATE)

    fun load(context: Context): List<ScheduledTask> = synchronized(lock) {
        cache ?: Schedules.decode(prefs(context).getString(KEY, null)).also {
            cache = it
            publish(it)
        }
    }

    fun find(context: Context, id: String): ScheduledTask? = load(context).firstOrNull { it.id == id }

    /** 改清单并存盘，再按新清单重排闹钟。返回改后的清单。 */
    fun update(context: Context, change: (List<ScheduledTask>) -> List<ScheduledTask>): List<ScheduledTask> {
        val next = synchronized(lock) {
            val before = load(context)
            val after = change(before)
            if (after == before) return before
            cache = after
            prefs(context).edit().putString(KEY, Schedules.encode(after)).apply()
            publish(after)
            after
        }
        ScheduleAlarms.sync(context, next)
        return next
    }

    /** 只改一个任务；它已经被删掉了就什么都不做。 */
    fun updateTask(context: Context, id: String, change: (ScheduledTask) -> ScheduledTask) {
        update(context) { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** 从工具线程改的，到主线程上再取最新的那份：先后几次改动排队到达时不会拿旧的盖掉新的。 */
    private fun publish(list: List<ScheduledTask>) {
        if (Looper.myLooper() == Looper.getMainLooper()) observed = list else main.post { observed = synchronized(lock) { cache } ?: list }
    }
}
