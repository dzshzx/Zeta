package com.dzshzx.zeta.core.claude

import java.util.Base64

/** 脚本输出的一条记录：一行，字段用 NUL 分开，第一个字段是标签。文件内容是 base64（见 [ClaudeScripts.LIB] 的 zf）。 */
class ShellRecord(val fields: List<String>) {
    val tag: String
        get() = fields[0]

    operator fun get(index: Int): String = fields.getOrElse(index) { "" }

    fun long(index: Int): Long = get(index).toDoubleOrNull()?.toLong() ?: -1L

    /** zf 记下的文件：大小为 -1 表示不存在或读不了。 */
    fun file(): ShellFile? {
        val size = long(2)
        if (size < 0) return null
        return ShellFile(
            get(1),
            size,
            long(3),
            runCatching { Base64.getDecoder().decode(get(4)) }.getOrDefault(ByteArray(0)),
        )
    }
}

/** 读到的一个文件：[head] 是开头的若干字节（读整个文件时就是全部内容）。 */
class ShellFile(val path: String, val size: Long, val mtime: Long, val head: ByteArray) {
    val complete: Boolean
        get() = head.size.toLong() >= size

    fun text(): String = head.toString(Charsets.UTF_8)
}

/**
 * 设置页在 Linux 环境里跑的全部壳脚本。都以 Claude Code 所属的普通用户身份运行，参数只放路径和名字， 文件内容、Token 之类一律经标准输入传进去，不出现在命令行上。
 *
 * 输出先打一行 `@zeta`，后面才是记录；登录 shell 的配置文件往标准输出打的字都在这行前面，丢掉。
 */
object ClaudeScripts {
    const val MARKER = "@zeta"

    /** 所有脚本的开头：记录输出的两个小函数。 */
    private val LIB =
        """
        set -u
        C=${'$'}HOME/.claude
        # z 字段…：输出一条记录
        z() { printf '%s\0' "${'$'}@"; printf '\n'; }
        # zf 标签 路径 上限：存在时带大小、修改时间和开头若干字节（base64），否则大小记 -1
        zf() {
          if [ -f "${'$'}2" ] && [ -r "${'$'}2" ]; then
            s=${'$'}(stat -Lc '%s %Y' -- "${'$'}2" 2>/dev/null) || s='0 0'
            printf '%s\0' "${'$'}1" "${'$'}2" "${'$'}{s% *}" "${'$'}{s#* }"
            if [ "${'$'}3" -gt 0 ]; then head -c "${'$'}3" -- "${'$'}2" | base64 -w0; fi
            printf '\n'
          else
            z "${'$'}1" "${'$'}2" -1 0
          fi
        }
        printf '$MARKER\n'
    """
            .trimIndent()

    fun script(body: String): String = LIB + "\n" + body.trimIndent()

    /**
     * 从 cwd 往上找第一个 `.git`；是文件（worktree）时顺着读 gitdir、commondir，交给 [ClaudePaths.canonicalRoot] 判断。 输出
     * R（cwd 的真实路径）和 G（找到的 `.git`）。参数 $1 是 cwd。
     */
    private val GIT_PROBE =
        """
        d=${'$'}(cd -- "${'$'}1" 2>/dev/null && pwd -P) || d=
        z R "${'$'}d"
        p=${'$'}d
        while [ -n "${'$'}p" ]; do
          if [ -d "${'$'}p/.git" ]; then z G "${'$'}p" d; break; fi
          if [ -f "${'$'}p/.git" ]; then
            f=${'$'}(head -c 4096 -- "${'$'}p/.git" 2>/dev/null)
            g=${'$'}{f#gitdir:}; g=${'$'}{g#"${'$'}{g%%[![:space:]]*}"}; g=${'$'}{g%"${'$'}{g##*[![:space:]]}"}
            case ${'$'}g in /*) a=${'$'}g ;; *) a=${'$'}p/${'$'}g ;; esac
            c=-; [ -f "${'$'}a/commondir" ] && c=${'$'}(head -c 4096 -- "${'$'}a/commondir")
            b=-; br=; [ -f "${'$'}a/gitdir" ] && b=${'$'}(head -c 4096 -- "${'$'}a/gitdir") && br=${'$'}(cd -- "${'$'}a" 2>/dev/null && realpath -m -- "${'$'}b")
            cc=${'$'}(printf '%s' "${'$'}c" | head -n1); case ${'$'}cc in /*) u=${'$'}cc ;; *) u=${'$'}a/${'$'}cc ;; esac
            ug=0; [ -e "${'$'}u/.git" ] && ug=1
            z G "${'$'}p" f "${'$'}f" "${'$'}a" "${'$'}c" "${'$'}b" "${'$'}br" "${'$'}ug"
            break
          fi
          [ "${'$'}p" = / ] && break
          p=${'$'}{p%/*}; [ -n "${'$'}p" ] || p=/
        done
        """
            .trimIndent()

