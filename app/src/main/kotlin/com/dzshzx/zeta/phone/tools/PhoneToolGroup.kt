package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.CommandResult
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.schedule.UnattendedRun
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 交给 Claude 的一个手机工具：MCP 定义 [definition]，加上工具权限页上的中文名字 [label]（手机宽度下要一行放得下）。
 * 这一步做了什么的一句中文（聊天里的步骤、顶部胶囊）由所在的组给（[PhoneToolGroup.title]）。用 [tool] 写。
 */
internal class PhoneTool(val label: String, val definition: JsonObject) {
    val name: String = (definition["name"] as JsonPrimitive).content
}

/**
 * 一次调用所在的对话给的东西：Android 的 [context]，对话在哪台主机上（[backend]，见 [com.dzshzx.zeta.core.Backends]），
 * 这个对话正在跑的无人值守后台运行 [unattended] （定时任务、通知触发跑起来的那一轮；平常的对话是 null）。
 */
internal class CallContext(
    val context: Context,
    val backend: String,
    val unattended: UnattendedRun? = null,
)

/**
 * 一组不碰屏幕的手机工具：设备状态、闹钟、日历、通知、个人数据这一类。 PhoneTools 把各组的定义并进 MCP 的
 * tools/list；调用时不走屏幕那一套（不记控件编号、不切屏），直接交给这里， 但用户暂停或接管时同样先等着。每组是本目录下自己文件里的一个 object，写好后在
 * [PhoneToolGroups.all] 里加一行。 加一个工具只改所在的组：[tools] 里一项（带中文名字）、[call] 和 [title] 里各一个分支。
 */
internal interface PhoneToolGroup {
    /** 工具定义，用 [tool]、[schema]、[prop]、[enumProp] 写。名字在交给 Claude 的整份清单（PhoneToolCatalog）里不能重复。 */
    val tools: List<PhoneTool>

    /** 执行一次调用，返回 MCP 的 result（[toolText]、[toolFailure]）。只会收到 [tools] 里有的名字；在后台线程调用，可以阻塞。 */
    fun call(call: CallContext, name: String, input: JsonObject): JsonObject

    /** 这一步做了什么，一句中文，比如「打开 Wi-Fi」「查看今天的日程」：聊天里的步骤和顶部胶囊都用它。[tools] 里每个名字都要有自己的说法。 */
    fun title(name: String, input: JsonObject): String
}

internal object PhoneToolGroups {
    /** 所有工具组。新的一组在这里加一行。 */
    val all: List<PhoneToolGroup> =
        listOf(
            PersonalTools,
            NoteTools,
            AppTools,
            ClipboardTools,
            DeviceTools,
            ScheduleTools,
            BrowserTools,
        )

    private val byName: Map<String, PhoneToolGroup> by lazy {
        all.flatMap { group -> group.tools.map { it.name to group } }.toMap()
    }

    fun find(name: String): PhoneToolGroup? = byName[name]
}

/** 以 root 在 Android 一侧执行一条 sh 命令（不在 Linux 环境里）。 */
internal fun rootShell(command: String, timeoutMs: Long = 15_000): CommandResult =
    LinuxEnv.runRoot(LinuxEnv.androidScript("exec /system/bin/sh -c \"\$2\"", command), timeoutMs)

internal fun toolText(value: String): JsonObject = buildJsonObject {
    putJsonArray("content") {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", value)
            },
        )
    }
}

internal fun toolFailure(value: String): JsonObject = buildJsonObject {
    putJsonArray("content") {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", value)
            },
        )
    }
    put("isError", true)
}

/** 执行一次调用：[ToolError]（含参数不对的 [BadInput]）照原话交给 Claude，别的异常报「执行失败」加原因。 */
internal inline fun toolCall(block: () -> JsonObject): JsonObject =
    try {
        block()
    } catch (e: ToolError) {
        toolFailure(e.message ?: "参数不对")
    } catch (e: Exception) {
        toolFailure("执行失败：" + (e.message ?: e.javaClass.simpleName))
    }

/** 一个工具：[label] 是工具权限页上的中文名字，[description] 和 [build] 写的参数是交给 Claude 的英文定义。 */
internal fun tool(
    name: String,
    label: String,
    description: String,
    readOnly: Boolean = false,
    build: JsonObjectBuilder.() -> Unit,
): PhoneTool =
    PhoneTool(
        label,
        buildJsonObject {
            put("name", name)
            put("description", description)
            build()
            if (readOnly) putJsonObject("annotations") { put("readOnlyHint", true) }
        },
    )

internal fun JsonObjectBuilder.schema(
    required: List<String> = emptyList(),
    properties: JsonObjectBuilder.() -> Unit,
) {
    putJsonObject("inputSchema") {
        put("type", "object")
        putJsonObject("properties", properties)
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

internal fun JsonObjectBuilder.prop(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

internal fun JsonObjectBuilder.enumProp(name: String, values: List<String>, description: String) {
    putJsonObject(name) {
        put("type", "string")
        put("enum", JsonArray(values.map { JsonPrimitive(it) }))
        put("description", description)
    }
}
