package com.dzshzx.zeta.core

/** 常用指令的存取（见 [PrefsListStore]）：存在 SharedPreferences `zeta` 里，这样设置备份能带上它。 */
internal object PromptStore : PrefsListStore<SavedPrompt>(SavedPrompts.KEY, SavedPrompts::decode, SavedPrompts::encode)
