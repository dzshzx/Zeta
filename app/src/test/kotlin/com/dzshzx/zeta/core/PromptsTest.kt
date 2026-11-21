package com.dzshzx.zeta.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读 Claude Code 等用户回应的请求、按 TUI 的规则拼应答（core/Prompts.kt）。请求和应答的样子照 2.1.330 实测， Claude Code
 * 那一侧怎么理解这些应答由 ClaudeInteractionContractTest 钉住。
 */
class PromptsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    private val bash =
        parsePrompt(
            obj(
                """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"touch a"},
        "decision_reason":"需要写文件","agent_id":"a7","tool_use_id":"t1",
        "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"touch a"}],"behavior":"allow","destination":"localSettings"},
        {"type":"addDirectories","directories":["/w/proj"],"destination":"session"},{"type":"setMode","mode":"acceptEdits","destination":"session"}]}}""",
            ),
        )
            as PermissionRequest

    @Test
    fun readsAToolRequest() {
        assertEquals("Bash", bash.toolName)
        assertEquals("需要写文件", bash.reason)
        assertEquals("a7", bash.agentId)
        assertEquals(3, bash.suggestions.size)
        assertEquals(
            listOf("允许 Bash(touch a)", "允许访问 /w/proj（只在这个对话里）", "这个对话里自动接受改动"),
            describeSuggestions(bash.suggestions),
        )
        val suppressed =
            parsePrompt(
                obj(
                    """{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{},
            "suppress_always_allow_rule":true,"permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash"}],"behavior":"allow"}]}}""",
                ),
            )
                as PermissionRequest
        assertTrue("TUI 不给“不再问”时这里也不给", suppressed.suggestions.isEmpty())
        assertNull(
            "手机工具的 MCP 消息不是要用户回应的",
            parsePrompt(
                obj(
                    """{"type":"control_request","request_id":"m","request":{"subtype":"mcp_message"}}""",
                ),
            ),
        )
    }

    @Test
    fun toolRepliesMatchTheTui() {
        assertEquals(
            obj("""{"behavior":"allow","updatedInput":{"command":"touch a"}}"""),
            permissionResponse(bash, PermissionReply.Allow),
        )
        assertEquals(
            "不再问：原样交回 Claude Code 的建议",
            bash.suggestions,
            permissionResponse(bash, PermissionReply.AllowAlways)["updatedPermissions"],
        )
        // 不写理由：带 interrupt，这一轮停下
        assertEquals(
            obj("""{"behavior":"deny","message":"User rejected tool use","interrupt":true}"""),
            permissionResponse(bash, PermissionReply.Deny("  ")),
        )
        // 写了理由：TUI 的那句开头加上用户的话，不打断
        assertEquals(
            obj(
                """{"behavior":"deny","message":${JsonPrimitive(REJECT_WITH_FEEDBACK + "用 ls")}}""",
            ),
            permissionResponse(bash, PermissionReply.Deny(" 用 ls ")),
        )
    }

    @Test
    fun planApprovalSetsTheModeForTheSession() {
        val plan =
            PermissionRequest(
                "p",
                "ExitPlanMode",
                obj("""{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"}"""),
            )
        assertTrue(plan.isPlan)
        assertEquals(
            obj(
                """{"behavior":"allow","updatedInput":{"plan":"# 计划","planFilePath":"/h/.claude/plans/x.md"},
                "updatedPermissions":[{"type":"setMode","mode":"default","destination":"session"}]}""",
            ),
            permissionResponse(plan, PermissionReply.ApprovePlan("default")),
        )
    }

    private val ask =
        parsePrompt(
            obj(
                """{"type":"control_request","request_id":"q","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","input":{"questions":[
        {"question":"用哪个库？","header":"库","multiSelect":false,"options":[{"label":"A","description":"a","preview":"import a"},{"label":"B","description":"b"}]},
        {"question":"要哪些？","header":"功能","multiSelect":true,"options":[{"label":"X","description":""},{"label":"Y","description":""}]}]}}}""",
            ),
        )
            as PermissionRequest

    @Test
    fun answersCarryPreviewAndNotes() {
        assertEquals("import a", ask.questions[0].options[0].preview)
        assertTrue(ask.questions[0].takesNotes)
        assertTrue("多选题不收备注", !ask.questions[1].takesNotes)
        val reply =
            permissionResponse(
                ask,
                PermissionReply.Answer(
                    mapOf("用哪个库？" to "A", "要哪些？" to "X, 自己写的"),
                    mapOf("用哪个库？" to "要新版", "要哪些？" to "多选题的备注不交"),
                ),
            )
        val input = reply["updatedInput"] as JsonObject
        assertEquals(ask.input["questions"], input["questions"])
        assertEquals(obj("""{"用哪个库？":"A","要哪些？":"X, 自己写的"}"""), input["answers"])
        assertEquals(
            obj("""{"用哪个库？":{"preview":"import a","notes":"要新版"}}"""),
            input["annotations"],
        )
    }

    @Test
    fun clarifyDeniesWithTheTuiExplanation() {
        val reply =
            permissionResponse(
                ask,
                PermissionReply.Clarify(mapOf("用哪个库？" to "B"), mapOf("用哪个库？" to "拿不准")),
            )
        assertEquals("deny", (reply["behavior"] as JsonPrimitive).content)
        assertNull("想聊聊不是打断", reply["interrupt"])
        val message = (reply["message"] as JsonPrimitive).content
        assertTrue(
            message.startsWith(REJECT_WITH_FEEDBACK + "The user wants to clarify these questions."),
        )
        assertTrue(
            message.endsWith(
                "- \"用哪个库？\"\n  Answer: B\n  User notes: 拿不准\n- \"要哪些？\"\n  (No answer provided)",
            ),
        )
    }

    private val form =
        parsePrompt(
            obj(
                """{"type":"control_request","request_id":"e","request":{"subtype":"elicitation","mcp_server_name":"eli","message":"请填写","mode":"form",
        "property_order":["count","name","prod","region","tags"],"requested_schema":{"type":"object","required":["name"],"properties":{
        "count":{"type":"integer","title":"实例数","minimum":1,"maximum":5,"default":2},
        "name":{"type":"string","title":"名字","minLength":2},
        "prod":{"type":"boolean","title":"生产环境","default":false},
        "region":{"type":"string","title":"地区","enum":["cn","us"],"enumNames":["中国","美国"]},
        "tags":{"type":"array","title":"标签","items":{"anyOf":[{"const":"a","title":"甲"},{"const":"b","title":"乙"}]}}}}}}""",
            ),
        )
            as ElicitationRequest

    @Test
    fun readsAnElicitationForm() {
        assertEquals("eli", form.server)
        assertNull(form.url)
        assertEquals(listOf("count", "name", "prod", "region", "tags"), form.fields.map { it.key })
        assertEquals(
            listOf(
                ElicitField.Kind.Integer,
                ElicitField.Kind.Text,
                ElicitField.Kind.Toggle,
                ElicitField.Kind.Single,
                ElicitField.Kind.Multi,
            ),
            form.fields.map { it.kind },
        )
        assertEquals(listOf("2"), form.fields[0].default)
        assertEquals(listOf("中国", "美国"), form.fields[3].choices.map { it.label })
        assertEquals(listOf("a", "b"), form.fields[4].choices.map { it.value })
        val url =
            parsePrompt(
                obj(
                    """{"type":"control_request","request_id":"u","request":{"subtype":"elicitation","mcp_server_name":"eli","message":"去登录","mode":"url","url":"https://example.com/a","elicitation_id":"x"}}""",
                ),
            )
                as ElicitationRequest
        assertEquals("https://example.com/a", url.url)
    }

    @Test
    fun formContentIsTypedAndValidated() {
        val (content, errors) =
            elicitationContent(
                form.fields,
                mapOf(
                    "count" to listOf("3"),
                    "name" to listOf(" svc "),
                    "prod" to listOf("true"),
                    "region" to listOf("cn"),
                    "tags" to listOf("a", "b"),
                ),
            )
        assertEquals(emptyMap<String, String>(), errors)
        assertEquals(
            obj("""{"count":3,"name":"svc","prod":true,"region":"cn","tags":["a","b"]}"""),
            content,
        )

        val (bad, problems) =
            elicitationContent(form.fields, mapOf("count" to listOf("9"), "name" to listOf("x")))
        assertNull(bad)
        assertEquals(mapOf("count" to "不能大于 5", "name" to "至少 2 个字"), problems)
        assertEquals(mapOf("name" to "必填"), elicitationContent(form.fields, emptyMap()).second)
        assertEquals(
            "要填整数",
            elicitationContent(form.fields, mapOf("name" to listOf("ok"), "count" to listOf("1.5")))
                .second["count"],
        )
        assertEquals(obj("""{"action":"decline"}"""), elicitationResponse("decline"))
    }

    @Test
    fun noticesSayWhatIsWaiting() {
        assertEquals("Claude 想用 Bash", bash.notice)
        assertTrue(ask.asksForInput && form.asksForInput && !bash.asksForInput)
        assertEquals("MCP 服务器 eli 需要你填写信息", form.notice)
        assertEquals(
            JsonArray(emptyList()),
            PermissionRequest("x", "Read", JsonObject(emptyMap())).suggestions,
        )
    }
}
