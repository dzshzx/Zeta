package com.dzshzx.zeta.core

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** 一个共享文件夹：Android 侧的目录 [source] 在 Linux 环境里出现在 /mnt/shared/[name]。 */
data class SharedFolder(val name: String, val source: String) {
    val mountPoint: String
        get() = "${SharedFolders.LINUX_ROOT}/$name"
}

/** 一个共享文件夹在 Linux 用户眼里的样子。 */
enum class SharedAccess {
    ReadWrite,
    ReadOnly,
    Denied,
    NotMounted,
}

/**
 * 共享文件夹的配置、校验和 Android 侧的操作。做法照 Eta：挂载不做在 Android 的全局命名空间里， 而是每次进入环境（LinuxEnv
 * 的私有挂载命名空间）时按当前清单绑定，随进程结束消失，不留要卸载的全局状态。 与 Eta 不同：内部存储改绑底层的 /data/media/<用户>（/sdcard 那层 FUSE 在
 * chroot 里普通用户用不了）， 并把 Linux 用户加进 media_rw 组，才能读写。
 */
object SharedFolders {
    /**
     * 挂载点建在 Zeta 自己的 rootfs 里。不放在 /workspace 下：那是用户的工作区（Android 的 [LinuxEnv.WORKSPACE] 绑过去的），
     * 挂载点不该和用户的文件混在一起。
     */
    const val LINUX_ROOT = "/mnt/shared"
    const val MAX = 16

    /** 内部存储底层目录的属组 media_rw：目录 2770、文件 0660，组内可读写。 */
    private const val MEDIA_GID = 1023

    private val NamePattern = Regex("[A-Za-z0-9._-]{1,48}")

    /** 系统关键目录、/workspace 本身（环境里本来就看得到）和 Zeta 自带环境的目录不能共享；当前用的 rootfs 由 [sourceProblem] 另外加上。 */
    private val Forbidden =
        listOf(
            "/proc",
            "/sys",
            "/dev",
            "/system",
            "/vendor",
            "/apex",
            "/product",
            "/odm",
            LinuxEnv.WORKSPACE,
            LinuxEnv.OWN_BASE,
        )

    /** 当前清单；Settings 读写时同步过来，进入环境的脚本据此挂载。 */
    @Volatile var current: List<SharedFolder> = emptyList()

    private val json = Json { ignoreUnknownKeys = true }

    /** 给 LinuxEnv 的挂载清单：每行“名字<Tab>Android 侧实际绑定的目录”。 */
    fun mountSpec(): String = current.joinToString("\n") { it.name + "\t" + bindSource(it.source) }

