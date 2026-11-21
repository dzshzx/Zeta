package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.str
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject

/**
 * 照片按内容搜（search_personal 的 photo_content）：图里识别出的文字、画面标签（猫、文档、海滩……）和拍摄地点。只读。
 *
 * 索引是小米澎湃 AI 引擎（com.xiaomi.aicr）给相册搜索在本机建的，Zeta 只读它，不调它的语义搜索（那条路要它的向量模型和意图识别）， 也不连任何服务器。表都在 aicr
 * 自己的库里：gallery 每张照片一行（long_id 是 `gallery_<uri_id>`，file_path、timestamp、地点各列）， gallery_ocr
 * 是识别出的文字（按段，parent_id 指向 long_id），gallery_scene 是标签编号和置信度，scene_tag 是标签编号到各语言名称的 词表（含每个标签的置信度门槛
 * threshold；父标签已经直接记在照片上，比如标了猫的也标了动物）。
 *
 * 读的是 aicr 不导出的 AiSearchDataProvider（exported=false，没有权限要求，root 调得到）：条件原样拼进 SQL，能带子查询和绑定参数；
 * 投影和排序只认白名单里的列；gallery 的 ocr、tags 两列是它按 long_id 拼好的这张图的全部文字（换行分段）和标签编号（逗号分隔）。 它不认
 * limit、offset，分页在子查询里做。
 *
 * 索引里删掉的照片还留着行，uri_id 也不一定等于 MediaStore 的 _id，所以按 file_path 到 MediaStore 换回 _id（view_media 用的就是它），
 * 查不到的不列，只报个数。纯逻辑都有单元测试，真正的读在 [search] 经 [RootProvider] 做。
 */
internal object PhotoContent {
    const val AUTHORITY = "com.xiaomi.aicr.provider.AiSearchDataProvider"
    const val GALLERY = "content://$AUTHORITY/gallery"
    const val SCENE_TAG = "content://$AUTHORITY/scene_tag"

    /** 一张图识别出的文字最多取这么多字找命中处（辅助进程的单格上限）；更长的，命中处可能在截掉的部分里。 */
    const val OCR_CHARS = 8_000

    /** 词表里关键词能对上的标签最多取这么多行（各语言各一行）。 */
    const val TAG_ROWS = 2_000

    val GALLERY_COLUMNS =
        listOf(
            "long_id",
            "timestamp",
            "file_path",
            "formatLocation",
            "detailLocation",
            "tags",
            "ocr",
        )
    val TAG_COLUMNS = listOf("tag_id", "scene_tag")
    val MEDIA_COLUMNS =
        listOf(
            "_id",
            "_data",
            "datetaken",
            "date_added",
            "media_type",
            "duration",
            "relative_path",
            "_display_name",
        )

    private const val MEDIA_VIDEO = 3L

    const val NEEDS_QUERY = "photo_content 要给 query：图里的文字、画面标签（如 猫、文档）或地点，一个词或短语"
    const val UNREADABLE = "相册的 AI 索引读不到（小米澎湃 AI 引擎 com.xiaomi.aicr 的 AiSearchDataProvider）："
    const val INDEX_EMPTY = "相册的 AI 索引是空的：小米澎湃 AI 引擎还没分析过照片（或相册的 AI 搜索没开），按内容搜不到，只能用 photos 按文件名找。"

    // ---- 拼查询 ----

    /**
     * 标签名对得上关键词：和它一样的（英文不分大小写）；词表里一个一样的都没有时，退到名字里包含它的。 [alias] 是 scene_tag 表在外层查询里的别名前缀（"t." 或
     * ""）；子查询里的 scene_tag 总是词表本身。
     */
    private fun tagMatch(alias: String) =
        "${alias}scene_tag = ? COLLATE NOCASE OR (NOT EXISTS (SELECT 1 FROM scene_tag WHERE scene_tag = ? COLLATE NOCASE) " +
            "AND ${alias}scene_tag LIKE ? ESCAPE '\\')"

    private fun tagArgs(query: String) = listOf(query, query, PersonalQueries.likePattern(query))

