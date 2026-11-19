package com.dzshzx.zeta.phone.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.ZoneId

/** 聊天图片缓存里的一个文件是哪一种，[rank] 越小越清楚（看图时先试）。 */
internal enum class ChatVariant(val label: String, val rank: Int) {
    Original("原图", 0),
    Large("大图", 1),
    ThumbHd("高清缩略图", 2),
    Thumb("缩略图", 3),
}

/** 缓存里的一个文件：[time] 是修改时间（毫秒）。 */
internal data class ChatFile(val path: String, val time: Long, val size: Long, val variant: ChatVariant)

/** 一张图：同一张图的几个文件（微信按 md5 归在一起；QQ 的文件名彼此对不上，一个文件算一张）。[time] 取其中最新的。 */
internal data class ChatImage(val id: Long, val time: Long, val files: List<ChatFile>)

/**
 * 微信、QQ 聊天里收到（和发出）的图片缓存，只读：search_personal 的 wechat_images、qq_images，view_media 的 kind wechat、qq。
 *
 * - 微信：`/data/user/<n>/com.tencent.mm/MicroMsg/<账号哈希>/image2/<aa>/<bb>/`（老版本在 `Android/data/com.tencent.mm` 下的同名目录，
 *   一起列）。同一张图按 md5 归在一起：`th_<md5>` 缩略图、`th_<md5>hd` 高清缩略图（都是普通 JPEG，多在 800 像素以内），
 *   `<md5>.jpg/.png` 原图（点开或自动下载过才有，多数是私有格式 wxgf，见 [Wxgf]）。`<aa>/<bb>` 是 md5 的前四位，
 *   编号取 md5 的前 13 位十六进制，回查时直接进那个目录。`.ref` 和名字带 ⌖ 的是它自己的引用记录，不列。
 * - QQ（9.x，NT 版）：`Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic/` 下 chatraw（原图）、chatimg（大图）、
 *   chatthumb（缩略图，`_hd` 是高清的），`<子目录>/Cache_<哈希>`，都是普通 JPEG、PNG、GIF、WebP。子目录是哈希的末三位；
 *   三处的文件名对不上，同一张图的缩略图和大图只能看同一时刻。编号是相对路径的 SHA-1 取 40 位、低 12 位放子目录。
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

    fun kindOf(source: PersonalSource): String? = when (source) {
        PersonalSource.WechatImages -> WECHAT
        PersonalSource.QqImages -> QQ
        else -> null
    }

    private fun sourceOf(kind: String) = if (kind == WECHAT) PersonalSource.WechatImages else PersonalSource.QqImages
    private fun appName(kind: String) = if (kind == WECHAT) "微信" else "QQ"

    /** 「微信图片」「QQ 图片」 */
    private fun imageName(kind: String) = sourceOf(kind).label

    /** 句子中间嵌进名字：英文开头、结尾的那一侧留空格（「的 QQ 图片」「QQ 的」），中文的照写。 */
    private fun inline(name: String) = (if (name.first().code < 128) " " else "") + name + (if (name.last().code < 128) " " else "")

    // ---- 列目录 ----

    /**
     * 列文件：每行「修改时间（秒，带小数） 大小 路径」，新的在前，最多 $5 行；$3、$4 是时间窗口（秒，可空），$6 接在根目录后面
     * （回查时是子目录）。根目录一个都不在时打出 [NO_CACHE]。
     */
    private fun listScript(roots: String, names: String) =
        "u=\$2 s=\$3 e=\$4 n=\$5 sub=\$6; set -- $roots; dirs=; for d in \"\$@\"; do [ -d \"\$d\" ] && dirs=\"\$dirs \$d\"; done; " +
            "if [ -z \"\$dirs\" ]; then echo $NO_CACHE; else " +
            "/system/bin/find \$dirs -name .ref -prune -o -type f \\( $names \\) -printf '%T@ %s %p\\n' 2>/dev/null | " +
            "/system/bin/awk -v s=\"\$s\" -v e=\"\$e\" '(s == \"\" || \$1 >= s + 0) && (e == \"\" || \$1 < e + 0)' | " +
            "/system/bin/sort -rn | /system/bin/head -n \"\$n\"; fi"

    private val WECHAT_SCRIPT = listScript(
        "/data/user/\$u/com.tencent.mm/MicroMsg/*/image2\$sub /data/media/\$u/Android/data/com.tencent.mm/MicroMsg/*/image2\$sub",
        "-name 'th_*' -o -name '*.jpg' -o -name '*.png' -o -name '*.gif' -o -name '*.webp'",
    )
    private val QQ_SCRIPT = run {
        val base = "/data/media/\$u/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
        listScript("$base/chatraw\$sub $base/chatimg\$sub $base/chatthumb\$sub", "-name 'Cache_*'")
    }

    private const val NO_CACHE = "ZETA_NO_CACHE"

    /** 列出缓存里的文件（新的在前）；缓存目录不在时返回 null。 */
    private fun list(kind: String, start: Long?, end: Long?, max: Int, sub: String = ""): List<ChatFile>? {
        val lines = RootFiles.lines(
            if (kind == WECHAT) WECHAT_SCRIPT else QQ_SCRIPT,
            listOf(RootFiles.user.toString(), start?.let(::seconds).orEmpty(), end?.let(::seconds).orEmpty(), max.toString(), sub),
        )
        if (lines.any { it.trim() == NO_CACHE }) return null
        return lines.mapNotNull { line -> parseLine(line)?.let { (time, size, path) -> file(kind, path, time, size) } }
    }

    private fun seconds(ms: Long): String = "%d.%03d".format(ms / 1000, ms % 1000)

    /** `1795102062.290211999 8639 /路径` → 毫秒、大小、路径。 */
    fun parseLine(line: String): Triple<Long, Long, String>? {
        val parts = line.trim().split(' ', limit = 3)
        if (parts.size != 3) return null
        val whole = parts[0].substringBefore('.').toLongOrNull() ?: return null
        val fraction = parts[0].substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: 0
        val size = parts[1].toLongOrNull() ?: return null
        return Triple(whole * 1000 + fraction, size, parts[2])
    }

    // ---- 认文件、归组、编号 ----

    private val WECHAT_NAME = Regex("(th_)?([0-9a-f]{32})(hd)?(\\.(?:jpg|jpeg|png|gif|webp))?")
    private val QQ_PATH = Regex("/chatpic/(chatraw|chatimg|chatthumb)/([0-9a-f]{3})/(Cache_-?[0-9a-f]+(_hd)?(?:\\.[a-z]+)?)$")

    /** 一个文件是哪一种；不是聊天图片（引用记录之类）时是 null。 */
    fun file(kind: String, path: String, time: Long, size: Long): ChatFile? {
        val variant = if (kind == WECHAT) {
            val m = WECHAT_NAME.matchEntire(path.substringAfterLast('/')) ?: return null
            val thumb = m.groupValues[1].isNotEmpty()
            when {
                thumb && m.groupValues[3].isNotEmpty() -> ChatVariant.ThumbHd
                thumb -> ChatVariant.Thumb
                m.groupValues[4].isNotEmpty() && m.groupValues[3].isEmpty() -> ChatVariant.Original
                else -> return null
            }
        } else {
            val m = QQ_PATH.find(path) ?: return null
            when (m.groupValues[1]) {
                "chatraw" -> ChatVariant.Original
                "chatimg" -> ChatVariant.Large
                else -> if (m.groupValues[4].isNotEmpty()) ChatVariant.ThumbHd else ChatVariant.Thumb
            }
        }
        return ChatFile(path, time, size, variant)
    }

    /** 文件归成图，新的在前。 */
    fun group(kind: String, files: List<ChatFile>): List<ChatImage> {
        val images = if (kind == WECHAT) {
            files.groupBy { it.path.substringBeforeLast('/') + "/" + wechatMd5(it.path) }.values.map { same ->
                ChatImage(wechatId(wechatMd5(same.first().path)), same.maxOf { it.time }, same.sortedBy { it.variant.rank })
            }
        } else {
            files.mapNotNull { f -> qqId(f.path)?.let { ChatImage(it, f.time, listOf(f)) } }
        }
        return images.sortedWith(compareByDescending<ChatImage> { it.time }.thenByDescending { it.id })
    }

    private fun wechatMd5(path: String): String = WECHAT_NAME.matchEntire(path.substringAfterLast('/'))?.groupValues?.get(2).orEmpty()

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
        val sub = if (kind == WECHAT) wechatPrefix(id).let { "/" + it.take(2) + "/" + it.substring(2, 4) } else "/" + qqDir(id)
        val files = list(kind, null, null, 500, sub) ?: return null
        return group(kind, files).firstOrNull { it.id == id }
    }

    // ---- 查询 ----

    fun search(request: SearchRequest, zone: ZoneId, now: Long): String {
        val kind = kindOf(request.source) ?: throw IllegalArgumentException("${request.source.key} 不是聊天图片")
        if (request.query != null) {
            throw BadInput("${request.source.key} 只能按时间查（start、end）：文件名只是哈希，也分不出是哪个聊天发的")
        }
        request.id?.let { id ->
            val image = find(kind, id) ?: return "没有编号为 $id 的${inline(imageName(kind))}（缓存可能已被清理）。"
            return formatOne(kind, image, zone)
        }
        // 微信一张图最多三个文件，多取一些再归组；QQ 一个文件一张
        val wanted = request.offset + request.limit + 1
        val files = list(kind, request.start, request.end, if (kind == WECHAT) wanted * 3 else wanted)
            ?: throw IllegalStateException("没找到${inline(appName(kind))}的图片缓存目录（没装、没登录过，或者新版本换了存放位置）")
        return format(kind, group(kind, files), request, zone, now)
    }

    fun format(kind: String, images: List<ChatImage>, request: SearchRequest, zone: ZoneId, now: Long): String {
        val page = images.drop(request.offset).take(request.limit)
        val more = images.size > request.offset + page.size
        val lines = page.map { image -> "#${image.id} ${PersonalQueries.formatTime(image.time, zone)} " + variants(image) }
        val order = "按缓存时间（收到或点开的时间）新的在前；分不出是哪个聊天；用 view_media 加 kind: $kind 看图" +
            if (kind == QQ) "；同一时刻的缩略图和大图多半是同一张" else ""
        return PersonalQueries.assemble(
            PersonalQueries.header(sourceOf(kind), page.size, more, request, zone, now, order), lines, "没有符合条件的${inline(imageName(kind))}。",
        )
    }

    private fun variants(image: ChatImage): String = image.files.joinToString(" + ") { it.variant.label + " " + PersonalQueries.size(it.size) }

    fun formatOne(kind: String, image: ChatImage, zone: ZoneId): String = buildString {
        append(imageName(kind)).append(" #").append(image.id).append(' ').append(PersonalQueries.formatTime(image.time, zone)).append('\n')
        for (f in image.files) {
            append(f.variant.label).append(' ').append(PersonalQueries.size(f.size)).append(' ')
                .append(PersonalQueries.formatTime(f.time, zone)).append(' ').append(f.path).append('\n')
        }
        append("用 view_media 加 kind: ").append(kind).append(" 看图")
    }

    // ---- 看图 ----

    fun viewTitle(kind: String?): String? = when (kind) {
        WECHAT -> "看微信图片"
        QQ -> "看 QQ 图片"
        else -> null
    }

    /** view_media 的 kind wechat、qq：从最清楚的文件试起，解不开的换下一个，说明给的是哪一个。 */
    fun view(kind: String, id: Long, edge: Int, zone: ZoneId): JsonObject {
        val image = find(kind, id) ?: throw BadInput("没有编号为 $id 的${inline(imageName(kind))}：先用 search_personal 查 ${sourceOf(kind).key}")
        val failures = mutableListOf<String>()
        for (file in image.files) {
            val decoded = try {
                decode(RootFiles.read(file.path, MAX_FILE), edge)
            } catch (e: Exception) {
                failures += file.variant.label + "：" + (e.message ?: e.javaClass.simpleName)
                continue
            }
            val text = buildString {
                append(imageName(kind)).append(" #").append(id).append(' ').append(PersonalQueries.formatTime(image.time, zone)).append(' ')
                append(file.variant.label).append(' ').append(decoded.sourceWidth).append('x').append(decoded.sourceHeight)
                if (decoded.format == "wxgf") append("（微信私有格式 wxgf，已在手机上用系统的 HEVC 解码器转成 JPEG）")
                append("\n画面 ").append(decoded.width).append('x').append(decoded.height)
                if (failures.isNotEmpty()) append("\n更清楚的没解开，给的是").append(file.variant.label).append("（").append(failures.joinToString("；")).append("）")
            }
            return buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                    add(buildJsonObject { put("type", "image"); put("data", Base64.encodeToString(decoded.jpeg, Base64.NO_WRAP)); put("mimeType", "image/jpeg") })
                }
            }
        }
        throw IllegalStateException("这张图的文件都没解开：" + failures.joinToString("；"))
    }

    /** 解出来的图：缩好的 JPEG 和它的宽高，[sourceWidth]×[sourceHeight] 是原文件的大小，[format] 是原文件的格式。 */
    class Decoded(val jpeg: ByteArray, val width: Int, val height: Int, val sourceWidth: Int, val sourceHeight: Int, val format: String)

    /** 文件头认格式：jpeg、png、gif、webp、wxgf；认不出是 null。 */
    fun sniff(bytes: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = bytes.size >= i + b.size && b.indices.all { bytes[i + it] == b[it].toByte() }
        return when {
            at(0, 0xff, 0xd8, 0xff) -> "jpeg"
            at(0, 0x89, 0x50, 0x4e, 0x47) -> "png"
            at(0, 0x47, 0x49, 0x46, 0x38) -> "gif"
            at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50) -> "webp"
            Wxgf.isWxgf(bytes) -> "wxgf"
            else -> null
        }
    }

    /** 文件内容缩到最长边不超过 [edge] 的 JPEG。wxgf 先转成 JPEG；透明的地方垫白底；动图取第一帧。 */
    private fun decode(bytes: ByteArray, edge: Int): Decoded {
        val format = sniff(bytes) ?: throw IllegalStateException("认不出的格式（文件头 " + bytes.take(4).joinToString(" ") { "%02x".format(it) } + "）")
        val plain = if (format == "wxgf") Wxgf.toJpeg(bytes) else bytes
        var source = 0 to 0
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(plain))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            source = info.size.width to info.size.height
            val (w, h) = PersonalQueries.fitWithin(info.size.width, info.size.height, edge)
            decoder.setTargetSize(w, h)
        }
        val bitmap = if (!decoded.hasAlpha()) decoded else {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(decoded, 0f, 0f, null) }
            }
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Decoded(out.toByteArray(), bitmap.width, bitmap.height, source.first, source.second, format)
    }
}
