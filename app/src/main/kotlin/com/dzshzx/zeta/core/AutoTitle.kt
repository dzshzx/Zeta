package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * 新对话的自动标题，用 Claude Code 自己的标题功能（2.1.327 实测）：
 * `claude -p` 不会自己起标题，但控制请求 `generate_session_title` 带 `persist: true` 时，它用自己的快速小模型（Haiku）
 * 照它的提示词起一个短标题，追加一行 `ai-title` 到会话记录，并在应答里带回 `title`。已经有自定义标题（改过名、分支）时直接返回那个，不另起。
 * 给它的内容不到 10 个字、或是斜杠命令时它返回 null：等下一轮内容多了再试，最多试 [MAX_ATTEMPTS] 轮。
 *
 * 只给 Zeta 里新开的对话起：第一次发消息时从第一句话截出临时标题（[arm]），之后标题被用户改过就不再起。
 * 出错、没起成都不提示，保留原来的标题。状态只在主线程上改。
 */
internal class AutoTitle {
    companion object {
        const val MAX_ATTEMPTS = 3

        /** 交给 Claude Code 的内容：按顺序取提问和回答的文字，最多这么多字（它自己也只看开头 2000 字）。 */
        const val MAX_DESCRIPTION = 1500

        /** Claude Code 少于 10 个字不起标题。 */
        private const val MIN_DESCRIPTION = 10

        /** 对话内容里给起标题用的文字：提问和回答依次接起来，附件行、斜杠命令、思考和工具调用不要。 */
        fun describe(items: List<ChatItem>): String {
            val parts = items.mapNotNull { item ->
                when (item) {
                    // 斜杠命令开头的消息 Claude Code 不拿来起标题，整条跳过
                    is UserItem -> item.text.lines().filterNot { it.startsWith("[Attached file: ") }.joinToString("\n").trim().takeUnless { it.startsWith("/") }
                    is AnswerItem -> item.text.trim()
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }
            val text = parts.joinToString("\n\n")
            return if (text.length <= MAX_DESCRIPTION) text else text.take(MAX_DESCRIPTION).trimEnd()
        }
    }

    /** 从第一句话截出来的临时标题；null 表示这个对话不再自动起标题。 */
    private var placeholder: String? = null
    private var attempts = 0

    /** 一次起标题的请求：[body] 是控制请求的 request 一项，应答回来时连同它交给 [onResponse]。 */
    class Request internal constructor(val body: JsonObject)

    /** 发出去还没回来的请求。 */
    private var pending: Request? = null

    /** 新对话（或 /clear 之后）第一次发消息时调用。 */
    fun arm(placeholderTitle: String) {
        placeholder = placeholderTitle
        attempts = 0
        pending = null
    }

    fun disarm() {
        placeholder = null
        pending = null
    }

    /**
     * 一轮正常结束时调用：该请求就返回要发给 Claude Code 的请求，否则 null。
     * 标题已经不是临时标题了（用户改了名、Claude Code 报了新标题）就不再起。
     */
    fun next(enabled: Boolean, title: String, items: List<ChatItem>): Request? {
        val expected = placeholder ?: return null
        if (!enabled || title != expected || attempts >= MAX_ATTEMPTS) {
            disarm()
            return null
        }
        if (pending != null) return null
        val description = describe(items)
        if (description.length < MIN_DESCRIPTION) return null
        attempts++
        return Request(controlBody("generate_session_title") {
            put("description", description)
            put("persist", true)
        }).also { pending = it }
    }

    /**
     * [request] 的应答（control_response 里的 response 一项）到了，新标题交给 [apply]。
     * 不是现在等着的那个（之后又重新 arm 过）就不管，返回 false；应答回来时标题已经被用户改了，就不用它。
     */
    fun onResponse(request: Request, response: JsonObject, currentTitle: String, apply: (String) -> Unit): Boolean {
        if (request !== pending) return false
        pending = null
        val expected = placeholder
        if (response.str("subtype") != "success" || expected == null) {
            // 出错就不再试了，保留原来的标题
            disarm()
            return true
        }
        val title = (response.obj("response")?.get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (title.isNullOrEmpty()) return true
        disarm()
        if (currentTitle == expected && title != currentTitle) apply(title)
        return true
    }
}
