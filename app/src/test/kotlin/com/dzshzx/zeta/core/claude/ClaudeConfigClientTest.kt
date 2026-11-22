package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.LinuxShell
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端：真的 [ClaudeConfigClient]、真的壳脚本，跑在一个临时 HOME 上，不碰任何人真实的 ~/.claude。 [LocalClientTest] 在跑测试的机器上用
 * bash；[PhoneClientTest] 设了 ZETA_TEST_SSH（手机 Debian 的 ssh 别名）时经 ssh 在手机的 Debian 里跑同一套。
 */
abstract class ClaudeConfigClientTest {
    protected abstract fun shell(): TestShell

    protected lateinit var sh: TestShell
    protected lateinit var client: ClaudeConfigClient
    protected val home
        get() = sh.home

    @Before
    fun setUp() {
        sh = shell()
        client = ClaudeConfigClient { sh }
    }

    @After
    fun tearDown() {
        if (::sh.isInitialized) sh.cleanup()
    }

    /** 在临时 HOME 里跑一段准备或检查用的命令，返回标准输出。 */
    protected fun run(script: String, stdin: ByteArray? = null): String {
        val process = sh.start(script)
        process.outputStream.use { out -> stdin?.let { out.write(it) } }
        val err = MCPDrain(process.errorStream)
        val out = process.inputStream.bufferedReader().readText()
        assertTrue("命令超时：$script", process.waitFor(60, TimeUnit.SECONDS))
        assertEquals("命令失败：$script\n${err.text()}", 0, process.exitValue())
        return out
    }

    private class MCPDrain(stream: java.io.InputStream) {
        private val text = StringBuilder()
        private val t = Thread {
            runCatching { text.append(stream.bufferedReader().readText()) }
        }
            .apply { start() }

        fun text(): String {
            t.join(2_000)
            return text.toString()
        }
    }

    private val git =
        "GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 git -c user.email=t@example.com -c user.name=t -c init.defaultBranch=main"

    @Test
    fun memoryFollowsGitAndClaudeCodeKeys() {
        run(
            """
            set -e
            mkdir -p "${'$'}HOME/repo/sub"
            cd "${'$'}HOME/repo" && $git init -q && $git commit -q --allow-empty -m init && $git worktree add -q "${'$'}HOME/wt" -b feature
            """
                .trimIndent(),
        )
        val repo = run("cd \"\$HOME/repo\" && pwd -P").trim()
        val key = ClaudePaths.projectKey(repo)
        val dir = ClaudePaths.memoryDir(home, key)
        run(
            """
            set -e
            mkdir -p '$dir' "${'$'}HOME/.claude/projects/-other-proj/memory"
            printf -- '---\nname: Role\ndescription: who I am\nmetadata:\n  type: user\n---\nI am a tester\n' > '$dir/user-role.md'
            printf -- '- [Role](user-role.md) — who\n- [Keep](keep.md)\n' > '$dir/MEMORY.md'
            printf 'k\n' > '$dir/keep.md'
            printf 'x\n' > "${'$'}HOME/.claude/projects/-other-proj/memory/a.md"
            mkdir -p "${'$'}HOME/.claude/projects/-linked" && ln -s ../-other-proj/memory "${'$'}HOME/.claude/projects/-linked/memory"
            printf '{"cwd":"/other/proj","type":"user"}\n' > "${'$'}HOME/.claude/projects/-other-proj/s1.jsonl"
            printf '{"model":"opus","autoMemoryEnabled":true}\n' > "${'$'}HOME/.claude/settings.json"
            """
                .trimIndent(),
        )
        // 子目录和 worktree 都落到主仓库那一份
        for (cwd in listOf("$home/repo/sub", "$home/wt")) {
            val overview = client.memory(cwd, null)
            assertEquals(cwd, key, overview.selected)
            assertEquals(MemoryProject(key, repo, 2, true), overview.projects.first())
        }
        val overview = client.memory("$home/repo", null)
        // memory 是符号链接的项目也照样数得出条数
        assertEquals(
            setOf("-other-proj", "-linked"),
            overview.projects.drop(1).map { it.key }.toSet(),
        )
        assertEquals(listOf(1, 1), overview.projects.drop(1).map { it.count })
        assertEquals("/other/proj", overview.projects.first { it.key == "-other-proj" }.label)
        assertEquals(listOf("keep.md", "user-role.md"), overview.files.map { it.fileName })
        val role = overview.files.first { it.fileName == "user-role.md" }
        assertEquals("Role", role.name)
        assertEquals("user", role.type)
        assertTrue(overview.index!!.exists)
        assertFalse(overview.instructions.exists)
        assertEquals("$dir/MEMORY.md", overview.index!!.path)
        assertEquals("-other-proj", client.memory("$home/repo", "-other-proj").selected)

        // 删一条记忆：文件和索引里的那一行都去掉
        client.deleteMemory(role.file)
        assertEquals("- [Keep](keep.md)\n", run("cat '$dir/MEMORY.md'"))
        assertEquals("gone", run("[ -e '$dir/user-role.md' ] && echo there || echo gone").trim())

        // 开关自动记忆：只改这一个键
        client.setAutoMemory(false)
        assertEquals(
            "{\n  \"model\": \"opus\",\n  \"autoMemoryEnabled\": false\n}\n",
            run("cat \"\$HOME/.claude/settings.json\""),
        )
        assertFalse(client.memory("$home/repo", null).autoMemory)

        // 设了 autoMemoryDirectory 以后所有项目共用一个目录
        run("printf '{\"autoMemoryDirectory\":\"~/mem\"}' > \"\$HOME/.claude/settings.json\"")
        val custom = client.memory("$home/repo", null)
        assertEquals("$home/mem", custom.dir)
        assertTrue(custom.projects.isEmpty())
        assertNotNull(client.write("$home/mem/new.md", "hello\n", null))
        assertEquals("hello\n", run("cat \"\$HOME/mem/new.md\""))

        // 不在记忆目录里的文件不让动
        try {
            client.read("$home/.claude/settings.json")
            fail()
        } catch (expected: ClaudeConfigException) {}
    }

