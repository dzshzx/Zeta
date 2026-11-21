package com.dzshzx.zeta.phone.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 小米录音机（com.android.soundrecorder）里一条录音的备注、摘要和转写，按 id 看录音时附在后面。只读。 录音机的 Provider（authority
 * `records`）只认系统身份的调用方：records 表按文件名找到录音的 sha1（媒体库里的录音几乎都对得上）， summary（JSON：theme 主题，summaryContent
 * 或 resultContent 是摘要）、recognition_sentence（逐句转写，content 是这一句）、 transcription_records 按 sha1
 * 归属。真正的读在 [PersonalTools] 经 [RootProvider] 做。
 */
internal object SoundRecorder {
    const val RECORDS = "content://records/records"
    const val SUMMARY = "content://records/summary"
    const val SENTENCES = "content://records/recognition_sentence"
    const val TRANSCRIPTS = "content://records/transcription_records"

    /** 逐句转写最多取这么多句。 */
    const val SENTENCE_ROWS = 400

    private const val SUMMARY_CAP = 2_000

    /** 按文件名找录音机里的这条录音（同名的取最新的）。 */
    fun recordOp(fileName: String): JsonObject =
        PersonalQueries.queryOp(
            RECORDS,
            listOf("_id", "sha1", "remark", "content"),
            listOf("file_name = ?" to listOf(fileName)),
            "_id DESC",
            1,
            0,
            PersonalQueries.ITEM_MAX,
        )

    /** 摘要、逐句转写、整段转写。 */
    fun textOps(sha1: String): List<JsonObject> =
        listOf(
            PersonalQueries.queryOp(
                SUMMARY,
                listOf("summary_result"),
                listOf("sha1 = ?" to listOf(sha1)),
                "_id DESC",
                1,
                0,
                PersonalQueries.ITEM_MAX,
            ),
            PersonalQueries.queryOp(
                SENTENCES,
                listOf("speaker", "content"),
                listOf("sha1 = ?" to listOf(sha1)),
                "_id ASC",
                SENTENCE_ROWS,
                0,
                1_000,
            ),
            PersonalQueries.queryOp(
                TRANSCRIPTS,
                listOf("original_content"),
                listOf("file_sha1 = ?" to listOf(sha1)),
                "_id DESC",
                1,
                0,
                8_000,
            ),
        )

    /** 录音机存的文字有的是纯文字、有的是 JSON：JSON 的话取出其中的文字（字符串值，去掉编号、时间这类），每段一行。 */
    fun text(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty() || raw[0] != '{' && raw[0] != '[') return raw
        val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        val out = ArrayList<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach(::walk)
                is JsonArray -> e.forEach(::walk)
                is JsonPrimitive ->
                    if (e.isString && looksLikeText(e.content)) out += e.content.trim()
            }
        }
        walk(element)
        return out.joinToString("\n")
    }

    /** 摘要：主题加正文（summaryContent，旧的在 resultContent），正文本身也可能是 JSON。不是 JSON 对象时按 [text] 取。 */
    fun summary(value: String?): String {
        val raw = value?.trim().orEmpty()
        val obj =
            runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return text(raw)
        fun field(key: String): String? =
            when (val e = obj[key]) {
                    null -> null
                    is JsonPrimitive -> if (e.isString) text(e.content) else null
                    else -> text(e.toString())
                }
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        val body = field("summaryContent") ?: field("resultContent")
        return listOfNotNull(field("theme")?.let { "主题：$it" }, body).joinToString("\n")
    }

    private val NOT_TEXT =
        Regex("[0-9a-fA-F-]{16,}|[0-9 :./TZ+-]+|[a-z]{2}([_-][A-Za-z]{2})?|https?://\\S+")

    private fun looksLikeText(s: String): Boolean = s.isNotBlank() && !NOT_TEXT.matches(s.trim())

    /** 排成附在录音后面的几段：备注、摘要、转写（逐句的优先，其次整段的，最后 records 表里的）。都没有时返回一句说明。 */
    fun format(
        record: Row,
        summary: List<Row>,
        sentences: List<Row>,
        transcripts: List<Row>,
    ): String {
        val parts = ArrayList<String>()
        record.text("remark")?.trim()?.takeIf(String::isNotEmpty)?.let {
            parts += "录音机备注：" + PersonalQueries.clip(it, 500)
        }
        summary
            .firstNotNullOfOrNull { summary(it.text("summary_result")).takeIf(String::isNotEmpty) }
            ?.let { parts += "录音机摘要：\n" + PersonalQueries.clip(it, SUMMARY_CAP, full = true) }
        val bySentence =
            sentences
                .mapNotNull { r ->
                    val said =
                        text(r.text("content")).replace('\n', ' ').trim().takeIf(String::isNotEmpty)
                            ?: return@mapNotNull null
                    // speaker 有的是名字（说话人1），有的只是编号
                    r.text("speaker")?.trim()?.takeIf(String::isNotEmpty)?.let {
                        (if (it.all(Char::isDigit)) "说话人$it" else it) + "：" + said
                    } ?: said
                }
                .joinToString("\n")
        val transcript = bySentence.ifEmpty {
            transcripts.firstNotNullOfOrNull {
                text(it.text("original_content")).takeIf(String::isNotEmpty)
            } ?: text(record.text("content"))
        }
        if (transcript.isNotEmpty()) {
            val cut = if (sentences.size >= SENTENCE_ROWS) "（只取了前 $SENTENCE_ROWS 句）" else ""
            parts +=
                "录音机转写$cut：\n" +
                    PersonalQueries.clip(transcript, PersonalQueries.ITEM_MAX, full = true)
        }
        return parts.joinToString("\n").ifEmpty { "录音机里这条录音没有备注、摘要或转写。" }
    }
}
