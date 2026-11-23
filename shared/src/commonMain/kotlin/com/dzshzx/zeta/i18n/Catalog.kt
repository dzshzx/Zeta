package com.dzshzx.zeta.i18n

import com.dzshzx.zeta.core.platformService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 一份文字表（docs/i18n.md）：包里的 Java 资源 `locales/<name>/<语言>.json`（构建时从文字表目录放进去，见 app/build.gradle.kts 的
 * catalogResources），ARB 格式，值是 ICU MessageFormat 消息。按语言出字，那种语言缺的句子用源语言的；某种语言的文件第一次用到时读一次。
 * 代码里调生成的取字函数（[Strings]、[CoreStrings]），不直接用它；按核心给的种类码出字时用 [sayOrNull]。
 */
class Catalog(private val name: String) {
    private val files = ConcurrentHashMap<String, Map<String, String>>()

    /** 键 [key] 的话在语言 [lang] 里怎么说，[args] 是参数；文字表里没有这个键时是键本身。 */
    fun say(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        lang: String = UiLanguage.current,
    ): String = sayOrNull(key, args, lang) ?: key

    /** 同 [say]，文字表里没有这个键时是 null（核心给的种类码这边还不认识时，用核心附带的源语言文字）。 */
    fun sayOrNull(
        key: String,
        args: Map<String, Any?> = emptyMap(),
        lang: String = UiLanguage.current,
    ): String? {
        val inLang = if (lang in UiLanguage.supported) lang else UiLanguage.SOURCE
        val found =
            messages(inLang)[key]?.let { it to inLang }
                ?: messages(UiLanguage.SOURCE)[key]?.let { it to UiLanguage.SOURCE }
                ?: return null
        val (pattern, patternLang) = found
        // 没有参数、也没有要转义的字符的（大多数）不用经 ICU
        if (args.isEmpty() && pattern.none { it == '{' || it == '\'' }) return pattern
        return MessageFormatter.current.format(pattern, patternLang, args)
    }

    /** 语言 [lang] 的全部消息，键 → 消息（不含 @ 开头的说明）。 */
    fun messages(lang: String): Map<String, String> = files.getOrPut(lang) { load(lang) }

    private fun load(lang: String): Map<String, String> {
        val path = "locales/$name/$lang.json"
        val text =
            Catalog::class.java.classLoader?.getResourceAsStream(path)?.use {
                it.readBytes().decodeToString()
            } ?: error("包里没有文字表 $path（app/build.gradle.kts 的 catalogResources 放进去）")
        val o = Json.parseToJsonElement(text) as JsonObject
        return o.filterKeys { !it.startsWith("@") }
            .mapValues { (key, v) ->
                (v as? JsonPrimitive)?.contentOrNull ?: error("$path 里 $key 的值不是字符串")
            }
    }
}

/**
 * 把一条 ICU MessageFormat 消息按语言出成字（命名参数、复数）：手机上是 app 里用 android.icu.text.MessageFormat 的实现，登记在
 * META-INF/services。单元测试另登记一个用 ICU4J 的（app/src/test/resources）：不带 Robolectric 的测试里 android.icu 是空壳。
 */
fun interface MessageFormatter {
    /** 消息 [pattern]（是语言 [lang] 写的，复数按它的规则）代入 [args]。 */
    fun format(pattern: String, lang: String, args: Map<String, Any?>): String

    companion object {
        val current: MessageFormatter by lazy { platformService<MessageFormatter>() }
    }
}

/**
 * 核心给的一句话（种类码 [code] 和参数 [args]，docs/i18n.md「核心种类码清单」）用界面语言说出来。没有码，或码、嵌着的码这边不认识时是
 * [fallback]：核心附带的源语言文字，或 Claude Code 的原文。
 */
fun coreSaid(code: String?, args: JsonObject?, fallback: String): String =
    code?.takeIf { it.isNotEmpty() }?.let { sayCore(it, args, UiLanguage.current) } ?: fallback

/** 核心给的一句话（`{code, args}`），比如确认卡「以后不再询问」的每一条（suggestion_msgs）。 */
data class CoreMsg(val code: String, val args: JsonObject? = null) {
    /** 用界面语言说出来；不认识这个码时是 [fallback]。 */
    fun said(fallback: String): String = coreSaid(code, args, fallback)

    companion object {
        /** `{code, args}` 读成一句话；没有 code 时是 null。 */
        fun of(o: JsonObject): CoreMsg? =
            (o["code"] as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotEmpty() }
                ?.let { CoreMsg(it.content, o["args"] as? JsonObject) }
    }
}

/** 键 [code] 在语言 [lang] 里怎么说；它或参数里嵌着的哪句话的码这边不认识时是 null。 */
private fun sayCore(code: String, args: JsonObject?, lang: String): String? {
    val values = args.orEmpty().mapValues { (_, v) -> coreArg(v, lang) }
    if (values.values.any { it === Unknown }) return null
    return CoreStrings.catalog.sayOrNull(code, values, lang)
}

/** 参数里嵌着的话不认识时的记号（整句退回源语言文字）。 */
private object Unknown

/**
 * 出字用的参数值：嵌着的一句话（带 code 的对象）先按同一种语言说出来，几句话的数组用这种语言的顿号连起来（同 helper/locales 的 Msg.argsIn）；别的是数（Long 或
 * Double）、布尔或字符串。
 */
private fun coreArg(v: JsonElement, lang: String): Any? {
    if (v is JsonObject) {
        val msg = CoreMsg.of(v) ?: return v.toString()
        return sayCore(msg.code, msg.args, lang) ?: Unknown
    }
    if (v is JsonArray && v.isNotEmpty() && v.all { it is JsonObject && CoreMsg.of(it) != null }) {
        val parts = v.map { coreArg(it, lang) }
        if (parts.any { it === Unknown }) return Unknown
        return parts.joinToString(CoreStrings.catalog.say("listSeparator", lang = lang))
    }
    return jsonArg(v)
}

private fun jsonArg(v: JsonElement): Any? {
    val p = v as? JsonPrimitive ?: return v.toString()
    if (p.isString) return p.content
    return p.longOrNull ?: p.doubleOrNull ?: p.booleanOrNull ?: p.contentOrNull
}
