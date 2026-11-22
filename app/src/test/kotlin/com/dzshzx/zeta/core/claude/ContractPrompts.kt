package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.arr
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.obj
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * 契约测试里扮客户端答 Claude Code 的请求用的：读 can_use_tool、拼和终端版一样的应答、还原代码的控制请求。
 * 产品里这些在核心（helper/prompt、helper/conv），这里只为对着真 claude 钉住它的行为，照同样的写法手写。
 */

/** TUI 拒绝并写了理由时交给模型的话，后面接用户写的（helper/prompt 的 rejectWithFeedback）。 */
internal const val REJECT_WITH_FEEDBACK =
    "The user doesn't want to proceed with this tool use. The tool use was rejected (eg. if it was a file edit, the new_string was NOT written to the file). To tell you how to proceed, the user said:\n"

/** 一个 can_use_tool 请求里测试要看的几项。 */
internal class Asked(val requestId: String, val request: JsonObject) {
    val toolName: String = request.str("tool_name").orEmpty()
    val input: JsonObject = request.obj("input") ?: JsonObject(emptyMap())
    val suggestions: JsonArray = request.arr("permission_suggestions") ?: JsonArray(emptyList())
    val agentId: String? = request.str("agent_id")
    val toolUseId: String? = request.str("tool_use_id")
    val isPlan: Boolean = toolName == "ExitPlanMode"
    val asksQuestions: Boolean = toolName == "AskUserQuestion"
}

/** 读一行 control_request：是 can_use_tool 时返回它，别的是 null。 */
internal fun askedOf(event: JsonObject): Asked? {
    if (event.str("type") != "control_request") return null
    val request =
        event.obj("request")?.takeIf { it.str("subtype") == "can_use_tool" } ?: return null
    return Asked(event.str("request_id") ?: return null, request)
}

/** 读一行 control_request：是 elicitation 时返回它的 request。 */
internal fun elicitationOf(event: JsonObject): JsonObject? =
    event.obj("request")?.takeIf {
        event.str("type") == "control_request" && it.str("subtype") == "elicitation"
    }

internal fun allow(asked: Asked): JsonObject = buildJsonObject {
    put("behavior", "allow")
    put("updatedInput", asked.input)
}

/** 允许并照建议以后不再问。 */
internal fun allowAlways(asked: Asked): JsonObject =
    JsonObject(allow(asked) + ("updatedPermissions" to asked.suggestions))

/** 拒绝：不写理由时和终端里按 Esc 一样（带 interrupt），写了理由交给模型。 */
internal fun deny(feedback: String?): JsonObject = buildJsonObject {
    put("behavior", "deny")
    if (feedback.isNullOrBlank()) {
        put("message", "User rejected tool use")
        put("interrupt", true)
    } else {
        put("message", REJECT_WITH_FEEDBACK + feedback)
    }
}

/** 批准计划，之后按 [mode] 跑。 */
internal fun approvePlan(asked: Asked, mode: String): JsonObject = buildJsonObject {
    put("behavior", "allow")
    put("updatedInput", asked.input)
    putJsonArray("updatedPermissions") {
        addJsonObject {
            put("type", "setMode")
            put("mode", mode)
            put("destination", "session")
        }
    }
}

/** 回答 AskUserQuestion 的一个单选题：选 [answer]，带上它的预览和 [note]。 */
internal fun answerQuestion(
    asked: Asked,
    question: String,
    answer: String,
    note: String,
): JsonObject {
    val preview =
        (asked.input.arr("questions")?.firstOrNull() as? JsonObject)
            ?.arr("options")
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .firstOrNull { it.str("label") == answer }
            ?.str("preview")
    val updated =
        JsonObject(
            asked.input +
                buildJsonObject {
                    putJsonObject("answers") { put(question, answer) }
                    putJsonObject("annotations") {
                        putJsonObject(question) {
                            preview?.let { put("preview", it) }
                            put("notes", note)
                        }
                    }
                },
        )
    return buildJsonObject {
        put("behavior", "allow")
        put("updatedInput", updated)
    }
}

/** elicitation 的应答。 */
internal fun elicitationReply(action: String, content: JsonObject?): JsonObject = buildJsonObject {
    put("action", action)
    content?.let { put("content", it) }
}

/** rewind_files 控制请求（还原代码）；[dryRun] 只看会动哪些文件。 */
internal fun rewindFiles(target: String, dryRun: Boolean): String = buildJsonObject {
    put("subtype", "rewind_files")
    put("user_message_id", target)
    if (dryRun) put("dry_run", true)
}
    .toString()

/** dry_run 的应答里会动到的文件；没有可还原的为 null。 */
internal fun rewindFilesChanged(response: JsonObject): List<String>? {
    val body = response.takeIf { it.str("subtype") == "success" }?.obj("response") ?: return null
    if (body.bool("canRewind") != true) return null
    return body
        .arr("filesChanged")
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .ifEmpty { null }
}

/** 真还原的应答：成了为 true。 */
internal fun rewindFilesDone(response: JsonObject): Boolean =
    response.str("subtype") == "success" && response.obj("response")?.bool("canRewind") == true

/** initialize 请求：声明后台任务页有逐个停止的按钮（和核心发的一样）。 */
internal const val INITIALIZE_REQUEST = """{"subtype":"initialize","perTaskStopAffordance":true}"""

/** 起进程时打开文件快照的环境变量（核心的 codeRewindEnv）：-p 模式默认不留快照。 */
internal val CODE_REWIND_ENV = mapOf("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING" to "1")
