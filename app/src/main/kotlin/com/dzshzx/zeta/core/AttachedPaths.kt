package com.dzshzx.zeta.core

/**
 * 随消息附带的 Linux 路径。[UserItem.files]、发送、排队和撤回时传的都是“附件说明”字符串，有两种：
 * - 以 / 开头：复制进 Linux 环境的文件（~/.cache/zeta/files/…）。消息里写成 `[Attached file: 路径]`，Claude 自己用工具去读。
 * - 以 @ 开头：引用 Linux 里本来就有的路径，结尾带 / 的是文件夹。消息里写成 `[Attached file: @"路径"]` 或 `[Attached
 *   folder: @"路径"]`。
 *
 * 引用用 Claude Code 自己的 @ 提及（2.1.327 实测，stream-json 输入也认）：前面是空白的 `@"路径"` 会被当成提及，
 * 文件把内容附进上下文（太大的只告诉模型去读），文件夹附上一层目录清单；路径不存在就只剩这行文字，模型照样看得到路径。
 * 带引号的写法里路径可以有空格，结尾的标点也不会被当成句读剥掉，所以路径里不能有双引号。
 */
object AttachedPaths {
    private const val FilePrefix = "[Attached file: "
    private const val FolderPrefix = "[Attached folder: "

    /** 引用 Linux 里的 [path]（已归一化的绝对路径）。 */
    fun reference(path: String, folder: Boolean): String =
        if (folder) "@" + path.trimEnd('/') + "/" else "@$path"

    fun isReference(entry: String): Boolean = entry.startsWith("@")

    fun isFolder(entry: String): Boolean = entry.startsWith("@") && entry.endsWith("/")

    /** 附件说明对应的 Linux 路径。 */
    fun path(entry: String): String =
        when {
            isFolder(entry) -> entry.substring(1, entry.length - 1).ifEmpty { "/" }
            isReference(entry) -> entry.substring(1)
            else -> entry
        }

    /** 附件小标签上显示的名字：路径的最后一段。 */
    fun label(entry: String): String =
        path(entry).trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

    /** 写进消息开头的那一行。 */
    fun header(entry: String): String =
        when {
            isFolder(entry) -> FolderPrefix + "@\"" + path(entry) + "\"]"
            isReference(entry) -> FilePrefix + "@\"" + path(entry) + "\"]"
            else -> "$FilePrefix$entry]"
        }

    /** [header] 的逆操作；不是附件行返回 null。 */
    fun parseHeader(line: String): String? {
        if (!line.endsWith("]")) return null
        val folder = line.startsWith(FolderPrefix)
        if (!folder && !line.startsWith(FilePrefix)) return null
        val body =
            line.substring(if (folder) FolderPrefix.length else FilePrefix.length, line.length - 1)
        if (body.length > 3 && body.startsWith("@\"") && body.endsWith("\"")) {
            val path = body.substring(2, body.length - 1)
            if (path.startsWith("/")) return reference(path, folder)
        }
        return if (folder || body.isEmpty()) null else body
    }

