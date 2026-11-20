package com.dzshzx.zeta.core

import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlinx.serialization.json.JsonObject

/**
 * 字节数给人看，和系统设置里一样用十进制单位（1 KB = 1000 B），保留三位有效数字：1.5 KB、12.3 MB、9.65 GB。
 * 界面上各处的文件大小、剩余空间都用它。
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    // 999.6 KB 显示出来会是 1000 KB，这时进到下一个单位
    while (value >= 999.5 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    val text = when {
        value >= 100 -> "%.0f".format(Locale.US, value)
        value >= 10 -> "%.1f".format(Locale.US, value)
        else -> "%.2f".format(Locale.US, value)
    }
    return (if ('.' in text) text.trimEnd('0').trimEnd('.') else text) + " " + units[unit]
}

/** 像 Claude Code 会话 id 的字符串（zeta-helper 那边同样校验）：存储清理要保护的会话、设置备份里的置顶会话都按它收。 */
internal val SessionIdPattern = Regex("[0-9a-fA-F-]{8,64}")

/** 存储页的三组。 */
enum class StorageGroup { Zeta, Claude, Debian }

/**
 * 存储页上能清理的一类。[key] 和 zeta-helper 各类清理计划的键一致（helper/storage.go）；[AppCache]、[ImageMemory] 在 Android 一侧，由应用自己算、自己删，
 * [Apt] 要 root，由应用以 root 执行 apt-get clean。[what] 是确认框里的“删什么”，[kept] 是“什么不删”。
 */
enum class StorageKind(val key: String, val group: StorageGroup, val title: String, val what: String, val kept: String) {
    AppCache(
        "app-cache", StorageGroup.Zeta, "应用缓存",
        "Zeta 在 Android 缓存目录里的临时文件（截图、分享进来的文件副本等）和内置浏览器的网页缓存",
        "十分钟内写过的文件；有对话在进行时，浏览器缓存先不清",
    ),
    ImageMemory(
        "image-memory", StorageGroup.Zeta, "图片缓存",
        "聊天里显示过的图片在内存里的缓存",
        "无（正在显示的图片之后会重新载入）",
    ),
    ZetaFiles(
        "zeta-files", StorageGroup.Zeta, "附件",
        "发给 Claude 的文件在 Linux 环境里的副本",
        "一小时内送进来的（可能还在输入框里）、进行中的对话提到的。七天以上的平时也会自动清掉",
    ),
    ZetaHosts(
        "zeta-hosts", StorageGroup.Zeta, "已结束对话的输出",
        "后台对话的输出记录里 Claude Code 进程已经结束的那些（对话内容在会话记录里，不受影响）",
        "还在运行、或应用还连着的对话的",
    ),
    ClaudeResidue(
        "claude-residue", StorageGroup.Claude, "已删会话的残留",
        "会话记录已经不在的会话留下的改动备份（file-history）、会话环境（session-env）、任务清单（tasks、todos）、上传（uploads），以及项目目录里的同名目录",
        "进行中的会话的、十分钟内动过的；会话记录本身一个都不删",
    ),
    ClaudeDebug(
        "claude-debug", StorageGroup.Claude, "调试日志",
        "Claude Code 的调试日志（~/.claude/debug）",
        "进行中的会话的、十分钟内写过的",
    ),
    ClaudeSnapshots(
        "claude-snapshots", StorageGroup.Claude, "Shell 快照",
        "Claude Code 执行命令前载入的 shell 环境快照（~/.claude/shell-snapshots），每次启动会重新建",
        "还在运行的 Claude Code 可能在用的（建于最早那个进程启动之后的）",
    ),
    ClaudePaste(
        "claude-paste", StorageGroup.Claude, "粘贴缓存",
        "终端里粘贴的大段文字（~/.claude/paste-cache），只用来在输入历史（上箭头）里调回；删掉后历史里那几条只剩占位",
        "还在运行的 Claude Code 启动以后粘贴的",
    ),
    ClaudeImageCache(
        "claude-image-cache", StorageGroup.Claude, "图片副本",
        "Claude Code 为对话里的图片存的副本（~/.claude/image-cache），它自己启动时也会清掉别的会话的",
        "进行中的会话的",
    ),
    ClaudeTelemetry(
        "claude-telemetry", StorageGroup.Claude, "未发出的使用统计",
        "没发送成功、等着重发的使用统计（~/.claude/telemetry）",
        "进行中的会话的",
    ),
    Apt(
        "apt", StorageGroup.Debian, "apt 缓存",
        "apt 下载过的软件包和包索引缓存（以 root 执行 apt-get clean），再装软件时会重新下载",
        "正在装软件时整个不清",
    ),
    Npm(
        "npm", StorageGroup.Debian, "npm 缓存",
        "npm 的下载缓存和日志（~/.npm/_cacache、_logs），和 npm cache clean 删的一样",
        "npx 装的包（~/.npm/_npx），MCP 服务可能正从那里运行；npm 刚启动不到十分钟时整个不清",
    ),
    Pip(
        "pip", StorageGroup.Debian, "pip 缓存",
        "pip 的下载和构建缓存（~/.cache/pip）",
        "pip 正在运行时整个不清",
    ),
    Tmp(
        "tmp", StorageGroup.Debian, "临时文件",
        "Debian 的 /tmp 里属于你的临时文件，包括 Claude Code 的命令输出和草稿目录（/tmp/claude-<uid>）",
        "一天内动过的、有进程正打开着或在里面工作的、带套接字的（tmux、ssh 等）、进行中的会话的",
    );

