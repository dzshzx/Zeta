package com.dzshzx.zeta.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.PrefStore
import com.dzshzx.zeta.core.PromptList
import com.dzshzx.zeta.core.SavedPrompt
import com.dzshzx.zeta.core.SavedPrompts

/** 桌面版的常用指令：和手机上一样存在设置文件的 [SavedPrompts.KEY] 下（JSON 文本）。桌面上只有界面线程读写，不用加锁。 */
class PrefsPromptList(private val prefs: PrefStore) : PromptList {
    override var observed by mutableStateOf<List<SavedPrompt>>(emptyList())
        private set

    private var loaded = false

    override fun load(): List<SavedPrompt> {
        if (!loaded) {
            observed = SavedPrompts.decode(prefs.getString(SavedPrompts.KEY, null))
            loaded = true
        }
        return observed
    }

    override fun update(change: (List<SavedPrompt>) -> List<SavedPrompt>): List<SavedPrompt> {
        val before = load()
        val after = change(before)
        if (after == before) return before
        prefs.edit().putString(SavedPrompts.KEY, SavedPrompts.encode(after)).apply()
        observed = after
        return after
    }
}
