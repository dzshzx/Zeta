package com.dzshzx.zeta.phone.tools

import android.util.Base64
import com.dzshzx.zeta.core.Images
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.imageMediaType
import com.dzshzx.zeta.i18n.Strings
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 聊天图片缓存里的一个文件是哪一种，[rank] 越小越清楚（看图时先试）。 */
internal enum class ChatVariant(val label: String, val rank: Int) {
    Original("原图", 0),
    Large("大图", 1),
    ThumbHd("高清缩略图", 2),
    Thumb("缩略图", 3),
}

/** 缓存里的一个文件：[time] 是修改时间（毫秒）。 */
internal data class ChatFile(
    val path: String,
    val time: Long,
    val size: Long,
    val variant: ChatVariant,
)

/** 一张图：同一张图的几个文件（微信按 md5 归在一起；QQ 的文件名彼此对不上，一个文件算一张）。[time] 取其中最新的。 */
internal data class ChatImage(val id: Long, val time: Long, val files: List<ChatFile>)

/**
 * 微信、QQ 聊天里收到（和发出）的图片缓存，只读：search_personal 的 wechat_images、qq_images，view_media 的 kind wechat、qq。
 *
 * - 微信：`/data/user/<n>/com.tencent.mm/MicroMsg/<账号哈希>/image2/<aa>/<bb>/`（老版本在
 *   `Android/data/com.tencent.mm` 下的同名目录， 一起列）。同一张图按 md5 归在一起：`th_<md5>` 缩略图、`th_<md5>hd`
 *   高清缩略图（都是普通 JPEG，多在 800 像素以内）， `<md5>.jpg/.png` 原图（点开或自动下载过才有，多数是私有格式 wxgf，见 [Wxgf]）。`<aa>/<bb>`
 *   是 md5 的前四位， 编号取 md5 的前 13 位十六进制，回查时直接进那个目录。`.ref` 和名字带 ⌖ 的是它自己的引用记录，不列。
 * - QQ（9.x，NT 版）：`Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/` 下
 *   chatraw（原图）、chatimg（大图）、 chatthumb（缩略图，`_hd` 是高清的），`<子目录>/Cache_<哈希>`，都是普通
 *   JPEG、PNG、GIF、WebP。子目录是哈希的末三位； 三处的文件名对不上，同一张图的缩略图和大图只能看同一时刻。编号是相对路径的 SHA-1 取 40 位、低 12 位放子目录。
 *
 * 分不出是哪个聊天发的：那要解密微信的 EnMicroMsg.db、QQ 的 nt_msg.db，这里不碰。时间是文件的修改时间，
 * 即收到（缩略图）或点开、下载（原图）的时间，按它新的在前。列目录和读文件都以 root 做（[RootFiles]），不改这两个应用的任何东西。
 */
internal object ChatImages {
    const val WECHAT = "wechat"
    const val QQ = "qq"
    val KINDS = listOf(WECHAT, QQ)

    /** 看图时读的单个文件最大多少（微信原图可达十几 MB）。 */
    private const val MAX_FILE = 40 * 1024 * 1024

    fun kindOf(source: PersonalSource): String? =
        when (source) {
            PersonalSource.WechatImages -> WECHAT
            PersonalSource.QqImages -> QQ
            else -> null
        }

    private fun sourceOf(kind: String) =
        if (kind == WECHAT) PersonalSource.WechatImages else PersonalSource.QqImages

    private fun appName(kind: String) = if (kind == WECHAT) "微信" else "QQ"

    /** 「微信图片」「QQ 图片」 */
    private fun imageName(kind: String) = sourceOf(kind).label

    /** 句子中间嵌进名字：英文开头、结尾的那一侧留空格（「的 QQ 图片」「QQ 的」），中文的照写。 */
    private fun inline(name: String) =
        (if (name.first().code < 128) " " else "") +
            name +
            (if (name.last().code < 128) " " else "")

    // ---- 列目录 ----

