package com.dzshzx.zeta.core.claude

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** ZIP 不能装：[message] 是给人看的中文说明。 */
class SkillArchiveException(message: String) : Exception(message)

/** 要装进 Skill 文件夹的一项：[path] 相对 Skill 的根，目录不带末尾的 /。 */
class SkillMember internal constructor(
    val path: String,
    val directory: Boolean,
    val executable: Boolean,
    internal val entry: ZipEntryInfo?,
) {
    val size: Long get() = entry?.size ?: 0
}

/** 检查过的 Skill 包：名字、说明，和要解出来的文件。 */
class SkillPackage(val name: String, val description: String, val members: List<SkillMember>, internal val archive: SkillArchive)

/** 中央目录里的一项。 */
class ZipEntryInfo internal constructor(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val unixMode: Int,
    val localOffset: Long,
)

/**
 * 应用这边读 Skill 的 ZIP 包：自己解析中央目录（要看每项的 Unix 权限位，认出符号链接），只支持不压缩和 deflate。
 * 查过路径（不许绝对路径、`..`、反斜杠）、符号链接、加密、ZIP64、项数和解压后的总大小，再挑出 SKILL.md 所在那一层的文件。
 * 解压时按声明的大小逐项核对实际字节数和 CRC，防压缩炸弹。
 */