    /** 在 Linux 环境里：环境没准备好时看不了。 */
    val inLinux: Boolean get() = this != AppCache && this != ImageMemory

    companion object {
        fun of(key: String): StorageKind? = entries.firstOrNull { it.key == key }
    }
}

data class StoragePart(val name: String, val bytes: Long, val items: Int)

/** 一类的统计：一共占多少，能清掉多少、几项，按来源的小计；[busy] 非空时那个工具正在运行，这一类先不清。 */
data class StorageUsage(
    val bytes: Long,
    val freeable: Long = 0,
    val items: Int = 0,
    val parts: List<StoragePart> = emptyList(),
    val busy: String? = null,
    val failed: Boolean = false,
)

/** ~/.claude/projects 下的一个项目目录。 */
data class ProjectUsage(val dir: String, val cwd: String, val bytes: Long, val sessions: Int) {
    /** 列表里显示的名字：工作目录；看不出来（项目里没有会话记录）时用目录名，太长就截掉中间。 */
    val label: String
        get() = cwd.ifBlank { if (dir.length <= 32) dir else dir.take(16) + "…" + dir.takeLast(15) }
}

/** 项目里的一个会话：记录文件加同名目录一共占多少。[live] 是它正在进行（这时不能删）。 */
data class SessionUsage(val id: String, val file: String, val bytes: Long, val updated: Long, val live: Boolean)

class StorageReport(
    val linux: Map<String, StorageUsage>,
    val projects: List<ProjectUsage>,
    val cleanupPeriodDays: Int?,
    /** 助手的输出走完了（有最后那一行）。 */
    val complete: Boolean,
) {
    val projectBytes: Long get() = projects.sumOf { it.bytes }
}

data class CleanOutcome(val freed: Long, val removed: Int, val failed: Int = 0, val busy: String? = null, val error: String? = null)

/** 存储页：解析助手的输出、拼确认框的文字（纯函数，有单元测试），以及在后台线程上做统计和清理。 */
object Storage {
    /** Claude Code 的 cleanupPeriodDays 没设时的默认值（2.1.328 实测）。 */
    const val DEFAULT_CLEANUP_DAYS = 30

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    private fun lines(stdout: String): Sequence<JsonObject> = stdout.lineSequence()
        .filter { it.startsWith("{") }
        .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.count(key: String): Long = long(key) ?: 0L

