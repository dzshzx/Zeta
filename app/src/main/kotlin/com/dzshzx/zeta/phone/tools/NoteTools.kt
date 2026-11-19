package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.ZoneId

/**
 * 小米便签的新建、修改（edit_note），每次写完读回核对。读便签在 search_personal（[PersonalTools]）；便签没有开放删除。
 * 写经便签的工具服务（[AppToolCall]），读回经 AgenticNoteProvider，都以 system 身份；途径和限制见 [MiuiNoteWrites]。
 * 私密便签、子文档、回收站里的不碰：先按 search_personal 的条件读一遍，读不到就不写。
 */
internal object NoteTools : PhoneToolGroup {
    private const val EDIT = "edit_note"

    override val tools: List<JsonElement> = listOf(
        tool(
            EDIT,
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

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = try {
        if (name == EDIT) toolText(edit(context, input)) else toolFailure("没有这个工具：$name")
    } catch (e: BadInput) {
        toolFailure(e.message ?: "参数不对")
    } catch (e: Exception) {
        toolFailure("执行失败：" + (e.message ?: e.javaClass.simpleName))
    }

    override fun title(name: String, input: JsonObject): String = MiuiNoteWrites.chatTitle(input)

    private fun edit(context: Context, input: JsonObject): String {
        val zone = ZoneId.systemDefault()
        val id = (input["note_id"] as? JsonPrimitive)?.longOrNull
        return when (input.str("action")) {
            "create" -> {
                val args = MiuiNoteWrites.createArgs(input)
                wake(context)
                val result = AppToolCall.bind(context, MiuiNoteWrites.SERVICE).use { call(context, MiuiNoteWrites.createOp(args)) }
                val created = MiuiNoteWrites.createdId(result)
                    ?: throw IllegalStateException("便签没交回新便签的编号，先用 search_personal 查一下，不要重复新建")
                val note = read(context, created)
                    ?: throw IllegalStateException("新建的便签 #$created 读不回来，先用 search_personal 查一下，不要重复新建")
                MiuiNoteWrites.saved("新建", note, args, zone)
            }
            "update" -> {
                val noteId = id ?: throw BadInput("update 需要 note_id")
                val existing = read(context, noteId) ?: throw BadInput(missing(noteId))
                val args = MiuiNoteWrites.updateArgs(noteId, input, existing)
                // 改和读回放在同一次辅助进程里
                val (update, readBack) = AppToolCall.bind(context, MiuiNoteWrites.SERVICE).use {
                    RootProvider.run(context, listOf(MiuiNoteWrites.updateOp(args), MiuiNoteWrites.readOp(noteId)), system = true)
                }
                MiuiNoteWrites.result(AppToolCall.reply(update))
                if (readBack.str("error") != null) throw IllegalStateException("改完读不回便签 #$noteId：" + readBack.str("error"))
                val note = RootProvider.rows(readBack).firstOrNull() ?: throw IllegalStateException("改完读不回便签 #$noteId")
                MiuiNoteWrites.saved("修改", note, args, zone)
            }
            else -> throw BadInput("action 是 create 或 update；便签没有开放删除，要删请用户在便签里自己删")
        }
    }

    private fun missing(id: Long) = "没有编号为 $id 的便签（或在回收站、私密便签里）"

    /**
     * 先以 system 身份碰一下便签的 Provider，把便签的进程拉起来。HyperOS 的「关联启动」管控（WakePathChecker）不许 Zeta
     * 绑定没在跑的便签（日志 `MIUILOG-AutoStart, Service/Provider/Broadcast Reject`，bindService 返回 false）；system 不受它管。
     * 修改前本来就要读一遍，只有新建要单独做。
     */
    private fun wake(context: Context) {
        RootProvider.one(context, MiuiNoteWrites.readOp(0), system = true)
    }

    /** 调一次工具服务（要先 [AppToolCall.bind]），交回它答的 JSON。 */
    private fun call(context: Context, op: JsonObject): JsonObject =
        MiuiNoteWrites.result(AppToolCall.reply(RootProvider.run(context, listOf(op), system = true).single()))

    /** 一条看得到的便签；没有（或在回收站、私密、子文档）返回 null。 */
    private fun read(context: Context, id: Long): Row? =
        RootProvider.rows(RootProvider.one(context, MiuiNoteWrites.readOp(id), system = true)).firstOrNull()
}
