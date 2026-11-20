package com.dzshzx.zeta.core.claude

import kotlin.math.abs

/** 在 cwd 往上找到的第一个 `.git`，以及它是 worktree 时顺着读到的几个文件（壳脚本 [ClaudeScripts.GIT_PROBE] 给的原样内容）。 */
data class GitProbe(
    /** `.git` 所在的目录；cwd 不在 git 仓库里时为 null。 */
    val root: String?,
    /** `.git` 是文件（worktree、submodule）。 */
    val dotGitFile: Boolean = false,
    /** `.git` 文件的内容。 */
    val gitFile: String = "",
    /** gitdir 指向的目录（相对路径已接到 root 上，未归一化）。 */
    val gitDir: String = "",
    /** gitDir/commondir 的内容；没有这个文件是 null。 */
    val commonDir: String? = null,
    /** gitDir/gitdir 的内容；没有这个文件是 null。 */
    val backLink: String? = null,
    /** gitDir/gitdir 指的路径，解析过符号链接。 */
    val backLinkReal: String = "",
    /** 共用的 git 目录下面是否还有 `.git`（判断裸仓库用）。 */
    val commonHasDotGit: Boolean = false,
)

/**
 * Claude Code 2.1.327 给项目算目录的办法（从它的程序里读出来的，见各函数的说明）。
 * 会话记录在 ~/.claude/projects/<key>/，自动记忆在 ~/.claude/projects/<key>/memory/。
 */
object ClaudePaths {
    /** 目录名超过这个长度就截断再加哈希。 */
    const val MAX_KEY = 200

    /** 非字母数字一律换成 -（按 UTF-16 码元逐个换，与 JS 的 `replace(/[^a-zA-Z0-9]/g, "-")` 一致）。 */
    fun sanitize(path: String): String = buildString(path.length) {
        for (c in path) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') c else '-')
    }

    /**
     * 项目目录名：先 [sanitize]；超过 200 个字符时取前 200 个，再接 `-` 和整条路径的哈希（36 进制）。
     * 哈希是 `(h << 5) - h + charCode | 0` 逐个码元累加，正是 Java 的 String.hashCode；取绝对值时按 JS 的数值算，不会溢出。
     */
    fun projectKey(path: String): String {
        val sanitized = sanitize(path)
        if (sanitized.length <= MAX_KEY) return sanitized
        return sanitized.take(MAX_KEY) + "-" + abs(path.hashCode().toLong()).toString(36)
    }

    /** 词法归一化成绝对路径（`.`、`..`、重复的 /），不碰文件系统；相对路径接在 [base] 上。 */
    fun resolve(base: String, path: String): String {
        val full = if (path.startsWith("/")) path else "$base/$path"
        val segments = ArrayList<String>()
        for (segment in full.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return "/" + segments.joinToString("/")
    }

    fun parent(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    fun baseName(path: String): String = path.substringAfterLast('/')

    /**
     * 同一个仓库的各个 worktree 共用一份自动记忆：`.git` 是文件时顺着 gitdir → commondir 找到主仓库。
     * 照 Claude Code 的做法逐项核对，任何一项对不上就当普通目录，用 `.git` 所在的目录。
     */
    fun canonicalRoot(probe: GitProbe): String? {
        val root = probe.root ?: return null
        if (!probe.dotGitFile) return root
        val content = probe.gitFile.trim()
        if (!content.startsWith("gitdir:")) return root
        val gitDir = resolve(root, content.removePrefix("gitdir:").trim())
        val common = probe.commonDir?.trim() ?: return root
        val commonDir = resolve(gitDir, common)
        if (parent(gitDir) != resolve(commonDir, "worktrees")) return root
        probe.backLink ?: return root
        if (probe.backLinkReal != resolve(root, ".git")) return root
        if (baseName(commonDir) != ".git") return if (probe.commonHasDotGit) root else commonDir
        return parent(commonDir)
    }

    /** 自动记忆的项目：git 仓库按主工作区（worktree、子目录共用一份），不在仓库里按 cwd 本身。 */
    fun memoryBase(cwd: String, probe: GitProbe): String = canonicalRoot(probe) ?: cwd

    /**
     * 列表里给项目目录找回原来的路径：目录名把 / 换成了 -，还原不了，只能拿会话记录里的 cwd 去对。
     * cwd 或它的某一级上层算出来正好是 [key] 就用它（会话常在仓库的子目录里开）；都对不上返回 null。
     */
    fun labelFor(key: String, cwds: Collection<String>): String? {
        for (cwd in cwds) {
            var path = cwd
            while (true) {
                if (projectKey(path) == key) return path
                if (path == "/" || !path.startsWith("/")) break
                path = parent(path)
            }
        }
        return null
    }

    private val KeyName = Regex("[A-Za-z0-9-]+")

    /** 记忆目录里的一个文件名：不带路径、不是隐藏文件、以 .md 结尾。 */
    fun isMemoryFileName(name: String): Boolean =
        name.endsWith(".md") && name.length > 3 && '/' !in name && !name.startsWith(".") && name.none { it < ' ' }

    /**
     * 设置页能读写的文件只有这些：~/.claude/CLAUDE.md，各项目 memory 目录里的 .md，以及设了 autoMemoryDirectory 时那个目录里的 .md。
     * 返回 null 表示可以，否则是原因。
     */
    fun editableProblem(path: String, home: String, customDir: String?): String? {
        if (path != resolve("/", path)) return "路径不对"
        if (path == instructions(home)) return null
        val name = baseName(path)
        if (!isMemoryFileName(name)) return "只能编辑记忆目录里的 .md 文件"
        val dir = parent(path)
        if (customDir != null && dir == customDir) return null
        val projects = projectsDir(home) + "/"
        if (dir.startsWith(projects) && dir.endsWith("/memory")) {
            val key = dir.removePrefix(projects).removeSuffix("/memory")
            if (KeyName.matches(key)) return null
        }
        return "不能编辑这个文件"
    }

    fun claudeDir(home: String) = resolve(home, ".claude")

    fun instructions(home: String) = claudeDir(home) + "/CLAUDE.md"

    fun settings(home: String) = claudeDir(home) + "/settings.json"

    fun projectsDir(home: String) = claudeDir(home) + "/projects"

    fun memoryDir(home: String, key: String) = projectsDir(home) + "/" + key + "/memory"

    fun agentsDir(home: String) = claudeDir(home) + "/agents"

    /**
     * settings.json 里的 autoMemoryDirectory：`~/` 开头的接到家目录上，去掉末尾的 /；
     * 不是绝对路径、太短或是根目录的，Claude Code 不认，这里也当没设。
     */
    fun customMemoryDir(value: String?, home: String): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expanded = when {
            raw == "~" -> return null
            raw.startsWith("~/") -> resolve(home, raw.removePrefix("~/"))
            raw.startsWith("/") -> resolve("/", raw)
            else -> return null
        }
        return expanded.takeIf { it.length >= 3 && '\u0000' !in it }
    }
}
