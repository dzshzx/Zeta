package com.dzshzx.zeta.core

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.TextRange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 一个对话的输入栏里还没发出的东西：文字（连同光标）、图片附件、附带的 Linux 路径（复制好的、还在复制的、引用的）。
 * 输入栏直接拿它当状态，由 [ComposerDrafts] 按对话保管：切到别的对话再回来、界面重建时都还在。在主线程上改。
 */
@Stable
class ComposerDraft internal constructor(text: String = "", selection: TextRange = TextRange(text.length)) {
    val input = TextFieldState(text, selection)
    val attachments = mutableStateListOf<Attachment>()
    val uploads = mutableStateListOf<FileUpload>()

    /** 要存下来的那部分；没东西可存时为 null。还在复制、复制失败的文件不存。 */
    internal fun record(key: String, at: Long): DraftRecord? {
        val selection = input.selection
        return DraftRecord(
            key = key,
            text = input.text.toString(),
            selectionStart = selection.start,
            selectionEnd = selection.end,
            images = attachments.map { DraftImage(it.id, it.mediaType, it.base64.length * 3L / 4) },
            files = uploads.mapNotNull { upload -> upload.path?.let { DraftFile(it, upload.name) } },
            at = at,
        ).takeUnless { it.isEmpty }
    }
}

/**
 * 各对话没发出的输入（[ComposerDraft]），按会话号保管；随手存进单独的设置文件 [PREFS]（图片另存成文件），
 * Zeta 被杀、重开后接着用。界面停手一会儿、离开对话、发出消息时调 [save]。在主线程上用。
 * 不放进主设置文件：那里有很大的斜杠命令缓存，每次存都要整份重写，Activity 停下时系统还会在主线程上等它写完。
 *
 * - 还没开始的新对话（没有会话号、也没有内容）共用一份草稿，存在 [NEW] 下：切走再点「新对话」，没发出的字还在。
 *   这一份跟着最近显示它的那个新对话走；那个对话拿到会话号时（发出第一条消息后）改记到会话号下，下一个新对话从空的开始。
 * - 已经有内容、还没有会话号的对话（分支、刚发出第一条的）的草稿只放在内存里（[unsaved]），拿到会话号后改记过去再存盘。
 * - 删除会话时连同草稿一起删（[forget]）。
 * - 存盘有上限（[DraftRecords.fit]）：草稿数、文字总长、图片总大小，超了先丢最久没动的。
 */