    /** 记忆页第一步：设置、CLAUDE.md、cwd 所在的仓库、各项目的记忆目录（条数、修改时间、会话记录里的一个 cwd）。$1 是 cwd。 */
    val MEMORY_SCAN =
        script(
            """
        zf S "${'$'}C/settings.json" 1048576
        zf I "${'$'}C/CLAUDE.md" 0
        """ +
                "\n" +
                GIT_PROBE +
                "\n" +
                """
        for d in "${'$'}C"/projects/*/; do
          [ -d "${'$'}d" ] || continue
          d=${'$'}{d%/}; k=${'$'}{d##*/}
          n=-1; t=0
          if [ -d "${'$'}d/memory" ]; then
            # memory 可能是指向别的项目的符号链接（几个项目共用一份），-H 跟着它走
            n=${'$'}(find -H "${'$'}d/memory" -maxdepth 1 -type f -name '*.md' ! -name MEMORY.md 2>/dev/null | wc -l)
            t=${'$'}(stat -Lc %Y -- "${'$'}d/memory" 2>/dev/null || echo 0)
          fi
          j=${'$'}(ls -t -- "${'$'}d"/*.jsonl 2>/dev/null | head -n1)
          w=; [ -n "${'$'}j" ] && w=${'$'}(head -n 40 -- "${'$'}j" | grep -m1 -o '"cwd":"[^"]*"')
          z P "${'$'}k" "${'$'}n" "${'$'}t" "${'$'}w"
        done
        """,
        )

    /** 一个记忆目录里的文件（每个读开头 4 KiB 取前言）和 MEMORY.md。$1 是目录。 */
    val MEMORY_FILES =
        script(
            """
        zf X "${'$'}1/MEMORY.md" 0
        for f in "${'$'}1"/*.md; do
          [ -f "${'$'}f" ] || continue
          [ "${'$'}{f##*/}" = MEMORY.md ] && continue
          zf M "${'$'}f" 4096
        done
        """,
        )

    /** 读一个文本文件（最多 $2 字节，读多一个字节好知道超没超）。$1 是路径。 */
    val READ_FILE =
        script(
            """
        zf F "${'$'}1" ${'$'}2
        """,
        )

    /**
     * 写文件：标准输入是新内容，先写到同一目录下的临时文件再改名；路径是符号链接时写到它指向的文件上，已有文件的权限照旧。 $1 路径，$2 期望的现有内容的 sha256（`*`
     * 不检查，`-` 表示应当还不存在），$3 写成功后要删掉的旧文件（可空，Subagent 改名用）。 现有内容对不上时输出 CONFLICT，什么都不改。
     */
    val WRITE_FILE =
        script(
            """
        t=${'$'}(readlink -f -- "${'$'}1" 2>/dev/null) || t=${'$'}1
        [ -n "${'$'}t" ] || t=${'$'}1
        dir=${'$'}{t%/*}
        mkdir -p -- "${'$'}dir" || exit 3
        if [ "${'$'}2" != '*' ]; then
          cur=-; [ -e "${'$'}t" ] && cur=${'$'}(sha256sum -- "${'$'}t" | cut -d' ' -f1)
          [ "${'$'}cur" = "${'$'}2" ] || { z CONFLICT; exit 0; }
        fi
        tmp=${'$'}dir/.${'$'}{t##*/}.zeta-${'$'}${'$'}
        trap 'rm -f -- "${'$'}tmp"' EXIT
        cat > "${'$'}tmp" || exit 5
        [ -e "${'$'}t" ] && chmod --reference="${'$'}t" -- "${'$'}tmp" 2>/dev/null
        mv -f -- "${'$'}tmp" "${'$'}t" || exit 6
        if [ -n "${'$'}{3:-}" ] && [ "${'$'}3" != "${'$'}1" ]; then rm -f -- "${'$'}3"; fi
        z OK "${'$'}(sha256sum -- "${'$'}t" | cut -d' ' -f1)"
        """,
        )

