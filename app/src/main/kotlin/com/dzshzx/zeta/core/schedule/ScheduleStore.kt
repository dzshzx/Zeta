package com.dzshzx.zeta.core.schedule

import android.content.Context
import com.dzshzx.zeta.core.PrefsListStore

/** 定时任务清单（见 [PrefsListStore]）。界面、闹钟、手机工具（工具线程）都会读写；每次改完重新排一遍闹钟。 */
internal object ScheduleStore : PrefsListStore<ScheduledTask>(
    Schedules.KEY, Schedules::decode, Schedules::encode, onChanged = { context, tasks -> ScheduleAlarms.sync(context, tasks) },
) {
    fun find(context: Context, id: String): ScheduledTask? = load(context).firstOrNull { it.id == id }

    /** 只改一个任务；它已经被删掉了就什么都不做。 */
    fun updateTask(context: Context, id: String, change: (ScheduledTask) -> ScheduledTask) {
        update(context) { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