    @Test
    fun instructionsWriteDetectsConflicts() {
        val path = ClaudePaths.instructions(home)
        val empty = client.read(path)
        assertFalse(empty.exists)
        val sha = client.write(path, "first\n", empty.sha.takeIf { empty.exists }.orEmpty())
        assertNotNull(sha)
        // 新建时要求文件还不存在
        assertNull(client.write(path, "again\n", ""))
        val doc = client.read(path)
        assertEquals("first\n", doc.content)
        assertEquals(sha, doc.sha)
        // 别人在中间改过
        run("printf 'changed by claude\\n' >> '$path'")
        assertNull(client.write(path, "mine\n", doc.sha))
        assertEquals("first\nchanged by claude\n", client.read(path).content)
        // 强制覆盖；符号链接写到它指的文件上，权限照旧
        run(
            "mv '$path' \"\$HOME/real.md\" && chmod 600 \"\$HOME/real.md\" && ln -s \"\$HOME/real.md\" '$path'",
        )
        assertNotNull(client.write(path, "forced 中文\n", null))
        assertEquals("forced 中文\n", run("cat \"\$HOME/real.md\""))
        assertEquals(
            "link 600",
            run("[ -L '$path' ] && printf link; printf ' '; stat -c %a \"\$HOME/real.md\"").trim(),
        )
    }

