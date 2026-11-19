package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.schedule.UnattendedRun
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 一组不碰屏幕的手机工具：设备状态、闹钟、日历、通知、个人数据这一类。
 * PhoneTools 把各组的定义并进 MCP 的 tools/list；调用时不走屏幕那一套（不记控件编号、不切屏），直接交给这里，
 * 但用户暂停或接管时同样先等着。每组是本目录下自己文件里的一个 object，写好后在 [PhoneToolGroups.all] 里加一行。
 */
internal interface PhoneToolGroup {
    /** MCP 工具定义，用 [tool]、[schema]、[prop]、[enumProp] 写。名字在所有组和 PhoneTools 之间不能重复。 */
    val tools: List<JsonElement>

    /** 执行一次调用，返回 MCP 的 result（[toolText]、[toolFailure]）。只会收到 [tools] 里有的名字；在后台线程调用，可以阻塞。 */
    fun call(context: Context, name: String, input: JsonObject): JsonObject

    /** 聊天里和胶囊上显示的一句中文，比如「打开 Wi-Fi」「查看今天的日程」。 */
    fun title(name: String, input: JsonObject): String

    /** 交给 Claude Code 的工具清单：[run] 是这个对话正在跑的无人值守后台运行（没有时为 null）。默认就是 [tools]。 */
    fun tools(run: UnattendedRun?): List<JsonElement> = tools

    /** 带上这个对话正在跑的后台运行执行一次调用（见 [tools]）；默认不看它。 */
    fun call(context: Context, name: String, input: JsonObject, run: UnattendedRun?): JsonObject = call(context, name, input)
}

internal object PhoneToolGroups {
    /** 所有工具组。新的一组在这里加一行。 */
    val all: List<PhoneToolGroup> = listOf(
        PersonalTools,
        ClipboardTools,
        DeviceTools,
        ScheduleTools,
        BrowserTools,
    )

    private val byName: Map<String, PhoneToolGroup> by lazy {
        all.flatMap { group -> group.tools.map { (it as JsonObject)["name"].toString().trim('"') to group } }.toMap()
    }

    fun find(name: String): PhoneToolGroup? = byName[name]
}

/** 以 root 在 Android 一侧执行一条 sh 命令（不在 Linux 环境里）。 */
internal fun rootShell(command: String, timeoutMs: Long = 15_000): CommandResult =
    LinuxEnv.runRoot(LinuxEnv.androidScript("exec /system/bin/sh -c \"\$2\"", command), timeoutMs)

internal fun toolText(value: String): JsonObject = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", value) }) }
}

internal fun toolFailure(value: String): JsonObject = buildJsonObject {
    putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", value) }) }
    put("isError", true)
}

internal fun tool(name: String, description: String, readOnly: Boolean = false, build: JsonObjectBuilder.() -> Unit): JsonElement = buildJsonObject {
    put("name", name)
    put("description", description)
    build()
    if (readOnly) putJsonObject("annotations") { put("readOnlyHint", true) }
}

internal fun JsonObjectBuilder.schema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit) {
    putJsonObject("inputSchema") {
        put("type", "object")
        putJsonObject("properties", properties)
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

internal fun JsonObjectBuilder.prop(name: String, type: String, description: String) {
    putJsonObject(name) { put("type", type); put("description", description) }
}

internal fun JsonObjectBuilder.enumProp(name: String, values: List<String>, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", description)
    }
}
