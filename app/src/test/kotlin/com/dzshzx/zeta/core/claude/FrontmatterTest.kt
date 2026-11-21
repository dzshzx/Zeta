package com.dzshzx.zeta.core.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FrontmatterTest {
    @Test
    fun splitNeedsBothFences() {
        assertEquals(Frontmatter.Split("a: 1", "body"), Frontmatter.split("---\na: 1\n---\nbody"))
        assertNull(Frontmatter.split("---\na: 1\nno end").front)
        assertNull(Frontmatter.split("text\n---\n").front)
        assertEquals("a: 1\r", Frontmatter.split("---\r\na: 1\r\n---\r\nbody").front)
    }

    @Test
    fun parsesScalarsListsAndBlocks() {
        val meta =
            Frontmatter.parse(
                """
                name: code-reviewer
                description: "Reviews code: finds bugs\nand smells"
                quoted: 'it''s'
                tools: [Read, Grep]
                list:
                  - a
                  - "b c"
                block: |
                  line one
                  line two
                folded: >-
                  one
                  two
                metadata:
                  type: feedback
                plain: value # comment
                wrapped: first part
                  second part
                crlf: yes
                """
                    .trimIndent()
                    .replace("crlf: yes", "crlf: yes\r"),
            )
        assertEquals("code-reviewer", meta["name"]?.text())
        assertEquals("Reviews code: finds bugs\nand smells", meta["description"]?.text())
        assertEquals("it's", meta["quoted"]?.text())
        assertEquals(FrontValue.Items(listOf("Read", "Grep")), meta["tools"])
        assertEquals(FrontValue.Items(listOf("a", "b c")), meta["list"])
        assertEquals("line one\nline two", meta["block"]?.text())
        assertEquals("one two", meta["folded"]?.text())
        assertEquals(FrontValue.Fields(mapOf("type" to "feedback")), meta["metadata"])
        assertEquals("value", meta["plain"]?.text())
        assertEquals("first part second part", meta["wrapped"]?.text())
        assertEquals("yes", meta["crlf"]?.text())
    }

    @Test
    fun yamlValueQuotesOnlyWhenNeeded() {
        assertEquals("simple words", Frontmatter.yamlValue("simple words"))
        assertEquals("Read, Grep", Frontmatter.yamlValue("Read, Grep"))
        assertEquals("\"a: b\"", Frontmatter.yamlValue("a: b"))
        assertEquals("\"line\\nbreak\"", Frontmatter.yamlValue("line\nbreak"))
        assertEquals("\"yes\"", Frontmatter.yamlValue("yes"))
        assertEquals("\"123\"", Frontmatter.yamlValue("123"))
        assertEquals("\"- dash\"", Frontmatter.yamlValue("- dash"))
        assertEquals("\"\"", Frontmatter.yamlValue(""))
        // 写出去再读回来不变
        for (text in listOf("a: b # c", "引号\"和'单引号", "tab\there", " lead", "#hash")) {
            assertEquals(text, Frontmatter.parse("k: " + Frontmatter.yamlValue(text))["k"]?.text())
        }
    }
}

class SubagentFilesTest {
    private val original =
        """
        ---
        # 注释
        name: reviewer
        description: Old text
        model: sonnet
        tools: Read, Grep
        color: blue
        hooks:
          PreToolUse:
            - matcher: Bash
        ---

        You review code.
        """
            .trimIndent() + "\n"

    @Test
    fun parsesAgentFile() {
        val agent = SubagentFiles.parse("team/reviewer.md", original)
        assertEquals("reviewer", agent.name)
        assertEquals("sonnet", agent.model)
        assertEquals(listOf("Read", "Grep"), agent.tools)
        assertEquals("You review code.", agent.prompt)
        assertEquals(listOf("color", "hooks"), agent.extraKeys)
        assertNull(SubagentFiles.parse("a.md", "---\nname: a\ndescription: d\n---\nx").tools)
        assertEquals(
            emptyList<String>(),
            SubagentFiles.parse("a.md", "---\nname: a\ntools: []\n---\n").tools,
        )
        assertEquals(
            "line1\nline2",
            SubagentFiles.parse("a.md", "---\ndescription: line1\\nline2\n---\n").description,
        )
    }