    /**
     * 列文件：每行「修改时间（秒，带小数） 大小 路径」，新的在前，最多 $5 行；$3、$4 是时间窗口（秒，可空），$6 接在根目录后面 （回查时是子目录）。根目录一个都不在时打出
     * [NO_CACHE]。
     *
     * $7 是 0 时整个扫一遍（微信十几万个文件，要好几秒）。大于 0 时先只列根目录下第 [depth] 层子目录（微信 `image2/<aa>/<bb>`、 QQ
     * `chatraw/<xxx>` 这三处）的修改时间：新建文件会更新所在目录的修改时间，所以最新的文件在最新的那些目录里。
     * 只在选上的目录里列文件：修改时间不早于窗口结束（$4）的全选上（里面可能有更早的文件），再往前取最新的 $7 个； 有窗口开始时只看修改时间不早于 $8
     * 的。没选上的目录里最新的修改时间打成一行 [NEXT]，够不够、要不要加倍再列由 [newest] 判断。 选上的目录超过 [MAX_DIRS] 个（传给 find
     * 的参数太长）时也整个扫一遍。
     */
    private fun listScript(roots: String, depth: Int, names: String) =
        "u=\$2 s=\$3 e=\$4 n=\$5 sub=\$6 k=\$7 ds=\$8; " +
            "list() { /system/bin/find \"\$@\" -type f \\( $names \\) -printf '%T@ %s %p\\n' 2>/dev/null | " +
            "/system/bin/awk -v s=\"\$s\" -v e=\"\$e\" '(s == \"\" || \$1 >= s + 0) && (e == \"\" || \$1 < e + 0)' | " +
            "/system/bin/sort -rn | /system/bin/head -n \"\$n\"; }; " +
            "set -- $roots; dirs=; for d in \"\$@\"; do [ -d \"\$d\" ] && dirs=\"\$dirs \$d\"; done; " +
            "if [ -z \"\$dirs\" ]; then echo $NO_CACHE; elif [ \"\$k\" -gt 0 ]; then " +
            "sel=\$(/system/bin/find \$dirs -mindepth $depth -maxdepth $depth -type d ! -path '*/.ref/*' -printf '%T@ %p\\n' 2>/dev/null | " +
            "/system/bin/awk -v s=\"\$ds\" 's == \"\" || \$1 >= s + 0' | /system/bin/sort -rn | " +
            "/system/bin/awk -v k=\"\$k\" -v e=\"\$e\" 'e != \"\" && \$1 >= e + 0 || ++c <= k { print substr(\$0, index(\$0, \" \") + 1); next } " +
            "{ print \"$NEXT \" \$1; exit }'); " +
            "set -- \$(echo \"\$sel\" | /system/bin/grep -v '^$NEXT '); " +
            "if [ \$# -gt $MAX_DIRS ]; then list \$dirs -name .ref -prune -o; " +
            "else echo \"\$sel\" | /system/bin/grep '^$NEXT '; [ \$# -eq 0 ] || list \"\$@\" -maxdepth 1; fi; " +
            "else list \$dirs -name .ref -prune -o; fi"

    private val WECHAT_SCRIPT =
        listScript(
            "/data/user/\$u/com.tencent.mm/MicroMsg/*/image2\$sub /data/media/\$u/Android/data/com.tencent.mm/MicroMsg/*/image2\$sub",
            2,
            "-name 'th_*' -o -name '*.jpg' -o -name '*.png' -o -name '*.gif' -o -name '*.webp'",
        )
    private val QQ_SCRIPT = run {
        val base = "/data/media/\$u/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
        listScript(
            "$base/chatraw\$sub $base/chatimg\$sub $base/chatthumb\$sub",
            1,
            "-name 'Cache_*'",
        )
    }

    private const val NO_CACHE = "ZETA_NO_CACHE"
    private const val NEXT = "ZETA_NEXT"

    /** 文件的修改时间可能比所在目录的晚一点（建好文件再写完），实测最多 1 秒。判断「没选上的目录里不会有更新的文件」、 按时间窗口挑目录时都留这么多余量。 */
    const val SLACK_MS = 60_000L

    /** 先列最新的这么多个目录（至少；要的文件多时取它的两倍）。 */
    const val FIRST_DIRS = 64

    /** 目录数加倍到超过这么多就改成整个扫一遍：目录太多时传给 find 的参数也太长。 */
    const val MAX_DIRS = 4_096

    /** 一次按目录列的结果：[lines] 是 [parseLine] 过的文件，新的在前；[next] 是没选上的目录里最新的修改时间（毫秒），都选上了是 null。 */
    class Listing(val lines: List<Triple<Long, Long, String>>, val next: Long?) {
        /** 前 [max] 条已经是全部目录里最新的 [max] 条：没选上的目录里的文件，修改时间不会晚于第 [max] 条。 */
        fun covers(max: Int): Boolean =
            next == null || (lines.size >= max && lines[max - 1].first > next + SLACK_MS)
    }

