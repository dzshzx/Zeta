package com.dzshzx.zeta.ui.chat

/**
 * 输入框里光标前正在打的斜杠命令（同 hub/chat 的 Slash）。
 *
 * 照 Claude Code 终端界面（2.1.330 上实测）：光标前那个词以 / 开头、在开头或空白后面就提示，句中空一格再打 / 也算；紧挨着别的字
 * （「hello/co」「你好，/co」）或光标前已经打了空白时不提示。[prefix] 是 / 到光标的命令名前缀（小写），[start]、[end] 是选了命令要换掉的 那一段：从 /
 * 到光标后面连着的命令名字符（[isNameChar]）为止。紧跟在后面的别的字（中文句子里没有空格）不算进去，留在原处。
 */
data class SlashToken(val prefix: String, val start: Int, val end: Int) {
    /** 选了命令 [name] 以后输入框里的字和光标：那一段换成“/name”，后面接着空白时用它、不然补一个空格，光标放在空格后面接着打参数。 */
    fun complete(text: String, name: String): Pair<String, Int> {
        val rest = text.substring(end)
        val spaced = rest.firstOrNull()?.isWhitespace() == true
        val inserted = if (spaced) "/$name" else "/$name "
        val caret = start + inserted.length + if (spaced) 1 else 0
        return text.substring(0, start) + inserted + rest to caret
    }

    companion object {
        /** [text] 里光标 [caret] 前正在打的斜杠命令；不是在打时为 null。 */
        fun at(text: String, caret: Int): SlashToken? {
            if (caret !in 0..text.length) return null
            var start = caret
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            if (start == caret || text[start] != '/') return null
            var end = caret
            while (end < text.length && isNameChar(text[end])) end++
            return SlashToken(text.substring(start + 1, caret).lowercase(), start, end)
        }

        /** 命令名里会出现的字符：ASCII 字母、数字和 - _ : .（插件的技能写成 plugin:skill）。 */
        private fun isNameChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_:."
    }
}
