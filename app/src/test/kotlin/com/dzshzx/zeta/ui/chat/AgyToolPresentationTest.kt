package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.FakeCore
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.awaitCore
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.fileChangeOf
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.PaneShot
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.settings.HostSettingsScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Antigravity 工具卡片展示、手机 MCP 前缀兼容，以及新对话工具切换、会话角标与主机设置页切换的测试。 */
class AgyToolPresentationTest : ShotTest() {
    private fun input(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun agyToolsArePresentedWithFriendlyTitlesAndDiffs() {
        val cmd =
            presentTool(
                "run_command",
                input("""{"CommandLine":"go test ./...","toolSummary":"Run unit tests"}"""),
            )
        assertEquals("Run unit tests", cmd.title)
        assertEquals("go test ./...", cmd.detail)

        val read =
            presentTool(
                "read_file",
                input("""{"Path":"/workspace/app/Main.kt","StartLine":1,"EndLine":50}"""),
            )
        assertEquals(Strings.chatToolRead("Main.kt"), read.title)
        assertEquals("/workspace/app/Main.kt", read.detail)

        val editIn =
            input(
                """{"Path":"/workspace/app/Main.kt","Target":"val a = 1","Replacement":"val a = 2"}""",
            )
        val edit = presentTool("edit_file", editIn)
        assertEquals(Strings.chatToolEdit("Main.kt"), edit.title)
        val editChange = fileChangeOf("edit_file", editIn, patch = null)
        assertNotNull(editChange)
        assertEquals("/workspace/app/Main.kt", editChange!!.path)

        val writeIn = input("""{"Path":"/workspace/app/New.kt","Content":"package app\n"}""")
        val write = presentTool("write_file", writeIn)
        assertEquals(Strings.chatToolWrite("New.kt"), write.title)
        assertNotNull(fileChangeOf("write_file", writeIn, patch = null))

        val grep =
            presentTool(
                "grep_search",
                input("""{"Query":"setHarness","SearchPath":"/workspace"}"""),
            )
        assertEquals(Strings.chatToolGrep("setHarness"), grep.title)

        val find =
            presentTool(
                "find_by_name",
                input("""{"Pattern":"*.kt","SearchDirectory":"/workspace"}"""),
            )
        assertEquals(Strings.chatToolGlob("*.kt"), find.title)

        val web = presentTool("search_web", input("""{"query":"kotlin multiplatform"}"""))
        assertEquals(Strings.chatToolWebSearch("kotlin multiplatform"), web.title)

        val sub =
            presentTool(
                "invoke_subagent",
                input("""{"Role":"Code Reviewer","TypeName":"research"}"""),
            )
        assertEquals(Strings.chatToolAgent("Code Reviewer"), sub.title)

        val ask =
            presentTool(
                "ask_question",
                input("""{"questions":[{"question":"选哪个方案？","options":["A","B"]}]}"""),
            )
        assertEquals(Strings.chatToolAsk(), ask.title)

        val steps =
            presentTool(
                "update_steps",
                input(
                    """{"steps":[{"title":"第一步","status":"done"},{"title":"第二步","status":"in_progress"}]}""",
                ),
            )
        assertEquals(Strings.chatToolUpdateStepsCount(2), steps.title)
        assertEquals("[x] 第一步\n[~] 第二步", steps.detail)

        val task = presentTool("manage_task", input("""{"Action":"status","TaskId":"bg-1"}"""))
        assertTrue(task.title, task.title.contains("bg-1"))

        // 兼容 mcp__phone__* 与 agy 的 mcp_phone_* 前缀
        assertEquals("tap", phoneToolSuffix("mcp__phone__tap"))
        assertEquals("tap", phoneToolSuffix("mcp_phone_tap"))
        val agyPhone = presentTool("mcp_phone_tap", input("""{"match":"确定"}"""))
        assertEquals("点按「确定」", agyPhone.title)
    }

    @Test
    fun newChatHarnessSwitcherUpdatesConversationAndHeader() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                ChatScreen(
                    hub = hub,
                    onOpenPane = {},
                    onOpenSettings = {},
                    onOpenInstall = {},
                    onOpenLogin = {},
                )
            }
        }
        compose.onNodeWithText("Antigravity").performClick()
        val opened = core.next("conv.open")
        assertEquals(Harnesses.AGY, opened.str("harness"))
        awaitCore { hub.current.harness == Harnesses.AGY }
        compose.onNodeWithText("Antigravity · 默认").assertExists()
        compose.onNodeWithText("交给 Antigravity 去完成").assertExists()
    }

    @Test
    fun sessionPaneShowsAgyBadge() {
        Fixtures.readyHub(app)
        val sessions =
            listOf(
                SessionSummary(
                    id = "s-agy",
                    file = "/f/s-agy.jsonl",
                    cwd = "/workspace/claude",
                    title = "Antigravity 会话",
                    preview = "摘要",
                    updated = 1_795_000_000_000L,
                    harness = Harnesses.AGY,
                ),
            )
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                PaneShot(sessions = sessions)
            }
        }
        compose.onNodeWithText("Antigravity 会话").assertExists()
        compose.onNodeWithText("agy").assertExists()
    }

    @Test
    fun hostSettingsShowsDefaultHarnessAndAntigravityItems() {
        val hub = Fixtures.readyHub(app)
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults(null, null, emptyMap(), null),
            Harnesses.AGY,
        )
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                HostSettingsScreen(
                    hub = hub,
                    backend = Backends.LOCAL,
                    onBack = {},
                    onOpenLinux = {},
                    onShowChat = {},
                    initialHarness = Harnesses.AGY,
                )
            }
        }
        compose.onNodeWithText("新对话默认工具").assertExists()
        compose.onNodeWithText("Antigravity CLI").assertExists()
        compose.onNodeWithText("Antigravity 内置默认").assertExists()
        compose.onNodeWithText("未设置，使用 Antigravity 默认值").assertExists()
    }
}