    /** 词法归一化：不解析符号链接，保留用户选的路径写法（/sdcard 不改写）；带控制字符的不收。 */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("/") || trimmed.any { it < ' ' }) return null
        val segments = ArrayList<String>()
        for (segment in trimmed.split('/')) {
            when (segment) {
                "",
                "." -> Unit
                ".." ->
                    if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    /**
     * 实际绑定的源目录。内部存储（/sdcard、/storage/emulated/N）是 MediaProvider 的 FUSE， chroot 里的普通用户一访问就报 Bad
     * address，所以改绑它底下的真实目录 /data/media/N；别的路径原样。
     */
    fun bindSource(source: String): String {
        Regex("/storage/emulated/(\\d+)(/.*)?").matchEntire(source)?.let {
            return "/data/media/" + it.groupValues[1] + it.groupValues[2]
        }
        val primary = "/data/media/${LinuxEnv.androidUser}"
        for (alias in listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard")) {
            if (source == alias) return primary
            if (source.startsWith("$alias/")) return primary + source.removePrefix(alias)
        }
        return source
    }

    /** 绑定的是内部存储（要靠 media_rw 组才能读写）。 */
    fun isMediaStorage(source: String): Boolean = MediaPath.matches(bindSource(source))

    private val MediaPath = Regex("/data/media/\\d+(/.*)?")

    /** 选系统文件选择器给的目录：只认内部存储（primary），返回 /storage/emulated/<用户>/... 这样的真实路径。 */
    fun fromTreeUri(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        if (!id.startsWith("primary:")) return null
        val rest = id.removePrefix("primary:").trim('/')
        return normalize("/storage/emulated/${LinuxEnv.androidUser}/$rest")
    }

    /** 源目录不能用的原因；能用返回 null。 */
    fun sourceProblem(source: String, existing: List<SharedFolder>, rootfs: String?): String? {
        val normalized = normalize(source) ?: return "目录路径无效"
        val bind = bindSource(normalized)
        if (
            bind == "/" ||
                (Forbidden + listOfNotNull(rootfs)).any { bind == it || bind.startsWith("$it/") }
        )
            return "该目录不能共享"
        if (existing.any { bindSource(it.source) == bind }) return "该目录已共享"
        if (existing.size >= MAX) return "最多配置 $MAX 个共享文件夹"
        return null
    }

    /** 挂载名不能用的原因；能用返回 null。 */
    fun nameProblem(name: String, existing: List<SharedFolder>): String? {
        if (!NamePattern.matches(name) || name == "." || name == "..")
            return "仅限 1–48 个字母、数字、点、下划线或连字符"
        if (existing.any { it.name == name }) return "挂载名已被使用"
        return null
    }

    /** 从源目录的最后一段推一个挂载名；整个内部存储叫 sdcard，剔掉非 ASCII 后为空就叫 share，重名的加序号。 */
    fun defaultName(source: String, existing: List<SharedFolder>): String {
        val bind = bindSource(source)
        val base =
            if (bind == "/data/media/${LinuxEnv.androidUser}") "sdcard"
            else source.trimEnd('/').substringAfterLast('/')
        val candidate =
            base
                .filter {
                    it in 'A'..'Z' ||
                        it in 'a'..'z' ||
                        it in '0'..'9' ||
                        it == '.' ||
                        it == '_' ||
                        it == '-'
                }
                .trim('.')
                .take(44)
                .ifBlank { "share" }
        if (existing.none { it.name == candidate }) return candidate
        return (2..99)
            .map { "$candidate-$it" }
            .firstOrNull { name -> existing.none { it.name == name } } ?: candidate
    }

    fun encode(folders: List<SharedFolder>): String = buildJsonArray {
        folders.forEach {
            addJsonObject {
                put("name", it.name)
                put("source", it.source)
            }
        }
    }
        .toString()

    /** 坏掉的条目跳过，整体坏了就是空清单。 */
    fun decode(text: String?): List<SharedFolder> {
        if (text.isNullOrBlank()) return emptyList()
        val array =
            runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull()
                ?: return emptyList()
        val folders = ArrayList<SharedFolder>()
        for (element in array) {
            val item = element as? JsonObject ?: continue
            val name = item.str("name")?.trim() ?: continue
            val source = normalize(item.str("source") ?: continue) ?: continue
            if (
                nameProblem(name, folders) != null ||
                    folders.any { bindSource(it.source) == bindSource(source) }
            )
                continue
            folders += SharedFolder(name, source)
        }
        return folders.take(MAX)
    }

    // 下面几个都经 root 执行，耗时，别在主线程调用。

    /** Android 侧 [path] 下的子文件夹（选目录用，不含隐藏的）；读不了返回 null。 */
    fun subfolders(path: String): List<String>? {
        val script =
            "cd \"\$2\" 2>/dev/null || exit 3; for d in *; do [ -d \"\$d\" ] && printf '%s\\n' \"\$d\"; done | \"\$1\" head -n 1000"
        val result =
            runCatching {
                LinuxEnv.runRoot(LinuxEnv.androidScript(script, bindSource(path)), 15_000)
            }
                .getOrNull() ?: return null
        if (result.exitCode != 0) return null
        return result.stdout
            .lineSequence()
            .filter { it.isNotEmpty() }
            .sortedBy { it.lowercase() }
            .toList()
    }

    /** 各个共享文件夹的源目录在 Android 侧还在不在（按名字）。查不了返回空表。 */
    fun sourcesPresent(folders: List<SharedFolder>): Map<String, Boolean> {
        if (folders.isEmpty()) return emptyMap()
        val script =
            "shift; while [ \$# -ge 2 ]; do [ -d \"\$2\" ] && echo \"\$1 1\" || echo \"\$1 0\"; shift 2; done"
        val arguments = folders.flatMap { listOf(it.name, bindSource(it.source)) }.toTypedArray()
        val result =
            runCatching { LinuxEnv.runRoot(LinuxEnv.androidScript(script, *arguments), 15_000) }
                .getOrNull() ?: return emptyMap()
        return result.stdout
            .lineSequence()
            .mapNotNull { line ->
                line.trim().split(' ').takeIf { it.size == 2 }?.let { it[0] to (it[1] == "1") }
            }
            .toMap()
    }

    /**
     * 移除后删掉 Android 侧留下的挂载点目录。只用 rmdir：目录里有东西（不是 Zeta 建的空目录）就留着。
     * 它在别的挂载命名空间里还是挂载点时，内核会顺带把那边的挂载摘掉，正在跑的对话也就看不到了；源目录里的文件不受影响。
     */
    fun removeMountPoint(rootfs: String, name: String) {
        if (nameProblem(name, emptyList()) != null) return
        runCatching {
            LinuxEnv.runRoot(
                LinuxEnv.androidScript(
                    "rmdir \"\$2\" 2>/dev/null; exit 0",
                    "$rootfs$LINUX_ROOT/$name",
                ),
                15_000,
            )
        }
    }

    /**
     * 让 Linux 用户能读写内部存储：底层目录属 media_rw 组（gid 1023）且组可读写，把用户加进环境里 gid 为 1023 的组， 没有就建一个。只改 Debian 里的
     * /etc/group，对之后进入环境的命令生效。成功返回 true。
     */
    fun ensureMediaGroup(config: LinuxEnvConfig): Boolean {
        if (config.uid == 0) return true
        val script =
            """
            g=${'$'}(getent group $MEDIA_GID | cut -d: -f1)
            if [ -z "${'$'}g" ]; then
              g=media_rw
              getent group "${'$'}g" >/dev/null && g=zeta_media_rw
              groupadd -g $MEDIA_GID "${'$'}g" || exit 1
            fi
            id -nG "${'$'}1" | tr ' ' '\n' | grep -qx "${'$'}g" || usermod -aG "${'$'}g" "${'$'}1"
        """
                .trimIndent()
        val command =
            "bash -c " + LinuxEnv.quote(script) + " zeta-share " + LinuxEnv.quote(config.user)
        return runCatching {
                LinuxEnv.run(LinuxEnv.rootConfig(config.rootfs), command).exitCode == 0
            }
            .getOrDefault(false)
    }

    /** 以 Linux 用户的身份看各个共享文件夹：挂上没有、能不能读写。查不了返回 null。 */
    fun access(helper: HelperRun, folders: List<SharedFolder>): Map<String, SharedAccess>? {
        if (folders.isEmpty()) return emptyMap()
        val output = helper("mounts", folders.map { it.name }).outputOrNull ?: return null
        return output
            .lineSequence()
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                val item =
                    runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: return@mapNotNull null
                val name = item.str("name") ?: return@mapNotNull null
                name to
                    when {
                        item.bool("mounted") != true -> SharedAccess.NotMounted
                        item.bool("read") != true -> SharedAccess.Denied
                        item.bool("write") == true -> SharedAccess.ReadWrite
                        else -> SharedAccess.ReadOnly
                    }
            }
            .toMap()
    }
}