    @Test
    fun skillsImportToggleDelete() {
        val skillMd = "---\nname: pdf-tools\ndescription: Work with PDFs\n---\n# PDF\n"
        val zip =
            zipOf(
                ZipItem("pdf-tools/SKILL.md", skillMd.toByteArray()),
                ZipItem(
                    "pdf-tools/bin/run.sh",
                    "#!/bin/sh\necho ok\n".toByteArray(),
                    mode = 0x81ED,
                ),
                ZipItem("pdf-tools/docs/", ByteArray(0)),
            )
        run(
            """
            mkdir -p "${'$'}HOME/.claude/skills/synced/acc" "${'$'}HOME/.claude/skills/broken"
            printf '{"skills":[{"name":"docx","description":"Word files"}]}' > "${'$'}HOME/.claude/skills/synced/acc/manifest.json"
            printf '{"theme":"dark"}' > "${'$'}HOME/.claude/settings.json"
            """
                .trimIndent(),
        )
        val installed = client.importSkill("pdf.zip", zip, replace = false)
        assertEquals(
            SkillImport.Installed("pdf-tools", replaced = false, enabled = true),
            installed,
        )
        assertEquals("ok", run("\"\$HOME/.claude/skills/pdf-tools/bin/run.sh\"").trim())
        assertEquals(
            "dir",
            run("[ -d \"\$HOME/.claude/skills/pdf-tools/docs\" ] && echo dir").trim(),
        )
        assertEquals("", run("ls -A \"\$HOME/.claude\" | grep zeta-skill || true").trim())

        assertEquals(
            SkillImport.Exists("pdf-tools", "Work with PDFs"),
            client.importSkill("pdf.zip", zip, replace = false),
        )
        val newer =
            zipOf(ZipItem("SKILL.md", skillMd.replace("Work with PDFs", "Newer").toByteArray()))
        assertEquals(
            SkillImport.Installed("pdf-tools", replaced = true, enabled = true),
            client.importSkill("pdf.zip", newer, replace = true),
        )
        assertEquals(
            "gone",
            run("[ -e \"\$HOME/.claude/skills/pdf-tools/bin\" ] && echo there || echo gone").trim(),
        )

        var list = client.skills()
        assertEquals(listOf("broken", "pdf-tools"), list.skills.map { it.name })
        assertFalse(list.skills[0].valid)
        assertEquals(
            SkillInfo("pdf-tools", "pdf-tools", "Newer", enabled = true, valid = true),
            list.skills[1],
        )
        assertEquals(listOf(SyncedSkill("docx", "Word files", true)), list.synced)

        client.setSkillEnabled(list.skills[1], enabled = false)
        assertEquals(
            "{\n  \"theme\": \"dark\",\n  \"skillOverrides\": {\n    \"pdf-tools\": \"off\"\n  }\n}\n",
            run("cat \"\$HOME/.claude/settings.json\""),
        )
        list = client.skills()
        assertFalse(list.skills[1].enabled)
        assertEquals(
            SkillImport.Exists("pdf-tools", "Newer"),
            client.importSkill("pdf.zip", newer, replace = false),
        )
        assertEquals(
            SkillImport.Installed("pdf-tools", replaced = true, enabled = false),
            client.importSkill("pdf.zip", newer, replace = true),
        )
        client.setSkillEnabled(list.skills[1], enabled = true)
        assertTrue(client.skills().skills[1].enabled)

        client.setSkillEnabled(list.skills[1], enabled = false)
        client.deleteSkill("pdf-tools")
        assertEquals(listOf("broken"), client.skills().skills.map { it.name })
        assertEquals(
            "{\n  \"theme\": \"dark\",\n  \"skillOverrides\": {}\n}\n",
            run("cat \"\$HOME/.claude/settings.json\""),
        )
        try {
            client.deleteSkill("../etc")
            fail()
        } catch (expected: ClaudeConfigException) {}
    }

    @Test
    fun subagentsRoundTrip() {
        assertEquals(emptyList<Subagent>(), client.agents())
        val file =
            client.saveAgent(
                null,
                SubagentDraft(
                    "reviewer",
                    "Review: after edits",
                    "",
                    listOf("Read", "Grep"),
                    "You review.",
                ),
            )
        assertEquals("reviewer.md", file)
        run(
            """
            mkdir -p "${'$'}HOME/.claude/agents/team"
            printf -- '---\nname: helper\ndescription: helps\ncolor: blue\npermissionMode: plan\n---\nBe helpful.\n' > "${'$'}HOME/.claude/agents/team/helper.md"
            """
                .trimIndent(),
        )
        val agents = client.agents()
        assertEquals(listOf("reviewer.md", "team/helper.md"), agents.map { it.file })
        assertEquals("Review: after edits", agents[0].description)
        assertEquals(listOf("Read", "Grep"), agents[0].tools)
        val helper = agents[1]
        assertEquals(listOf("color", "permissionMode"), helper.extraKeys)

        // 改名：留在原来的子目录里，旧文件删掉，不认识的键留着
        val renamed =
            client.saveAgent(
                helper,
                SubagentDraft("assistant", "helps more", "haiku", null, "Be very helpful."),
            )
        assertEquals("team/assistant.md", renamed)
        assertEquals(
            "---\nname: assistant\ndescription: helps more\nmodel: haiku\ncolor: blue\npermissionMode: plan\n---\n\nBe very helpful.\n",
            run("cat \"\$HOME/.claude/agents/team/assistant.md\""),
        )
        assertEquals(
            "gone",
            run("[ -e \"\$HOME/.claude/agents/team/helper.md\" ] && echo there || echo gone")
                .trim(),
        )

        // 新建时不能和已有的重名
        try {
            client.saveAgent(null, SubagentDraft("reviewer", "x", "", null, "")).let {
                fail("存成了 $it")
            }
        } catch (expected: ClaudeConfigException) {}
        val reviewer = client.agents().first { it.name == "reviewer" }
        // 编辑期间文件被改过
        run("printf 'more\\n' >> \"\$HOME/.claude/agents/reviewer.md\"")
        try {
            client.saveAgent(reviewer, SubagentDraft("reviewer", "x", "", null, ""))
            fail()
        } catch (expected: ClaudeConfigException) {}
        client.deleteAgent("team/assistant.md")
        client.deleteAgent("reviewer.md")
        assertEquals(emptyList<Subagent>(), client.agents())
        try {
            client.deleteAgent("../settings.json")
            fail()
        } catch (expected: ClaudeConfigException) {}
    }