    /**
     * 最新的 [max] 个文件：[pass] 按给的目录数列一次（0 是整个扫一遍），交回 [Listing]；缓存目录不在时交回 null，这里也是 null。
     * 从最新的几个目录列起，不够（[Listing.covers]）就把目录数加倍再列，超过 [MAX_DIRS] 时整个扫一遍。
     */
    fun newest(max: Int, pass: (dirs: Int) -> Listing?): List<Triple<Long, Long, String>>? {
        var dirs = (max * 2).coerceAtLeast(FIRST_DIRS)
        while (true) {
            if (dirs > MAX_DIRS) return pass(0)?.lines
            val listing = pass(dirs) ?: return null
            if (listing.covers(max)) return listing.lines.take(max)
            dirs *= 2
        }
    }

    /** 列出缓存里的文件（新的在前，最多 [max] 个）；缓存目录不在时返回 null。[sub] 给了时只整个扫那个子目录。 */
    private fun list(
        kind: String,
        start: Long?,
        end: Long?,
        max: Int,
        sub: String = "",
    ): List<ChatFile>? {
        val lines =
            if (sub.isNotEmpty()) pass(kind, start, end, max, 0, sub)?.lines
            else newest(max) { dirs -> pass(kind, start, end, max, dirs, sub) }
        return lines?.mapNotNull { (time, size, path) -> file(kind, path, time, size) }
    }

    /** 以 root 跑一次 [listScript]。 */
    private fun pass(
        kind: String,
        start: Long?,
        end: Long?,
        max: Int,
        dirs: Int,
        sub: String,
    ): Listing? {
        val lines =
            RootFiles.lines(
                if (kind == WECHAT) WECHAT_SCRIPT else QQ_SCRIPT,
                listOf(
                    LinuxEnv.androidUser.toString(),
                    start?.let(::seconds).orEmpty(),
                    end?.let(::seconds).orEmpty(),
                    max.toString(),
                    sub,
                    dirs.toString(),
                    start?.let { seconds((it - SLACK_MS).coerceAtLeast(0)) }.orEmpty(),
                ),
            )
        if (lines.any { it.trim() == NO_CACHE }) return null
        val next =
            lines
                .firstOrNull { it.startsWith("$NEXT ") }
                ?.let { parseSeconds(it.removePrefix("$NEXT ").trim()) }
        return Listing(lines.mapNotNull(::parseLine), next)
    }

    private fun seconds(ms: Long): String = "%d.%03d".format(ms / 1000, ms % 1000)

    /** `1795102062.290211999`（秒，带小数）→ 毫秒。 */
    fun parseSeconds(text: String): Long? {
        val whole = text.substringBefore('.').toLongOrNull() ?: return null
        val fraction = text.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: 0
        return whole * 1000 + fraction
    }