class SkillArchive private constructor(private val data: ByteArray, val entries: List<ZipEntryInfo>) {
    companion object {
        const val MAX_ENTRIES = 4000
        const val MAX_TOTAL = 128L * 1024 * 1024
        const val MAX_SKILL_MD = 1024 * 1024
        private val NamePattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        private const val S_IFMT = 0xF000
        private const val S_IFLNK = 0xA000

        fun open(data: ByteArray): SkillArchive = SkillArchive(data, readCentralDirectory(data))

        /** 小写字母、数字和连字符，最长 64 个字符，不许叫 synced（那是账号同步的目录）。 */
        fun isValidName(name: String): Boolean = NamePattern.matches(name) && name != "synced"

        /** 把随便一段文字变成 Skill 的名字：转小写，别的字符换成 -，去掉首尾的 -，最长 64。 */
        fun toSkillName(text: String): String =
            text.trim().lowercase().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(64).trim('-')

        private fun u16(d: ByteArray, at: Int): Int = (d[at].toInt() and 0xFF) or ((d[at + 1].toInt() and 0xFF) shl 8)

        private fun u32(d: ByteArray, at: Int): Long = u16(d, at).toLong() or (u16(d, at + 2).toLong() shl 16)

        private fun readCentralDirectory(d: ByteArray): List<ZipEntryInfo> {
            // 中央目录结束记录在最后 22 + 注释长度字节里，从后往前找签名
            var eocd = -1
            var i = d.size - 22
            val stop = maxOf(0, d.size - 22 - 65_535)
            while (i >= stop) {
                if (u32(d, i) == 0x06054b50L) {
                    eocd = i
                    break
                }
                i--
            }
            if (eocd < 0) throw SkillArchiveException("这不是一个有效的 ZIP 文件")
            val count = u16(d, eocd + 10)
            val cdSize = u32(d, eocd + 12)
            val cdOffset = u32(d, eocd + 16)
            if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL) throw SkillArchiveException("不支持 ZIP64 格式的包")
            if (count > MAX_ENTRIES) throw SkillArchiveException("ZIP 里的文件太多（超过 $MAX_ENTRIES 个）")
            if (cdOffset + cdSize > eocd) throw SkillArchiveException("ZIP 文件已损坏")
            val result = ArrayList<ZipEntryInfo>(count)
            var at = cdOffset.toInt()
            repeat(count) {
                if (at + 46 > eocd || u32(d, at) != 0x02014b50L) throw SkillArchiveException("ZIP 文件已损坏")
                val madeBy = u16(d, at + 4) shr 8
                val flags = u16(d, at + 8)
                val method = u16(d, at + 10)
                val crc = u32(d, at + 16)
                val compressed = u32(d, at + 20)
                val size = u32(d, at + 24)
                val nameLength = u16(d, at + 28)
                val extraLength = u16(d, at + 30)
                val commentLength = u16(d, at + 32)
                val external = u32(d, at + 38)
                val localOffset = u32(d, at + 42)
                if (at + 46 + nameLength > eocd) throw SkillArchiveException("ZIP 文件已损坏")
                val name = String(d, at + 46, nameLength, Charsets.UTF_8)
                // 只有 Unix 上做的包，外部属性的高 16 位才是权限位
                val mode = if (madeBy == 3) (external shr 16).toInt() and 0xFFFF else 0
                result += ZipEntryInfo(name, method, flags, crc, compressed, size, mode, localOffset)
                at += 46 + nameLength + extraLength + commentLength
            }
            return result
        }
    }

    /** 取一项的全部内容（只用来读 SKILL.md 这种小文件）。 */
    fun read(entry: ZipEntryInfo, limit: Int): ByteArray {
        if (entry.size > limit) throw SkillArchiveException("${entry.name} 太大")
        val out = java.io.ByteArrayOutputStream(entry.size.toInt())
        extract(entry) { b, off, len -> out.write(b, off, len) }
        return out.toByteArray()
    }

    /** 解压一项，逐块交给 [sink]；实际字节数、CRC 与中央目录不符时抛错。 */
    fun extract(entry: ZipEntryInfo, sink: (ByteArray, Int, Int) -> Unit) {
        if (entry.flags and 1 != 0) throw SkillArchiveException("不支持加密的 ZIP：${entry.name}")
        val local = entry.localOffset
        if (local + 30 > data.size || u32(data, local.toInt()) != 0x04034b50L) throw SkillArchiveException("ZIP 文件已损坏：${entry.name}")
        val start = local + 30 + u16(data, local.toInt() + 26) + u16(data, local.toInt() + 28)
        if (start + entry.compressedSize > data.size) throw SkillArchiveException("ZIP 文件已损坏：${entry.name}")
        val crc = CRC32()
        var total = 0L
        val emit = { b: ByteArray, off: Int, len: Int ->
            total += len
            if (total > entry.size) throw SkillArchiveException("ZIP 里的 ${entry.name} 比声明的大，不安全")
            crc.update(b, off, len)
            sink(b, off, len)
        }
        when (entry.method) {
            0 -> {
                if (entry.compressedSize != entry.size) throw SkillArchiveException("ZIP 文件已损坏：${entry.name}")
                var offset = start.toInt()
                val end = (start + entry.size).toInt()
                while (offset < end) {
                    val n = minOf(65_536, end - offset)
                    emit(data, offset, n)
                    offset += n
                }
            }
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(data, start.toInt(), entry.compressedSize.toInt())
                    val buffer = ByteArray(65_536)
                    while (!inflater.finished()) {
                        val n = try {
                            inflater.inflate(buffer)
                        } catch (error: DataFormatException) {
                            throw SkillArchiveException("ZIP 文件已损坏：${entry.name}")
                        }
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                            continue
                        }
                        emit(buffer, 0, n)
                    }
                } finally {
                    inflater.end()
                }
            }
            else -> throw SkillArchiveException("不支持这种压缩方式（${entry.method}）：${entry.name}")
        }
        if (total != entry.size || crc.value != entry.crc) throw SkillArchiveException("ZIP 文件已损坏：${entry.name}")
    }

    /**
     * 挑出 Skill：SKILL.md 可以在包的根上，也可以在第一层文件夹里，取最浅的那个；它所在那一层的东西就是 Skill 的全部。
     * 名字依次取 SKILL.md 里的 name、那层文件夹的名字、ZIP 的文件名，第一个合规的。
     */
    fun skill(fileName: String): SkillPackage {
        val usable = entries.filter { e ->
            !e.name.startsWith("__MACOSX/") && !e.name.trimEnd('/').substringAfterLast('/').startsWith("._")
        }
        if (usable.sumOf { it.size } > MAX_TOTAL) throw SkillArchiveException("ZIP 解压后太大（超过 ${MAX_TOTAL shr 20} MiB）")
        val skillMd = usable.filter { it.name.substringAfterLast('/') == "SKILL.md" && it.name.count { c -> c == '/' } <= 1 }
            .minByOrNull { it.name.count { c -> c == '/' } }
            ?: throw SkillArchiveException("包里没有 SKILL.md（应放在根目录或第一层文件夹里）")
        val prefix = skillMd.name.removeSuffix("SKILL.md")
        val text = read(skillMd, MAX_SKILL_MD).toString(Charsets.UTF_8)
        if ('�' in text) throw SkillArchiveException("SKILL.md 不是 UTF-8 文本")
        val split = Frontmatter.split(text)
        val meta = Frontmatter.parse(split.front)
        val description = meta["description"]?.text()?.trim().orEmpty()
        if (split.front == null || description.isEmpty()) {
            throw SkillArchiveException("SKILL.md 开头缺少带 description 的前言（--- 包起来的 name、description），Claude 不知道什么时候该用它")
        }
        val name = listOf(meta["name"]?.text().orEmpty(), prefix.trim('/'), fileName.substringBeforeLast('.'))
            .map { toSkillName(it) }
            .firstOrNull { isValidName(it) }
            ?: throw SkillArchiveException("认不出 Skill 的名字：请在 SKILL.md 里写上 name（小写字母、数字和连字符）")
        val members = mutableListOf<SkillMember>()
        for (entry in usable) {
            if (!entry.name.startsWith(prefix) || entry.name == prefix) continue
            val relative = entry.name.removePrefix(prefix)
            val parts = relative.split('/').filter { it.isNotEmpty() }
            if (relative.startsWith("/") || '\\' in relative || parts.any { it == "." || it == ".." } ||
                relative.any { it < ' ' || it == '\u007f' } || parts.isEmpty()
            ) {
                throw SkillArchiveException("ZIP 里有不安全的路径：${entry.name}")
            }
            if (entry.unixMode and S_IFMT == S_IFLNK) throw SkillArchiveException("ZIP 里有符号链接，不支持：${entry.name}")
            val directory = entry.name.endsWith("/")
            members += SkillMember(parts.joinToString("/"), directory, !directory && entry.unixMode and 0b001_001_001 != 0, if (directory) null else entry)
        }
        // 先完整解一遍，确认每项都对得上，再往 Linux 那边送；送到一半才发现坏了会留下半个 Skill
        for (member in members) member.entry?.let { e -> extract(e) { _, _, _ -> } }
        return SkillPackage(name, description, members, this)
    }

    /** 把 Skill 的文件写成 tar（交给 Linux 那边的 tar -x）。 */
    fun writeTar(skill: SkillPackage, out: OutputStream) {
        val tar = TarWriter(out)
        for (member in skill.members) {
            val entry = member.entry
            if (member.directory || entry == null) {
                tar.directory(member.path)
            } else {
                tar.file(member.path, entry.size, member.executable) { sink -> extract(entry) { b, off, len -> sink.write(b, off, len) } }
            }
        }
        tar.finish()
    }
}

