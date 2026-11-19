package com.dzshzx.zeta.phone.tools

/**
 * 以 root 读出来的别的应用的 XML（shared_prefs、WifiConfigStore.xml）里取值和还原转义；便签旧版富文本的转义也用它还原。
 * 不是完整的 XML 解析，只认这几种文件里实际出现的写法。
 */
internal object XmlText {
    private val ENTITY = Regex("&(#\\d+|#[xX][0-9a-fA-F]+|amp|lt|gt|quot|apos|nbsp);")

    /**
     * 还原 `&amp;` `&lt;` `&gt;` `&quot;` `&apos;` 和数字字符引用（`&#128512;`、`&#x1F600;`：按码位还原，表情、生僻字不会坏），
     * 便签富文本里的 `&nbsp;` 还原成空格。一遍还原，`&amp;lt;` 得到的是 `&lt;`。认不出的原样留着。
     */
    fun unescape(text: String): String = if ('&' !in text) text else text.replace(ENTITY) { m ->
        when (val name = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> (if (name[1] == 'x' || name[1] == 'X') name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull())
                ?.takeIf(Character::isValidCodePoint)?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    /** `<string name="[name]">值</string>`（shared_prefs 和 WifiConfigStore.xml 的写法）里的值，转义已还原；没有这一项时是 null。 */
    fun string(xml: String, name: String): String? =
        Regex("<string name=\"" + Regex.escape(name) + "\">([^<]*)</string>").find(xml)?.groupValues?.get(1)?.let(::unescape)
}
