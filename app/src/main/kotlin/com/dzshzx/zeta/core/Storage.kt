package com.dzshzx.zeta.core

import android.os.Environment
import android.os.StatFs
import com.dzshzx.zeta.i18n.Strings
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 像 Claude Code 会话 id 的字符串（zeta-helper 那边同样校验）：存储清理要保护的会话、设置备份里的置顶会话都按它收。 */
internal val SessionIdPattern = Regex("[0-9a-fA-F-]{8,64}")

/** 存储页的三组。 */
enum class StorageGroup {
    Zeta,
    Claude,
    Debian,
}

/**
 * 存储页上能清理的一类。[key] 和 zeta-helper 各类清理计划的键一致（helper/storage.go）；[AppCache]、[ImageMemory] 在 Android
 * 一侧，由应用自己算、自己删， [Apt] 要 root，由应用以 root 执行 apt-get clean。[what] 是确认框里的“删什么”，[kept] 是“什么不删”。
 */
enum class StorageKind(
    val key: String,
    val group: StorageGroup,
    private val titleText: () -> String,
    private val whatText: () -> String,
    private val keptText: () -> String,
) {
    AppCache(
        "app-cache",
        StorageGroup.Zeta,
        Strings::coreStorageAppCacheTitle,
        Strings::coreStorageAppCacheWhat,
        Strings::coreStorageAppCacheKept,
    ),
    ImageMemory(
        "image-memory",
        StorageGroup.Zeta,
        Strings::coreStorageImageMemoryTitle,
        Strings::coreStorageImageMemoryWhat,
        Strings::coreStorageImageMemoryKept,
    ),
    ZetaFiles(
        "zeta-files",
        StorageGroup.Zeta,
        Strings::coreStorageZetaFilesTitle,
        Strings::coreStorageZetaFilesWhat,
        Strings::coreStorageZetaFilesKept,
    ),
    ZetaHosts(
        "zeta-hosts",
        StorageGroup.Zeta,
        Strings::coreStorageZetaHostsTitle,
        Strings::coreStorageZetaHostsWhat,
        Strings::coreStorageZetaHostsKept,
    ),
    ClaudeResidue(
        "claude-residue",
        StorageGroup.Claude,
        Strings::coreStorageClaudeResidueTitle,
        Strings::coreStorageClaudeResidueWhat,
        Strings::coreStorageClaudeResidueKept,
    ),
    ClaudeDebug(
        "claude-debug",
        StorageGroup.Claude,
        Strings::coreStorageClaudeDebugTitle,
        Strings::coreStorageClaudeDebugWhat,
        Strings::coreStorageClaudeDebugKept,
    ),
    ClaudeSnapshots(
        "claude-snapshots",
        StorageGroup.Claude,
        Strings::coreStorageClaudeSnapshotsTitle,
        Strings::coreStorageClaudeSnapshotsWhat,
        Strings::coreStorageClaudeSnapshotsKept,
    ),
    ClaudePaste(
        "claude-paste",
        StorageGroup.Claude,
        Strings::coreStorageClaudePasteTitle,
        Strings::coreStorageClaudePasteWhat,
        Strings::coreStorageClaudePasteKept,
    ),
    ClaudeImageCache(
        "claude-image-cache",
        StorageGroup.Claude,
        Strings::coreStorageClaudeImageCacheTitle,
        Strings::coreStorageClaudeImageCacheWhat,
        Strings::coreStorageKeptRunningSessions,
    ),
    ClaudeTelemetry(
        "claude-telemetry",
        StorageGroup.Claude,
        Strings::coreStorageClaudeTelemetryTitle,
        Strings::coreStorageClaudeTelemetryWhat,
        Strings::coreStorageKeptRunningSessions,
    ),
    Apt(
        "apt",
        StorageGroup.Debian,
        Strings::coreStorageAptTitle,
        Strings::coreStorageAptWhat,
        Strings::coreStorageAptKept,
    ),
    Npm(
        "npm",
        StorageGroup.Debian,
        Strings::coreStorageNpmTitle,
        Strings::coreStorageNpmWhat,
        Strings::coreStorageNpmKept,
    ),
    Pip(
        "pip",
        StorageGroup.Debian,
        Strings::coreStoragePipTitle,
        Strings::coreStoragePipWhat,
        Strings::coreStoragePipKept,
    ),
    Tmp(
        "tmp",
        StorageGroup.Debian,
        Strings::coreStorageTmpTitle,
        Strings::coreStorageTmpWhat,
        Strings::coreStorageTmpKept,
    );

    /** 列表和确认框里的名字（按界面语言）。 */
    val title: String
        get() = titleText()

    /** 确认框里的“删什么”。 */
    val what: String
        get() = whatText()

    /** 确认框里的“什么不删”。 */
    val kept: String
        get() = keptText()

    /** 在 Linux 环境里：环境没准备好时看不了。 */
    val inLinux: Boolean
        get() = this != AppCache && this != ImageMemory

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
data class SessionUsage(
    val id: String,
    val file: String,
    val bytes: Long,
    val updated: Long,
    val live: Boolean,
)

class StorageReport(
    val linux: Map<String, StorageUsage>,
    val projects: List<ProjectUsage>,
    val cleanupPeriodDays: Int?,
) {
    val projectBytes: Long
        get() = projects.sumOf { it.bytes }
}

data class CleanOutcome(
    val freed: Long,
    val removed: Int,
    val failed: Int = 0,
    val busy: String? = null,
    val error: String? = null,
)

/** 存储页：读核心的 storage.* 方法的结果、拼确认框的文字（纯函数，有单元测试），以及在后台线程上做统计和清理。 */
object Storage {
    /** Claude Code 的 cleanupPeriodDays 没设时的默认值（2.1.328 实测）。 */
    const val DEFAULT_CLEANUP_DAYS = 30

    private fun JsonObject.count(key: String): Long = long(key) ?: 0L

    private fun JsonObject.items(key: String): List<JsonObject> =
        arr(key).orEmpty().mapNotNull { it as? JsonObject }

    /** `storage.scan` 的结果。 */
    fun parseReport(result: JsonObject): StorageReport {
        val linux = LinkedHashMap<String, StorageUsage>()
        for (o in result.items("kinds")) {
            val key = o.str("key") ?: continue
            linux[key] =
                if (o.bool("error") == true) StorageUsage(0, failed = true)
                else
                    StorageUsage(
                        bytes = o.count("bytes"),
                        freeable = o.count("freeable"),
                        items = o.count("items").toInt(),
                        parts =
                            o.items("parts").mapNotNull { p ->
                                StoragePart(
                                    p.str("name") ?: return@mapNotNull null,
                                    p.count("bytes"),
                                    p.count("items").toInt(),
                                )
                            },
                        busy = o.str("busy"),
                    )
        }
        val projects =
            result.items("projects").mapNotNull { o ->
                ProjectUsage(
                    dir = o.str("dir") ?: return@mapNotNull null,
                    cwd = o.str("cwd").orEmpty(),
                    bytes = o.count("bytes"),
                    sessions = o.count("sessions").toInt(),
                )
            }
        return StorageReport(
            linux,
            projects.sortedByDescending { it.bytes },
            result.int("cleanupPeriodDays"),
        )
    }

    /** `storage.sessions` 的结果：项目里的会话（按占用从大到小）和不属于任何会话的那部分的大小。 */
    fun parseSessions(result: JsonObject): Pair<List<SessionUsage>, Long> {
        val sessions =
            result.items("sessions").mapNotNull { o ->
                SessionUsage(
                    id = o.str("id") ?: return@mapNotNull null,
                    file = o.str("file") ?: return@mapNotNull null,
                    bytes = o.count("bytes"),
                    updated = o.count("updated"),
                    live = o.bool("live") == true,
                )
            }
        return sessions.sortedWith(
            compareByDescending<SessionUsage> { it.bytes }.thenByDescending { it.updated },
        ) to result.count("rest")
    }

    /** `storage.clean` 的结果。 */
    fun parseCleaned(result: JsonObject): CleanOutcome =
        CleanOutcome(
            result.count("freed"),
            result.count("removed").toInt(),
            result.count("failed").toInt(),
            result.str("busy"),
        )

    /** 交给核心的“要保护的会话”：只要像会话 id 的（核心那边同样校验，不合的整个请求拒绝）。 */
    fun keepArguments(ids: Collection<String>): List<String> =
        ids.filter { SessionIdPattern.matches(it) }.distinct().sorted()

    /** 字节数给人看（见 [formatBytes]）。 */
    fun size(bytes: Long): String = formatBytes(bytes)

    /** 小计的来源名：残留按 ~/.claude 下的目录，/tmp 分 Claude Code 的和别的。 */
    fun partName(name: String): String =
        when (name) {
            "projects" -> Strings.coreStoragePartProjects()
            "claude" -> Strings.coreStoragePartClaude()
            "other" -> Strings.coreStoragePartOther()
            else -> name
        }

    /** 列表里一类的说明：能清多少，或者为什么现在不能清。 */
    fun summary(kind: StorageKind, usage: StorageUsage?): String =
        when {
            usage == null -> Strings.coreStorageCounting()
            usage.failed -> Strings.coreStorageCountFailed()
            usage.busy != null -> Strings.coreStorageBusyLater(usage.busy)
            usage.freeable <= 0 -> Strings.coreStorageNothing()
            kind == StorageKind.ImageMemory -> Strings.coreStorageFreeable(size(usage.freeable))
            else -> Strings.coreStorageFreeableItems(size(usage.freeable), usage.items)
        }

    /** 确认框里的说明：删什么、能腾出多少、什么不删。 */
    fun confirmMessage(kind: StorageKind, usage: StorageUsage): String = buildString {
        append(Strings.coreStorageConfirmDelete(kind.what)).append("\n")
        append(
            if (kind == StorageKind.ImageMemory)
                Strings.coreStorageConfirmFree(size(usage.freeable))
            else Strings.coreStorageConfirmFreeItems(size(usage.freeable), usage.items),
        )
        val parts = usage.parts.filter { it.items > 0 }
        if (parts.size > 1 || (parts.size == 1 && kind == StorageKind.ClaudeResidue)) {
            append(
                Strings.coreStorageConfirmParts(
                    parts.joinToString(Strings.coreStorageListSeparator()) {
                        "${partName(it.name)} ${size(it.bytes)}"
                    },
                ),
            )
        }
        append("\n").append(Strings.coreStorageConfirmKept(kind.kept))
    }

    /** 清理后的一句话。 */
    fun outcomeText(kind: StorageKind, outcome: CleanOutcome): String =
        when {
            outcome.error != null -> outcome.error
            outcome.busy != null -> Strings.coreStorageBusySkipped(outcome.busy, kind.title)
            outcome.removed == 0 && outcome.freed == 0L -> Strings.coreStorageNothingOf(kind.title)
            outcome.failed > 0 ->
                Strings.coreStorageFreedPartly(size(outcome.freed), outcome.failed)
            else -> Strings.coreStorageFreed(size(outcome.freed))
        }

    /** cleanupPeriodDays 的显示：没设时是 Claude Code 的默认值。 */
    fun cleanupText(days: Int?): String =
        if (days == null) Strings.coreStorageCleanupDaysDefault(DEFAULT_CLEANUP_DAYS)
        else Strings.coreStorageCleanupDays(days)

    /** 手机 /data 分区的剩余和总量（Debian 环境也在这个分区上）。 */
    fun phoneSpace(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.availableBytes to stat.totalBytes
    }
        .getOrDefault(0L to 0L)

    /** 应用认为正在进行的会话：当前对话，侧栏里在跑、等确认、有后台任务的，和别处的 Claude Code 开着的。在主线程上调用。 */
    fun keepIds(hub: ConversationHub): List<String> =
        keepArguments(
            listOfNotNull(hub.current.sessionId) +
                hub.sessionList.sessions
                    .filter { it.external || hub.isActive(it.id) }
                    .map { it.id },
        )

    /** 统计 Linux 一侧的各类（核心的 `storage.scan`）。没统计成是 null。耗时，别在主线程调用。 */
    fun scan(hub: ConversationHub, keep: List<String>): StorageReport? = runCatching {
        hub.callCore(
            Backends.LOCAL,
            "storage.scan",
            buildJsonObject { put("keep", jsonStrings(keep)) },
            180_000,
        )
    }
        .getOrNull()
        ?.let { (it as? JsonObject)?.let(::parseReport) }

    /** 一个项目里各会话的占用（核心的 `storage.sessions`）。耗时，别在主线程调用。 */
    fun sessions(
        hub: ConversationHub,
        project: String,
        keep: List<String>,
    ): Pair<List<SessionUsage>, Long>? = runCatching {
        hub.callCore(
            Backends.LOCAL,
            "storage.sessions",
            buildJsonObject {
                put("project", project)
                put("keep", jsonStrings(keep))
            },
            120_000,
        )
    }
        .getOrNull()
        ?.let { (it as? JsonObject)?.let(::parseSessions) }

    /** 由核心清掉一类（`storage.clean`）。耗时，别在主线程调用。 */
    fun clean(hub: ConversationHub, kind: StorageKind, keep: List<String>): CleanOutcome =
        runCatching {
            hub.callCore(
                Backends.LOCAL,
                "storage.clean",
                buildJsonObject {
                    put("kind", kind.key)
                    put("keep", jsonStrings(keep))
                },
                300_000,
            )
        }
        .fold(
            {
                (it as? JsonObject)?.let(::parseCleaned)
                    ?: CleanOutcome(0, 0, error = Strings.coreStorageCleanFailed())
            },
            { CleanOutcome(0, 0, error = it.message ?: Strings.coreStorageCleanFailed()) },
        )

    /**
     * 以 root 执行 apt-get clean，前后各量一次 /var/cache/apt。apt 自己拿锁，别的 apt、dpkg 在跑时它会拒绝。
     * 应用正在装软件或做维护时不执行。耗时，别在主线程调用。
     */
    fun cleanApt(hub: ConversationHub): CleanOutcome {
        val config = hub.settings.env ?: return CleanOutcome(0, 0, error = Strings.coreNoLinuxEnv())
        if (hub.environment.installer.running || hub.environment.maintenance?.running == true)
            return CleanOutcome(0, 0, busy = Strings.coreStorageBusyMaintenance())
        val script =
            "b=\$(du -sk /var/cache/apt | cut -f1); apt-get clean 2>&1 | tail -n 3; rc=\${PIPESTATUS[0]}; " +
                "a=\$(du -sk /var/cache/apt | cut -f1); echo \"@zeta apt \$b \$a \$rc\""
        val result =
            runCatching {
                LinuxEnv.run(
                    LinuxEnv.rootConfig(config.rootfs),
                    "exec bash -c " + LinuxEnv.quote(script),
                    timeoutMs = 120_000,
                )
            }
                .getOrNull() ?: return CleanOutcome(0, 0, error = Strings.coreLinuxEnvNoResponse())
        val line =
            result.stdout
                .lineSequence()
                .firstOrNull { it.startsWith("@zeta apt ") }
                ?.removePrefix("@zeta apt ")
                ?.split(" ")
        val before = line?.getOrNull(0)?.toLongOrNull()
        val after = line?.getOrNull(1)?.toLongOrNull()
        val code = line?.getOrNull(2)?.toIntOrNull()
        if (before == null || after == null || code != 0) {
            val reason =
                result.stdout
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("@zeta ") }
                    .lastOrNull()
            return CleanOutcome(
                0,
                0,
                error =
                    reason?.let { Strings.coreStorageAptFailedReason(cleanLine(it).take(160)) }
                        ?: Strings.coreStorageAptFailed(),
            )
        }
        return CleanOutcome(((before - after) * 1024).coerceAtLeast(0), 1)
    }

    /** Debian 环境整个占多少：在 Android 一侧（全局挂载命名空间，看不到进环境时的绑定挂载）用 du -x 量 rootfs。耗时。 */
    fun rootfsBytes(config: LinuxEnvConfig): Long? = runCatching {
        val result =
            LinuxEnv.runRoot(
                LinuxEnv.androidScript("exec \"\$1\" du -sxk \"\$2\"", config.rootfs),
                timeoutMs = 240_000,
            )
        if (result.exitCode != 0) return null
        result.stdout.trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull()?.times(1024)
    }
        .getOrNull()
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
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile) bytes += attrs.size()
                        // 符号链接里没有数据，它自己的修改时刻不算
                        if (!attrs.isSymbolicLink)
                            newest = maxOf(newest, attrs.lastModifiedTime().toMillis())
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        }
        return Measure(bytes, newest)
    }

    /** 能删的直接下一层：不是符号链接，真实路径就在 [root] 的真实路径下面，不在 [skip] 里，十分钟内没写过。 */
    private fun candidates(root: File, skip: Set<String>, now: Long): List<Pair<Path, Measure>> {
        val base = root.toPath()
        if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS))
            return emptyList()
        val real = base.toRealPath()
        val entries = runCatching {
            Files.newDirectoryStream(base).use { it.toList() }
        }
            .getOrDefault(emptyList())
        return entries.mapNotNull { entry ->
            val name = entry.fileName.toString()
            if (name in skip || Files.isSymbolicLink(entry)) return@mapNotNull null
            if (runCatching { entry.toRealPath() }.getOrNull() != real.resolve(name))
                return@mapNotNull null
            val measure = measure(entry)
            if (now - measure.newest < FRESH_MS) null else entry to measure
        }
    }

    /** 一共多少，能清多少（不算 [skip] 里的）。 */
    fun scan(
        root: File,
        skip: Set<String> = setOf(WEBVIEW),
        now: Long = System.currentTimeMillis(),
    ): Pair<Long, Long> {
        val total = if (root.exists()) measure(root.toPath()).bytes else 0L
        return total to candidates(root, skip, now).sumOf { it.second.bytes }
    }

    /** 删掉能删的，返回腾出的字节数。删目录时遇到符号链接只删链接本身。 */
    fun clean(
        root: File,
        skip: Set<String> = setOf(WEBVIEW),
        now: Long = System.currentTimeMillis(),
    ): Long {
        var freed = 0L
        for ((entry, measure) in candidates(root, skip, now)) {
            runCatching {
                Files.walkFileTree(
                    entry,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: IOException?,
                        ): FileVisitResult {
                            Files.deleteIfExists(dir)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: IOException,
                        ): FileVisitResult = FileVisitResult.CONTINUE
                    },
                )
            }
            freed +=
                measure.bytes -
                    if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) measure(entry).bytes else 0L
        }
        return freed
    }
}