    /** 删一个文件（记忆、Subagent）。$1 路径。 */
    val REMOVE_FILE =
        script(
            """
        rm -f -- "${'$'}1" || exit 3
        z OK
        """,
        )

    /** Skills：用户装的每个文件夹（读 SKILL.md 开头 8 KiB）、账号同步的清单、settings.json（skillOverrides）。 */
    val SKILLS =
        script(
            """
        zf S "${'$'}C/settings.json" 1048576
        for d in "${'$'}C"/skills/*/; do
          [ -d "${'$'}d" ] || continue
          d=${'$'}{d%/}; n=${'$'}{d##*/}
          [ "${'$'}n" = synced ] && continue
          l=0; [ -L "${'$'}d" ] && l=1
          z K "${'$'}n" "${'$'}l"
          zf D "${'$'}d/SKILL.md" 8192
        done
        for m in "${'$'}C"/skills/synced/*/manifest.json; do
          [ -f "${'$'}m" ] && zf Y "${'$'}m" 4194304
        done
        """,
        )

    /**
     * 装一个 Skill：标准输入是 tar（应用这边已经从 ZIP 里挑好、查过的文件）。先解到 ~/.claude 下的临时目录，再整个换上去。 $1 名字，$2 为 1
     * 时替换已有的。已有又没让替换时输出 EXISTS 和原来那个的 SKILL.md 开头，不读标准输入。
     */
    val SKILL_INSTALL =
        script(
            """
        dest=${'$'}C/skills/${'$'}1
        if { [ -e "${'$'}dest" ] || [ -L "${'$'}dest" ]; } && [ "${'$'}2" != 1 ]; then
          z EXISTS
          zf D "${'$'}dest/SKILL.md" 8192
          exit 0
        fi
        mkdir -p -- "${'$'}C/skills" || exit 3
        stage=${'$'}(mktemp -d -- "${'$'}C/.zeta-skill-XXXXXX") || exit 3
        old=
        trap 'rm -rf -- "${'$'}stage"; [ -n "${'$'}old" ] && rm -rf -- "${'$'}old"' EXIT
        tar -x --no-same-owner --no-same-permissions -f - -C "${'$'}stage" || exit 4
        replaced=0
        if [ -e "${'$'}dest" ] || [ -L "${'$'}dest" ]; then
          old=${'$'}C/.zeta-skill-old-${'$'}${'$'}
          mv -- "${'$'}dest" "${'$'}old" || exit 5
          replaced=1
        fi
        if ! mv -- "${'$'}stage" "${'$'}dest"; then
          [ -n "${'$'}old" ] && mv -- "${'$'}old" "${'$'}dest" && old=
          exit 6
        fi
        z OK "${'$'}replaced"
        zf S "${'$'}C/settings.json" 1048576
        """,
        )

    /** 删一个 Skill 的文件夹（是链接就只删链接）。$1 名字。 */
    val SKILL_DELETE =
        script(
            """
        p=${'$'}C/skills/${'$'}1
        if [ -L "${'$'}p" ]; then rm -f -- "${'$'}p" || exit 3
        elif [ -d "${'$'}p" ]; then rm -rf -- "${'$'}p" || exit 3
        else z MISSING; exit 0; fi
        z OK
        """,
        )

    /** Subagent：~/.claude/agents 下（含子目录）的每个 .md，整个读进来（最多 1 MiB）。 */
    val AGENTS =
        script(
            """
        [ -d "${'$'}C/agents" ] || exit 0
        find -L "${'$'}C/agents" -type f -name '*.md' 2>/dev/null | sort | while IFS= read -r f; do
          zf A "${'$'}f" 1048577
        done
        """,
        )

