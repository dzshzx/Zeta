package com.dzshzx.zeta.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * 聊天条目的 JSON：Go 核心推来的 conv.items 里每项的写法（helper/transcript/items.go）。[chatItemJson] 写出全部字段（没有的写成 null、
 * false、空数组），Go 一侧省掉零值；[chatItemOf] 两种都认，没写出的字段当零值。归约器的对照用例（helper/testdata/transcript 的
 * <名字>.items.json）也是 [chatItemJson] 写的。
 */

/** 条目写成 JSON，字段名和 Go 核心的一样。 */
fun chatItemJson(item: ChatItem): JsonObject =
    when (item) {
        is UserItem ->
            buildJsonObject {
                put("kind", "user")
                put("id", item.id)
                put("text", item.text)
                put("images", JsonArray(item.images.map(::imageRefJson)))
                put("files", JsonArray(item.files.map(::JsonPrimitive)))
                put("uuid", item.uuid)
            }
        is AnswerItem ->
            buildJsonObject {
                put("kind", "answer")
                put("id", item.id)
                put("text", item.text)
                put("streaming", item.streaming)
            }
        is NoticeItem ->
            buildJsonObject {
                put("kind", "notice")
                put("id", item.id)
                put("text", item.text)
                put("error", item.error)
            }
        is WorkItem ->
            buildJsonObject {
                put("kind", "work")
                put("id", item.id)
                put("steps", JsonArray(item.steps.map(::workStepJson)))
            }
    }

private fun workStepJson(step: WorkStep): JsonObject =
    when (step) {
        is ThinkingStep ->
            buildJsonObject {
                put("kind", "thinking")
                put("id", step.id)
                put("text", step.text)
                put("running", step.running)
                put("seconds", step.seconds)
            }
        is ToolStep ->
            buildJsonObject {
                put("kind", "tool")
                put("id", step.id)
                put("name", step.name)
                put("input", step.input ?: JsonNull)
                put("status", step.status.name.lowercase())
                put("result", step.result)
                put("images", JsonArray(step.images.map(::imageRefJson)))
                put("patch", step.patch?.let(::editPatchJson) ?: JsonNull)
            }
    }

/** 图片：Go 核心只出 inline（带数据）和 session（会话记录里的位置）两种；file、remote 是界面里 Markdown 引用的，写出来只为读得回。 */
private fun imageRefJson(image: ImageRef): JsonObject =
    when (image) {
        is InlineImage ->
            buildJsonObject {
                put("kind", "inline")
                put("key", image.key)
                put("data", image.base64)
            }
        is SessionImage ->
            buildJsonObject {
                put("kind", "session")
                put("file", image.file)
                put("ref", image.ref)
            }
        is FileImage ->
            buildJsonObject {
                put("kind", "file")
                put("path", image.path)
            }
        is RemoteImage ->
            buildJsonObject {
                put("kind", "remote")
                put("url", image.url)
            }
    }

private fun editPatchJson(patch: EditPatch): JsonObject = buildJsonObject {
    put("created", patch.created)
    put("hunks", JsonArray(patch.hunks.map { JsonObject(mapOf("lines" to diffLinesJson(it))) }))
}

private fun diffLinesJson(hunk: DiffHunk): JsonArray =
    JsonArray(
        hunk.lines.map { line ->
            buildJsonObject {
                put("kind", line.kind.name.lowercase())
                put("text", line.text)
                put("old", line.oldLine)
                put("new", line.newLine)
            }
        },
    )

/**
 * [chatItemJson] 的逆操作，也读 Go 核心省掉零值的写法。认不出的种类读成一条提示（[NoticeItem]），不丢掉：conv.items
 * 的增量按下标替换，少一项后面就全对不上了。
 */
fun chatItemOf(o: JsonObject): ChatItem {
    val id = o.str("id").orEmpty()
    return when (o.str("kind")) {
        "user" ->
            UserItem(
                id = id,
                text = o.str("text").orEmpty(),
                images = imagesOf(o),
                files = o.arr("files").orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                uuid = o.str("uuid")?.ifEmpty { null },
            )
        "answer" -> AnswerItem(id, o.str("text").orEmpty(), o.bool("streaming") == true)
        "work" ->
            WorkItem(
                id,
                o.arr("steps").orEmpty().mapNotNull { (it as? JsonObject)?.let(::workStepOf) },
            )
        else -> NoticeItem(id, o.str("text").orEmpty(), o.bool("error") == true)
    }
}

/** 一步；认不出的种类是 null（工作组里少一步不影响别的）。 */
private fun workStepOf(o: JsonObject): WorkStep? {
    val id = o.str("id").orEmpty()
    return when (o.str("kind")) {
        "thinking" ->
            ThinkingStep(id, o.str("text").orEmpty(), o.bool("running") == true, o.int("seconds"))
        "tool" ->
            ToolStep(
                id = id,
                name = o.str("name").orEmpty(),
                input = o.obj("input"),
                status = toolStatusOf(o.str("status")),
                result = o.str("result"),
                images = imagesOf(o),
                patch = o.obj("patch")?.let(::editPatchOf),
            )
        else -> null
    }
}

private fun toolStatusOf(status: String?): ToolStatus =
    when (status) {
        "success" -> ToolStatus.Success
        "failed" -> ToolStatus.Failed
        else -> ToolStatus.Running
    }

private fun imagesOf(o: JsonObject): List<ImageRef> =
    o.arr("images").orEmpty().mapNotNull { (it as? JsonObject)?.let(::imageRefOf) }

private fun imageRefOf(o: JsonObject): ImageRef? =
    when (o.str("kind")) {
        "inline" -> InlineImage(o.str("key").orEmpty(), o.str("data").orEmpty())
        "session" -> SessionImage(o.str("file").orEmpty(), o.str("ref").orEmpty())
        "file" -> FileImage(o.str("path").orEmpty())
        "remote" -> RemoteImage(o.str("url").orEmpty())
        else -> null
    }

private fun editPatchOf(o: JsonObject): EditPatch =
    EditPatch(
        created = o.bool("created") == true,
        hunks =
            o.arr("hunks").orEmpty().mapNotNull { hunk ->
                (hunk as? JsonObject)?.let {
                    DiffHunk(it.arr("lines").orEmpty().mapNotNull(::diffLineOf))
                }
            },
    )

/** 对比里的一行；Go 核心把不适用的行号（0）省掉，读成 null。 */
private fun diffLineOf(element: JsonElement): DiffLine? {
    val o = element as? JsonObject ?: return null
    val kind =
        when (o.str("kind")) {
            "added" -> DiffKind.Added
            "removed" -> DiffKind.Removed
            else -> DiffKind.Context
        }
    return DiffLine(kind, o.str("text").orEmpty(), o.int("old"), o.int("new"))
}
