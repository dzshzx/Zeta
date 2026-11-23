package com.dzshzx.zeta.i18n

import java.util.Locale

/**
 * 界面的语言（docs/i18n.md）：简体中文（源语言）或英文。手机上由 app 的 `AppLanguage` 照 Android 的应用语言设好、语言变了时改它；没设过（不带
 * Android 的单元测试）时是简体中文。生成的取字函数（[Strings]、[CoreStrings]）按它出字，连核心时 core.client 报的也是它。
 */
object UiLanguage {
    /** 源语言：文字表先写它，别的语言缺的句子用它。 */
    const val SOURCE = "zh-Hans"

    const val ENGLISH = "en"

    /** 界面能用的语言（BCP 47），源语言在前；每种在文字表目录里都有 <语言>.json。 */
    val supported: List<String> = listOf(SOURCE, ENGLISH)

    /** 界面现在的语言，[supported] 之一；在 [forClaude] 里是源语言。 */
    var current: String
        get() = claudeScope.get() ?: chosen
        set(value) {
            chosen = value
        }

    @Volatile private var chosen: String = SOURCE

    private val claudeScope = ThreadLocal<String?>()

    /**
     * 在这个线程上跑 [block] 时取字固定用源语言：交给 Claude 的文字（工具结果）不随界面语言变（docs/i18n.md「哪些不翻译」）。
     * 手机工具借用界面那边的取字结果（定时任务的规则说明、运行结果的叫法）写进工具结果时，整个调用包在这里面。
     */
    fun <T> forClaude(block: () -> T): T {
        val outer = claudeScope.get()
        claudeScope.set(SOURCE)
        try {
            return block()
        } finally {
            claudeScope.set(outer)
        }
    }

    /**
     * 读者的语言 [preferred]（BCP 47，最想要的在前）里第一种界面能用的；都不能用时是简体中文。照 mygo 的 i18n.Match（桌面版用它）：
     * 英语哪个地区的都算英文；中文写的是简体（zh-Hans，或没写文字、地区不是台湾、香港、澳门的）才算简体中文，繁体不拿简体顶，看下一种。
     */
    fun resolve(preferred: List<String>): String {
        for (tag in preferred) {
            val locale = Locale.forLanguageTag(tag)
            when (locale.language) {
                "en" -> return ENGLISH
                "zh" ->
                    if (
                        locale.script == "Hans" ||
                            locale.script.isEmpty() && locale.country !in TRADITIONAL_REGIONS
                    )
                        return SOURCE
            }
        }
        return SOURCE
    }

    /** 语言 [tag] 用它自己的写法叫什么（选语言的菜单里列）：这几个名字不翻译。 */
    fun autonym(tag: String): String =
        when (tag) {
            SOURCE -> "简体中文"
            ENGLISH -> "English"
            else -> tag
        }

    /** 没写文字时默认写繁体的地区。 */
    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
}
