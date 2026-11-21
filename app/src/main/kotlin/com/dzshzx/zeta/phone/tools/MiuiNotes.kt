package com.dzshzx.zeta.phone.tools

import android.content.Context
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject

/**
 * 小米便签（com.miui.notes）的读：拼查询、整理正文、排版是纯逻辑；列表在 [PersonalTools]、按编号读一条在 [read]，都经 [RootProvider]。
 * 新建、修改（edit_note）走另一条路，见 [MiuiNoteWrites]。
 *
 * 读的是便签开给系统智能体的 AgenticNoteProvider（authority `com.miui.notes.AgenticNoteProvider`）：`note` 就是便签应用
 * 自己的 documents 表，能带条件和排序。它只认平台签名的调用方，所以和录音机一样以 system 身份读。旧的 `notes` Provider 是迁移前的库，对 root 和
 * system 都不给结果，不读。只看便签列表里显示的：不在回收站、不是私密便签、不是子文档（[VISIBLE]， 和它不带条件时的默认一致）。
 */
internal object MiuiNotes {
    const val NOTES = "content://com.miui.notes.AgenticNoteProvider/note"

    const val VISIBLE = "parentId IS NULL AND deletedAt IS NULL AND isPrivate = 0"

    /** 它只接受「列名 + 可选的 ASC/DESC」组成的排序，别的写法会被它换成默认的。 */
    private const val SORT = "updatedAt DESC, id DESC"

    val LIST_COLUMNS =
        listOf("id", "title", "content", "updatedAt", "alertDate", "isPinned", "transcriptText")
    val NOTE_COLUMNS =
        listOf(
            "id",
            "title",
            "content",
            "createdAt",
            "updatedAt",
            "alertDate",
            "isPinned",
            "transcriptText",
        )

    /** 列表（关键词在标题、正文或语音转写里，按修改时间新的在前），或按 [SearchRequest.id] 取一条的全文。 */
    fun searchOp(request: SearchRequest): JsonObject {
        val like = request.query?.let(PersonalQueries::likePattern)
        val clauses =
            listOfNotNull(
                VISIBLE to emptyList(),
                request.id?.let { "id = ?" to listOf(it.toString()) },
                like?.let {
                    "title LIKE ? ESCAPE '\\' OR content LIKE ? ESCAPE '\\' OR transcriptText LIKE ? ESCAPE '\\'" to
                        listOf(it, it, it)
                },
                request.start?.let { "updatedAt >= CAST(? AS INTEGER)" to listOf(it.toString()) },
                request.end?.let { "updatedAt < CAST(? AS INTEGER)" to listOf(it.toString()) },
            )
        return if (request.id != null) {
            PersonalQueries.queryOp(
                NOTES,
                NOTE_COLUMNS,
                clauses,
                SORT,
                1,
                0,
                PersonalQueries.ITEM_MAX * 2,
            )
        } else {
            PersonalQueries.queryOp(
                NOTES,
                LIST_COLUMNS,
                clauses,
                SORT,
                request.limit,
                request.offset,
                PersonalQueries.itemCap(request.limit),
            )
        }
    }

    /** 按编号读一条看得到的便签（和 search_personal 按 id 读的一样）。 */
    fun readOp(id: Long): JsonObject =
        searchOp(SearchRequest(PersonalSource.Notes, null, null, null, id, 1, 0))

    /** 一条看得到的便签（[NOTE_COLUMNS]）；没有（或在回收站、私密、子文档）返回 null。以 system 身份读。 */
    fun read(context: Context, id: Long): Row? =
        RootProvider.rows(RootProvider.one(context, readOp(id), system = true)).firstOrNull()

    /** 按编号读不到时的话。 */
    fun missing(id: Long): String = "没有编号为 $id 的便签（或在回收站、私密便签里）"

