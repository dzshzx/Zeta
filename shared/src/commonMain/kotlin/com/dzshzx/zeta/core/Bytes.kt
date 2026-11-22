package com.dzshzx.zeta.core

import java.util.Locale

/** 字节数给人看，和系统设置里一样用十进制单位（1 KB = 1000 B），保留三位有效数字：1.5 KB、12.3 MB、9.65 GB。 界面上各处的文件大小、剩余空间都用它。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    // 999.6 KB 显示出来会是 1000 KB，这时进到下一个单位
    while (value >= 999.5 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text =
        when {
            value >= 100 -> "%.0f".format(Locale.US, value)
            value >= 10 -> "%.1f".format(Locale.US, value)
            else -> "%.2f".format(Locale.US, value)
        }
    return (if ('.' in text) text.trimEnd('0').trimEnd('.') else text) + " " + units[unit]
}