    @Test
    fun renderKeepsUnknownKeysAndDropsDefaultModel() {
        val text =
            SubagentFiles.render(
                SubagentDraft(
                    "reviewer",
                    "Use after: edits",
                    "",
                    listOf("Read", " ", "mcp__phone__tap"),
                    "\nNew prompt\n",
                ),
                original,
            )
        assertEquals(
            """
            ---
            name: reviewer
            description: "Use after: edits"
            tools: Read, mcp__phone__tap
            # 注释
            color: blue
            hooks:
              PreToolUse:
                - matcher: Bash
            ---

            New prompt
            """
                .trimIndent() + "\n",
            text,
        )
        val reparsed = SubagentFiles.parse("reviewer.md", text)
        assertEquals("Use after: edits", reparsed.description)
        assertEquals("", reparsed.model)
        assertEquals(listOf("color", "hooks"), reparsed.extraKeys)
        // 全部工具：不写 tools；没勾任何工具：tools: []
        assertFalse(
            "tools:" in SubagentFiles.render(SubagentDraft("a", "d", "inherit", null, "p"), null),
        )
        assertTrue(
            "tools: []" in
                SubagentFiles.render(SubagentDraft("a", "d", "inherit", emptyList(), "p"), null),
        )
    }

    @Test
    fun pathsStayInsideAgents() {
        assertNull(SubagentFiles.relativeProblem("a.md"))
        assertNull(SubagentFiles.relativeProblem("team/a.md"))
        assertNotNull(SubagentFiles.relativeProblem("../a.md"))
        assertNotNull(SubagentFiles.relativeProblem("/etc/a.md"))
        assertNotNull(SubagentFiles.relativeProblem("a.txt"))
        assertNotNull(SubagentFiles.relativeProblem("team//a.md"))
        assertEquals("team/new.md", SubagentFiles.targetFile("team/old.md", "new"))
        assertEquals("new.md", SubagentFiles.targetFile(null, "new"))
        assertNotNull(SubagentFiles.draftProblem(SubagentDraft("Bad Name", "d", "", null, "")))
        assertNotNull(SubagentFiles.draftProblem(SubagentDraft("ok", " ", "", null, "")))
        assertNull(SubagentFiles.draftProblem(SubagentDraft("ok", "d", "", null, "")))
    }
}

class ClaudeSettingsTest {
    @Test
    fun renderMatchesJsonStringify() {
        // 期望值由 node 的 JSON.stringify(value, null, 2) 算出
        val value =
            Json.parseToJsonElement("""{"a":1,"b":[],"c":{},"d":[1,{"x":"中\u0001\"/"}],"f":null}""")
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [],\n  \"c\": {},\n  \"d\": [\n    1,\n    {\n      \"x\": \"中\\u0001\\\"/\"\n    }\n  ],\n  \"f\": null\n}\n",
            ClaudeSettings.render(value),
        )
    }

    @Test
    fun autoMemoryKeepsOtherKeysInPlace() {
        val settings =
            ClaudeSettings.parse("""{"model":"opus","autoMemoryEnabled":true,"env":{"A":"1"}}""")
        val off = ClaudeSettings.withAutoMemory(settings, false)
        assertEquals(listOf("model", "autoMemoryEnabled", "env"), off.keys.toList())
        assertFalse(ClaudeSettings.memory(off).autoMemory)
        assertTrue(ClaudeSettings.memory(ClaudeSettings.parse(null)).autoMemory)
        val added =
            ClaudeSettings.withAutoMemory(ClaudeSettings.parse("""{"model":"opus"}"""), true)
        assertEquals(listOf("model", "autoMemoryEnabled"), added.keys.toList())
    }

    @Test
    fun forcedOffAndCustomDirectory() {
        val memory =
            ClaudeSettings.memory(
                ClaudeSettings.parse(
                    """{"env":{"CLAUDE_CODE_DISABLE_AUTO_MEMORY":"True"},"autoMemoryDirectory":"~/m"}""",
                ),
            )
        assertTrue(memory.forcedOff)
        assertEquals("~/m", memory.customDir)
    }

    @Test
    fun invalidSettingsAreNotTouched() {
        try {
            ClaudeSettings.parse("{broken")
            fail()
        } catch (expected: ClaudeSettings.Invalid) {}
        try {
            ClaudeSettings.parse("[1]")
            fail()
        } catch (expected: ClaudeSettings.Invalid) {}
        assertEquals(JsonObject(emptyMap()), ClaudeSettings.parseLenient("{broken"))
    }

    @Test
    fun skillOverrides() {
        val start = ClaudeSettings.parse("""{"skillOverrides":{"keep":"name-only"}}""")
        val off =
            ClaudeSettings.withSkillEnabled(start, "pdf", listOf("PDF Tools"), enabled = false)
        assertEquals("off", ClaudeSettings.skillOverride(off, listOf("pdf")))
        assertEquals("name-only", ClaudeSettings.skillOverride(off, listOf("keep")))
        // 前言里的名字也可能被关掉；启用时一起去掉
        val both =
            ClaudeSettings.with(
                off,
                "skillOverrides",
                JsonObject(
                    (off["skillOverrides"] as JsonObject) + ("PDF Tools" to JsonPrimitive("off")),
                ),
            )
        val on = ClaudeSettings.withSkillEnabled(both, "pdf", listOf("PDF Tools"), enabled = true)
        assertEquals(JsonObject(mapOf("keep" to JsonPrimitive("name-only"))), on["skillOverrides"])
        // 没有 skillOverrides 时启用：不加空对象
        val untouched = ClaudeSettings.parse("""{"a":1}""")
        assertEquals(
            untouched,
            ClaudeSettings.withSkillEnabled(untouched, "pdf", emptyList(), enabled = true),
        )
        assertEquals(
            JsonObject(mapOf("keep" to JsonPrimitive("name-only"))),
            ClaudeSettings.withoutSkill(off, "pdf")["skillOverrides"],
        )
        assertEquals(untouched, ClaudeSettings.withoutSkill(untouched, "pdf"))
    }
}