    /** 设置页的默认模型、默认权限模式：只改 model 和 permissions.defaultMode，别的键和顺序不动；按模型存的思考强度原样读出来。 */
    @Test
    fun defaultsEditOnlyTheirKeys() {
        val settings = "\$HOME/.claude/settings.json"
        assertEquals(ClaudeDefaults(null, null, emptyMap(), null), client.defaults())
        run(
            """
            mkdir -p "${'$'}HOME/.claude"
            printf '%s\n' '{"theme":"dark","permissions":{"allow":["Bash"],"defaultMode":"default"},"modelSettings":{"claude-opus-5-5":{"effortLevel":"xhigh"},"claude-fable-5-2":{"effortLevel":"high","maxEffortLevel":"max"}}}' > "$settings"
            """
                .trimIndent(),
        )
        assertEquals(
            ClaudeDefaults(
                null,
                "default",
                mapOf("claude-opus-5-5" to "xhigh", "claude-fable-5-2" to "high"),
                null,
            ),
            client.defaults(),
        )
        assertEquals("opus", client.setDefaultModel("opus").model)
        assertEquals("auto", client.setDefaultPermissionMode("auto").permissionMode)
        assertEquals(
            """
            {
              "theme": "dark",
              "permissions": {
                "allow": [
                  "Bash"
                ],
                "defaultMode": "auto"
              },
              "modelSettings": {
                "claude-opus-5-5": {
                  "effortLevel": "xhigh"
                },
                "claude-fable-5-2": {
                  "effortLevel": "high",
                  "maxEffortLevel": "max"
                }
              },
              "model": "opus"
            }
            """
                .trimIndent() + "\n",
            run("cat \"$settings\""),
        )
        // 跟随 Claude Code 内置的默认：删掉 model
        assertNull(client.setDefaultModel(null).model)
        assertFalse(run("cat \"$settings\"").contains("\"model\""))
        // 完全放行不能设成默认，文件不动
        val before = run("cat \"$settings\"")
        assertTrue(
            runCatching { client.setDefaultPermissionMode("bypassPermissions") }.exceptionOrNull()
                is ClaudeConfigException,
        )
        assertEquals(before, run("cat \"$settings\""))
        // 上下文窗口：autoCompactWindow 写成数字；太小的不写；跟随是删掉
        assertNull(client.defaults().compactWindow)
        assertEquals(500_000L, client.setCompactWindow(500_000).compactWindow)
        assertTrue(run("cat \"$settings\"").contains("\"autoCompactWindow\": 500000\n"))
        assertTrue(
            runCatching { client.setCompactWindow(50_000) }.exceptionOrNull()
                is ClaudeConfigException,
        )
        assertEquals(500_000L, client.defaults().compactWindow)
        assertNull(client.setCompactWindow(null).compactWindow)
        assertEquals(before, run("cat \"$settings\""))
        // 没有 permissions 时建出来
        run("printf '{}' > \"$settings\"")
        client.setDefaultPermissionMode("plan")
        assertEquals(
            "{\n  \"permissions\": {\n    \"defaultMode\": \"plan\"\n  }\n}\n",
            run("cat \"$settings\""),
        )
    }

