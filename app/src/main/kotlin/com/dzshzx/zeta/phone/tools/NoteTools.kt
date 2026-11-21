package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.str
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 小米便签的新建、修改（edit_note），每次写完读回核对。读便签在 search_personal（[PersonalTools]）；便签没有开放删除。
 * 写经便签的工具服务（[AppToolCall]），读回经 AgenticNoteProvider，都以 system 身份；途径和限制见 [MiuiNoteWrites]。
 * 私密便签、子文档、回收站里的不碰：先按 search_personal 的条件读一遍，读不到就不写。
 */
internal object NoteTools : PhoneToolGroup {
    private const val EDIT = "edit_note"

    override val tools: List<PhoneTool> =
        listOf(
            tool(
                EDIT,
                "新建、修改小米便签",
                "Create or update a Xiaomi Notes note, then read it back. Update replaces the title and/or the whole body " +
                    "(plain text or Markdown): read the note with search_personal first and keep its ![](…) image lines. " +
                    "Notes cannot be deleted (Xiaomi Notes has no interface for it; the user deletes in the app). Private notes are off limits.",
            ) {
                schema(required = listOf("action")) {
                    enumProp("action", listOf("create", "update"), "What to do.")
                    prop("note_id", "integer", "Note to update (id from search_personal).")
                    prop("title", "string", "Title; required for create.")
                    prop("content", "string", "Body. On update it replaces the whole body.")
                }
            },
        )

    override fun call(call: CallContext, name: String, input: JsonObject): JsonObject = toolCall {
        if (name == EDIT) toolText(edit(call.context, input)) else toolFailure("没有这个工具：$name")
    }

    override fun title(name: String, input: JsonObject): String = MiuiNoteWrites.chatTitle(input)

    private fun edit(context: Context, input: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val id = (input["note_id"] as? JsonPrimitive)?.longOrNull
        return when (input.str("action")) {
            "create" -> {
                val args = MiuiNoteWrites.createArgs(input)
                val (reply) =
                    AppToolCall.call(context, MiuiNoteWrites.PACKAGE, MiuiNoteWrites.CREATE, args)
                val created =
                    MiuiNoteWrites.createdId(MiuiNoteWrites.result(reply))
                        ?: throw IllegalStateException("便签没交回新便签的编号，先用 search_personal 查一下，不要重复新建")
                val note =
                    MiuiNotes.read(context, created)
                        ?: throw IllegalStateException(
                            "新建的便签 #$created 读不回来，先用 search_personal 查一下，不要重复新建",
                        )
                MiuiNoteWrites.saved("新建", note, args, zone)
            }
            "update" -> {
                val noteId = id ?: throw BadInput("update 需要 note_id")
                val existing =
                    MiuiNotes.read(context, noteId) ?: throw BadInput(MiuiNotes.missing(noteId))
                val args = MiuiNoteWrites.updateArgs(noteId, input, existing)
                // 改和读回放在同一次辅助进程里
                val (reply, after) =
                    AppToolCall.call(
                        context,
                        MiuiNoteWrites.PACKAGE,
                        MiuiNoteWrites.UPDATE,
                        args,
                        then = listOf(MiuiNotes.readOp(noteId)),
                    )
                MiuiNoteWrites.result(reply)
                val readBack = after.single()
                if (readBack.str("error") != null)
                    throw IllegalStateException("改完读不回便签 #$noteId：" + readBack.str("error"))
                val note =
                    RootProvider.rows(readBack).firstOrNull()
                        ?: throw IllegalStateException("改完读不回便签 #$noteId")
                MiuiNoteWrites.saved("修改", note, args, zone)
            }
            else -> throw BadInput("action 是 create 或 update；便签没有开放删除，要删请用户在便签里自己删")
        }
    }
}
