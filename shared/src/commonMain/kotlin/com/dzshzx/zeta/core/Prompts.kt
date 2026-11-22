package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Claude Code 等用户回应的请求，和终端版（TUI）里弹出的那些对话框一一对应。它们经 --permission-prompt-tool stdio 变成
 * 发给 Zeta 的 control_request（2.1.330 实测，ClaudeInteractionContractTest 钉住）：
 * - can_use_tool：批准一次工具调用（TUI 的权限确认）、计划审批（ExitPlanMode）、回答问题（AskUserQuestion）。
 * - elicitation：MCP 服务器要用户填一张表（form）或去网页上做一件事（url）。
 * 主对话和后台子代理可能同时各有一个在等（子代理的带 agent_id），所以按到达顺序排队、界面一次弹一个。
 * 这里是不碰 Android 的部分：从请求里读出界面要的东西，把用户的选择拼成和 TUI 一样的应答。
 */

/** 一个在等用户回应的请求。 */
@Immutable
sealed interface UserPrompt {
    val requestId: String
}

@Immutable
data class QuestionOption(val label: String, val description: String, val preview: String? = null)

@Immutable
data class Question(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
) {
    /** 和 TUI 一样：单选题有选项带预览时，才能给选中的那个写备注（annotations 的 notes）。 */
    val takesNotes: Boolean
        get() = !multiSelect && options.any { it.preview != null }
}

/** Claude Code 发来的 can_use_tool：等用户批准一次工具调用、审批计划，或回答 AskUserQuestion。 */
@Immutable
data class PermissionRequest(
    override val requestId: String,
    val toolName: String,
    val input: JsonObject,
    val questions: List<Question> = emptyList(),
    /** Claude Code 建议的“以后不再问”的改动（permission_suggestions），选了就原样交回去；空的时候不给这个选项。 */
    val suggestions: JsonArray = JsonArray(emptyList()),
    /** Claude Code 说明为什么要问（decision_reason），没有时为 null。 */
    val reason: String? = null,
    /** 发起它的后台子代理（agent_id）；主对话发起的为 null。 */
    val agentId: String? = null,
    /**
     * 要批准的那次工具调用（tool_use_id）。几个客户端接着同一个进程时，别的客户端答了，这边看到它的 tool_result
     * 就收起（Conversation.handleUser）。
     */
    val toolUseId: String? = null,
) : UserPrompt {
    val isPlan: Boolean
        get() = toolName == "ExitPlanMode"
}