    /**
     * 默认思考强度：按模型改 modelSettings.<完整 ID>.effortLevel，和 /effort 存为默认是同一个位置；同一模型下别的键、别的模型不动，
     * 删空了连这个模型一起删。不带模型时改顶层的 effortLevel。
     */
    @Test
    fun defaultEffortEditsOneModel() {
        val settings = "\$HOME/.claude/settings.json"
        run(
            """
            mkdir -p "${'$'}HOME/.claude"
            printf '%s\n' '{"model":"opus","modelSettings":{"claude-opus-5-5":{"effortLevel":"xhigh"},"claude-fable-5-2":{"effortLevel":"high","maxEffortLevel":"max"}}}' > "$settings"
            """
                .trimIndent(),
        )
        val sonnet = client.setDefaultEffort("claude-sonnet-5-5", "low")
        assertEquals(
            mapOf(
                "claude-opus-5-5" to "xhigh",
                "claude-fable-5-2" to "high",
                "claude-sonnet-5-5" to "low",
            ),
            sonnet.efforts,
        )
        assertEquals(
            "medium",
            client.setDefaultEffort("claude-fable-5-2", "medium").efforts["claude-fable-5-2"],
        )
        // 跟随 Claude Code：删掉这个模型的 effortLevel；opus 下没别的键了，整项删掉
        assertNull(client.setDefaultEffort("claude-opus-5-5", null).efforts["claude-opus-5-5"])
        assertEquals("high", client.setDefaultEffort(null, "high").effort)
        assertEquals(
            """
            {
              "model": "opus",
              "modelSettings": {
                "claude-fable-5-2": {
                  "effortLevel": "medium",
                  "maxEffortLevel": "max"
                },
                "claude-sonnet-5-5": {
                  "effortLevel": "low"
                }
              },
              "effortLevel": "high"
            }
            """
                .trimIndent() + "\n",
            run("cat \"$settings\""),
        )
        // 只剩别的键的模型留着，删空了的模型和空的 modelSettings 都不留
        client.setDefaultEffort("claude-fable-5-2", null)
        client.setDefaultEffort("claude-sonnet-5-5", null)
        assertEquals(
            "{\n  \"model\": \"opus\",\n  \"modelSettings\": {\n    \"claude-fable-5-2\": {\n      \"maxEffortLevel\": \"max\"\n    }\n  },\n  \"effortLevel\": \"high\"\n}\n",
            run("cat \"$settings\""),
        )
        run(
            "printf '{\"modelSettings\":{\"claude-opus-5-5\":{\"effortLevel\":\"low\"}}}' > \"$settings\"",
        )
        client.setDefaultEffort("claude-opus-5-5", null)
        assertEquals("{}\n", run("cat \"$settings\""))
        // modelSettings 不是对象：不写，报原因
        run("printf '{\"modelSettings\":3}' > \"$settings\"")
        assertTrue(
            runCatching { client.setDefaultEffort("claude-opus-5-5", "low") }.exceptionOrNull()
                is ClaudeConfigException,
        )
        assertEquals("{\"modelSettings\":3}", run("cat \"$settings\""))
    }

