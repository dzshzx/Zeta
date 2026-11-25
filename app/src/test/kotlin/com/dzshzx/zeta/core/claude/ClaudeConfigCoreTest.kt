package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.removeTestDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Claude Code 配置」各页经核心读写（[ClaudeConfigCore]，docs/core.md「Claude Code 配置」）：对着临时 HOME 上真的
 * `zeta-helper serve`，假的 claude（helper/claudecfg/testdata/fakeclaude）。读写的细节（保留别的键、前言、ZIP 检查、权限的算法）由
 * helper/claudecfg 的 Go 测试钉住，这里核对的是手机版发的参数、读回来的样子和界面靠着的那几种结局（冲突、已有同名、出错的说明）。
 */
class ClaudeConfigCoreTest {
    private val home: File = Files.createTempDirectory("zeta-config").toFile().canonicalFile
    private val core = LocalConfigCore(home)
    private val client = core.client
    private val settings = File(home, ".claude/settings.json")

    @After
    fun tearDown() {
        core.close()
        removeTestDir(home)
    }

    private fun write(path: String, text: String) =
        File(home, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun defaultsReadAndWriteTheirKeys() {
        assertEquals(ClaudeDefaults(null, null, emptyMap(), null, null), client.defaults())
        write(".claude/settings.json", "{\n  \"env\": {\n    \"A\": \"1\"\n  }\n}\n")
        client.setDefaultModel("claude-opus-5-5")
        val bypass = client.setDefaultPermissionMode("bypassPermissions")
        assertEquals("bypassPermissions", bypass.permissionMode)
        client.setDefaultEffort("claude-opus-5-5", "high")
        client.setDefaultEffort(null, "low")
        val all = client.setCompactWindow(400_000)
        assertEquals(
            ClaudeDefaults(
                "claude-opus-5-5",
                "bypassPermissions",
                mapOf("claude-opus-5-5" to "high"),
                "low",
                400_000,
            ),
            all,
        )
        assertEquals(all, client.defaults())
        // 别的键原样留着
        assertTrue(settings.readText().startsWith("{\n  \"env\": {\n    \"A\": \"1\"\n  },"))

        // 删掉：null 交给核心是空串、0
        client.setDefaultModel(null)
        client.setDefaultEffort("claude-opus-5-5", null)
        val cleared = client.setCompactWindow(null)
        assertEquals(ClaudeDefaults(null, "bypassPermissions", emptyMap(), "low", null), cleared)

        // 核心拒绝的原因原样交给界面
        val small = assertThrows(ServeError::class.java) { client.setCompactWindow(50_000) }
        assertTrue(small.message!!, small.message!!.startsWith("上下文窗口最小是"))
    }

    @Test
    fun memoryDocumentsAndConflicts() {
        val project = File(home, "proj").apply { mkdirs() }
        // 一个用过的项目：会话记录里的 cwd 还原出项目路径
        val key = project.path.replace('/', '-')
        write(".claude/projects/$key/s1.jsonl", "{\"cwd\":\"${project.path}\",\"type\":\"user\"}\n")
        write(
            ".claude/projects/$key/memory/role.md",
            "---\nname: Role\ndescription: who I am\nmetadata:\n  type: user\n---\nI am a tester\n",
        )
        write(
            ".claude/projects/$key/memory/MEMORY.md",
            "- [Role](role.md) — who\n- [Keep](keep.md)\n",
        )

        val overview = client.memory(project.path, null)
        assertTrue(overview.autoMemory)
        assertNull(overview.customDir)
        assertFalse(overview.instructions.exists)
        assertEquals("${home.path}/.claude/CLAUDE.md", overview.instructions.path)
        assertEquals(MemoryProject(key, project.path, 1, true), overview.projects.single())
        assertEquals(key, overview.selected)
        assertTrue(overview.index!!.exists)
        val role = overview.files.single()
        assertEquals(MemoryEntry(role.file, "role.md", "Role", "who I am", "user", role.size), role)

        // 用户指令：原来没有时按空的版本写，之后按读到的版本写；中间被改过就返回 null，什么都不写
        val path = overview.instructions.path
        val empty = client.read(path)
        assertEquals(TextDocument("", "", false), empty)
        val sha = client.write(path, "回答用中文\n", "")
        assertNotNull(sha)
        assertEquals(TextDocument("回答用中文\n", sha!!, true), client.read(path))
        File(path).writeText("Claude 改过了\n")
        assertNull(client.write(path, "我的版本\n", sha))
        assertEquals("Claude 改过了\n", File(path).readText())
        // 仍然保存：不比对，直接覆盖
        assertNotNull(client.write(path, "我的版本\n", null))
        assertEquals("我的版本\n", File(path).readText())
        assertTrue(client.memory(project.path, null).instructions.exists)

        // 只认记忆页上的文件
        val outside = assertThrows(ServeError::class.java) { client.read("${home.path}/.bashrc") }
        assertTrue(outside.message!!.isNotBlank())

        // 删一条记忆，索引里那一行一起去掉
        client.deleteMemory(role.file)
        assertFalse(File(role.file).exists())
        assertEquals(
            "- [Keep](keep.md)\n",
            File(home, ".claude/projects/$key/memory/MEMORY.md").readText(),
        )

        client.setAutoMemory(false)
        assertFalse(client.memory(project.path, null).autoMemory)
        assertEquals("{\n  \"autoMemoryEnabled\": false\n}\n", settings.readText())
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun skillsImportToggleDelete() {
        val skill =
            zip(
                "notes/SKILL.md" to "---\nname: notes\ndescription: 记笔记\n---\n用法\n",
                "notes/a.txt" to "a",
            )
        val installed = client.importSkill("notes.zip", skill, replace = false)
        assertEquals(SkillImport.Installed("notes", replaced = false, enabled = true), installed)
        assertTrue(File(home, ".claude/skills/notes/a.txt").exists())
        // 同名的：先问，确认后替换
        assertEquals(
            SkillImport.Exists("notes", "记笔记"),
            client.importSkill("notes.zip", skill, replace = false),
        )
        assertEquals(
            SkillImport.Installed("notes", replaced = true, enabled = true),
            client.importSkill("notes.zip", skill, replace = true),
        )
        // 不是 Skill 包的：核心说清原因
        val bad =
            assertThrows(ServeError::class.java) {
                client.importSkill("x.zip", zip("readme.txt" to "x"), replace = false)
            }
        assertTrue(bad.message!!.isNotBlank())

        write(
            ".claude/skills/synced/acct/manifest.json",
            """{"skills":[{"name":"docx","description":"Word 文档"}]}""",
        )
        val list = client.skills()
        assertEquals(listOf(SkillInfo("notes", "notes", "记笔记", true, true)), list.skills)
        assertEquals(listOf(SyncedSkill("docx", "Word 文档", true)), list.synced)

        client.setSkillEnabled(list.skills.single(), false)
        assertFalse(client.skills().skills.single().enabled)
        assertTrue(settings.readText().contains("\"notes\": \"off\""))
        client.setSkillEnabled(list.skills.single(), true)
        assertTrue(client.skills().skills.single().enabled)

        client.deleteSkill("notes")
        assertEquals(emptyList<SkillInfo>(), client.skills().skills)
        val gone = assertThrows(ServeError::class.java) { client.deleteSkill("notes") }
        assertEquals("找不到这个 Skill，可能已经被删了", gone.message)
    }

    @Test
    fun subagentsRoundTrip() {
        write(
            ".claude/agents/team/reviewer.md",
            "---\nname: reviewer\ndescription: Old text\ntools: Read, Grep\ncolor: blue\n---\n\nYou review code.\n",
        )
        val original = client.agents().single()
        assertEquals("team/reviewer.md", original.file)
        assertEquals(listOf("Read", "Grep"), original.tools)
        assertEquals(listOf("color"), original.extraKeys)
        assertTrue(original.sha.isNotEmpty())

        // 改名：放在原来的子目录里，旧文件删掉，界面不管的键留着
        val file =
            client.saveAgent(
                original,
                SubagentDraft("checker", "New text", "sonnet", null, "Check things."),
            )
        assertEquals("team/checker.md", file)
        assertFalse(File(home, ".claude/agents/team/reviewer.md").exists())
        val saved = client.agents().single()
        assertEquals("checker", saved.name)
        assertEquals("sonnet", saved.model)
        assertNull(saved.tools)
        assertEquals("Check things.", saved.prompt)
        assertEquals(listOf("color"), saved.extraKeys)

        // 编辑期间文件被改过：核心拒绝
        File(home, ".claude/agents/team/checker.md").appendText("more\n")
        val changed =
            assertThrows(ServeError::class.java) {
                client.saveAgent(saved, SubagentDraft("checker", "x", "", emptyList(), "p"))
            }
        assertTrue(changed.message!!.contains("被改过了"))

        // 新建：名字不对时核心给原因；空的工具清单写成 tools: []
        assertThrows(ServeError::class.java) {
            client.saveAgent(null, SubagentDraft("Bad Name", "x", "", null, ""))
        }
        assertEquals(
            "helper.md",
            client.saveAgent(null, SubagentDraft("helper", "Helps", "", emptyList(), "Help.")),
        )
        assertEquals(emptyList<String>(), client.agents().first { it.name == "helper" }.tools)

        client.deleteAgent("helper.md")
        client.deleteAgent("team/checker.md")
        assertEquals(emptyList<Subagent>(), client.agents())
    }

    @Test
    fun permissionsViewAndEdits() {
        write(
            ".claude/settings.json",
            "{\n  \"permissions\": {\n    \"defaultMode\": \"plan\",\n    \"allow\": [\"Bash(git push:*)\", \"Bash(\"]\n  }\n}\n",
        )
        write(
            ".claude.json",
            """{"mcpServers":{"gh":{"type":"http","url":"https://x.invalid/mcp"}}}""",
        )
        val query = PermissionsQuery(home.path, "phone", listOf("screen", "tap"))

        var view = client.permissions(query)
        // 规则按拒绝、询问、允许排；括号不配对的标出来
        assertEquals(
            listOf(
                PermissionRule(PermissionBehavior.Allow, "Bash(git push:*)"),
                PermissionRule(PermissionBehavior.Allow, "Bash("),
            ),
            view.rules,
        )
        assertEquals(setOf(PermissionRule(PermissionBehavior.Allow, "Bash(")), view.malformed)
        val bash = view.builtin.first { it.name == "Bash" }
        assertEquals("运行命令", bash.label)
        assertEquals(1, bash.state.specific.size)
        assertTrue(view.builtin.any { it.more })
        assertEquals(view.builtin.map { it.name }, view.builtinGroup.tools)
        assertEquals(listOf("gh"), view.servers.map { it.name })
        assertEquals("user", view.servers.single().scope)
        // 查询里给的手机工具 phone：整体和每个工具都算出来，规则名由核心给
        assertEquals("mcp__phone", view.focus!!.rule)
        assertEquals(
            listOf("mcp__phone__screen", "mcp__phone__tap"),
            view.serverTools.map { it.rule },
        )

        view = client.setToolPermission(query, view.focus!!.rule, PermissionBehavior.Ask)
        assertEquals(PermissionBehavior.Ask, view.focus!!.state.behavior)
        val screen = view.serverTools.first { it.name == "screen" }
        assertEquals(PermissionBehavior.Ask, screen.state.behavior)
        assertTrue(screen.state.inherited)
        assertNull(screen.own)
        view = client.setToolPermission(query, screen.rule, PermissionBehavior.Deny)
        assertEquals(PermissionBehavior.Deny, view.serverTools.first { it.name == "screen" }.own)

        val rule = PermissionRule(PermissionBehavior.Ask, "Read(./.env)")
        view = client.addPermissionRule(query, rule)
        assertTrue(rule in view.rules)
        val moved = PermissionRule(PermissionBehavior.Deny, "Read(./.env)")
        view = client.replacePermissionRule(query, rule, moved)
        assertTrue(moved in view.rules && rule !in view.rules)
        view = client.removePermissionRule(query, moved)
        assertFalse(moved in view.rules)
        // 原来那条已经不在了、新的规则写错了：核心给原因
        val stale =
            assertThrows(ServeError::class.java) {
                client.replacePermissionRule(query, moved, rule)
            }
        assertTrue(stale.message!!.contains("已经被改过了"))
        val malformed =
            assertThrows(ServeError::class.java) {
                client.addPermissionRule(query, PermissionRule(PermissionBehavior.Ask, "Bash(x"))
            }
        assertEquals("括号不配对，写成 工具名 或 工具名(参数)", malformed.message)

        // 一键关掉手机工具：只留一条整个服务器的拒绝
        val off = view.focus!!.bulk.disable
        assertFalse(off.isEmpty)
        view = client.setGroupPermission(query, PermissionGroup(server = "phone"), enable = false)
        assertEquals(PermissionBehavior.Deny, view.focus!!.state.behavior)
        assertTrue(view.focus!!.bulk.disable.isEmpty)
        // 一键开启内置工具
        view = client.setGroupPermission(query, view.builtinGroup, enable = true)
        assertTrue(view.builtinBulk.enable.isEmpty)
        assertEquals(PermissionBehavior.Allow, view.builtin.first { it.name == "Bash" }.own)
        // permissions 里别的键原样留着
        assertTrue(settings.readText().contains("\"defaultMode\": \"plan\""))

        // 不带服务器时没有 focus
        assertNull(client.permissions(PermissionsQuery(home.path)).focus)
    }

    @Test
    fun mcpThroughClaudeCli() {
        val claude = File(home, ".local/bin/claude").path
        client.addStdioServer(
            "local-fake",
            claude,
            listOf("mcp-server"),
            mapOf("TOOL_PREFIX" to "x_"),
        )
        client.addHttpServer(
            "remote",
            "http",
            "https://a.example.invalid/mcp?key=SECRET",
            "T0KEN-VALUE",
        )
        val dup =
            assertThrows(ServeError::class.java) {
                client.addHttpServer("remote", "http", "https://b.example.invalid", "")
            }
        assertEquals("已经有一个叫 remote 的服务器了", dup.message)

        val servers = client.mcpServers(home.path)
        assertEquals(listOf("local-fake", "remote"), servers.map { it.name }.sorted())
        val stdio = servers.first { it.name == "local-fake" }
        assertEquals("stdio", stdio.type)
        assertEquals(listOf("mcp-server"), stdio.args)
        assertEquals(listOf("TOOL_PREFIX"), stdio.envKeys)
        val remote = servers.first { it.name == "remote" }
        assertTrue(remote.bearer)
        assertFalse(remote.url.contains("SECRET"))
        assertNull(remote.shadowedBy)

        val status = client.mcpStatus(home.path)
        assertEquals("connected", status.of("user", "remote")?.state)
        assertEquals("claude.ai Gmail", status.connectors.single().name)
        assertEquals("auth", status.connectors.single().state.state)

        // stdio 的经登录 shell 起，名字前缀来自配置里的环境变量
        assertEquals(
            listOf("x_read", "x_write"),
            client.mcpTools("user", "local-fake", home.path).map { it.name },
        )

        client.setMcpToken(remote, home.path, "")
        assertFalse(client.mcpServers(home.path).first { it.name == "remote" }.bearer)
        val noToken =
            assertThrows(ServeError::class.java) {
                client.setMcpToken(stdio, home.path, "x")
            }
        assertEquals("命令行启动的服务器没有 Token", noToken.message)

        client.removeMcp(remote, home.path)
        assertEquals(listOf("local-fake"), client.mcpServers(home.path).map { it.name })
    }

    @Test
    fun mcpNamesFollowClaudeCode() {
        assertEquals("mcp__phone", McpNames.server("phone"))
        assertEquals("mcp__my_server__ping", McpNames.tool("my.server", "ping"))
        assertEquals("mcp__claude_ai_Gmail", McpNames.server("claude.ai Gmail"))
    }
}