    fun parseReport(stdout: String): StorageReport {
        val linux = LinkedHashMap<String, StorageUsage>()
        val projects = ArrayList<ProjectUsage>()
        var days: Int? = null
        var complete = false
        for (o in lines(stdout)) {
            when (o.str("type")) {
                "zeta_storage" -> {
                    val key = o.str("key") ?: continue
                    linux[key] = if (o.bool("error") == true) StorageUsage(0, failed = true) else StorageUsage(
                        bytes = o.count("bytes"),
                        freeable = o.count("freeable"),
                        items = o.count("items").toInt(),
                        parts = o.arr("parts").orEmpty().mapNotNull { part ->
                            val p = part as? JsonObject ?: return@mapNotNull null
                            StoragePart(p.str("name") ?: return@mapNotNull null, p.count("bytes"), p.count("items").toInt())
                        },
                        busy = o.str("busy"),
                    )
                }
                "zeta_storage_project" -> projects += ProjectUsage(
                    dir = o.str("dir") ?: continue,
                    cwd = o.str("cwd").orEmpty(),
                    bytes = o.count("bytes"),
                    sessions = o.count("sessions").toInt(),
                )
                "zeta_storage_settings" -> days = o.int("cleanupPeriodDays")
                "zeta_storage_done" -> complete = true
            }
        }
        return StorageReport(linux, projects.sortedByDescending { it.bytes }, days, complete)
    }

    /** 一个项目里的会话（按占用从大到小）和不属于任何会话的那部分的大小。 */
    fun parseSessions(stdout: String): Pair<List<SessionUsage>, Long> {
        val sessions = ArrayList<SessionUsage>()
        var rest = 0L
        for (o in lines(stdout)) {
            when (o.str("type")) {
                "zeta_storage_session" -> sessions += SessionUsage(
                    id = o.str("id") ?: continue,
                    file = o.str("file") ?: continue,
                    bytes = o.count("bytes"),
                    updated = o.count("updated"),
                    live = o.bool("live") == true,
                )
                "zeta_storage_rest" -> rest = o.count("bytes")
            }
        }
        return sessions.sortedWith(compareByDescending<SessionUsage> { it.bytes }.thenByDescending { it.updated }) to rest
    }

    fun parseCleaned(stdout: String): CleanOutcome? = lines(stdout).firstOrNull { it.str("type") == "zeta_cleaned" }?.let {
        CleanOutcome(it.count("freed"), it.count("removed").toInt(), it.count("failed").toInt(), it.str("busy"))
    }

    /** 给助手的“要保护的会话”参数：只要像会话 id 的（助手那边同样校验，不合的整条命令拒绝）。 */
    fun keepArguments(ids: Collection<String>): List<String> = ids.filter { SessionIdPattern.matches(it) }.distinct().sorted()

    /** 字节数给人看（见 [formatBytes]）。 */
    fun size(bytes: Long): String = formatBytes(bytes)

    /** 小计的来源名：残留按 ~/.claude 下的目录，/tmp 分 Claude Code 的和别的。 */
    fun partName(name: String): String = when (name) {
        "projects" -> "项目里的同名目录"
        "claude" -> "Claude Code 的临时目录"
        "other" -> "其他"
        else -> name
    }

    /** 列表里一类的说明：能清多少，或者为什么现在不能清。 */
    fun summary(kind: StorageKind, usage: StorageUsage?): String = when {
        usage == null -> "统计中…"
        usage.failed -> "没统计成"
        usage.busy != null -> "${usage.busy} 正在运行，稍后再清"
        usage.freeable <= 0 -> "没有可清理的"
        kind == StorageKind.ImageMemory -> "可清理 ${size(usage.freeable)}"
        else -> "可清理 ${size(usage.freeable)}（${usage.items} 项）"
    }

