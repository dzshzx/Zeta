package com.dzshzx.zeta.core

/**
 * 截断的位置 [end] 落在一对代理（表情、生僻字这类 BMP 之外的字）中间时往前退一格，不把它切成两半；
 * [from] 是这一段的开头，最多退到它。不在中间时原样返回。
 */
fun safeCut(text: CharSequence, end: Int, from: Int = 0): Int =
    if (end > from && end < text.length && text[end - 1].isHighSurrogate()) end - 1 else end

/** 最多 [max] 个字符（UTF-16），不把表情切成两半；截掉了就去掉末尾空白、接上 [ellipsis]（比如「…」，空的就只截不接）。 */
fun String.clipChars(max: Int, ellipsis: String = ""): String {
    if (length <= max) return this
    val head = substring(0, safeCut(this, max.coerceAtLeast(0)))
    return if (ellipsis.isEmpty()) head else head.trimEnd() + ellipsis
}
