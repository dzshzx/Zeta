package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.long
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * edit_note 的纯逻辑：校验参数，拼小米便签工具服务的调用，解析回答，核对读回来的便签。真正的调用在 [NoteTools]。
 *
 * 写的途径是便签开给系统智能体的工具服务 NoteToolService（经 [AppToolCall]）：`note_create` 新建、`note_update` 改标题和整篇正文，
 * 走便签自己的存储层，和在便签里编辑一样。
 *
 * 不能删除（便签 3.3.2.5 反编译核对过）：读用的 AgenticNoteProvider 的 update、delete 直接返回 0；工具服务的 26 个工具里没有删除；
 * App Functions 只有 createNote、findNotes、getNotes、showNote；系统智能体动作（AiActionProvider）只有搜索、新建、分享、待办；
 * 旧的 `notes` Provider 只管迁移前的库。工具服务的 `rollback_operation` 撤销 `note_create` 时会物理删除那条便签，
 * 可它要的快照由一层包装记下，包装认运行时的 @Tool 注解，这一版的工具清单写在 app_tools.json 里、方法上没有这个注解，
 * 快照一条都不会记（真机上快照表是空的），所以这条路也不通。剩下的只有点便签的界面或改它的私有数据库，都不做。
 */
internal object MiuiNoteWrites {
    const val SERVICE = "com.miui.notes/com.miui.notes.agent.tool.provider.NoteToolService"

    const val MAX_TITLE = 200

    /** 便签自己的上限是 5 万字；这里取读回来能整篇核对的长度（[ProviderHelper] 每格最多交回 8000 字）。 */
    const val MAX_CONTENT = 8_000

    private val LITERAL_NEWLINE = Regex("\\\\r\\\\n|\\\\n")

    private fun text(input: JsonObject, key: String, max: Int): String? = input.str(key)?.replace("\r\n", "\n")?.trim()?.also {
        if (it.length > max) throw BadInput("$key 最多 $max 字")
    }

    /** 新建：标题必填，正文可空。 */
    fun createArgs(input: JsonObject): JsonObject {
        val title = text(input, "title", MAX_TITLE)?.takeIf { it.isNotEmpty() } ?: throw BadInput("create 需要 title")
        return buildJsonObject {
            put("title", title)
            put("content", text(input, "content", MAX_CONTENT).orEmpty())
        }
    }

    /**
     * 修改：给了的才改，content 整篇替换正文。[note] 是现有的那一行（[MiuiNotes.NOTE_COLUMNS]，只会是列表里看得到的便签）。
     * 整篇替换前要确认 Claude 读到的就是全文：旧版富文本（勾选框、图片、录音、格式）在读出来的文字里已经变了样，
     * 超过 [PersonalQueries.ITEM_MAX] 字的正文读出来是截断的，这两种只许改标题。
     */
    fun updateArgs(id: Long, input: JsonObject, note: Row): JsonObject {
        val title = text(input, "title", MAX_TITLE)
        val content = text(input, "content", MAX_CONTENT)
        if (title == null && content == null) throw BadInput("update 要给 title 或 content")
        if (title != null && title.isEmpty()) throw BadInput("title 不能为空")
        if (content != null) {
            val raw = note.text("content").orEmpty()
            if (MiuiNotes.rich(raw)) {
                throw BadInput("便签 #$id 是旧版富文本（勾选框、图片、录音或格式），整篇替换会把这些丢掉，只能改标题")
            }
            if (MiuiNotes.plainText(raw).length > PersonalQueries.ITEM_MAX) {
                throw BadInput("便签 #$id 的正文超过 ${PersonalQueries.ITEM_MAX} 字，读出来是截断的，整篇替换会丢内容，只能改标题")
            }
        }
        return buildJsonObject {
            put("noteId", id)
            title?.let { put("title", it) }
            content?.let { put("content", it) }
        }
    }

    // ---- 操作（交给 [ProviderHelper]，以 system 身份） ----

    fun createOp(args: JsonObject): JsonObject = AppToolCall.op(SERVICE, "note_create", args)

    fun updateOp(args: JsonObject): JsonObject = AppToolCall.op(SERVICE, "note_update", args)

    /** 读一条看得到的便签（和 search_personal 按 id 读的一样）。 */
    fun readOp(id: Long): JsonObject = MiuiNotes.searchOp(SearchRequest(PersonalSource.Notes, null, null, null, id, 1, 0))

    // ---- 回答 ----

    /** 工具服务答的 JSON；工具报错或答 success: false 时抛出它的原话。 */
    fun result(reply: AppToolCall.Reply): JsonObject {
        if (reply.isError) throw IllegalStateException("便签报错：" + reply.text.ifBlank { "（没说原因）" }.take(300))
        val json = runCatching { Json.parseToJsonElement(reply.text).jsonObject }.getOrNull()
            ?: throw IllegalStateException("便签的回答读不懂：" + reply.text.take(200))
        if (json.bool("success") == false) throw IllegalStateException("便签没做成：" + (json.str("message") ?: json.toString()).take(300))
        return json
    }

    /** note_create 交回的新便签编号。 */
    fun createdId(result: JsonObject): Long? = result.long("noteId")?.takeIf { it > 0 }

    // ---- 核对 ----

    /** 便签存正文前做的处理：正文里有反斜杠时，把字面的 \n、\r\n 换成换行。 */
    fun stored(content: String): String = if ('\\' in content) content.replace(LITERAL_NEWLINE, "\n") else content

    private fun normalize(text: String?): String = text.orEmpty().replace("\r\n", "\n").trim()

    /** 读回来的 [note] 和写进去的 [args] 哪里对不上（「标题」「正文」）；都对得上返回 null。 */
    fun mismatch(note: Row, args: JsonObject): String? = listOfNotNull(
        args.str("title")?.takeIf { normalize(note.text("title")) != normalize(it) }?.let { "标题" },
        args.str("content")?.takeIf { normalize(note.text("content")) != normalize(stored(it)) }?.let { "正文" },
    ).takeIf { it.isNotEmpty() }?.joinToString("和")

    /** 新建或修改后的回复：核对结果加读回来的全文。 */
    fun saved(verb: String, note: Row, args: JsonObject, zone: ZoneId): String {
        val head = when (val wrong = mismatch(note, args)) {
            null -> "已$verb，读回核对一致。"
            else -> "已$verb，但读回的${wrong}和写入的不一样（未确认）："
        }
        return head + "\n" + MiuiNotes.formatNote(note, zone)
    }

    /** 聊天里的一句话。 */
    fun chatTitle(input: JsonObject): String = when (input.str("action")) {
        "create" -> input.str("title")?.trim()?.takeIf { it.isNotEmpty() }?.let { "新建便签「" + (if (it.length > 12) it.take(12) + "…" else it) + "」" } ?: "新建便签"
        "update" -> "修改便签"
        else -> "改便签"
    }
}