    /** MCP：~/.claude.json、cwd 所在的仓库（本地配置按它找）、cwd 往上各级的 .mcp.json。$1 是 cwd。 */
    val MCP_SCAN =
        script(
            """
        zf J "${'$'}HOME/.claude.json" 16777216
        """ +
                "\n" +
                GIT_PROBE +
                "\n" +
                """
        p=${'$'}d
        while [ -n "${'$'}p" ]; do
          [ -f "${'$'}p/.mcp.json" ] && zf M "${'$'}p/.mcp.json" 4194304
          [ "${'$'}p" = / ] && break
          p=${'$'}{p%/*}; [ -n "${'$'}p" ] || p=/
        done
        """,
        )

    /**
     * 在 cwd 里跑 claude（MCP 相关的子命令）。$1 是 cwd，其余是 claude 的参数；标准输入原样交给 claude（add-json 的 `-`）。 最后输出一条
     * EXIT 记录带退出码，claude 自己的输出在它前面。
     */
    val CLAUDE =
        script(
            """
        cd -- "${'$'}1" 2>/dev/null || cd
        shift
        claude "${'$'}@" 2>&1
        z EXIT "${'$'}?"
        """,
        )

    /** 取几个环境变量的值（配置里写了 ${'$'}{VAR} 时要用）：每个输出一条 V 名字 是否设了 值。参数是变量名。 */
    val ENV_VALUES =
        script(
            """
        for v in "${'$'}@"; do
          if [ -n "${'$'}{!v+x}" ]; then z V "${'$'}v" 1 "${'$'}{!v}"; else z V "${'$'}v" 0 ""; fi
        done
        """,
        )

    /**
     * 起一个 stdio 的 MCP 服务器：标准输入开头若干行是 `A base64` 参数（第一个是命令）和 `E base64` 环境变量（KEY=VALUE），空行结束；
     * 之后的标准输入、标准输出就是这个服务器的 JSON-RPC。命令和环境变量里可能有 Token，所以不放命令行。$1 是 cwd。 bash
     * 从管道读一行时逐字节读，不会多吃掉后面给服务器的数据。
     */
    val MCP_STDIO =
        """
        cd -- "${'$'}1" 2>/dev/null || cd
        a=()
        while IFS= read -r l && [ -n "${'$'}l" ]; do
          v=${'$'}(printf '%s' "${'$'}{l#* }" | base64 -d)
          case ${'$'}{l%% *} in A) a+=("${'$'}v") ;; E) export "${'$'}v" ;; esac
        done
        [ ${'$'}{#a[@]} -gt 0 ] || exit 64
        exec "${'$'}{a[@]}"
        """
            .trimIndent()

    /** 解析脚本输出：[MARKER] 那一行之后、带 NUL 的行才是记录。 */
    fun records(stdout: String): List<ShellRecord> {
        val start =
            stdout.indexOf("$MARKER\n").takeIf { it >= 0 }?.let { it + MARKER.length + 1 }
                ?: return emptyList()
        return stdout.substring(start).split('\n').mapNotNull { line ->
            if ('\u0000' !in line) return@mapNotNull null
            ShellRecord(
                line.split('\u0000').let { if (it.last().isEmpty()) it.dropLast(1) else it },
            )
        }
    }

    /** [CLAUDE] 的输出：EXIT 之前的文字和退出码。 */
    fun claudeOutput(stdout: String): Pair<String, Int?> {
        val start =
            stdout.indexOf("$MARKER\n").takeIf { it >= 0 }?.let { it + MARKER.length + 1 }
                ?: return stdout to null
        val body = stdout.substring(start)
        val exit = body.lineSequence().lastOrNull { it.startsWith("EXIT\u0000") }
        val code = exit?.split('\u0000')?.getOrNull(1)?.toIntOrNull()
        val text = if (exit != null) body.substring(0, body.lastIndexOf(exit)) else body
        return text to code
    }

    fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