/** 最简单的 tar（POSIX ustar，路径长或不是 ASCII 时加一条 PAX 头），只写目录和普通文件，属主交给解包的人。 */
class TarWriter(private val out: OutputStream) {
    private val mtime = System.currentTimeMillis() / 1000

    fun directory(path: String) {
        header(path.trimEnd('/') + "/", 0, "755", '5')
    }

    fun file(path: String, size: Long, executable: Boolean, write: (OutputStream) -> Unit) {
        header(path, size, if (executable) "755" else "644", '0')
        val counting = object : OutputStream() {
            var count = 0L
            override fun write(b: Int) {
                count++
                out.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                count += len
                out.write(b, off, len)
            }
        }
        write(counting)
        if (counting.count != size) throw SkillArchiveException("$path 的大小不对")
        pad(size)
    }

    fun finish() {
        out.write(ByteArray(1024))
        out.flush()
    }

    private fun pad(size: Long) {
        val rest = (512 - size % 512) % 512
        if (rest > 0) out.write(ByteArray(rest.toInt()))
    }

    private fun header(path: String, size: Long, mode: String, type: Char) {
        val bytes = path.toByteArray(Charsets.UTF_8)
        val ascii = bytes.size == path.length
        var name = path
        if (bytes.size > 100 || !ascii) {
            val record = paxRecord("path", path)
            block("././@PaxHeader", record.size.toLong(), "644", 'x')
            out.write(record)
            pad(record.size.toLong())
            // ustar 里的名字只是给不认 PAX 的 tar 看的
            name = path.filter { it.code in 0x20..0x7e }.takeLast(100)
        }
        block(name, size, mode, type)
    }

    /** PAX 记录 `长度 键=值\n`，长度包括它自己的位数。 */
    private fun paxRecord(key: String, value: String): ByteArray {
        val body = " $key=$value\n".toByteArray(Charsets.UTF_8)
        var length = body.size + 1
        while (length.toString().length + body.size != length) length = length.toString().length + body.size
        return length.toString().toByteArray() + body
    }

    private fun block(name: String, size: Long, mode: String, type: Char) {
        val h = ByteArray(512)
        fun put(text: String, at: Int, width: Int) {
            val b = text.toByteArray(Charsets.UTF_8)
            System.arraycopy(b, 0, h, at, minOf(b.size, width))
        }
        fun octal(value: Long, at: Int, width: Int) = put(value.toString(8).padStart(width - 1, '0'), at, width - 1)
        put(name, 0, 100)
        put("0000$mode", 100, 7)
        octal(0, 108, 8)
        octal(0, 116, 8)
        octal(size, 124, 12)
        octal(mtime, 136, 12)
        h[156] = type.code.toByte()
        put("ustar", 257, 6)
        put("00", 263, 2)
        for (k in 148 until 156) h[k] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0'), 148, 6)
        h[154] = 0
        h[155] = ' '.code.toByte()
        out.write(h)
    }
}
