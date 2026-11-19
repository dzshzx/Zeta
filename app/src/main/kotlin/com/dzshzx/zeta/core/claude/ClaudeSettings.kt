package com.dzshzx.zeta.core.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** ~/.claude/settings.json 里设置页关心的几项。 */
data class MemorySettings(
    val autoMemory: Boolean,
    /** env 里设了 CLAUDE_CODE_DISABLE_AUTO_MEMORY，开关不起作用。 */
    val forcedOff: Boolean,
    /** autoMemoryDirectory 原样的值。 */
    val customDir: String?,
)

/**
 * 读改 settings.json：只动要改的那一个键，别的键和顺序原样保留；写回的格式和 Claude Code 自己写的一样（两格缩进、末尾换行）。
 * 文件不是有效的 JSON 时不碰它。
 */
object ClaudeSettings {
    private val json = Json

    class Invalid : Exception("~/.claude/settings.json 不是有效的 JSON，先在终端里修好它")

    /** 文件不存在或是空的给空对象；不是 JSON 对象抛 [Invalid]。 */
    fun parse(text: String?): JsonObject {
        if (text == null || text.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw Invalid()
    }

    /** 读的时候宽松一些：坏了也只当没设。 */
    fun parseLenient(text: String?): JsonObject = runCatching { parse(text) }.getOrDefault(JsonObject(emptyMap()))

    fun memory(settings: JsonObject): MemorySettings {
        val env = settings["env"] as? JsonObject
        val disable = (env?.get("CLAUDE_CODE_DISABLE_AUTO_MEMORY") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        return MemorySettings(
            autoMemory = (settings["autoMemoryEnabled"] as? JsonPrimitive)?.booleanOrNull != false,
            forcedOff = disable in setOf("1", "true", "yes", "on"),
            customDir = (settings["autoMemoryDirectory"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    /** 把 [key] 设成 [value]（null 是删掉）；已有的键留在原位，新键放在最后。 */
    fun with(settings: JsonObject, key: String, value: JsonElement?): JsonObject {
        val map = LinkedHashMap(settings)
        if (value == null) map.remove(key) else map[key] = value
        return JsonObject(map)
    }

    fun withAutoMemory(settings: JsonObject, on: Boolean): JsonObject = with(settings, "autoMemoryEnabled", JsonPrimitive(on))

    /**
     * skillOverrides：Claude Code 自己停用 Skill 的设置（键是 Skill 的名字，"off" 是对模型和斜杠命令都隐藏）。
     * 停用写 "off"；启用把这个名字下的 "off" 删掉（没写就是启用），别的取值（name-only 之类）不动。
     */
    fun skillOverride(settings: JsonObject, names: Collection<String>): String? {
        val overrides = settings["skillOverrides"] as? JsonObject ?: return null
        return names.firstNotNullOfOrNull { (overrides[it] as? JsonPrimitive)?.contentOrNull }
    }

    fun withSkillEnabled(settings: JsonObject, name: String, aliases: Collection<String>, enabled: Boolean): JsonObject {
        val current = settings["skillOverrides"] as? JsonObject ?: JsonObject(emptyMap())
        val overrides = LinkedHashMap(current)
        if (enabled) {
            (aliases + name).forEach { if ((overrides[it] as? JsonPrimitive)?.contentOrNull == "off") overrides.remove(it) }
        } else {
            overrides[name] = JsonPrimitive("off")
        }
        return with(settings, "skillOverrides", if (overrides.isEmpty() && settings["skillOverrides"] == null) null else JsonObject(overrides))
    }

    /** 删掉某个 Skill 时顺带清掉它的 skillOverrides 项。 */
    fun withoutSkill(settings: JsonObject, name: String): JsonObject {
        val current = settings["skillOverrides"] as? JsonObject ?: return settings
        if (name !in current) return settings
        return with(settings, "skillOverrides", JsonObject(LinkedHashMap(current).apply { remove(name) }))
    }

    /** 和 `JSON.stringify(value, null, 2) + "\n"` 一样的写法。 */
    fun render(element: JsonElement): String = StringBuilder().also { write(it, element, 0) }.append('\n').toString()

    private fun write(out: StringBuilder, element: JsonElement, depth: Int) {
        when (element) {
            is JsonObject -> if (element.isEmpty()) out.append("{}") else {
                out.append("{\n")
                element.entries.forEachIndexed { index, (key, value) ->
                    indent(out, depth + 1)
                    out.append(JsonPrimitive(key).toString()).append(": ")
                    write(out, value, depth + 1)
                    if (index < element.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append('}')
            }
            is JsonArray -> if (element.isEmpty()) out.append("[]") else {
                out.append("[\n")
                element.forEachIndexed { index, value ->
                    indent(out, depth + 1)
                    write(out, value, depth + 1)
                    if (index < element.size - 1) out.append(',')
                    out.append('\n')
                }
                indent(out, depth)
                out.append(']')
            }
            is JsonNull -> out.append("null")
            is JsonPrimitive -> out.append(element.toString())
        }
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }
}