class ClaudeMemoryTest {
    @Test
    fun indexLineRemoval() {
        val index =
            "# Memory\n- [Role](user-role.md) — who\n- [Other](./other.md) — x\n- [Role again](user-role.md)\n"
        assertEquals(
            "# Memory\n- [Other](./other.md) — x\n",
            ClaudeMemory.indexWithout(index, "user-role.md"),
        )
        assertEquals(
            "# Memory\n- [Role](user-role.md) — who\n- [Role again](user-role.md)\n",
            ClaudeMemory.indexWithout(index, "other.md"),
        )
        assertNull(ClaudeMemory.indexWithout(index, "missing.md"))
    }

    @Test
    fun entryReadsNestedType() {
        val entry =
            ClaudeMemory.entry(
                "/m/a.md",
                10,
                "---\nname: A\ndescription: about\nmetadata:\n  type: feedback\n---\nbody",
            )
        assertEquals("A", entry.name)
        assertEquals("feedback", entry.type)
        assertEquals("a.md", entry.fileName)
        assertEquals("user", ClaudeMemory.entry("/m/b.md", 1, "---\ntype: user\n---\n").type)
    }

    @Test
    fun projectsPutDefaultFirst() {
        val dirs =
            listOf(
                ProjectDir("-workspace-claude", 3, 100, "/workspace/claude/zeta"),
                ProjectDir("-other", 2, 300, null),
                ProjectDir("-empty", 0, 400, null),
                ProjectDir("-nomemory", -1, 0, "/nomemory"),
                ProjectDir("-w-x", 1, 200, "/w/x"),
            )
        val projects = ClaudeMemory.projects(dirs, "-new-default", "/new/default")
        assertEquals(
            listOf("-new-default", "-other", "-w-x", "-workspace-claude"),
            projects.map { it.key },
        )
        assertEquals(MemoryProject("-new-default", "/new/default", 0, true), projects[0])
        assertEquals("/w/x", projects[2].label)
        assertEquals("/workspace/claude", projects[3].label)
        assertEquals("-other", projects[1].label)
        assertEquals("/a\"b", ClaudeMemory.sampleCwd("\"cwd\":\"/a\\\"b\""))
        assertNull(ClaudeMemory.sampleCwd(""))
    }

    @Test
    fun recordsSkipProfileNoise() {
        val out =
            "Welcome!\nnoise\u0000line\n@zeta\nX\u0000/m/MEMORY.md\u0000-1\u00000\u0000\nM\u0000/m/a.md\u00005\u0000100\u0000aGVsbG8=\ngarbage\n"
        val records = ClaudeScripts.records(out)
        assertEquals(listOf("X", "M"), records.map { it.tag })
        assertNull(records[0].file())
        val file = records[1].file()!!
        assertEquals("hello", file.text())
        assertTrue(file.complete)
        assertEquals(100L, file.mtime)
        assertEquals("ok\n" to 0, ClaudeScripts.claudeOutput("x\n@zeta\nok\nEXIT\u00000\u0000\n"))
    }
}