class ComposerDrafts(
    context: Context,
    private val settings: Settings,
    /** 在主机（[Backends]）上跑命令的入口（[ConversationHub.shell]），查草稿里的文件还在不在用；没就绪时为 null。 */
    private val shellFor: (String) -> LinuxShell?,
) {
    private val imageDir = File(context.applicationContext.filesDir, "drafts")
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 内存里的草稿，按会话号；存盘的都在这里。 */
    private val live = HashMap<String, ComposerDraft>()

    /** 已经开始、还没有会话号的对话的草稿，按对话的 key：重开 Zeta 后找不回这个对话，不存盘。 */
    private val unsaved = HashMap<String, ComposerDraft>()

    /** 还没开始的新对话那一份，和正显示着它的那个对话。 */
    private var fresh: ComposerDraft? = null
    private var freshOwner: Conversation? = null

    /** 存盘的那份（已按上限裁过），按 key。 */
    private val records = LinkedHashMap<String, DraftRecord>()

    /** 侧栏显示的一行，按会话号（可观察）。 */
    private val previews = mutableStateMapOf<String, String>()

    /**
     * 输入栏的后台活（读图片、把文件复制进 Linux、恢复存下来的草稿）在这里跑：切到别的对话时接着做完，
     * 结果落进原来那份草稿，不会停在「复制中」。
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 图片文件的读写排成一队：先写后删的顺序不乱。 */
    private val disk = Dispatchers.IO.limitedParallelism(1)

    init {
        for (record in DraftRecords.decode(prefs.getString(KEY, null))) {
            records[record.key] = record
            if (record.key != NEW) previews[record.key] = DraftRecords.preview(record)
        }
    }

    /**
     * 对话 [conversation] 的输入栏用的那份。还没开始的新对话拿共用的那份（见类说明）；
     * 新对话换到了别的主机上时，复制进上一台主机、引用那台主机上路径的附件去掉（文字、图片照留）。
     */
    fun of(conversation: Conversation): ComposerDraft {
        fresh?.let { if (freshOwner === conversation) return it }
        if (conversation.notStarted) {
            val draft = fresh ?: (records[NEW]?.let { restore(it, conversation.backend) } ?: ComposerDraft()).also { fresh = it }
            if (freshOwner != null && freshOwner?.backend != conversation.backend) draft.uploads.clear()
            freshOwner = conversation
            return draft
        }
        val id = conversation.sessionId ?: return unsaved.getOrPut(conversation.key) { ComposerDraft() }
        return live.getOrPut(id) { records[id]?.let { restore(it, conversation.backend) } ?: ComposerDraft() }
    }

    /** 侧栏用：会话 [sessionId] 有没发出的草稿时，草稿开头的一行（可观察）。 */
    fun preview(sessionId: String): String? = previews[sessionId]

    /**
     * 把 [draft] 现在的样子存盘（空了就删掉存着的那份）。已经不归谁（会话删了）的不存。
     * 新对话那一份在它的对话开始后（发出了第一条）归那个对话，下一个新对话从空的开始。
     */
    fun save(draft: ComposerDraft) {
        if (draft === fresh) {
            val owner = freshOwner
            if (owner == null || owner.notStarted) return store(NEW, draft)
            fresh = null
            freshOwner = null
            drop(NEW)
            val id = owner.sessionId
            if (id == null) {
                // 刚发出第一条、会话号还没来：先放在内存里，存着的新对话那份已经去掉
                unsaved[owner.key] = draft
                return write()
            }
            live[id] = draft
            return store(id, draft, force = true)
        }
        val key = live.entries.firstOrNull { it.value === draft }?.key ?: return
        store(key, draft)
    }

    /** 对话的会话号变了（新对话拿到会话号、/clear 换了新会话）：草稿跟着改记。 */
    internal fun rekey(conversation: Conversation, from: String, to: String) {
        if (from == to) return
        val draft = if (fresh != null && freshOwner === conversation) {
            fresh.also {
                fresh = null
                freshOwner = null
                drop(NEW)
            }
        } else {
            live.remove(from)?.also { drop(from) } ?: unsaved.remove(from)
        } ?: return
        live[to] = draft
        store(to, draft, force = true)
    }

    /** 会话删掉了：草稿一并删。 */
    fun forget(sessionIds: Collection<String>) {
        var changed = false
        for (id in sessionIds) {
            live.remove(id)
            changed = drop(id) || changed
        }
        if (changed) write()
    }

    private fun drop(key: String): Boolean {
        previews.remove(key)
        return records.remove(key) != null
    }

    /** 存 [key] 下的那份；[force] 是改记了地方，存着的那份已经去掉，没东西可存也要写一次盘。 */
    private fun store(key: String, draft: ComposerDraft, force: Boolean = false) {
        val record = draft.record(key, System.currentTimeMillis())
        if (record == null) {
            if (!drop(key) && !force) return
        } else {
            records[key] = record
        }
        write(draft.attachments.associate { it.id to it.base64 })
    }

    /** 按上限裁好、写进 [PREFS]；图片文件补上新的、删掉不再用的。[images] 是刚存的那份草稿里的图片内容（id → base64）。 */
    private fun write(images: Map<String, String> = emptyMap()) {
        val kept = DraftRecords.fit(records.values.toList())
        records.clear()
        kept.forEach { records[it.key] = it }
        val shown = kept.filter { it.key != NEW }.associate { it.key to DraftRecords.preview(it) }
        (previews.keys - shown.keys).forEach(previews::remove)
        for ((key, line) in shown) if (previews[key] != line) previews[key] = line
        prefs.edit().apply { if (kept.isEmpty()) remove(KEY) else putString(KEY, DraftRecords.encode(kept)) }.apply()
        val wanted = kept.flatMap { it.images }.mapNotNullTo(HashSet()) { imageName(it.id) }
        val toWrite = images.filterKeys { imageName(it) in wanted }
        scope.launch(disk) {
            runCatching {
                imageDir.mkdirs()
                for ((id, base64) in toWrite) {
                    val file = File(imageDir, imageName(id) ?: continue)
                    if (!file.exists()) file.writeBytes(Base64.decode(base64, Base64.NO_WRAP))
                }
                imageDir.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
            }
        }
    }

    /** 存盘的草稿重新做成输入栏用的那份。文字和路径马上就有；图片读回来、复制进 Linux 的文件确认还在，之后再补上或去掉。 */
    private fun restore(record: DraftRecord, backend: String): ComposerDraft {
        val draft = ComposerDraft(record.text, TextRange(record.selectionStart, record.selectionEnd))
        for (file in record.files) draft.uploads += FileUpload(UUID.randomUUID().toString(), file.label).also { it.path = file.entry }
        val copied = record.files.map { it.entry }.filterNot(AttachedPaths::isReference)
        if (record.images.isEmpty() && copied.isEmpty()) return draft
        scope.launch {
            val images = withContext(disk) {
                record.images.mapNotNull { image ->
                    val bytes = imageName(image.id)?.let { runCatching { File(imageDir, it).readBytes() }.getOrNull() }
                    bytes?.let { Attachments.restore(image.id, image.mediaType, it) }
                }
            }
            draft.attachments.addAll(0, images.filter { image -> draft.attachments.none { it.id == image.id } })
            val shell = shellFor(backend)
            val missing = if (shell == null || copied.isEmpty()) emptySet() else withContext(Dispatchers.IO) {
                // 查不成（进不了环境、连不上）时当作都还在
                copied.zip(PathCheck.checkAll(shell, copied.map(AttachedPaths::path), hostKey = null)).filter { it.second == PathKind.Missing }.map { it.first }.toSet()
            }
            if (missing.isNotEmpty()) draft.uploads.removeAll { it.path in missing }
            // 读不回来的图片、已经不在的文件，存着的那份也去掉；读回来之前存过一次的，把图片补回去
            save(draft)
        }
        return draft
    }

    companion object {
        /** 还没开始的新对话那一份草稿存在这个 key 下。 */
        const val NEW = "new"

        /** 草稿存在这个设置文件的 [KEY] 下。 */
        const val PREFS = "zeta_drafts"
        const val KEY = "drafts"

        private val ImageName = Regex("[A-Za-z0-9-]{1,64}")

        /** 图片存成哪个文件；id 不像文件名的不存。 */
        private fun imageName(id: String): String? = id.takeIf { ImageName.matches(it) }
    }
}

