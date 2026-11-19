package com.dzshzx.zeta.core.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 前言里一个键的值：普通文字、列表（`[a, b]` 或逐行 `- a`），或下一层的 `key: value`。 */
sealed interface FrontValue {
    data class Text(val value: String) : FrontValue
    data class Items(val values: List<String>) : FrontValue
    data class Fields(val values: Map<String, String>) : FrontValue

    /** 给人看的文字：列表用逗号连起来，下一层的键值不算。 */
    fun text(): String = when (this) {
        is Text -> value
        is Items -> values.joinToString(", ")
        is Fields -> ""
    }
}

/** 前言里的一个顶层键和它占的原文行；[key] 为 null 是第一个键之前的行（注释之类）。 */
data class FrontBlock(val key: String?, val lines: List<String>)

/**
 * Markdown 开头 `---` 包起来的 YAML 前言（SKILL.md、记忆文件、Subagent 文件都用它）。
 * 只认顶层的 `key: value`，够读这几种文件；改写时按原文行保留不认识的键，不经过 YAML 库重排。
 */
object Frontmatter {
    data class Split(
        /** 前言的原文（不含两条 `---`）；没有前言是 null。 */
        val front: String?,
        val body: String,
    )

    private val KeyLine = Regex("([A-Za-z0-9_-]+)\\s*:(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val NestedLine = Regex("\\s+([A-Za-z0-9_-]+)\\s*:(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val TrailingComment = Regex("\\s+#.*$")
    private val json = Json

    fun split(text: String): Split {
        if (!text.startsWith("---")) return Split(null, text)
        val lines = text.split("\n")
        if (lines[0].trim() != "---") return Split(null, text)
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                return Split(lines.subList(1, i).joinToString("\n"), lines.subList(i + 1, lines.size).joinToString("\n"))
            }
        }
        return Split(null, text)
    }

    /** 把前言切成顶层块。 */
    fun blocks(front: String): List<FrontBlock> {
        val blocks = mutableListOf<Pair<String?, MutableList<String>>>()
        for (line in front.split("\n")) {
            val match = KeyLine.matchEntire(line)
            if (match != null && line.firstOrNull()?.isWhitespace() != true) {
                blocks += match.groupValues[1] to mutableListOf(line)
            } else if (blocks.isNotEmpty()) {
                blocks.last().second += line
            } else {
                blocks += null to mutableListOf(line)
            }
        }
        return blocks.map { FrontBlock(it.first, it.second) }
    }

    fun parse(front: String?): Map<String, FrontValue> {
        val data = LinkedHashMap<String, FrontValue>()
        for (block in blocks(front ?: return data)) {
            val key = block.key ?: continue
            val first = block.lines[0].substringAfter(':').trim()
            val tail = block.lines.drop(1)
            val rest = tail.filter { it.isNotBlank() }
            data[key] = when {
                first in BlockIndicators -> {
                    val indent = rest.minOfOrNull { it.length - it.trimStart().length } ?: 0
                    val body = tail.map { if (it.length >= indent) it.substring(indent) else "" }
                    FrontValue.Text(body.joinToString(if (first[0] == '|') "\n" else " ").trim())
                }
                first.isEmpty() && rest.isNotEmpty() && rest.all { it.trimStart().startsWith("- ") } ->
                    FrontValue.Items(rest.map { scalar(it.trimStart().substring(2)).text() })
                first.isEmpty() && rest.isNotEmpty() ->
                    FrontValue.Fields(
                        rest.mapNotNull { NestedLine.matchEntire(it) }
                            .associate { it.groupValues[1] to scalar(it.groupValues[2]).text() },
                    )
                // 折行的普通标量
                rest.isNotEmpty() && !first.startsWith("[") && !first.startsWith("\"") && !first.startsWith("'") ->
                    scalar((listOf(first) + rest.map { it.trim() }).joinToString(" "))
                else -> scalar(first)
            }
        }
        return data
    }

    private val BlockIndicators = setOf("|", ">", "|-", ">-", "|+", ">+")

    fun scalar(raw: String): FrontValue {
        val value = raw.trim()
        if (value.isEmpty()) return FrontValue.Text("")
        if (value[0] == '"') {
            val decoded = runCatching { (json.parseToJsonElement(value) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull }.getOrNull()
            return FrontValue.Text(decoded ?: value.trim('"'))
        }
        if (value[0] == '\'' && value.length > 1 && value.endsWith("'")) {
            return FrontValue.Text(value.substring(1, value.length - 1).replace("''", "'"))
        }
        if (value[0] == '[' && value.endsWith("]")) {
            return FrontValue.Items(value.substring(1, value.length - 1).split(",").filter { it.isNotBlank() }.map { scalar(it).text() })
        }
        return FrontValue.Text(TrailingComment.replace(value, ""))
    }

    private val PlainWords = setOf("true", "false", "null", "yes", "no", "on", "off", "~")
    private val Numberish = Regex("[-+]?(\\d[\\d_]*)?(\\.\\d*)?([eE][-+]?\\d+)?")

    /** 能写成普通标量就原样写，否则写成双引号字符串（JSON 的写法同时也是合法的 YAML）。 */
    fun yamlValue(text: String): String {
        val quote = text.isEmpty() || text != text.trim() || '\n' in text || '\r' in text || '\t' in text ||
            ": " in text || " #" in text || text.endsWith(":") || text[0] in "!&*[]{}|>'\"%@`#,?-:" ||
            text.lowercase() in PlainWords || (text.any { it.isDigit() } && Numberish.matches(text))
        return if (quote) JsonPrimitive(text).toString() else text
    }
}