    /** 旧版便签的富文本标签（迁移过来的便签可能还是这种写法）；没有它们时正文就是纯文字。 */
    private val RICH =
        Regex(
            "<(?:text|bullet|size|mid-size|h3-size|quote|order|new-format|splitline|b|input|hr)[ >/]|<(?:img|sound) fileid=|☺",
        )
    private val CHECKBOX = Regex("<input\\b[^>]*type=\"checkbox\"[^>]*>", RegexOption.IGNORE_CASE)
    private val CHECKED = Regex("checked=\"(true|checked)\"", RegexOption.IGNORE_CASE)
    private val IMAGE = Regex("☺\\s*\\S+|<img\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val SOUND = Regex("<sound\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val RULE = Regex("<hr\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>")
    private val BLANK_LINES = Regex("\n{3,}")

    /** 正文是旧版富文本：[plainText] 读出来的已经和原文不一样（勾选框、图片、录音、格式变了样）。 */
    fun rich(content: String?): Boolean = RICH.containsMatchIn(content.orEmpty())

    /**
     * 正文变成纯文字。旧版富文本：勾选框写成 [x] / [ ]，图片、录音写成 [图片] / [录音]，分隔线写成 ---，
     * 其余标签（粗体、字号、颜色、缩进等）去掉，常见的转义还原。纯文字的只理一下换行。
     */
    fun plainText(content: String?): String {
        val text = content.orEmpty().replace("\r\n", "\n")
        val plain =
            if (!RICH.containsMatchIn(text)) {
                text
            } else {
                text
                    .replace(CHECKBOX) { if (CHECKED.containsMatchIn(it.value)) "[x] " else "[ ] " }
                    .replace(IMAGE, "[图片]")
                    .replace(SOUND, "[录音]")
                    .replace(RULE, "---")
                    .replace(TAG, "")
                    .let(XmlText::unescape)
            }
        return plain.replace(BLANK_LINES, "\n\n").trim()
    }

    private fun Row.pinned() = long("isPinned") == 1L

    private fun Row.alert() = long("alertDate")?.takeIf { it > 0 }

    /** 列表：一条一行，标题加正文开头（没有正文时用语音转写）压成一行。 */
    fun formatList(
        rows: List<Row>,
        more: Boolean,
        request: SearchRequest,
        zone: ZoneId,
        now: Long,
    ): String {
        val cap = PersonalQueries.itemCap(rows.size)
        val lines = rows.map { r ->
            buildString {
                append('#')
                    .append(r.long("id"))
                    .append(' ')
                    .append(
                        r.long("updatedAt")?.let { PersonalQueries.formatTime(it, zone) } ?: "?",
                    )
                if (r.pinned()) append(" [置顶]")
                r.alert()?.let {
                    append(" [提醒 ").append(PersonalQueries.formatTime(it, zone)).append(']')
                }
                val title = r.text("title")?.trim().orEmpty()
                val body =
                    plainText(r.text("content")).ifEmpty {
                        r.text("transcriptText")?.trim().orEmpty()
                    }
                val text = listOf(title, body).filter(String::isNotEmpty).joinToString(" — ")
                append(": ").append(PersonalQueries.clip(text, cap).ifEmpty { "(空)" })
            }
        }
        return PersonalQueries.assemble(
            PersonalQueries.header(
                PersonalSource.Notes,
                rows.size,
                more,
                request,
                zone,
                now,
                "按修改时间新的在前；用 id 读全文",
            ),
            lines,
            "没有符合条件的便签。",
        )
    }

    /** 一条便签的全文：[note] 是 [NOTE_COLUMNS] 那一行。语音便签附上转写。 */
    fun formatNote(note: Row, zone: ZoneId): String = buildString {
        append("便签 #")
            .append(note.long("id"))
            .append(if (note.pinned()) "（置顶）" else "")
            .append('\n')
        note.text("title")?.trim()?.takeIf(String::isNotEmpty)?.let {
            append("标题：").append(it).append('\n')
        }
        note.long("createdAt")?.let {
            append("新建：").append(PersonalQueries.formatTime(it, zone)).append('\n')
        }
        note.long("updatedAt")?.let {
            append("修改：").append(PersonalQueries.formatTime(it, zone)).append('\n')
        }
        note.alert()?.let {
            append("提醒：").append(PersonalQueries.formatTime(it, zone)).append('\n')
        }
        val body = plainText(note.text("content"))
        val transcript = note.text("transcriptText")?.trim().orEmpty()
        append("正文：\n")
            .append(
                PersonalQueries.clip(body, PersonalQueries.ITEM_MAX, full = true).ifEmpty { "(空)" },
            )
        if (transcript.isNotEmpty())
            append("\n转写：\n")
                .append(PersonalQueries.clip(transcript, PersonalQueries.ITEM_MAX, full = true))
    }
}