/** 存进设置的一份草稿（编解码、裁剪见 [DraftRecords]）。 */
data class DraftRecord(
    /** 会话号；还没开始的新对话是 [ComposerDrafts.NEW]。 */
    val key: String,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val images: List<DraftImage>,
    val files: List<DraftFile>,
    /** 最后存的时间（毫秒）：超出上限时先丢最久没动的。 */
    val at: Long,
) {
    val isEmpty: Boolean get() = text.isBlank() && images.isEmpty() && files.isEmpty()
}

/** 草稿里的一张图片：内容另存成文件（应用私有目录 files/drafts/<id>），这里只记类型和大约多少字节。 */
data class DraftImage(val id: String, val mediaType: String, val bytes: Long)

/** 草稿里附带的一个 Linux 路径：[entry] 是附件说明（见 [AttachedPaths]），[label] 是附件小标签上的名字。 */
data class DraftFile(val entry: String, val label: String)

/** 草稿存盘的格式和上限，纯函数。 */
object DraftRecords {
    /** 最多存这么多份草稿。 */
    const val MAX_DRAFTS = 30

    /** 一份草稿最多存这么多字（和分享进来时直接放进输入框的上限一样），再长的只存开头。 */
    const val MAX_TEXT = IntakeParser.MAX_INLINE_TEXT