/** MCP 服务器的 elicitation：[url] 不为 null 时是网址模式（去网页上做），否则是表单，[fields] 是要填的各项。 */
@Immutable
data class ElicitationRequest(
    override val requestId: String,
    val server: String,
    val message: String,
    val url: String? = null,
    val fields: List<ElicitField> = emptyList(),
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

/** 读一行 control_request；不是要用户回应的（比如手机工具的 mcp_message）返回 null。 */
fun parsePrompt(event: JsonObject): UserPrompt? {
    if (event.str("type") != "control_request") return null
    val id = event.str("request_id") ?: return null
    val request = event.obj("request") ?: return null
    return when (request.str("subtype")) {
        "can_use_tool" -> {
            val tool = request.str("tool_name").orEmpty()
            val input = request.obj("input") ?: JsonObject(emptyMap())
            PermissionRequest(
                requestId = id,
                toolName = tool,
                input = input,
                questions =
                    if (tool == "AskUserQuestion") parseQuestions(input.arr("questions"))
                    else emptyList(),
                // 这类请求 TUI 不给“不再问”那一行（规则会比这次要做的宽）
                suggestions =
                    if (request.bool("suppress_always_allow_rule") == true) JsonArray(emptyList())
                    else request.arr("permission_suggestions") ?: JsonArray(emptyList()),
                reason =
                    (request["decision_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf {
                        it.isNotBlank()
                    } ?: request.obj("decision_reason")?.str("reason")?.takeIf { it.isNotBlank() },
                agentId = request.str("agent_id"),
                toolUseId = request.str("tool_use_id"),
            )
        }
        "elicitation" ->
            ElicitationRequest(
                requestId = id,
                server = request.str("display_name") ?: request.str("mcp_server_name").orEmpty(),
                message = request.str("message").orEmpty(),
                url = request.str("url")?.takeIf { request.str("mode") == "url" },
                fields =
                    request
                        .obj("requested_schema")
                        ?.let { parseFields(it, request.arr("property_order")) }
                        .orEmpty(),
            )
        else -> null
    }
}

private fun parseQuestions(list: JsonArray?): List<Question> =
    list.orEmpty().mapNotNull { element ->
        val q = element as? JsonObject ?: return@mapNotNull null
        Question(
            question = q.str("question").orEmpty(),
            header = q.str("header").orEmpty(),
            options =
                q.arr("options").orEmpty().mapNotNull {
                    (it as? JsonObject)?.let { o ->
                        QuestionOption(
                            o.str("label").orEmpty(),
                            o.str("description").orEmpty(),
                            o.str("preview"),
                        )
                    }
                },
            multiSelect = q.bool("multiSelect") == true,
        )
    }

private fun parseFields(schema: JsonObject, order: JsonArray?): List<ElicitField> {
    val properties = schema.obj("properties") ?: return emptyList()
    val required =
        schema
            .arr("required")
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .toSet()
    val keys =
        order
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it in properties }
            .ifEmpty { properties.keys.toList() }
    return keys.mapNotNull { key ->
        (properties[key] as? JsonObject)?.let { field(key, it, key in required) }
    }
}

private fun field(key: String, p: JsonObject, required: Boolean): ElicitField {
    val type = p.str("type")
    val choices = choicesOf(p)
    val kind =
        when {
            type == "array" -> ElicitField.Kind.Multi
            choices.isNotEmpty() -> ElicitField.Kind.Single
            type == "boolean" -> ElicitField.Kind.Toggle
            type == "integer" -> ElicitField.Kind.Integer
            type == "number" -> ElicitField.Kind.Number
            else -> ElicitField.Kind.Text
        }
    val default =
        when (val d = p["default"]) {
            is JsonArray -> d.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(d.contentOrNull)
            else -> emptyList()
        }
    return ElicitField(
        key = key,
        title = p.str("title")?.takeIf { it.isNotBlank() } ?: key,
        description = p.str("description").orEmpty(),
        required = required,
        kind = kind,
        choices =
            if (kind == ElicitField.Kind.Multi) choicesOf(p.obj("items") ?: JsonObject(emptyMap()))
            else choices,
        default = default,
        min =
            (p["minimum"] as? JsonPrimitive)?.doubleOrNull
                ?: (p["minItems"] as? JsonPrimitive)?.doubleOrNull,
        max =
            (p["maximum"] as? JsonPrimitive)?.doubleOrNull
                ?: (p["maxItems"] as? JsonPrimitive)?.doubleOrNull,
        minLength = p.int("minLength"),
        maxLength = p.int("maxLength"),
        format = p.str("format"),
    )
}

/** enum（可带 enumNames）或 oneOf / anyOf 的 const + title 两种写法。 */
private fun choicesOf(p: JsonObject): List<ElicitField.Choice> {
    p.arr("enum")?.let { values ->
        val names = p.arr("enumNames").orEmpty()
        return values.mapIndexedNotNull { i, v ->
            val value = (v as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
            ElicitField.Choice(
                value,
                (names.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: value,
            )
        }
    }
    return (p.arr("oneOf") ?: p.arr("anyOf"))
        .orEmpty()
        .mapNotNull { (it as? JsonObject) }
        .mapNotNull { o ->
            val value = (o["const"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            ElicitField.Choice(value, o.str("title") ?: value)
        }
}

/** 用户对一个 can_use_tool 的回应。 */
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

/** TUI 拒绝并写了理由时交给模型的话，后面接用户写的。 */
const val REJECT_WITH_FEEDBACK =
    "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n"

/** can_use_tool 应答里的 response（behavior 那一层）。 */
fun permissionResponse(request: PermissionRequest, reply: PermissionReply): JsonObject =
    buildJsonObject {
        when (reply) {
            PermissionReply.Allow -> allow(request.input)
            PermissionReply.AllowAlways -> {
                allow(request.input)
                if (request.suggestions.isNotEmpty()) put("updatedPermissions", request.suggestions)
            }
            is PermissionReply.ApprovePlan -> {
                allow(request.input)
                putJsonArray("updatedPermissions") {
                    addJsonObject {
                        put("type", "setMode")
                        put("mode", reply.mode)
                        put("destination", "session")
                    }
                }
            }
            is PermissionReply.Answer -> allow(answered(request, reply.answers, reply.notes))
            is PermissionReply.Deny -> {
                put("behavior", "deny")
                val feedback = reply.feedback?.trim().orEmpty()
                if (feedback.isEmpty()) {
                    // 带 interrupt 时 Claude Code 用它自己的那句（STOP … wait for the user），message 不交给模型
                    put("message", "User rejected tool use")
                    put("interrupt", true)
                } else {
                    put("message", REJECT_WITH_FEEDBACK + feedback)
                }
            }
            is PermissionReply.Clarify -> {
                put("behavior", "deny")
                put(
                    "message",
                    REJECT_WITH_FEEDBACK +
                        clarifyText(request.questions, reply.answers, reply.notes),
                )
            }
        }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.allow(input: JsonObject) {
    put("behavior", "allow")
    put("updatedInput", input)
}

/** 原来的参数加上 answers 和 annotations（选中那项的预览、备注），和 TUI 交回去的一样。 */
private fun answered(
    request: PermissionRequest,
    answers: Map<String, String>,
    notes: Map<String, String>,
): JsonObject {
    val annotations = buildJsonObject {
        for (q in request.questions) {
            val preview =
                if (q.multiSelect) null
                else q.options.firstOrNull { it.label == answers[q.question] }?.preview
            val note = notes[q.question]?.trim()?.takeIf { it.isNotEmpty() && q.takesNotes }
            if (preview == null && note == null) continue
            putJsonObject(q.question) {
                preview?.let { put("preview", it) }
                note?.let { put("notes", it) }
            }
        }
    }
    val extra =
        buildMap<String, JsonElement> {
            put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
            if (annotations.isNotEmpty()) put("annotations", annotations)
        }
    return JsonObject(request.input + extra)
}

/** TUI 的 “Chat about this” 交给模型的说明，连同已经选了的回答和备注。 */
fun clarifyText(
    questions: List<Question>,
    answers: Map<String, String>,
    notes: Map<String, String>,
): String = buildString {
    append("The user wants to clarify these questions.\n")
    append("    This means they may have additional information, context or questions for you.\n")
    append(
        "    Take their response into account and then reformulate the questions if appropriate.\n",
    )
    append("    Start by asking them what they would like to clarify.\n")
    append("    Questions asked:\n")
    append(
        questions.joinToString("\n") { q ->
            buildString {
                append("- \"").append(q.question).append('"')
                val answer = answers[q.question]?.takeIf { it.isNotBlank() }
                append(if (answer != null) "\n  Answer: $answer" else "\n  (No answer provided)")
                notes[q.question]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && q.takesNotes }
                    ?.let { append("\n  User notes: ").append(it) }
            }
        },
    )
}

/** “以后不再问”会做的几件事，界面上逐条列出来。认不出的类型照原样写类型名。 */
fun describeSuggestions(suggestions: JsonArray): List<String> =
    suggestions
        .mapNotNull { it as? JsonObject }
        .flatMap { s ->
            when (s.str("type")) {
                "addRules" -> {
                    val rules =
                        s.arr("rules").orEmpty().mapNotNull { r ->
                            (r as? JsonObject)?.let { o ->
                                val tool = o.str("toolName") ?: return@mapNotNull null
                                o.str("ruleContent")?.let { "$tool($it)" } ?: tool
                            }
                        }
                    val verb =
                        when (s.str("behavior")) {
                            "deny" -> "一律拒绝"
                            "ask" -> "每次都问"
                            else -> "允许"
                        }
                    rules.map { "$verb $it" + destinationNote(s.str("destination")) }
                }
                "addDirectories" ->
                    s.arr("directories")
                        .orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .map { "允许访问 $it" + destinationNote(s.str("destination")) }
                "setMode" ->
                    listOf(
                        when (s.str("mode")) {
                            "acceptEdits" -> "这个对话里自动接受改动"
                            "bypassPermissions" -> "这个对话里完全放行"
                            "default" -> "这个对话里逐项确认"
                            "plan" -> "这个对话切到计划模式"
                            else -> "这个对话切到 ${s.str("mode")} 模式"
                        },
                    )
                else -> listOf(s.str("type").orEmpty())
            }
        }

/** 规则记在哪：项目里只给自己用的（settings.local.json）是 TUI 的默认，不另外说明。 */
private fun destinationNote(destination: String?): String =
    when (destination) {
        "userSettings" -> "（所有项目）"
        "projectSettings" -> "（这个项目，进 Git）"
        "session" -> "（只在这个对话里）"
        else -> ""
    }

/** elicitation 应答里的 response：action 是 accept、decline 或 cancel，accept 表单时带 [content]。 */
fun elicitationResponse(action: String, content: JsonObject? = null): JsonObject = buildJsonObject {
    put("action", action)
    content?.let { put("content", it) }
}

/**
 * 把表单里填的（键 → 输入框里的字；多选是选中的值，开关是 "true"/"false"）换成要交回去的 content，顺带按 schema 校验。 返回 content 和每一项的错误（键
 * → 说明）；有错误时 content 为 null。没填的可选项不交。
 */
fun elicitationContent(
    fields: List<ElicitField>,
    values: Map<String, List<String>>,
): Pair<JsonObject?, Map<String, String>> {
    val errors = mutableMapOf<String, String>()
    val content = buildJsonObject {
        for (f in fields) {
            val (value, error) = fieldValue(f, values[f.key].orEmpty())
            value?.let { put(f.key, it) }
            error?.let { errors[f.key] = it }
        }
    }
    return (if (errors.isEmpty()) content else null) to errors
}

/** 表单里一项要交的值（没填的可选项是 null）和校验没过的说明。 */
private fun fieldValue(f: ElicitField, raw: List<String>): Pair<JsonElement?, String?> {
    val text = raw.firstOrNull()?.trim().orEmpty()
    return when (f.kind) {
        // 开关没有“没填”：没动就是关
        ElicitField.Kind.Toggle -> JsonPrimitive(JsonPrimitive(text).booleanOrNull == true) to null
        ElicitField.Kind.Multi -> if (raw.isEmpty()) null to required(f) else multiValue(f, raw)
        ElicitField.Kind.Integer,
        ElicitField.Kind.Number -> if (text.isEmpty()) null to required(f) else numberValue(f, text)
        ElicitField.Kind.Text,
        ElicitField.Kind.Single -> if (text.isEmpty()) null to required(f) else textValue(f, text)
    }
}

private fun required(f: ElicitField): String? = if (f.required) "必填" else null

private fun textValue(f: ElicitField, text: String): Pair<JsonElement, String?> {
    val error =
        when {
            f.minLength != null && text.length < f.minLength -> "至少 ${f.minLength} 个字"
            f.maxLength != null && text.length > f.maxLength -> "最多 ${f.maxLength} 个字"
            else -> null
        }
    return JsonPrimitive(text) to error
}

private fun numberValue(f: ElicitField, text: String): Pair<JsonElement?, String?> {
    val integer = f.kind == ElicitField.Kind.Integer
    val number =
        text.toDoubleOrNull()?.takeIf { !integer || it % 1.0 == 0.0 }
            ?: return null to (if (integer) "要填整数" else "要填数字")
    val error =
        when {
            f.min != null && number < f.min -> "不能小于 ${plain(f.min)}"
            f.max != null && number > f.max -> "不能大于 ${plain(f.max)}"
            else -> null
        }
    return (if (integer) JsonPrimitive(number.toLong()) else JsonPrimitive(number)) to error
}

private fun multiValue(f: ElicitField, raw: List<String>): Pair<JsonElement, String?> {
    val error =
        when {
            f.min != null && raw.size < f.min -> "至少选 ${plain(f.min)} 项"
            f.max != null && raw.size > f.max -> "最多选 ${plain(f.max)} 项"
            else -> null
        }
    return buildJsonArray { raw.forEach { add(it) } } to error
}

private fun plain(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

/** 是要用户回答（问题、MCP 表单），不只是点允许或拒绝。胶囊据此说“需要你回答”还是“需要你确认”。 */
val UserPrompt.asksForInput: Boolean
    get() =
        this is ElicitationRequest || (this is PermissionRequest && toolName == "AskUserQuestion")

/** 通知里的一句：在等什么。 */
val UserPrompt.notice: String
    get() =
        when (this) {
            is ElicitationRequest -> "MCP 服务器 $server 需要你" + (if (url != null) "去网页上操作" else "填写信息")
            is PermissionRequest ->
                when {
                    isPlan -> "Claude 交来了计划，等你审批"
                    toolName == "AskUserQuestion" -> "Claude 有问题要问你"
                    else -> "Claude 想用 $toolName"
                }
        }
