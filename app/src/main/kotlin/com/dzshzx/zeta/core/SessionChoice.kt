package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 重新打开旧对话时，模型和思考强度照这个对话主线上最后一次真实回答恢复。纯逻辑。
 *
 * 那次回答用的模型和思考强度由 zeta_helper.py history 在 zeta_history 行给出（会话记录里每条回答都记着 model、effort）。
 * 记录里的模型是完整 ID（claude-opus-5-5），选择器里是 Claude Code 报的选项（default、opus、opus[1m] 这类），
 * 靠选项的 resolvedModel 对上。
 */
internal object SessionChoice {
    /** 恢复出的模型：用 [value]（null 是 Claude Code 的默认模型）；[missing] 是记录里那个、现在选不到的模型，要提示一句。 */
    data class Model(val value: String?, val missing: String? = null)

    /**
     * 记录里的模型 [recorded] 对应的选项。[fallback] 是新对话的默认模型（null 是 Claude Code 的默认）：
     * 没有记录、对不上、或者默认模型本来就是它时都用它。对得上的选项有几个时，先取 resolvedModel 一字不差的，
     * 再取带后缀的变体（opus[1m]），default 放最后，免得 Claude Code 换了默认模型以后这个对话跟着换。
     * 不知道选项对应什么模型（还没有选项清单，或 Claude Code 没报 resolvedModel）时不提示，照用默认。
     */
    fun model(recorded: String?, options: List<ModelOption>, fallback: String?): Model {
        val wanted = recorded?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("<") } ?: return Model(fallback)
        val base = baseModel(wanted)
        if (fallback != null && baseModel(fallback) == base) return Model(fallback)
        val matches = options.filter { it.value == wanted || it.resolved?.let(::baseModel) == base }
        if (matches.isEmpty()) {
            return if (options.any { it.resolved != null }) Model(fallback, missing = wanted) else Model(fallback)
        }
        if (matches.any { it.value == (fallback ?: DEFAULT) }) return Model(fallback)
        val best = matches.sortedBy {
            when {
                it.value == DEFAULT -> 2
                it.value == wanted || it.resolved == wanted -> 0
                else -> 1
            }
        }.first()
        return Model(best.value.takeIf { it != DEFAULT })
    }

    /** 记录里的思考强度 [recorded]：是 Claude Code 认的那几档才恢复，否则返回 null，照用默认。 */
    fun effort(recorded: String?): String? = recorded?.takeIf { value -> EffortLevels.any { it.first == value } }

    /** 选择器里显示的模型名：null、default 是“默认模型”。 */
    fun label(value: String?, options: List<ModelOption>): String =
        if (value == null || value == DEFAULT) "默认模型" else options.firstOrNull { it.value == value }?.displayName?.ifBlank { null } ?: value

    /** 恢复时模型对不上，在对话末尾给的提示。 */
    fun missingNotice(recorded: String, used: String?, options: List<ModelOption>): String =
        "这个对话上次用的 $recorded 现在选不到了，先改用${label(used, options)}"

    /** Claude Code initialize 应答里的 models；也是存在设置里的那份缓存的形状。 */
    fun parseOptions(list: JsonArray?): List<ModelOption> = list.orEmpty().mapNotNull { element ->
        val m = element as? JsonObject ?: return@mapNotNull null
        ModelOption(m.str("value") ?: return@mapNotNull null, m.str("displayName").orEmpty(), m.str("description").orEmpty(), m.str("resolvedModel"))
    }

    fun encodeOptions(list: List<ModelOption>): String = buildJsonArray {
        for (m in list) add(buildJsonObject {
            put("value", m.value)
            put("displayName", m.displayName)
            put("description", m.description)
            m.resolved?.let { put("resolvedModel", it) }
        })
    }.toString()

    /** 去掉 [1m] 这类后缀：会话记录里的模型 ID 不带它。 */
    private fun baseModel(id: String): String = id.substringBefore('[').trim()

    private const val DEFAULT = "default"
}