    /** 确认框里的说明：删什么、能腾出多少、什么不删。 */
    fun confirmMessage(kind: StorageKind, usage: StorageUsage): String = buildString {
        append("删除：").append(kind.what).append("。\n")
        append("能腾出约 ").append(size(usage.freeable))
        if (kind != StorageKind.ImageMemory) append("（").append(usage.items).append(" 项）")
        append("。")
        val parts = usage.parts.filter { it.items > 0 }
        if (parts.size > 1 || (parts.size == 1 && kind == StorageKind.ClaudeResidue)) {
            append("其中：").append(parts.joinToString("，") { "${partName(it.name)} ${size(it.bytes)}" }).append("。")
        }
        append("\n不删：").append(kind.kept).append("。")
    }

    /** 清理后的一句话。 */
    fun outcomeText(kind: StorageKind, outcome: CleanOutcome): String = when {
        outcome.error != null -> outcome.error
        outcome.busy != null -> "${outcome.busy} 正在运行，这次没有清理${kind.title}"
        outcome.removed == 0 && outcome.freed == 0L -> "没有可清理的${kind.title}"
        outcome.failed > 0 -> "已腾出 ${size(outcome.freed)}，${outcome.failed} 项没删成（核对时不合要求，留着没动）"
        else -> "已腾出 ${size(outcome.freed)}"
    }

    /** cleanupPeriodDays 的显示：没设时是 Claude Code 的默认值。 */
    fun cleanupText(days: Int?): String = if (days == null) "$DEFAULT_CLEANUP_DAYS 天（默认）" else "$days 天"

    /** 手机 /data 分区的剩余和总量（Debian 环境也在这个分区上）。 */
    fun phoneSpace(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes to stat.totalBytes
    }.getOrDefault(0L to 0L)

    /** 应用认为正在进行的会话：当前对话，侧栏里在跑、等确认、有后台任务的，和别处的 Claude Code 开着的。在主线程上调用。 */
    fun keepIds(hub: ConversationHub): List<String> = keepArguments(
        listOfNotNull(hub.current.sessionId) + hub.sessionList.sessions.filter { it.external || hub.isActive(it.id) }.map { it.id },
    )

    /** 统计 Linux 一侧的各类。耗时，别在主线程调用。 */
    fun scan(hub: ConversationHub, keep: List<String>): StorageReport? =
        hub.helper("storage", keep, 180_000).outputOrNull?.let(::parseReport)

    /** 一个项目里各会话的占用。耗时，别在主线程调用。 */
    fun sessions(hub: ConversationHub, project: String, keep: List<String>): Pair<List<SessionUsage>, Long>? =
        hub.helper("storage-sessions", listOf(project) + keep, 120_000).outputOrNull?.let(::parseSessions)

    /** 由助手清掉一类。耗时，别在主线程调用。 */
    fun clean(hub: ConversationHub, kind: StorageKind, keep: List<String>): CleanOutcome {
        return when (val result = hub.helper("clean", listOf(kind.key) + keep, 300_000)) {
            is HelperResult.Failed -> CleanOutcome(0, 0, error = result.reason)
            is HelperResult.Ok -> parseCleaned(result.output) ?: CleanOutcome(0, 0, error = "没清理成")
        }
    }

    /**
     * 以 root 执行 apt-get clean，前后各量一次 /var/cache/apt。apt 自己拿锁，别的 apt、dpkg 在跑时它会拒绝。
     * 应用正在装软件或做维护时不执行。耗时，别在主线程调用。
     */
    fun cleanApt(hub: ConversationHub): CleanOutcome {
        val config = hub.settings.env ?: return CleanOutcome(0, 0, error = "还没有可用的 Linux 环境")
        if (hub.environment.installer.running || hub.environment.maintenance?.running == true) return CleanOutcome(0, 0, busy = "安装或维护")
        val script = "b=\$(du -sk /var/cache/apt | cut -f1); apt-get clean 2>&1 | tail -n 3; rc=\${PIPESTATUS[0]}; " +
            "a=\$(du -sk /var/cache/apt | cut -f1); echo \"@zeta apt \$b \$a \$rc\""
        val result = runCatching {
            LinuxEnv.run(LinuxEnv.rootConfig(config.rootfs), "exec bash -c " + LinuxEnv.quote(script), timeoutMs = 120_000)
        }.getOrNull() ?: return CleanOutcome(0, 0, error = "Linux 环境没有响应")
        val line = result.stdout.lineSequence().firstOrNull { it.startsWith("@zeta apt ") }?.removePrefix("@zeta apt ")?.split(" ")
        val before = line?.getOrNull(0)?.toLongOrNull()
        val after = line?.getOrNull(1)?.toLongOrNull()
        val code = line?.getOrNull(2)?.toIntOrNull()
        if (before == null || after == null || code != 0) {
            val reason = result.stdout.lineSequence().filter { it.isNotBlank() && !it.startsWith("@zeta ") }.lastOrNull()
            return CleanOutcome(0, 0, error = "apt-get clean 没有成功" + (reason?.let { "：${cleanLine(it).take(160)}" } ?: ""))
        }
        return CleanOutcome(((before - after) * 1024).coerceAtLeast(0), 1)
    }