    @Test
    fun permissionsEditOnlyTheirRules() {
        val settings = "\$HOME/.claude/settings.json"
        assertEquals(PermissionRules.Empty, client.permissions())
        // 不存在时加一条：建出 permissions
        client.setToolPermission("WebSearch", PermissionBehavior.Ask)
        assertEquals(
            "{\n  \"permissions\": {\n    \"ask\": [\n      \"WebSearch\"\n    ]\n  }\n}\n",
            run("cat \"$settings\""),
        )
        run(
            """
            printf '%s\n' '{"model":"opus","permissions":{"defaultMode":"bypassPermissions","allow":["Bash(git status:*)",5,"Bash"],"deny":["Bash(rm:*"]},"env":{"A":"1"}}' > "$settings"
            """
                .trimIndent(),
        )
        val before = client.permissions()
        assertEquals(
            PermissionRules(listOf("Bash(git status:*)", "Bash"), emptyList(), listOf("Bash(rm:*")),
            before,
        )

        // 整个 Bash 设成拒绝：允许里的 Bash 去掉，带参数的、认不出的、非字符串的、defaultMode 和别的键都不动
        val denied = client.setToolPermission("Bash", PermissionBehavior.Deny)
        assertEquals(PermissionBehavior.Deny, ClaudePermissions.tool(denied, "Bash").behavior)
        assertEquals(
            """
            {
              "model": "opus",
              "permissions": {
                "defaultMode": "bypassPermissions",
                "allow": [
                  "Bash(git status:*)",
                  5
                ],
                "deny": [
                  "Bash(rm:*",
                  "Bash"
                ]
              },
              "env": {
                "A": "1"
              }
            }

            """
                .trimIndent(),
            run("cat \"$settings\""),
        )

        client.addPermissionRule(PermissionBehavior.Ask, "mcp__phone__shell")
        val moved =
            client.replacePermissionRule(
                PermissionBehavior.Allow,
                "Bash(git status:*)",
                PermissionBehavior.Ask,
                "Bash(git status:*)",
            )
        assertEquals(
            PermissionRules(
                emptyList(),
                listOf("mcp__phone__shell", "Bash(git status:*)"),
                listOf("Bash(rm:*", "Bash"),
            ),
            moved,
        )
        assertEquals(moved, client.permissions())

        // 别人在中间改了文件：改的是新内容，别人加的留着
        run(
            "jq -c '.permissions.deny += [\"Read(./.env)\"] | .theme = \"dark\"' \"$settings\" > \"$settings.new\" && mv \"$settings.new\" \"$settings\"",
        )
        val removed = client.removePermissionRule(PermissionBehavior.Deny, "Bash(rm:*")
        assertEquals(listOf("Bash", "Read(./.env)"), removed.deny)
        assertTrue(run("cat \"$settings\"").contains("\"theme\": \"dark\""))
        // 要改的那条已经被别人删了
        try {
            client.replacePermissionRule(
                PermissionBehavior.Deny,
                "Bash(rm:*",
                PermissionBehavior.Ask,
                "Bash(rm:*)",
            )
            fail()
        } catch (expected: ClaudeConfigException) {}
        try {
            client.addPermissionRule(PermissionBehavior.Ask, "Bash(ls")
            fail()
        } catch (expected: ClaudeConfigException) {}

        // 文件坏了：读、改都报错，不碰它
        run("printf '{oops' > \"$settings\"")
        for (action in
            listOf({ client.permissions() }, { client.setToolPermission("Bash", null) })) {
            try {
                action()
                fail()
            } catch (expected: ClaudeConfigException) {}
        }
        assertEquals("{oops", run("cat \"$settings\""))
    }

    @Test
    fun bulkPermissionsRewriteTheGroup() {
        val settings = "\$HOME/.claude/settings.json"
        run(
            """
            mkdir -p "${'$'}HOME/.claude"
            printf '%s\n' '{"model":"opus","permissions":{"defaultMode":"bypassPermissions","allow":["mcp__phone__screen","Read"],"ask":["Bash(git push:*)","mcp__phone__shell"],"deny":["Bash(rm:*","WebFetch"]}}' > "$settings"
            """
                .trimIndent(),
        )
        // 关掉手机工具：一条整个服务器的拒绝，手机工具上的允许、询问去掉
        val off = client.setGroupPermission(PermissionGroup.Server("phone"), enable = false)
        assertEquals(
            PermissionRules(
                listOf("Read"),
                listOf("Bash(git push:*)"),
                listOf("Bash(rm:*", "WebFetch", "mcp__phone"),
            ),
            off,
        )
        // 开启内置工具：逐个写允许，内置工具上的拒绝、询问去掉，认不出的 Bash(rm:* 留着
        val tools = listOf("Bash", "Read", "WebFetch")
        val on = client.setGroupPermission(PermissionGroup.Tools(tools), enable = true)
        assertEquals(
            PermissionRules(
                listOf("Read", "Bash", "WebFetch"),
                emptyList(),
                listOf("Bash(rm:*", "mcp__phone"),
            ),
            on,
        )
        assertEquals(
            """
            {
              "model": "opus",
              "permissions": {
                "defaultMode": "bypassPermissions",
                "allow": [
                  "Read",
                  "Bash",
                  "WebFetch"
                ],
                "ask": [],
                "deny": [
                  "Bash(rm:*",
                  "mcp__phone"
                ]
              }
            }

            """
                .trimIndent(),
            run("cat \"$settings\""),
        )
        // 已经全开：不写文件
        run("touch -d '2001-01-01' \"$settings\"")
        assertEquals(on, client.setGroupPermission(PermissionGroup.Tools(tools), enable = true))
        assertEquals("2001", run("date -r \"$settings\" +%Y").trim())
    }

