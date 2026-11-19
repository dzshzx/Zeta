package com.dzshzx.zeta.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 常用指令的存取：存在 SharedPreferences `zeta` 里（这样设置备份能带上它），界面看 [observed]。都在主线程上调用。 */
internal object PromptStore {
    private var loaded = false

    var observed by mutableStateOf<List<SavedPrompt>>(emptyList())
        private set

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("zeta", Context.MODE_PRIVATE)

    fun load(context: Context): List<SavedPrompt> {
        if (!loaded) reload(context)
        return observed
    }

    /** 从存储重读（设置备份导入之后）。 */
    fun reload(context: Context) {
        observed = SavedPrompts.decode(prefs(context).getString(SavedPrompts.KEY, null))
        loaded = true
    }

    fun update(context: Context, change: (List<SavedPrompt>) -> List<SavedPrompt>) {
        val before = load(context)
        val after = change(before)
        if (after == before) return
        observed = after
        prefs(context).edit().putString(SavedPrompts.KEY, SavedPrompts.encode(after)).apply()
    }
}