    /** `1795102062.290211999 8639 /路径` → 毫秒、大小、路径。 */
    fun parseLine(line: String): Triple<Long, Long, String>? {
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size != 3) return null
        val time = parseSeconds(parts[0]) ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        return Triple(time, size, parts[2])
    }

    // ---- 认文件、归组、编号 ----

    private val WECHAT_NAME = Regex("(th_)?([0-9a-f]{32})(hd)?(\\.(?:jpg|jpeg|png|gif|webp))?")
    private val QQ_PATH =
        Regex(
            "/chatpic/(chatraw|chatimg|chatthumb)/([0-9a-f]{3})/(Cache_-?[0-9a-f]+(_hd)?(?:\\.[a-z]+)?)$",
        )

    /** 一个文件是哪一种；不是聊天图片（引用记录之类）时是 null。 */
    fun file(kind: String, path: String, time: Long, size: Long): ChatFile? {
        val variant =
            if (kind == WECHAT) {
                val m = WECHAT_NAME.matchEntire(path.substringAfterLast('/')) ?: return null
                val thumb = m.groupValues[1].isNotEmpty()
                when {
                    thumb && m.groupValues[3].isNotEmpty() -> ChatVariant.ThumbHd
                    thumb -> ChatVariant.Thumb
                    m.groupValues[4].isNotEmpty() && m.groupValues[3].isEmpty() ->
                        ChatVariant.Original
                    else -> return null
                }
            } else {
                val m = QQ_PATH.find(path) ?: return null
                when (m.groupValues[1]) {
                    "chatraw" -> ChatVariant.Original
                    "chatimg" -> ChatVariant.Large
                    else ->
                        if (m.groupValues[4].isNotEmpty()) ChatVariant.ThumbHd
                        else ChatVariant.Thumb
                }
            }
        return ChatFile(path, time, size, variant)
    }

    /** 文件归成图，新的在前。 */
    fun group(kind: String, files: List<ChatFile>): List<ChatImage> {
        val images =
            if (kind == WECHAT) {
                files
                    .groupBy { it.path.substringBeforeLast('/') + "/" + wechatMd5(it.path) }
                    .values
                    .map { same ->
                        ChatImage(
                            wechatId(wechatMd5(same.first().path)),
                            same.maxOf { it.time },
                            same.sortedBy { it.variant.rank },
                        )
                    }
            } else {
                files.mapNotNull { f -> qqId(f.path)?.let { ChatImage(it, f.time, listOf(f)) } }
            }
        return images.sortedWith(
            compareByDescending<ChatImage> { it.time }.thenByDescending { it.id },
        )
    }

    private fun wechatMd5(path: String): String =
        WECHAT_NAME.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(2).orEmpty()

    /** 微信的编号：md5 的前 13 位十六进制（52 位，JSON 里的整数不丢精度）。 */
    fun wechatId(md5: String): Long = md5.take(13).toLong(16)

    /** 编号还原成 md5 的前 13 位，前四位就是它在 image2 下的两级子目录。 */
    fun wechatPrefix(id: Long): String = id.toString(16).padStart(13, '0')

    /** QQ 的编号：chatpic 之后的相对路径做 SHA-1，取 40 位，左移 12 位，低 12 位放子目录（哈希的末三位十六进制）。 */
    fun qqId(path: String): Long? {
        val m = QQ_PATH.find(path) ?: return null
        val relative = m.groupValues[1] + "/" + m.groupValues[2] + "/" + m.groupValues[3]
        val digest = MessageDigest.getInstance("SHA-1").digest(relative.toByteArray())
        var hash = 0L
        for (i in 0 until 5) hash = (hash shl 8) or (digest[i].toLong() and 0xff)
        return (hash shl 12) or m.groupValues[2].toLong(16)
    }

    fun qqDir(id: Long): String = "%03x".format(id and 0xfff)

    /** 按编号找回一张图：只列它所在的那个子目录。 */
    private fun find(kind: String, id: Long): ChatImage? {
        if (id <= 0 || id >= (1L shl 52)) return null
        val sub =
            if (kind == WECHAT) wechatPrefix(id).let { "/" + it.take(2) + "/" + it.substring(2, 4) }
            else "/" + qqDir(id)
        val files = list(kind, null, null, 500, sub) ?: return null
        return group(kind, files).firstOrNull { it.id == id }
    }

    // ---- 查询 ----

    fun search(request: SearchRequest, zone: ZoneId, now: Long): String {
        val kind =
            kindOf(request.source) ?: throw IllegalArgumentException("${request.source.key} 不是聊天图片")
        if (request.query != null) {
            throw BadInput("${request.source.key} 只能按时间查（start、end）：文件名只是哈希，也分不出是哪个聊天发的")
        }
        request.id?.let { id ->
            val image = find(kind, id) ?: return "没有编号为 $id 的${inline(imageName(kind))}（缓存可能已被清理）。"
            return formatOne(kind, image, zone)
        }
        // 微信一张图最多三个文件，多取一些再归组；QQ 一个文件一张
        val wanted = request.offset + request.limit + 1
        val files =
            list(kind, request.start, request.end, if (kind == WECHAT) wanted * 3 else wanted)
                ?: throw IllegalStateException(
                    "没找到${inline(appName(kind))}的图片缓存目录（没装、没登录过，或者新版本换了存放位置）",
                )
        return format(kind, group(kind, files), request, zone, now)
    }

    fun format(
        kind: String,
        images: List<ChatImage>,
        request: SearchRequest,
        zone: ZoneId,
        now: Long,
    ): String {
        val page = images.drop(request.offset).take(request.limit)
        val more = images.size > request.offset + page.size
        val lines = page.map { image ->
            "#${image.id} ${PersonalQueries.formatTime(image.time, zone)} " + variants(image)
        }
        val order =
            "按缓存时间（收到或点开的时间）新的在前；分不出是哪个聊天；用 view_media 加 kind: $kind 看图" +
                if (kind == QQ) "；同一时刻的缩略图和大图多半是同一张" else ""
        return PersonalQueries.assemble(
            PersonalQueries.header(sourceOf(kind), page.size, more, request, zone, now, order),
            lines,
            "没有符合条件的${inline(imageName(kind))}。",
        )
    }

    private fun variants(image: ChatImage): String =
        image.files.joinToString(" + ") { it.variant.label + " " + PersonalQueries.size(it.size) }

    fun formatOne(kind: String, image: ChatImage, zone: ZoneId): String = buildString {
        append(imageName(kind))
            .append(" #")
            .append(image.id)
            .append(' ')
            .append(PersonalQueries.formatTime(image.time, zone))
            .append('\n')
        for (f in image.files) {
            append(f.variant.label)
                .append(' ')
                .append(PersonalQueries.size(f.size))
                .append(' ')
                .append(PersonalQueries.formatTime(f.time, zone))
                .append(' ')
                .append(f.path)
                .append('\n')
        }
        append("用 view_media 加 kind: ").append(kind).append(" 看图")
    }

    // ---- 看图 ----

    fun viewTitle(kind: String?): String? =
        when (kind) {
            WECHAT -> Strings.phoneTitleViewWechatImage()
            QQ -> Strings.phoneTitleViewQqImage()
            else -> null
        }

    /** view_media 的 kind wechat、qq：从最清楚的文件试起，解不开的换下一个，说明给的是哪一个。 */
    fun view(kind: String, id: Long, edge: Int, zone: ZoneId): JsonObject {
        val image =
            find(kind, id)
                ?: throw BadInput(
                    "没有编号为 $id 的${inline(imageName(kind))}：先用 search_personal 查 ${sourceOf(kind).key}",
                )
        val failures = mutableListOf<String>()
        for (file in image.files) {
            val decoded =
                try {
                    decode(RootFiles.read(file.path, MAX_FILE), edge)
                } catch (e: Exception) {
                    failures += file.variant.label + "：" + (e.message ?: e.javaClass.simpleName)
                    continue
                }
            val text = buildString {
                append(imageName(kind))
                    .append(" #")
                    .append(id)
                    .append(' ')
                    .append(PersonalQueries.formatTime(image.time, zone))
                    .append(' ')
                append(file.variant.label)
                    .append(' ')
                    .append(decoded.sourceWidth)
                    .append('x')
                    .append(decoded.sourceHeight)
                if (decoded.format == "wxgf") append("（微信私有格式 wxgf，已在手机上用系统的 HEVC 解码器转成 JPEG）")
                append("\n画面 ").append(decoded.width).append('x').append(decoded.height)
                if (failures.isNotEmpty())
                    append("\n更清楚的没解开，给的是")
                        .append(file.variant.label)
                        .append("（")
                        .append(failures.joinToString("；"))
                        .append("）")
            }
            return buildJsonObject {
                putJsonArray("content") {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "image")
                            put("data", Base64.encodeToString(decoded.jpeg, Base64.NO_WRAP))
                            put("mimeType", "image/jpeg")
                        },
                    )
                }
            }
        }
        throw IllegalStateException("这张图的文件都没解开：" + failures.joinToString("；"))
    }

    /** 解出来的图：缩好的 JPEG 和它的宽高，[sourceWidth]×[sourceHeight] 是原文件的大小，[format] 是原文件的格式。 */
    class Decoded(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val format: String,
    )

    /** 文件头认格式：jpeg、png、gif、webp、wxgf；认不出是 null。 */
    fun sniff(bytes: ByteArray): String? =
        imageMediaType(bytes)?.removePrefix("image/") ?: if (Wxgf.isWxgf(bytes)) "wxgf" else null

    /** 文件内容缩到最长边不超过 [edge] 的 JPEG。wxgf 先转成 JPEG；透明的地方垫白底；动图取第一帧。 */
    private fun decode(bytes: ByteArray, edge: Int): Decoded {
        val format =
            sniff(bytes)
                ?: throw IllegalStateException(
                    "认不出的格式（文件头 " + bytes.take(4).joinToString(" ") { "%02x".format(it) } + "）",
                )
        val decoded = Images.decodeWithin(if (format == "wxgf") Wxgf.toJpeg(bytes) else bytes, edge)
        val bitmap = decoded.bitmap
        return Decoded(
            Images.jpeg(bitmap, 80),
            bitmap.width,
            bitmap.height,
            decoded.sourceWidth,
            decoded.sourceHeight,
            format,
        )
    }
}
