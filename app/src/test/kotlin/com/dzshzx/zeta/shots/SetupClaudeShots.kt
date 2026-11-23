package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.awaitMain
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.ui.setup.ClaudeAgentsScreen
import com.dzshzx.zeta.ui.setup.ClaudeMcpScreen
import com.dzshzx.zeta.ui.setup.ClaudeMemoryScreen
import com.dzshzx.zeta.ui.setup.ClaudeSkillsScreen
import com.dzshzx.zeta.ui.setup.ConfigDocumentEditor
import com.dzshzx.zeta.ui.setup.ToolPermissionsScreen
import org.junit.Test

/**
 * 设置里主机页「Claude Code 配置」的几页。它们经那台的核心（zeta-helper serve）读 Claude Code 的配置，这里没有 root、起不了核心：
 * 先弹“读取失败”的告知框（[memoryNotice] 留着它），其余几张点“知道了”关掉，看页面本身。连核心失败的结果在后台线程上投回来，先等它到。
 */
class SetupClaudeShots : ShotTest() {
    /** 等读取失败的结果投回来、页面上出现 [text]：每轮时钟走一帧，10 秒内等不到就失败。 */
    private fun AndroidComposeTestRule<*, *>.awaitText(text: String) =
        awaitMain(10_000) {
            mainClock.advanceTimeByFrame()
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }

    private val awaitNotice: AndroidComposeTestRule<*, *>.() -> Unit = { awaitText("知道了") }

    /** 点“知道了”关掉告知框；时钟拨过它的退场，后面再点页面才不会点到它的遮罩上。 */
    private val dismissNotice: AndroidComposeTestRule<*, *>.() -> Unit = {
        awaitNotice()
        onNodeWithText("知道了").performClick()
        mainClock.advanceTimeBy(500)
    }

    @Test
    fun memoryNotice() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-memory-notice", prepare = awaitNotice) {
            ClaudeMemoryScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    @Test
    fun memory() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-memory", prepare = dismissNotice) {
            ClaudeMemoryScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    @Test
    fun skills() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-skills", prepare = dismissNotice) {
            ClaudeSkillsScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    @Test
    fun mcp() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-mcp", prepare = dismissNotice) {
            ClaudeMcpScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    /** 右上角 + 弹出的添加服务器对话框。 */
    @Test
    fun mcpAdd() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "claude-mcp-add",
            prepare = {
                dismissNotice()
                onNodeWithContentDescription("添加服务器").performClick()
            },
        ) {
            ClaudeMcpScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    @Test
    fun agents() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-agents", prepare = dismissNotice) {
            ClaudeAgentsScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    /** 右上角 + 打开的新建 Subagent 编辑页。 */
    @Test
    fun agentEditor() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "claude-agent-editor",
            prepare = {
                dismissNotice()
                onNodeWithContentDescription("新建 Subagent").performClick()
            },
        ) {
            ClaudeAgentsScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    /** 记忆、Subagent 共用的文档编辑页：切到编辑、改了内容，底下出现保存条。 */
    @Test
    fun documentEditor() {
        val hub = Fixtures.readyHub(app)
        shoot(
            "claude-document",
            prepare = {
                dismissNotice()
                onNodeWithText("编辑").performClick()
                // 预览和编辑之间有个淡入淡出，时钟拨过去编辑框才在
                mainClock.advanceTimeBy(1000)
                onNode(hasSetTextAction()).performTextInput("回答用中文。\n改代码前先说明打算怎么改。")
            },
        ) {
            ConfigDocumentEditor(
                client = ClaudeConfigCore.of(hub, hub.backend),
                path = "/home/dzshzx/.claude/CLAUDE.md",
                title = "用户指令",
                fileLabel = "CLAUDE.md",
                placeholder = "比如：回答用中文",
                onBack = {},
                onSaved = {},
                deleteTitle = "删除这条记忆",
                onDelete = {},
            )
        }
    }

    /** 读不出规则时：说明里带着读取失败的原因，下拉都是灰的。 */
    @Test
    fun permissions() {
        val hub = Fixtures.readyHub(app)
        shoot("claude-permissions", prepare = { awaitText("读取失败") }) {
            ToolPermissionsScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }
}