    /** Debian 环境整个占多少：在 Android 一侧（全局挂载命名空间，看不到进环境时的绑定挂载）用 du -x 量 rootfs。耗时。 */
    fun rootfsBytes(config: LinuxEnvConfig): Long? = runCatching {
        val result = LinuxEnv.runRoot(LinuxEnv.androidScript("exec \"\$1\" du -sxk \"\$2\"", config.rootfs), timeoutMs = 240_000)
        if (result.exitCode != 0) return null
        result.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024)
    }.getOrNull()
}

/**
 * 应用自己的 Android 缓存目录（context.cacheDir）。只看、只删它的直接下一层，不跟符号链接（链接本身也不删），
 * 十分钟内写过的不删（可能正要发出去）。浏览器的网页缓存（[WEBVIEW]）由 WebView 自己清，这里跳过。
 */
object AndroidCache {
    const val WEBVIEW = "WebView"
    const val FRESH_MS = 10 * 60 * 1000L

    class Measure(val bytes: Long, val newest: Long)

    /** [path]（不跟符号链接）里普通文件的总大小和最近修改时刻。 */
    fun measure(path: Path): Measure {
        var bytes = 0L
        var newest = 0L
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) bytes += attrs.size()
                    // 符号链接里没有数据，它自己的修改时刻不算
                    if (!attrs.isSymbolicLink) newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
        return Measure(bytes, newest)
    }

    /** 能删的直接下一层：不是符号链接，真实路径就在 [root] 的真实路径下面，不在 [skip] 里，十分钟内没写过。 */
    private fun candidates(root: File, skip: Set<String>, now: Long): List<Pair<Path, Measure>> {
        val base = root.toPath()
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val real = base.toRealPath()
        val entries = runCatching { Files.newDirectoryStream(base).use { it.toList() } }.getOrDefault(emptyList())
        return entries.mapNotNull { entry ->
            val name = entry.fileName.toString()
            if (name in skip || Files.isSymbolicLink(entry)) return@mapNotNull null
            if (runCatching { entry.toRealPath() }.getOrNull() != real.resolve(name)) return@mapNotNull null
            val measure = measure(entry)
            if (now - measure.newest < FRESH_MS) null else entry to measure
        }
    }

    /** 一共多少，能清多少（不算 [skip] 里的）。 */
    fun scan(root: File, skip: Set<String> = setOf(WEBVIEW), now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val total = if (root.exists()) measure(root.toPath()).bytes else 0L
        return total to candidates(root, skip, now).sumOf { it.second.bytes }
    }

    /** 删掉能删的，返回腾出的字节数。删目录时遇到符号链接只删链接本身。 */
    fun clean(root: File, skip: Set<String> = setOf(WEBVIEW), now: Long = System.currentTimeMillis()): Long {
        var freed = 0L
        for ((entry, measure) in candidates(root, skip, now)) {
            runCatching {
                Files.walkFileTree(entry, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                })
            }
            freed += measure.bytes - if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) measure(entry).bytes else 0L
        }
        return freed
    }
}
