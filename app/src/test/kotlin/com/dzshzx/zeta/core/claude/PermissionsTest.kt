package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.ui.setup.bulkMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun render(settings: JsonObject) = ClaudeSettings.render(settings)

    @Test
    fun parsesLikeClaudeCode() {
        assertEquals(ParsedRule("Bash", null), ClaudePermissions.parse("Bash"))
        assertEquals(ParsedRule("Bash", "git push:*"), ClaudePermissions.parse("Bash(git push:*)"))
        // 别名换成现在的名字
        assertEquals(ParsedRule("Agent", "Explore"), ClaudePermissions.parse("Task(Explore)"))
        assertEquals("TaskStop", ClaudePermissions.parse("KillShell").tool)
        // 参数里的括号：转义的不算；最后一个 ) 收尾
        assertEquals(ParsedRule("Bash", "echo (a)"), ClaudePermissions.parse("Bash(echo \\(a\\))"))
        assertEquals(ParsedRule("Bash", "f(x)"), ClaudePermissions.parse("Bash(f(x))"))
        // 整个工具的几种写法
        assertTrue(ClaudePermissions.parse("Bash()").wholeTool)
        assertTrue(ClaudePermissions.parse("Bash(*)").wholeTool)
        assertFalse(ClaudePermissions.parse("Bash(ls)").wholeTool)
        // 括号不配对
        assertTrue(ClaudePermissions.parse("Bash(ls").malformed)
        assertTrue(ClaudePermissions.parse("Bash(ls) x").malformed)
        assertTrue(ClaudePermissions.parse("(ls)").malformed)
        assertTrue(ClaudePermissions.parse("Ba)sh").malformed)
        assertFalse(ClaudePermissions.parse("mcp__github__*").malformed)
    }

    @Test
    fun validatesInput() {
        assertNull(ClaudePermissions.ruleProblem("Bash(git push:*)"))
        assertNull(ClaudePermissions.ruleProblem("mcp__github"))
        assertEquals("规则不能是空的", ClaudePermissions.ruleProblem("  "))
        assertEquals("规则前后不能有空格", ClaudePermissions.ruleProblem(" Bash"))
        assertEquals("规则里不能有换行", ClaudePermissions.ruleProblem("Bash(a\nb)"))
        assertEquals("括号不配对，写成 工具名 或 工具名(参数)", ClaudePermissions.ruleProblem("Bash(ls"))
    }

    @Test
    fun readsOnlyStringRules() {
        val settings =
            obj(
                """{"permissions":{"defaultMode":"plan","allow":["Read",3,{"x":1}],"deny":["Bash(rm:*)"]}}""",
            )
        assertEquals(
            PermissionRules(listOf("Read"), emptyList(), listOf("Bash(rm:*)")),
            ClaudePermissions.rules(settings),
        )
        assertEquals(PermissionRules.Empty, ClaudePermissions.rules(obj("""{"model":"opus"}""")))
        try {
            ClaudePermissions.rules(obj("""{"permissions":[]}"""))
            throw AssertionError("应当报错")
        } catch (expected: ClaudePermissions.InvalidPermissions) {}
    }

    @Test
    fun strongestRuleWins() {
        // 实测的语义：拒绝 > 询问 > 允许，和写得细不细无关
        val rules =
            PermissionRules(
                allow = listOf("Bash", "Read", "WebFetch(domain:github.com)", "Task"),
                ask = listOf("Bash(*)", "Bash(git push:*)"),
                deny = listOf("Read()"),
            )
        val bash = ClaudePermissions.tool(rules, "Bash")
        assertEquals(PermissionBehavior.Ask, bash.behavior)
        assertEquals(listOf(PermissionBehavior.Ask to "Bash(git push:*)"), bash.specific)
        assertEquals(PermissionBehavior.Deny, ClaudePermissions.tool(rules, "Read").behavior)
        assertEquals(PermissionBehavior.Allow, ClaudePermissions.tool(rules, "Agent").behavior)
        val fetch = ClaudePermissions.tool(rules, "WebFetch")
        assertNull(fetch.behavior)
        assertEquals(1, fetch.specific.size)
        assertNull(ClaudePermissions.tool(rules, "Write").behavior)
    }

    @Test
    fun mcpToolsInheritFromServer() {
        assertEquals("mcp__phone", McpNames.server("phone"))
        assertEquals("mcp__my_server__ping", McpNames.tool("my.server", "ping"))
        assertEquals("mcp__claude_ai_Gmail", McpNames.server("claude.ai Gmail"))
        val rules =
            PermissionRules(
                allow = listOf("mcp__phone__screen"),
                ask = listOf("mcp__github__*"),
                deny = listOf("mcp__phone__shell"),
            )
        assertEquals(
            ToolPermission(PermissionBehavior.Deny),
            ClaudePermissions.mcpTool(rules, "phone", "shell"),
        )
        assertEquals(
            ToolPermission(PermissionBehavior.Allow),
            ClaudePermissions.mcpTool(rules, "phone", "screen"),
        )
        assertEquals(ToolPermission(null), ClaudePermissions.mcpTool(rules, "phone", "tap"))
        // 整个服务器的询问压过工具自己的允许
        assertEquals(PermissionBehavior.Ask, ClaudePermissions.server(rules, "github").behavior)
        val issue =
            ClaudePermissions.mcpTool(
                rules.copy(allow = listOf("mcp__github__create_issue")),
                "github",
                "create_issue",
            )
        assertEquals(PermissionBehavior.Ask, issue.behavior)
        assertTrue(issue.inherited)
        // 服务器设了允许、工具自己设了拒绝：拒绝，不是继承来的
        val own =
            ClaudePermissions.mcpTool(
                PermissionRules(listOf("mcp__x"), emptyList(), listOf("mcp__x__rm")),
                "x",
                "rm",
            )
        assertEquals(ToolPermission(PermissionBehavior.Deny, inherited = false), own)
    }

    @Test
    fun settingAToolKeepsEverythingElse() {
        val settings =
            obj(
                """{"model":"opus","permissions":{"defaultMode":"acceptEdits","allow":["Bash(npm test:*)","Bash",7,"Read"],"deny":["Task"],"additionalDirectories":["/x"]},"theme":"dark"}""",
            )
        val denied = ClaudePermissions.withTool(settings, "Bash", PermissionBehavior.Deny)
        assertEquals(
            """
            {
              "model": "opus",
              "permissions": {
                "defaultMode": "acceptEdits",
                "allow": [
                  "Bash(npm test:*)",
                  7,
                  "Read"
                ],
                "deny": [
                  "Task",
                  "Bash"
                ],
                "additionalDirectories": [
                  "/x"
                ]
              },
              "theme": "dark"
            }

            """
                .trimIndent(),
            render(denied),
        )
        // 别名：Agent 也去掉 Task；表删空了留着 []
        val agent = ClaudePermissions.withTool(settings, "Agent", null)
        assertEquals("[]", (agent["permissions"] as JsonObject)["deny"].toString())
        // 没有 ask 表时设询问才建表，放在最后
        val ask = ClaudePermissions.withTool(settings, "WebSearch", PermissionBehavior.Ask)
        assertEquals(
            listOf("defaultMode", "allow", "deny", "additionalDirectories", "ask"),
            (ask["permissions"] as JsonObject).keys.toList(),
        )
        // 没变化时原样返回
        assertSame(settings, ClaudePermissions.withTool(settings, "Write", null))
        // 没有 permissions、也不加规则：不建
        val bare = obj("""{"model":"opus"}""")
        assertSame(bare, ClaudePermissions.withTool(bare, "Bash", null))
        assertEquals(
            """{"model":"opus","permissions":{"allow":["Bash"]}}""",
            ClaudePermissions.withTool(bare, "Bash", PermissionBehavior.Allow).toString(),
        )
    }

    @Test
    fun serverLevelRulesReplaceBothSpellings() {
        val settings =
            obj(
                """{"permissions":{"allow":["mcp__github__*","mcp__github__create_issue"],"ask":["mcp__github"]}}""",
            )
        val denied = ClaudePermissions.withTool(settings, "mcp__github", PermissionBehavior.Deny)
        assertEquals(
            PermissionRules(
                listOf("mcp__github__create_issue"),
                emptyList(),
                listOf("mcp__github"),
            ),
            ClaudePermissions.rules(denied),
        )
        // 单个工具不碰服务器的规则
        val tool = ClaudePermissions.withTool(settings, "mcp__github__create_issue", null)
        assertEquals(
            PermissionRules(listOf("mcp__github__*"), listOf("mcp__github"), emptyList()),
            ClaudePermissions.rules(tool),
        )
    }

    @Test
    fun addRemoveAndReplaceRules() {
        val settings =
            obj(
                """{"permissions":{"allow":["Bash(git push:*)","Read"],"deny":["Bash(rm -rf:*)"]}}""",
            )
        // 加到询问：同一条从允许里拿走
        val moved = ClaudePermissions.withRule(settings, PermissionBehavior.Ask, "Bash(git push:*)")
        assertEquals(
            PermissionRules(listOf("Read"), listOf("Bash(git push:*)"), listOf("Bash(rm -rf:*)")),
            ClaudePermissions.rules(moved),
        )
        // 已经在了：不动
        assertSame(settings, ClaudePermissions.withRule(settings, PermissionBehavior.Allow, "Read"))
        val removed =
            ClaudePermissions.withoutRule(settings, PermissionBehavior.Deny, "Bash(rm -rf:*)")
        assertEquals(
            PermissionRules(listOf("Bash(git push:*)", "Read"), emptyList(), emptyList()),
            ClaudePermissions.rules(removed),
        )
        // 同一张表里改写：留在原位
        val edited =
            ClaudePermissions.replaceRule(
                settings,
                PermissionBehavior.Allow,
                "Bash(git push:*)",
                PermissionBehavior.Allow,
                "Bash(git push origin:*)",
            )!!
        assertEquals(
            listOf("Bash(git push origin:*)", "Read"),
            ClaudePermissions.rules(edited).allow,
        )
        // 改写成表里已有的一条：不重复
        val merged =
            ClaudePermissions.replaceRule(
                settings,
                PermissionBehavior.Allow,
                "Bash(git push:*)",
                PermissionBehavior.Allow,
                "Read",
            )!!
        assertEquals(listOf("Read"), ClaudePermissions.rules(merged).allow)
        // 换一张表
        val other =
            ClaudePermissions.replaceRule(
                settings,
                PermissionBehavior.Deny,
                "Bash(rm -rf:*)",
                PermissionBehavior.Ask,
                "Bash(rm:*)",
            )!!
        assertEquals(
            PermissionRules(listOf("Bash(git push:*)", "Read"), listOf("Bash(rm:*)"), emptyList()),
            ClaudePermissions.rules(other),
        )
        // 原来那条已经不在了
        assertNull(
            ClaudePermissions.replaceRule(
                settings,
                PermissionBehavior.Ask,
                "Bash(git push:*)",
                PermissionBehavior.Deny,
                "Bash(git push:*)",
            ),
        )
    }

    @Test
    fun catalogHasNoDuplicates() {
        val names = (BuiltinTools.common + BuiltinTools.other).map { it.name }
        assertEquals(names.size, names.toSet().size)
        names.forEach { assertEquals(it, ClaudePermissions.canonicalTool(it)) }
    }

    @Test
    fun fileToolsWithStarInAllowOnlyCoverWorkingDirs() {
        // 允许表里 Edit(*) 只在工作目录放行，不算整个工具；拒绝表里的 Edit(*) 还是整个工具
        val rules =
            PermissionRules(
                allow = listOf("Edit(*)", "Bash(*)"),
                ask = emptyList(),
                deny = listOf("Write(*)"),
            )
        val edit = ClaudePermissions.tool(rules, "Edit")
        assertNull(edit.behavior)
        assertEquals(listOf(PermissionBehavior.Allow to "Edit(*)"), edit.specific)
        assertEquals(PermissionBehavior.Allow, ClaudePermissions.tool(rules, "Bash").behavior)
        assertEquals(PermissionBehavior.Deny, ClaudePermissions.tool(rules, "Write").behavior)
        // 设 Edit 为未设置时不动只管工作目录的那条
        val settings = obj("""{"permissions":{"allow":["Edit(*)","Edit"]}}""")
        assertEquals(
            listOf("Edit(*)"),
            ClaudePermissions.rules(ClaudePermissions.withTool(settings, "Edit", null)).allow,
        )
    }

    private val builtins = PermissionGroup.Tools(listOf("Bash", "Read", "Edit", "Agent"))

    @Test
    fun bulkEnableBuiltinsWritesEachToolAndDropsBlockers() {
        val settings =
            obj(
                """{"permissions":{"defaultMode":"plan","allow":["Read","Bash(git push:*)","Edit(*)"],"ask":["Bash","WebFetch(domain:x.com)","Task(Explore)"],"deny":["Bash(rm:*)","Bash(ls",9,"mcp__phone__shell"]}}""",
            )
        val change =
            ClaudePermissions.bulk(ClaudePermissions.rules(settings), builtins, enable = true)
        // Read 已经整体允许，不再加；Edit(*) 只管工作目录，要补 Edit
        assertEquals(
            listOf("Bash", "Edit", "Agent").map { PermissionBehavior.Allow to it },
            change.add,
        )
        // 组里工具上的拒绝和询问都删（带参数的、别名写的也算），认不出的、别组的、组外工具的不动
        assertEquals(
            listOf(
                PermissionBehavior.Deny to "Bash(rm:*)",
                PermissionBehavior.Ask to "Bash",
                PermissionBehavior.Ask to "Task(Explore)",
            ),
            change.remove,
        )
        val after = ClaudePermissions.withBulk(settings, builtins, enable = true)
        assertEquals(
            """{"permissions":{"defaultMode":"plan","allow":["Read","Bash(git push:*)","Edit(*)","Bash","Edit","Agent"],""" +
                """"ask":["WebFetch(domain:x.com)"],"deny":["Bash(ls",9,"mcp__phone__shell"]}}""",
            after.toString(),
        )
        val rules = ClaudePermissions.rules(after)
        listOf("Bash", "Read", "Edit", "Agent").forEach {
            assertEquals(it, PermissionBehavior.Allow, ClaudePermissions.tool(rules, it).behavior)
        }
        // 再点一次：已经全开了
        assertTrue(ClaudePermissions.bulk(rules, builtins, enable = true).isEmpty)
        assertSame(after, ClaudePermissions.withBulk(after, builtins, enable = true))
    }

    @Test
    fun bulkDisableBuiltinsDropsAllowAndAsk() {
        val settings =
            obj(
                """{"permissions":{"allow":["Read","Bash(git push:*)","Edit(*)"],"ask":["Agent"],"deny":["Bash(*)","Bash(rm:*)"]}}""",
            )
        val change =
            ClaudePermissions.bulk(ClaudePermissions.rules(settings), builtins, enable = false)
        // Bash(*) 在拒绝表里就是整个 Bash，不再加；带参数的拒绝留着
        assertEquals(
            listOf("Read", "Edit", "Agent").map { PermissionBehavior.Deny to it },
            change.add,
        )
        assertEquals(
            listOf(
                PermissionBehavior.Ask to "Agent",
                PermissionBehavior.Allow to "Read",
                PermissionBehavior.Allow to "Bash(git push:*)",
                PermissionBehavior.Allow to "Edit(*)",
            ),
            change.remove,
        )
        val after = ClaudePermissions.withBulk(settings, builtins, enable = false)
        assertEquals(
            PermissionRules(
                emptyList(),
                emptyList(),
                listOf("Bash(*)", "Bash(rm:*)", "Read", "Edit", "Agent"),
            ),
            ClaudePermissions.rules(after),
        )
        assertTrue(
            ClaudePermissions.bulk(ClaudePermissions.rules(after), builtins, enable = false)
                .isEmpty,
        )
        // 没有 permissions 的文件：建出来
        assertEquals(
            """{"permissions":{"deny":["Bash","Read","Edit","Agent"]}}""",
            ClaudePermissions.withBulk(obj("{}"), builtins, enable = false).toString(),
        )
    }

    @Test
    fun bulkServerUsesOneRule() {
        val phone = PermissionGroup.Server("phone")
        val settings =
            obj(
                """{"permissions":{"allow":["mcp__phone__screen","mcp__phoney__x"],"ask":["mcp__phone__tap"],"deny":["mcp__phone__shell","mcp__phone__*","mcp__github"]}}""",
            )
        val on = ClaudePermissions.bulk(ClaudePermissions.rules(settings), phone, enable = true)
        assertEquals(listOf(PermissionBehavior.Allow to "mcp__phone"), on.add)
        // 名字只是前缀相同的服务器（phoney）不算
        assertEquals(
            listOf(
                PermissionBehavior.Deny to "mcp__phone__shell",
                PermissionBehavior.Deny to "mcp__phone__*",
                PermissionBehavior.Ask to "mcp__phone__tap",
            ),
            on.remove,
        )
        val enabled = ClaudePermissions.withBulk(settings, phone, enable = true)
        assertEquals(
            PermissionRules(
                listOf("mcp__phone__screen", "mcp__phoney__x", "mcp__phone"),
                emptyList(),
                listOf("mcp__github"),
            ),
            ClaudePermissions.rules(enabled),
        )
        assertEquals(
            PermissionBehavior.Allow,
            ClaudePermissions.mcpTool(ClaudePermissions.rules(enabled), "phone", "shell").behavior,
        )
        // mcp__phone__* 在拒绝表里已经是整个服务器：关闭时不再加 mcp__phone，只删允许和询问
        val off = ClaudePermissions.bulk(ClaudePermissions.rules(settings), phone, enable = false)
        assertEquals(emptyList<Pair<PermissionBehavior, String>>(), off.add)
        assertEquals(
            listOf(
                PermissionBehavior.Ask to "mcp__phone__tap",
                PermissionBehavior.Allow to "mcp__phone__screen",
            ),
            off.remove,
        )
        // 别的服务器按规整后的名字算
        val github =
            ClaudePermissions.bulk(
                ClaudePermissions.rules(settings),
                PermissionGroup.Server("github"),
                enable = false,
            )
        assertTrue(github.isEmpty)
    }

    @Test
    fun bulkMessageListsEveryRule() {
        val change =
            BulkChange(
                add = listOf(PermissionBehavior.Deny to "Bash", PermissionBehavior.Deny to "Read"),
                remove =
                    listOf(
                        PermissionBehavior.Ask to "Agent",
                        PermissionBehavior.Allow to "Bash(git push:*)",
                    ),
            )
        assertEquals(
            "加到拒绝（2 条）：Bash、Read\n从允许里删（1 条）：Bash(git push:*)\n从询问里删（1 条）：Agent\n" +
                "\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用手机工具、MCP 工具。",
            bulkMessage(change, warnNoTools = true),
        )
        assertEquals(
            "加到允许（1 条）：mcp__phone",
            bulkMessage(
                BulkChange(listOf(PermissionBehavior.Allow to "mcp__phone"), emptyList()),
                warnNoTools = false,
            ),
        )
    }
}