    /** 所有草稿的文字加起来最多这么多：设置文件每次启动都整个读进内存。 */
    const val MAX_TOTAL_TEXT = 60_000

    /** 所有草稿的图片加起来最多这么多字节；放不下的只在内存里留着，重开 Zeta 后就没了。 */
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

    private const val PREVIEW_CHARS = 60
    private val json = Json { ignoreUnknownKeys = true }
    private val Spaces = Regex("\\s+")

    /** 去掉空的，按最近改动排，裁到上限以内。新的先占额度。 */
    fun fit(records: List<DraftRecord>): List<DraftRecord> {
        var textLeft = MAX_TOTAL_TEXT
        var imagesLeft = MAX_IMAGE_BYTES
        val kept = ArrayList<DraftRecord>()
        for (record in records.filterNot { it.isEmpty }.sortedByDescending { it.at }) {
            if (kept.size >= MAX_DRAFTS) break
            val text = record.text.clipChars(minOf(MAX_TEXT, textLeft))
            textLeft -= text.length
            val images = ArrayList<DraftImage>()
            for (image in record.images) {
                if (image.bytes > imagesLeft) continue
                imagesLeft -= image.bytes
                images += image
            }
            val fitted = record.copy(
                text = text,
                selectionStart = record.selectionStart.coerceIn(0, text.length),
                selectionEnd = record.selectionEnd.coerceIn(0, text.length),
                images = images,
            )
            if (!fitted.isEmpty) kept += fitted
        }
        return kept
    }

    /** 侧栏上草稿那一行：文字的第一行；没有文字时说有几张图片、附了哪个文件。 */
    fun preview(record: DraftRecord): String {
        val line = record.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.replace(Spaces, " ")
        return when {
            line != null -> line.clipChars(PREVIEW_CHARS)
            record.images.isNotEmpty() -> "${record.images.size} 张图片"
            record.files.size > 1 -> "${record.files.first().label} 等 ${record.files.size} 个文件"
            else -> record.files.firstOrNull()?.label.orEmpty()
        }
    }

    fun encode(records: List<DraftRecord>): String = buildJsonObject {
        put("version", 1)
        putJsonArray("drafts") {
            for (r in records) addJsonObject {
                put("key", r.key)
                put("text", r.text)
                putJsonArray("selection") { add(r.selectionStart); add(r.selectionEnd) }
                putJsonArray("images") { for (i in r.images) addJsonObject { put("id", i.id); put("type", i.mediaType); put("bytes", i.bytes) } }
                putJsonArray("files") { for (f in r.files) addJsonObject { put("entry", f.entry); put("label", f.label) } }
                put("at", r.at)
            }
        }
    }.toString()

    /** 存储里的文字 → 草稿。坏的条目跳过，不让一条坏数据弄丢全部；同一个 key 只留第一份。 */
    fun decode(raw: String?): List<DraftRecord> {
        val root = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return emptyList()
        val array = root.arr("drafts") ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val key = o.str("key")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val text = o.str("text").orEmpty()
            val selection = o.arr("selection")?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
            val images = o.arr("images").orEmpty().mapNotNull { (it as? JsonObject)?.let { i ->
                DraftImage(i.str("id") ?: return@let null, i.str("type") ?: "image/jpeg", i.long("bytes") ?: 0)
            } }
            val files = o.arr("files").orEmpty().mapNotNull { (it as? JsonObject)?.let { f ->
                val entry = f.str("entry")?.takeIf { e -> e.startsWith("/") || AttachedPaths.isReference(e) } ?: return@let null
                DraftFile(entry, f.str("label") ?: AttachedPaths.label(entry))
            } }
            DraftRecord(
                key = key,
                text = text,
                selectionStart = (selection?.getOrNull(0) ?: text.length).coerceIn(0, text.length),
                selectionEnd = (selection?.getOrNull(1) ?: text.length).coerceIn(0, text.length),
                images = images,
                files = files,
                at = o.long("at") ?: 0,
            ).takeUnless { it.isEmpty }
        }.distinctBy { it.key }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
