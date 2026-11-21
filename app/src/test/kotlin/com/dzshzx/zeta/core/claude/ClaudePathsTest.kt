package com.dzshzx.zeta.core.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudePathsTest {
    // 期望值由 node 照 Claude Code 2.1.327 的 rN() 算出：replace(/[^a-zA-Z0-9]/g, "-")，超过 200 截断加
    // Math.abs(hash).toString(36)
    @Test
    fun projectKeyMatchesClaudeCode() {
        assertEquals("-workspace-claude", ClaudePaths.projectKey("/workspace/claude"))
        // 汉字一个码元一个 -，emoji 是两个码元，两个 -
        assertEquals("-home-u------x", ClaudePaths.projectKey("/home/u/项目😀/x"))
        val long = ClaudePaths.projectKey("/data/" + "a".repeat(250))
        assertEquals(207, long.length)
        assertEquals("-jdek9i", long.takeLast(7))
        // 哈希是负数时取绝对值
        val negative = ClaudePaths.projectKey("/data/" + "b".repeat(240) + "/z")
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbb-98slzz", negative.takeLast(30))
        val mixed = "/workspace/" + "very-long-directory-name-".repeat(10) + "项目😀"
        assertEquals(
            "-workspace-very-long-directory-name-very-long-directory-name-very-long-directory-name-very-long-directory-name-" +
                "very-long-directory-name-very-long-directory-name-very-long-directory-name-very-long-dire-845x7j",
            ClaudePaths.projectKey(mixed),
        )
        // 正好 200 个字符不加哈希
        val exact = "/" + "c".repeat(199)
        assertEquals(ClaudePaths.sanitize(exact), ClaudePaths.projectKey(exact))
    }

    @Test
    fun resolveIsLexical() {
        assertEquals("/a/c", ClaudePaths.resolve("/a/b", "../c"))
        assertEquals("/x", ClaudePaths.resolve("/a/b", "/x/./"))
        assertEquals("/", ClaudePaths.resolve("/", "../.."))
    }

    @Test
    fun plainRepositoryUsesDotGitDirectory() {
        assertEquals("/w/repo", ClaudePaths.canonicalRoot(GitProbe("/w/repo")))
        assertNull(ClaudePaths.canonicalRoot(GitProbe(null)))
        assertEquals("/w/plain", ClaudePaths.memoryBase("/w/plain", GitProbe(null)))
    }

    @Test
    fun worktreeSharesMainRepository() {
        val probe =
            GitProbe(
                root = "/w/feature",
                dotGitFile = true,
                gitFile = "gitdir: /w/main/.git/worktrees/feature\n",
                gitDir = "/w/main/.git/worktrees/feature",
                commonDir = "../..\n",
                backLink = "/w/feature/.git\n",
                backLinkReal = "/w/feature/.git",
            )
        assertEquals("/w/main", ClaudePaths.canonicalRoot(probe))
        // gitdir 回指的不是自己：不认，当普通目录
        assertEquals(
            "/w/feature",
            ClaudePaths.canonicalRoot(probe.copy(backLinkReal = "/elsewhere/.git")),
        )
        // 没有 commondir（submodule）：用自己
        assertEquals("/w/feature", ClaudePaths.canonicalRoot(probe.copy(commonDir = null)))
        // 内容不是 gitdir:
        assertEquals("/w/feature", ClaudePaths.canonicalRoot(probe.copy(gitFile = "garbage")))
        // 相对路径的 gitdir
        val relative = probe.copy(gitFile = "gitdir: ../main/.git/worktrees/feature")
        assertEquals("/w/main", ClaudePaths.canonicalRoot(relative))
    }

    @Test
    fun bareRepositoryWorktree() {
        val probe =
            GitProbe(
                root = "/w/wt",
                dotGitFile = true,
                gitFile = "gitdir: /w/bare.git/worktrees/wt",
                gitDir = "/w/bare.git/worktrees/wt",
                commonDir = "../..",
                backLink = "/w/wt/.git",
                backLinkReal = "/w/wt/.git",
            )
        assertEquals("/w/bare.git", ClaudePaths.canonicalRoot(probe))
        assertEquals("/w/wt", ClaudePaths.canonicalRoot(probe.copy(commonHasDotGit = true)))
    }

    @Test
    fun labelsComeFromSessionCwdOrItsParents() {
        val key = ClaudePaths.projectKey("/workspace/claude")
        assertEquals(
            "/workspace/claude",
            ClaudePaths.labelFor(key, listOf("/workspace/claude/zeta/app", "/other")),
        )
        assertNull(ClaudePaths.labelFor(key, listOf("/elsewhere")))
    }

    @Test
    fun onlyMemoryFilesAreEditable() {
        val home = "/home/u"
        assertNull(ClaudePaths.editableProblem("/home/u/.claude/CLAUDE.md", home, null))
        assertNull(
            ClaudePaths.editableProblem(
                "/home/u/.claude/projects/-workspace-claude/memory/MEMORY.md",
                home,
                null,
            ),
        )
        assertNull(
            ClaudePaths.editableProblem(
                "/home/u/.claude/projects/-w/memory/user-role.md",
                home,
                null,
            ),
        )
        assertNotNull(ClaudePaths.editableProblem("/home/u/.claude/settings.json", home, null))
        assertNotNull(
            ClaudePaths.editableProblem(
                "/home/u/.claude/projects/-w/memory/../../x.md",
                home,
                null,
            ),
        )
        assertNotNull(
            ClaudePaths.editableProblem("/home/u/.claude/projects/-w/abc.jsonl", home, null),
        )
        assertNotNull(
            ClaudePaths.editableProblem(
                "/home/u/.claude/projects/-w/memory/.hidden.md",
                home,
                null,
            ),
        )
        assertNotNull(
            ClaudePaths.editableProblem("/home/u/.claude/projects/a b/memory/x.md", home, null),
        )
        assertNotNull(ClaudePaths.editableProblem("/etc/x.md", home, null))
        assertNull(ClaudePaths.editableProblem("/srv/mem/x.md", home, "/srv/mem"))
        assertNotNull(ClaudePaths.editableProblem("/srv/mem/sub/x.md", home, "/srv/mem"))
    }

    @Test
    fun customMemoryDirectoryRules() {
        assertEquals("/home/u/notes", ClaudePaths.customMemoryDir("~/notes/", "/home/u"))
        assertEquals("/srv/mem", ClaudePaths.customMemoryDir(" /srv//mem/ ", "/home/u"))
        assertNull(ClaudePaths.customMemoryDir("relative", "/home/u"))
        assertNull(ClaudePaths.customMemoryDir("/", "/home/u"))
        assertNull(ClaudePaths.customMemoryDir("", "/home/u"))
        assertNull(ClaudePaths.customMemoryDir(null, "/home/u"))
    }
}
