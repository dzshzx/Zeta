package com.dzshzx.zeta.i18n

import com.ibm.icu.text.MessageFormat
import com.ibm.icu.util.ULocale

/**
 * 单元测试出字用的 [MessageFormatter]（app/src/test/resources 的 META-INF/services 登记，排在应用登记的
 * [IcuMessageFormatter] 前面）：不带 Robolectric 的测试里 android.icu 是空壳（format 返回 null），这里用同一套 ICU 的 Java 版
 * ICU4J。两者对全部文字表出的字一样， 由 [CatalogTest] 在 Robolectric 里核对。
 */
class Icu4jMessageFormatter : MessageFormatter {
    override fun format(pattern: String, lang: String, args: Map<String, Any?>): String =
        MessageFormat(pattern, ULocale.forLanguageTag(lang)).format(args)
}