    /**
     * 用户输入的 Linux 路径：去掉首尾空白，`~` 换成家目录，按字面消掉 `.` 和 `..`，去掉结尾的 /。 不是绝对路径、带控制字符或双引号（写不进 @"…"）的返回 null。
     */
    fun normalize(raw: String, home: String? = null): String? {
        var text = raw.trim()
        if (home != null && (text == "~" || text.startsWith("~/")))
            text = home.trimEnd('/') + text.substring(1)
        if (!text.startsWith("/") || text.any { it < ' ' || it == '\u007f' || it == '"' })
            return null
        val segments = ArrayList<String>()
        for (segment in text.split('/')) {
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
     * Android 侧的目录在 Linux 环境里叫什么；Linux 里看不到时返回 null。 进入环境时绑定的有：[LinuxEnv.WORKSPACE] 在
     * /workspace，/data/local/tmp 原样，各共享文件夹在 /mnt/shared/<名字>， rootfs 本身就是
     * /。内部存储（/sdcard、/storage/emulated）本身在 Linux 里不能用，只能经共享文件夹。
     */
    fun linuxPathFor(androidPath: String, shares: List<SharedFolder>, rootfs: String?): String? {
        val bind = SharedFolders.bindSource(SharedFolders.normalize(androidPath) ?: return null)
        fun under(base: String): String? =
            when {
                bind == base -> ""
                bind.startsWith("$base/") -> bind.substring(base.length)
                else -> null
            }
        under(LinuxEnv.WORKSPACE)?.let {
            return "/workspace$it"
        }
        under("/data/local/tmp")?.let {
            return "/data/local/tmp$it"
        }
        if (rootfs != null)
            under(rootfs.trimEnd('/'))?.let {
                return it.ifEmpty { "/" }
            }
        // 共享文件夹可能套着共享文件夹：取源目录最长的那个
        return shares
            .mapNotNull { share ->
                under(SharedFolders.bindSource(share.source))?.let {
                    share.mountPoint + it to share.source.length
                }
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * [linuxPathFor] 反过来：手机 Linux 环境里的路径（Claude 看到的）在 Android 一侧（以 root、全局挂载命名空间）叫什么。 /workspace 是
     * [LinuxEnv.WORKSPACE]，共享文件夹换回它绑定的源目录，别的都在 [rootfs] 里。/data（Linux 里只有原样绑进去的
     * /data/local/tmp）、/sdcard、/storage 当作已经是 Android 路径，原样用。[home] 用来展开 `~`。不是绝对路径、 或该在 rootfs
     * 里却没有 [rootfs] 时返回 null。
     */
    fun androidPathFor(
        linuxPath: String,
        shares: List<SharedFolder>,
        rootfs: String?,
        home: String? = null,
    ): String? {
        val path = normalize(linuxPath, home) ?: return null
        fun under(base: String): String? =
            when {
                path == base -> ""
                path.startsWith("$base/") -> path.substring(base.length)
                else -> null
            }
        under("/workspace")?.let {
            return LinuxEnv.WORKSPACE + it
        }
        if (AndroidSide.any { under(it) != null }) return path
        shares
            .firstNotNullOfOrNull { share ->
                under(share.mountPoint)?.let { SharedFolders.bindSource(share.source) + it }
            }
            ?.let {
                return it
            }
        val root = rootfs?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        return if (path == "/") root else root + path
    }

    /** 在 Linux 环境里和 Android 一侧是同一个路径的顶层目录（/data/local/tmp 原样绑进去），或只有 Android 才有的。 */
    private val AndroidSide = listOf("/data", "/sdcard", "/storage")
}

/** 在 Linux 用户（和对话的 Claude Code 进程）眼里，一个路径是什么。 */
enum class PathKind {
    Folder,
    File,
    Missing,
    /** 在，但这个用户读不了。 */
    Denied,
    /** 新开的环境里看得到（是文件夹），对话正在跑的 Claude Code 进程却看不到：共享文件夹是它启动之后才加的。 */
    FolderNotInProcess,
    /** 同上，是文件。 */
    FileNotInProcess,
    /** 查不成（进不了环境之类）。 */
    Unknown,
}

/** 对话的工作目录进不去（没有这个目录、不是目录或没有权限）：起进程前拦下时和托管脚本报出来时给用户的话。 */
object CwdProblem {
    /** 托管脚本（zeta_host.sh 的 start）进不去工作目录时在标准错误输出里写的开头。 */
    const val MARK = "zeta: 进不去工作目录"

    /** 进不去时聊天页上的提示：说清 Claude Code 没启动、是哪台上的哪个目录、去哪改。 */
    fun notice(hub: ConversationHub, backend: String, cwd: String): String =
        "Claude Code 没有启动：${where(hub, backend)}进不去工作目录 $cwd（没有这个目录，或没有权限）。" +
            "新对话在开始页点工作目录那一行改；默认工作目录在设置里这台设备的页面上改。"

    /** 要把 [path] 设成主机 [backend] 上的工作目录：进不去时返回给用户的原因，进得去（或那台这会儿查不了）返回 null。耗时，别在主线程调用。 */
    fun check(hub: ConversationHub, backend: String, path: String): String? {
        val shell = hub.shell(backend) ?: return null
        return when (PathCheck.check(shell, path, null)) {
            PathKind.Missing -> "${where(hub, backend)}没有 $path 这个目录"
            PathKind.File -> "$path 是文件，不是目录"
            PathKind.Denied -> "${where(hub, backend)}的 $path 没有权限进去"
            else -> null
        }
    }

    private fun where(hub: ConversationHub, backend: String): String =
        if (backend == Backends.LOCAL) "Linux 环境里" else "「${hub.backendName(backend)}」上"
}

object PathCheck {
    /**
     * 参数：$1 对话的托管标识（可以为空），之后是要查的路径；每个路径输出一行 `zeta_path <种类>`，顺序同参数。 有在跑的 Claude Code 进程时经
     * /proc/<pid>/root 看它那个挂载命名空间： 共享文件夹按进入环境时的清单绑定，进程启动后加的它看不到。挂载点目录留在 rootfs 里，进程那边看到的是同名的空目录，
     * 所以按设备号和 inode 比两边是不是同一个东西，只看存在与否会把它当成已经看得到。
     */
    val Script =
        """
        r=
        if [ -n "${'$'}1" ]; then
          p=${'$'}(cat "${'$'}HOME/.cache/${'$'}{ZETA_NAME:-zeta}/hosts/${'$'}1/pid" 2>/dev/null)
          [ -n "${'$'}p" ] && [ -d "/proc/${'$'}p/root/" ] && r=/proc/${'$'}p/root
        fi
        shift
        for a; do
          t=${'$'}r${'$'}a
          if [ -n "${'$'}r" ] && [ -e "${'$'}a" ] && { [ ! -e "${'$'}t" ] || [ "${'$'}(stat -L -c %d:%i "${'$'}a" 2>/dev/null)" != "${'$'}(stat -L -c %d:%i "${'$'}t" 2>/dev/null)" ]; }; then
            if [ -d "${'$'}a" ]; then k=hidden-folder; else k=hidden-file; fi
          elif [ -d "${'$'}t" ]; then k=folder; [ -r "${'$'}t" ] && [ -x "${'$'}t" ] || k=denied
          elif [ -e "${'$'}t" ]; then k=file; [ -r "${'$'}t" ] || k=denied
          else k=missing
          fi
          echo "zeta_path ${'$'}k"
        done
        """
            .trimIndent()

    /** 在 Linux 环境里执行的整条命令。 */
    fun command(paths: List<String>, hostKey: String?): String =
        "sh -c " +
            LinuxEnv.quote(Script) +
            " zeta-path " +
            LinuxEnv.quote(hostKey.orEmpty()) +
            paths.joinToString("") { " " + LinuxEnv.quote(it) }

    /** 脚本的输出，按路径的顺序（登录提示之类的别的行跳过）。 */
    fun parse(stdout: String): List<PathKind> =
        stdout
            .lineSequence()
            .filter { it.startsWith("zeta_path ") }
            .map { line ->
                when (line.removePrefix("zeta_path ").trim()) {
                    "folder" -> PathKind.Folder
                    "file" -> PathKind.File
                    "missing" -> PathKind.Missing
                    "denied" -> PathKind.Denied
                    "hidden-folder" -> PathKind.FolderNotInProcess
                    "hidden-file" -> PathKind.FileNotInProcess
                    else -> PathKind.Unknown
                }
            }
            .toList()

    /**
     * 以 Linux 用户的身份在 [shell] 那台机器上一次查 [paths]，结果顺序同 [paths]；查不成时都是 [PathKind.Unknown]。耗时，别在主线程调用。
     */
    fun checkAll(shell: LinuxShell, paths: List<String>, hostKey: String?): List<PathKind> {
        if (paths.isEmpty()) return emptyList()
        val kinds = runCatching {
            shell.run(command(paths, hostKey), timeoutMs = 20_000, plain = true)
        }
            .getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.let { parse(it.stdout) }
        return kinds?.takeIf { it.size == paths.size } ?: paths.map { PathKind.Unknown }
    }

    /** 以 Linux 用户的身份查 [path]。耗时，别在主线程调用。 */
    fun check(shell: LinuxShell, path: String, hostKey: String?): PathKind =
        checkAll(shell, listOf(path), hostKey).single()
}
