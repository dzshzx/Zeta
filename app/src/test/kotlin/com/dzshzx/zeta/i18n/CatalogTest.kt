package com.dzshzx.zeta.i18n

import com.dzshzx.zeta.core.NoticeItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字表的出字（[Catalog]，不带 Robolectric）：按语言、复数、命名参数，那种语言缺的退回简体中文，没有的键；生成的取字函数读的是包里的文字表；
 * 提示条目有种类码时按界面语言出字。测试用的文字表在 app/src/test/resources/locales/test。
 */
class CatalogTest {
    private val test = Catalog("test")

    @After
    fun restore() {
        UiLanguage.current = UiLanguage.SOURCE
    }

    @Test
    fun saysInTheLanguageWithPluralsAndNamedArgs() {
        val args = { n: Int -> mapOf("count" to n, "host" to "nerv") }
        assertEquals("nerv 上选了 1 个文件", test.say("files", args(1), "zh-Hans"))
        assertEquals("1 file selected on nerv", test.say("files", args(1), "en"))
        assertEquals("3 files selected on nerv", test.say("files", args(3), "en"))
        // 不传语言时按界面语言
        UiLanguage.current = "en"
        assertEquals("2 files selected on nerv", test.say("files", args(2)))
    }

    @Test
    fun fallsBackToTheSourceLanguage() {
        assertEquals("只有中文", test.say("onlyChinese", lang = "en"))
        // 不认识的语言也用源语言
        assertEquals(
            "nerv 上选了 2 个文件",
            test.say("files", mapOf("count" to 2, "host" to "nerv"), "de"),
        )
        assertNull(test.sayOrNull("missing"))
        assertEquals("missing", test.say("missing"))
    }

    @Test
    fun quotesFollowIcu() {
        assertEquals("写成 {名字} 的是字面", test.say("quoted"))
        assertEquals("{name} is literal", test.say("quoted", lang = "en"))
    }

    /** 生成的取字函数读的是打进包里的两份文字表（app/build.gradle.kts 的 catalogResources），不是测试的。 */
    @Test
    fun generatedAccessorsReadThePackagedCatalogs() {
        assertEquals("语言", Strings.settingsLanguage())
        assertEquals("已停止", CoreStrings.noticeStopped())
        UiLanguage.current = "en"
        assertEquals("Language", Strings.settingsLanguage())
        assertEquals("Stopped", CoreStrings.noticeStopped())
        for (catalog in listOf(Strings.catalog, CoreStrings.catalog)) {
            for (lang in UiLanguage.supported) {
                assertTrue(lang, catalog.messages(lang).isNotEmpty())
            }
        }
    }

    /** 不带 Robolectric 时 android.icu 是空壳，出字用测试登记的 ICU4J（登记的先后见 [Icu4jMessageFormatter]）。 */
    @Test
    fun unitTestsFormatWithIcu4j() {
        assertTrue(MessageFormatter.current is Icu4jMessageFormatter)
    }

    @Test
    fun noticesWithACodeAreSaidInTheUiLanguage() {
        val stopped = NoticeItem("n-1", "已停止", error = false, code = "noticeStopped")
        assertEquals("已停止", stopped.said())
        UiLanguage.current = "en"
        assertEquals("Stopped", stopped.said())
        // 不认识的码、没有码：照核心附带的文字
        assertEquals("旧文字", NoticeItem("n-2", "旧文字", error = false, code = "noticeNew").said())
        assertEquals("Claude Code 的原文", NoticeItem("n-3", "Claude Code 的原文", error = true).said())
    }

    @Test
    fun jsonArgsBecomeFormattableValues() {
        val o =
            Json.parseToJsonElement("""{"count":3,"x":1.5,"s":"a","b":true,"z":null}""")
                as JsonObject
        assertEquals(
            mapOf("count" to 3L, "x" to 1.5, "s" to "a", "b" to true, "z" to null),
            jsonArgs(o),
        )
        assertEquals("nerv 上选了 3 个文件", test.say("files", jsonArgs(o) + ("host" to "nerv")))
    }

    @Test
    fun resolvesTheReadersLanguage() {
        val cases =
            mapOf(
                listOf("en-US") to "en",
                listOf("en-GB", "zh-CN") to "en",
                listOf("zh-CN") to "zh-Hans",
                listOf("zh-Hans-SG") to "zh-Hans",
                listOf("zh") to "zh-Hans",
                // 繁体不拿简体顶，看下一种
                listOf("zh-TW", "en-US") to "en",
                listOf("zh-Hant-CN", "en") to "en",
                listOf("fr-FR", "en-US") to "en",
                // 都不能用时是简体中文
                listOf("fr-FR", "zh-HK") to "zh-Hans",
                emptyList<String>() to "zh-Hans",
            )
        for ((preferred, want) in cases) assertEquals(
            "$preferred",
            want,
            UiLanguage.resolve(preferred),
        )
    }

    /** 交给 Claude 的文字在 [UiLanguage.forClaude] 里固定用源语言：只管这个线程、只在块里，出来恢复界面语言。 */
    @Test
    fun forClaudeSaysTheSourceLanguageOnThisThreadOnly() {
        UiLanguage.current = UiLanguage.ENGLISH
        var other: String? = null
        val inside = UiLanguage.forClaude {
            val t = Thread { other = Strings.settingsLanguage() }
            t.start()
            t.join()
            UiLanguage.forClaude { Strings.settingsLanguage() } + Strings.settingsLanguage()
        }
        assertEquals("语言语言", inside)
        assertEquals("Language", other)
        assertEquals("Language", Strings.settingsLanguage())
        assertEquals(UiLanguage.ENGLISH, UiLanguage.current)
    }
}