    /**
     * 一次查询的三项，交给 [RootProvider] 一起执行：
     * 1. 索引里命中的照片（文字、标签、地点任一命中），按拍摄时间新的在前，这一页多取一条；
     * 2. 词表里对得上关键词的标签，用来标出每张图命中的是哪个标签；
     * 3. 索引里有没有照片，没找到时用来区分「没命中」和「索引是空的」。
     */
    fun searchOps(request: SearchRequest): List<JsonObject> {
        val query = request.query ?: throw BadInput(NEEDS_QUERY)
        val like = PersonalQueries.likePattern(query)
        val match =
            "long_id IN (SELECT parent_id FROM gallery_ocr WHERE content LIKE ? ESCAPE '\\')" +
                " OR long_id IN (SELECT s.parent_id FROM gallery_scene s JOIN scene_tag t ON t.tag_id = s.tag WHERE (${tagMatch("t.")})" +
                " AND (s.confidence IS NULL OR s.confidence >= COALESCE(t.threshold, 0)))" +
                " OR formatLocation LIKE ? ESCAPE '\\' OR detailLocation LIKE ? ESCAPE '\\'"
        val window =
            listOfNotNull(
                request.start?.let { "timestamp >= CAST(? AS INTEGER)" to it.toString() },
                request.end?.let { "timestamp < CAST(? AS INTEGER)" to it.toString() },
            )
        val where = (listOf(match) + window.map { it.first }).joinToString(" AND ") { "($it)" }
        // Provider 不认 limit、offset：在子查询里分页，多取一条好知道后面还有没有（辅助进程只读 limit 条，再看有没有下一条）
        val page =
            "long_id IN (SELECT long_id FROM gallery WHERE $where ORDER BY timestamp DESC, long_id DESC " +
                "LIMIT ${request.limit + 1} OFFSET ${request.offset})"
        val args = listOf(like) + tagArgs(query) + listOf(like, like) + window.map { it.second }
        return listOf(
            PersonalQueries.queryOp(
                GALLERY,
                GALLERY_COLUMNS,
                listOf(page to args),
                "timestamp DESC, long_id DESC",
                request.limit,
                0,
                OCR_CHARS,
            ),
            PersonalQueries.queryOp(
                SCENE_TAG,
                TAG_COLUMNS,
                listOf(tagMatch("") to tagArgs(query)),
                "tag_id ASC",
                TAG_ROWS,
                0,
                100,
            ),
            PersonalQueries.queryOp(
                GALLERY,
                listOf("long_id"),
                listOf("long_id IN (SELECT long_id FROM gallery LIMIT 1)" to emptyList()),
                null,
                1,
                0,
                100,
            ),
        )
    }

    /** 按路径到 MediaStore 换回 _id 和拍摄时间；删掉的照片查不到。 */
    fun mediaOp(paths: List<String>): JsonObject =
        PersonalQueries.queryOp(
            PersonalQueries.FILES,
            MEDIA_COLUMNS,
            listOf(("_data IN (" + paths.joinToString(", ") { "?" } + ")") to paths),
            null,
            paths.size.coerceAtLeast(1),
            0,
            1_000,
        )

    // ---- 读 ----

    /** 查一次：先查索引，再按路径换回 MediaStore 的 _id。读不到时抛出异常，message 说明原因。 */
    fun search(context: Context, request: SearchRequest, zone: ZoneId, now: Long): String {
        val ops = searchOps(request)
        val results =
            try {
                RootProvider.run(context, ops)
            } catch (e: Exception) {
                throw IllegalStateException(UNREADABLE + (e.message ?: e.javaClass.simpleName))
            }
        results
            .firstOrNull { it.bool("ok") != true }
            ?.let { throw IllegalStateException(UNREADABLE + it.str("error")) }
        val (gallery, tags, probe) = results
        val rows = RootProvider.rows(gallery)
        val paths = rows.mapNotNull { it.text("file_path") }.distinct()
        val media =
            if (paths.isEmpty()) emptyList()
            else RootProvider.rows(RootProvider.one(context, mediaOp(paths)))
        return format(
            rows,
            gallery.bool("more") == true,
            RootProvider.rows(tags),
            media,
            RootProvider.rows(probe).isEmpty(),
            request,
            zone,
            now,
        )
    }

    // ---- 排版 ----

    private val WHITESPACE = Regex("\\s+")