    /** 需要 claude 命令行：没有就跳过。 */
    protected fun assumeClaude() {
        assumeTrue(
            "没有 claude 命令",
            run("command -v claude >/dev/null && echo yes || echo no").trim() == "yes",
        )
    }

    @Test
    fun mcpThroughClaudeCli() {
        assumeClaude()
        val cwd = "$home/proj"
        run(
            """
            set -e
            mkdir -p "${'$'}HOME/proj"
            cat > "${'$'}HOME/fake-mcp.sh" <<'EOF'
            #!/bin/bash
            while IFS= read -r line; do
              id=${'$'}(printf '%s' "${'$'}line" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
              [ -n "${'$'}id" ] || continue
              case ${'$'}line in
                *'"initialize"'*) printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}}\n' "${'$'}id" ;;
                *tools/list*) printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"whoami","description":"%s %s","inputSchema":{"type":"object"}}]}}\n' "${'$'}id" "${'$'}FAKE_GREETING" "${'$'}1" ;;
                *) printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "${'$'}id" ;;
              esac
            done
            EOF
            chmod +x "${'$'}HOME/fake-mcp.sh"
            """
                .trimIndent(),
        )
        client.addHttpServer(
            "remote",
            "http",
            "http://127.0.0.1:9/mcp?key=QUERYSECRET",
            "TOKENSECRET",
        )
        client.addStdioServer(
            "local-fake",
            "$home/fake-mcp.sh",
            listOf("--flag"),
            mapOf("FAKE_GREETING" to "hello"),
        )
        try {
            client.addHttpServer("remote", "sse", "http://127.0.0.1:9/sse", "")
            fail()
        } catch (error: ClaudeConfigException) {
            assertTrue(error.message!!, "已经有" in error.message!!)
        }
        val servers = client.mcpServers(cwd)
        assertEquals(listOf("remote", "local-fake"), servers.map { it.name })
        assertFalse(servers.toString(), "SECRET" in servers.toString())
        assertTrue(servers[0].bearer)
        assertEquals("http://127.0.0.1:9/mcp?key=…", servers[0].url)
        assertEquals(listOf("FAKE_GREETING"), servers[1].envKeys)
        // claude 自己写进 ~/.claude.json 的配置
        val raw =
            Json.parseToJsonElement(run("cat \"\$HOME/.claude.json\""))
                .jsonObject["mcpServers"]!!
                .jsonObject
        assertEquals(
            "Bearer TOKENSECRET",
            raw["remote"]!!
                .jsonObject["headers"]!!
                .jsonObject["Authorization"]!!
                .jsonPrimitive
                .content,
        )

        val status = client.mcpStatus(cwd)
        assertEquals("failed", status.statuses["user/remote"]?.state)
        assertEquals("connected", status.statuses["user/local-fake"]?.state)

        assertEquals(listOf(McpTool("whoami", "hello --flag")), client.mcpTools(servers[1], cwd))
        try {
            client.mcpTools(servers[0], cwd)
            fail()
        } catch (error: ClaudeConfigException) {
            assertFalse(error.message!!, "SECRET" in error.message!!)
        }

        client.setMcpToken(servers[0], cwd, "NEWSECRET")
        val updated =
            Json.parseToJsonElement(run("cat \"\$HOME/.claude.json\""))
                .jsonObject["mcpServers"]!!
                .jsonObject
        assertEquals(
            "Bearer NEWSECRET",
            updated["remote"]!!
                .jsonObject["headers"]!!
                .jsonObject["Authorization"]!!
                .jsonPrimitive
                .content,
        )
        client.setMcpToken(client.mcpServers(cwd).first { it.name == "remote" }, cwd, "")
        assertFalse(client.mcpServers(cwd).first { it.name == "remote" }.authHeader)

        client.removeMcp(client.mcpServers(cwd).first { it.name == "remote" }, cwd)
        assertEquals(listOf("local-fake"), client.mcpServers(cwd).map { it.name })
    }
}

/** 测试用的 shell：[home] 是临时目录，用完删掉。 */
interface TestShell : LinuxShell {
    fun cleanup()
}

class LocalClientTest : ClaudeConfigClientTest() {
    override fun shell(): TestShell =
        object : TestShell {
            private val dir =
                java.nio.file.Files.createTempDirectory("zeta-home").toFile().canonicalFile
            override val home: String = dir.path

