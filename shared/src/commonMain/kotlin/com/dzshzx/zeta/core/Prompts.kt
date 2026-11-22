package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject

/*
 * Claude Code 等用户回应的请求，和终端版（TUI）里弹出的那些对话框一一对应：工具确认、计划审批、提问（can_use_tool），
 * MCP 服务器的表单或网址（elicitation）。读请求、拼应答、校验表单都在核心里（helper/prompt），这里只是核心推来的样子
 * （conv.state 的 prompts，读法见 conv/ConvState.kt）和用户的回应（经 conv.answer、conv.elicit 交回核心）。
 */

/** 一个在等用户回应的请求。 */
@Immutable
sealed interface UserPrompt {
    val requestId: String

    /** 通知里的一句：在等什么。 */
    val notice: String

    /** 要用户回答（提问、MCP 表单），不只是点允许或拒绝。胶囊据此说“需要你回答”还是“需要你确认”。 */
    val asksInput: Boolean
}

@Immutable
data class QuestionOption(val label: String, val description: String, val preview: String? = null)

@Immutable
data class Question(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
    /** 能给选中的那项写备注（和 TUI 一样，只有单选题、有选项带预览时才能写）。 */
    val takesNotes: Boolean = false,
)

/** Claude Code 发来的 can_use_tool：等用户批准一次工具调用、审批计划，或回答 AskUserQuestion。 */
@Immutable
data class PermissionRequest(
    override val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val questions: List<Question> = emptyList(),
    /** “以后不再问”会做的几件事，逐条的大白话；空的时候不给这个选项。 */
    val suggestionLines: List<String> = emptyList(),
    /** Claude Code 说明为什么要问（decision_reason），没有时为 null。 */
    val reason: String? = null,
    /** 后台子代理发起的：确认框上它的叫法（核心给的 agent_label，找不到说明时是「后台子代理」）；主对话发起的为 null。 */
    val agentLabel: String? = null,
    /** 要批准的那次工具调用（tool_use_id）。 */
    val toolUseId: String? = null,
    /** 这是计划审批（ExitPlanMode）。 */
    val isPlan: Boolean = false,
    override val notice: String = "",
    override val asksInput: Boolean = false,
) : UserPrompt

/** MCP 服务器的 elicitation：[url] 不为 null 时是网址模式（去网页上做），否则是表单，[fields] 是要填的各项。 */
@Immutable
data class ElicitationRequest(
    override val requestId: String,
    val server: String,
    val message: String,
    val url: String? = null,
    val fields: List<ElicitField> = emptyList(),
    override val notice: String = "",
    override val asksInput: Boolean = true,
) : UserPrompt

/** 表单里的一项，按 MCP 的 requestedSchema（只有一层、只有基本类型）读出来。 */
@Immutable
data class ElicitField(
    val key: String,
    val title: String,
    val description: String,
    val required: Boolean,
    val kind: Kind,
    /** 单选、多选的可选值。 */
    val choices: List<Choice> = emptyList(),
    /** schema 给的默认值，已经按 [kind] 换成输入框里的写法（多选是选中的值，开关是 "true"/"false"）。 */
    val default: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** email、uri、date、date-time 之类，只用来换键盘和提示。 */
    val format: String? = null,
) {
    enum class Kind {
        Text,
        Integer,
        Number,
        Toggle,
        Single,
        Multi,
    }

    @Immutable data class Choice(val value: String, val label: String)
}

/** 用户对一个 can_use_tool 的回应（conv.answer 的 reply）。 */
sealed interface PermissionReply {
    /** 允许这一次。 */
    data object Allow : PermissionReply

    /** 允许，并照 Claude Code 的建议以后不再问（TUI 的 “Yes, and don't ask again …”）。 */
    data object AllowAlways : PermissionReply

    /**
     * 拒绝。没写理由时和 TUI 按 Esc 一样：这一轮停下等用户；写了理由（TUI 的 “No, and tell Claude what to do differently”）
     * 就把它交给 Claude，它接着做。计划审批的“不批准、继续完善”也走这里，理由必填。
     */
    data class Deny(val feedback: String?) : PermissionReply

    /** 批准计划，之后按 [mode] 跑（acceptEdits 是自动接受改动，default 是逐项确认）。 */
    data class ApprovePlan(val mode: String) : PermissionReply

    /** 回答 AskUserQuestion：问题 → 回答（多选用 ", " 连起来，自己写的照原样），问题 → 备注。 */
    data class Answer(
        val answers: Map<String, String>,
        val notes: Map<String, String> = emptyMap(),
    ) : PermissionReply

    /** 先不回答，想和 Claude 聊聊这些问题（TUI 的 “Chat about this”）。[answers]、[notes] 是已经选了、写了的。 */
    data class Clarify(
        val answers: Map<String, String>,
        val notes: Map<String, String> = emptyMap(),
    ) : PermissionReply
}
