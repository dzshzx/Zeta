package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 一条常用指令：[name] 可以空着，显示时取内容的开头（[SavedPrompts.displayName]）。内容是纯文本，原样填进输入框。 */
@Immutable
data class SavedPrompt(val id: String, val name: String, val text: String)

/**
 * 常用指令（用户存下来的提问）的清单操作，纯函数。存在 SharedPreferences `zeta` 的 [KEY] 里（JSON 数组），读写在 [PromptStore]。
 * 顺序就是用户排的顺序，新加的放最前面。不进 `/` 菜单：那里是 Claude Code 的斜杠命令。
 */
object SavedPrompts {
    const val KEY = "savedPrompts"
    const val MAX = 100
    const val MAX_NAME = 40
    const val MAX_TEXT = 20_000

    /** 名字空着时取内容开头的几个字。 */
    private const val NAME_CHARS = 12

    private val json = Json { ignoreUnknownKeys = true }
    private val Spaces = Regex("\\s+")

    /** 内容的第一行非空文字，压掉多余空白，最多 [NAME_CHARS] 个字（按码点数，不切开表情）。 */
    fun defaultName(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.replace(Spaces, " ") ?: return ""
        val count = line.codePointCount(0, line.length)
        if (count <= NAME_CHARS) return line
        return line.substring(0, line.offsetByCodePoints(0, NAME_CHARS)).trimEnd() + "…"
    }

    fun displayName(prompt: SavedPrompt): String = prompt.name.trim().ifEmpty { defaultName(prompt.text) }

    /** 存储里的文字 → 清单。坏的条目跳过，不让一条坏数据弄丢全部。 */
    fun decode(raw: String?): List<SavedPrompt> {
        val array = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val text = o.text("text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SavedPrompt(o.text("id") ?: "p${text.hashCode()}", o.text("name").orEmpty(), text)
        }.distinctBy { it.id }
    }

    fun encode(list: List<SavedPrompt>): String = buildJsonArray {
        list.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name); put("text", it.text) }) }
    }.toString()

    /** 新建或保存编辑时的问题；null 表示可以存。[editingId] 是正在编辑的那条（新建时为 null）。 */
    fun problem(list: List<SavedPrompt>, name: String, text: String, editingId: String? = null): String? = when {
        text.isBlank() -> "内容不能为空"
        text.length > MAX_TEXT -> "内容太长了（最多 $MAX_TEXT 个字）"
        name.trim().length > MAX_NAME -> "名字太长了（最多 $MAX_NAME 个字）"
        editingId == null && list.size >= MAX -> "最多存 $MAX 条，先删掉一些"
        else -> null
    }

    /** 已经存过同样内容的那条（比较时忽略首尾空白）。 */
    fun findSame(list: List<SavedPrompt>, text: String): SavedPrompt? = text.trim().let { t -> list.firstOrNull { it.text.trim() == t } }

    /** 加到最前面。名字和默认名一样时存成空的，以后改了内容名字跟着变。 */
    fun add(list: List<SavedPrompt>, id: String, name: String, text: String): List<SavedPrompt> =
        listOf(SavedPrompt(id, storedName(name, text), text)) + list

    fun update(list: List<SavedPrompt>, id: String, name: String, text: String): List<SavedPrompt> =
        list.map { if (it.id == id) it.copy(name = storedName(name, text), text = text) else it }

    fun remove(list: List<SavedPrompt>, id: String): List<SavedPrompt> = list.filterNot { it.id == id }

    /** 往前（[delta] < 0）或往后挪；挪不动时原样返回。 */
    fun move(list: List<SavedPrompt>, id: String, delta: Int): List<SavedPrompt> {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        val mutable = list.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return mutable
    }

    private fun storedName(name: String, text: String): String = name.trim().takeUnless { it == defaultName(text) }.orEmpty()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