            override fun start(command: String): Process {
                val builder = ProcessBuilder("bash", "-c", command).directory(dir)
                val env = builder.environment()
                val path =
                    listOfNotNull(
                            System.getProperty("user.home")?.let { "$it/.local/bin" },
                            System.getenv("PATH"),
                        )
                        .joinToString(":")
                env.clear()
                env["HOME"] = home
                env["PATH"] = path
                env["LANG"] = "C.UTF-8"
                env["USER"] = System.getProperty("user.name") ?: "user"
                return builder.start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }

            override fun cleanup() {
                dir.deleteRecursively()
            }
        }

    /** HTTP 的 MCP 服务器从应用这边直接连：本机起一个假的，配置经 claude 写进去，再按配置连。 */
    @Test
    fun httpToolsFromConfig() {
        assumeClaude()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/mcp") { ex ->
            // Claude Code 的客户端还会 GET 一条事件流、最后 DELETE 会话：都回 405，表示不支持
            val message = runCatching {
                Json.parseToJsonElement(ex.requestBody.readBytes().toString(Charsets.UTF_8))
                    .jsonObject
            }
                .getOrNull()
            if (ex.requestMethod != "POST" || message == null) {
                ex.sendResponseHeaders(405, -1)
                ex.close()
                return@createContext
            }
            val id = message["id"]?.jsonPrimitive?.int
            if (id == null || ex.requestHeaders.getFirst("Authorization") != "Bearer T0KEN-VALUE") {
                ex.sendResponseHeaders(if (id == null) 202 else 401, -1)
                ex.close()
                return@createContext
            }
            val result =
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" ->
                        """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
                    "tools/list" ->
                        """{"tools":[{"name":"ping","inputSchema":{"type":"object"}}]}"""
                    else -> "{}"
                }
            val bytes = """{"jsonrpc":"2.0","id":$id,"result":$result}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            client.addHttpServer(
                "fake-http",
                "http",
                "http://127.0.0.1:${server.address.port}/mcp",
                "T0KEN-VALUE",
            )
            val entry = client.mcpServers(home).first { it.name == "fake-http" }
            assertEquals(listOf(McpTool("ping", "")), client.mcpTools(entry, home))
            assertEquals("connected", client.mcpStatus(home).statuses["user/fake-http"]?.state)
        } finally {
            server.stop(0)
        }
    }
}

/** 设了环境变量 ZETA_TEST_SSH=主机 时，经 ssh 在那台机器上用临时 HOME 跑同一套。 */
class PhoneClientTest : ClaudeConfigClientTest() {
    override fun shell(): TestShell {
        val host = System.getenv("ZETA_TEST_SSH")
        assumeTrue("没设 ZETA_TEST_SSH", !host.isNullOrBlank())
        val made = ProcessBuilder("ssh", "-T", "-o", "BatchMode=yes", host, "mktemp -d").start()
        val remoteHome = made.inputStream.bufferedReader().readText().trim()
        assertTrue(made.waitFor(30, TimeUnit.SECONDS) && remoteHome.startsWith("/tmp/"))
        val user =
            ProcessBuilder("ssh", "-T", "-o", "BatchMode=yes", host, "id -un")
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
        return object : TestShell {
            override val home: String = remoteHome

            override fun start(command: String): Process {
                // 和 LinuxEnv 一样清空环境、用登录 shell；PATH 补上真实用户的 ~/.local/bin（claude 装在那里）
                val remote =
                    "cd " +
                        LinuxEnv.quote(remoteHome) +
                        " && exec env -i HOME=" +
                        LinuxEnv.quote(remoteHome) +
                        " USER=$user LOGNAME=$user LANG=C.UTF-8 " +
                        "PATH=/home/$user/.local/bin:/usr/local/bin:/usr/bin:/bin bash -c " +
                        LinuxEnv.quote(command)
                return ProcessBuilder("ssh", "-T", "-o", "BatchMode=yes", host, remote).start()
            }

            override fun kill(process: Process) {
                process.destroyForcibly()
            }

            override fun cleanup() {
                ProcessBuilder(
                        "ssh",
                        "-T",
                        "-o",
                        "BatchMode=yes",
                        host,
                        "rm -rf -- " + LinuxEnv.quote(remoteHome),
                    )
                    .start()
                    .waitFor(30, TimeUnit.SECONDS)
            }
        }
    }
}
