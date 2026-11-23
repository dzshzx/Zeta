package com.dzshzx.zeta.i18n

import android.icu.text.MessageFormat
import android.icu.util.ULocale

/** 手机上出字用的 [MessageFormatter]：Android 自带的 ICU（android.icu.text.MessageFormat，命名参数、复数）。 */
class IcuMessageFormatter : MessageFormatter {
    override fun format(pattern: String, lang: String, args: Map<String, Any?>): String =
        MessageFormat(pattern, ULocale.forLanguageTag(lang)).format(args)
}