    /** [text] 里第一处 [query]（不分大小写）连同前后文，共约 [width] 字；空白并成一个空格，截断处加省略号。没有命中返回 null。 */
    fun snippet(text: String, query: String, width: Int): String? {
        val flat = text.replace(WHITESPACE, " ").trim()
        val at = flat.indexOf(query, ignoreCase = true)
        if (at < 0) return null
        // 前文占三分之一，余下给后文
        val from = (at - (width - query.length).coerceAtLeast(0) / 3).coerceAtLeast(0)
        val to = (from + width).coerceAtLeast(at + query.length).coerceAtMost(flat.length)
        return (if (from > 0) "…" else "") +
            flat.substring(from, to) +
            (if (to < flat.length) "…" else "")
    }

    /** 这张图命中的标签：它的标签编号（tags 列，逗号分隔）里对得上关键词的那些的名字。 */
    fun matchedTags(tagsColumn: String?, matched: Map<Long, String>): List<String> =
        tagsColumn
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull()?.let(matched::get) }
            .distinct()

    /** 一张图命中了什么：文字（带上下文）、标签、地点；都认不出时只可能是文字命中处在截掉的部分里。 */
    fun hits(row: Row, query: String, matched: Map<Long, String>, width: Int): String {
        val parts = mutableListOf<String>()
        row.text("ocr")?.let { snippet(it, query, width) }?.let { parts += "文字「$it」" }
        matchedTags(row.text("tags"), matched)
            .takeIf { it.isNotEmpty() }
            ?.let { parts += "标签 " + it.joinToString("、") }
        listOf("formatLocation", "detailLocation")
            .firstNotNullOfOrNull { key ->
                row.text(key)?.takeIf { it.contains(query, ignoreCase = true) }
            }
            ?.let { parts += "地点 " + PersonalQueries.clip(it, 120) }
        return parts.joinToString("；").ifEmpty { "文字（命中处在取到的前 $OCR_CHARS 字之外）" }
    }

    /**
     * [gallery] 是索引里这一页的行（[GALLERY_COLUMNS]，按时间新的在前），[tags] 是词表里对得上的标签，[media] 是 MediaStore 里按路径
     * 查到的（[MEDIA_COLUMNS]），[indexEmpty] 是索引里一张照片都没有。[more] 是索引里这一页之后还有。
     */
    fun format(
        gallery: List<Row>,
        more: Boolean,
        tags: List<Row>,
        media: List<Row>,
        indexEmpty: Boolean,
        request: SearchRequest,
        zone: ZoneId,
        now: Long,
    ): String {
        val query = request.query.orEmpty()
        if (gallery.isEmpty()) {
            return if (indexEmpty) INDEX_EMPTY
            else
                "照片内容里没有找到「$query」：图里识别出的文字、画面标签、拍摄地点都没有命中。只搜得到系统已经分析过的照片；" +
                    "标签要用它词表里的词（如 猫、文档、海滩），文字按原样的一段连续字找。"
        }
        val matched = LinkedHashMap<Long, String>()
        for (t in tags) {
            val id = t.long("tag_id") ?: continue
            val name = t.text("scene_tag")?.takeIf { it.isNotBlank() } ?: continue
            // 各语言的名字各一行：取和关键词一样的那个，没有就取第一个
            if (id !in matched || name.equals(query, ignoreCase = true)) matched[id] = name
        }
        val byPath = media.associateBy { it.text("_data") }
        val width = PersonalQueries.itemCap(gallery.size)
        var gone = 0
        val lines = gallery.mapNotNull { row ->
            val file =
                byPath[row.text("file_path")]
                    ?: run {
                        gone++
                        return@mapNotNull null
                    }
            buildString {
                append(
                    PersonalQueries.mediaLine(
                        file,
                        zone,
                        PersonalQueries.mediaTime(file) ?: row.long("timestamp"),
                    ),
                )
                if (file.long("media_type") == MEDIA_VIDEO) {
                    append(" [视频")
                    file
                        .long("duration")
                        ?.takeIf { it > 0 }
                        ?.let { append(' ').append(PersonalQueries.duration(it / 1000)) }
                    append(']')
                }
                append(" — ").append(hits(row, query, matched, width))
            }
        }
        // 下一页从索引里这一页之后接着取：已删除没列出的也算进去
        val header =
            PersonalQueries.header(
                PersonalSource.PhotoContent,
                lines.size,
                more,
                request,
                zone,
                now,
                "文字、标签、地点里找「$query」，按拍摄时间新的在前；用 view_media 看画面，视频加 kind: video",
                next = request.offset + gallery.size,
            ) + if (gone > 0) "；另有 $gone 张在索引里但照片已删除，没有列出" else ""
        return PersonalQueries.assemble(header, lines, header)
    }
}
