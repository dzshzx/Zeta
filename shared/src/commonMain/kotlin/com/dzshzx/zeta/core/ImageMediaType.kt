package com.dzshzx.zeta.core

/** 从图片数据的开头认出类型（image/jpeg、image/png、image/gif、image/webp）；认不出返回 null。 */
fun imageMediaType(bytes: ByteArray): String? {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    fun ascii(index: Int, text: String) = text.indices.all { at(index + it) == text[it].code }
    return when {
        at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
        at(0) == 0x89 && ascii(1, "PNG") -> "image/png"
        ascii(0, "GIF8") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        else -> null
    }
}
