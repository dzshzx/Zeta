package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.SlashCommand
import com.dzshzx.zeta.core.core
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** 输入栏的斜杠命令提示：句中空一格再打 / 也出（同 Claude Code 终端界面），点一条只换掉光标处那个词。借截图测试的 Robolectric 环境，不出图。 */
class SlashSuggestionsScreenTest : ShotTest() {
    private val commands =
        listOf(
            SlashCommand("compact", "压缩上下文", "<说明>"),
            SlashCommand("clear", "清空对话", ""),
            SlashCommand("review", "审查改动", ""),
            SlashCommand("config", "Claude Code 配置", "key=value"),
        )

    private fun show(onOpenClaudeConfig: ((Conversation, ClaudeConfigCommand) -> Unit)? = null) {
        val hub = Fixtures.readyHub(app)
        compose.runOnUiThread {
            val conversation =
                Conversation(hub, Fixtures.env.defaultCwd, "s-a", "会话", historyFile = null)
            conversation.core { it.copy(commands = commands) }
            hub.park(conversation)
            hub.openConversation("s-a")
        }
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                ChatScreen(
                    hub = hub,
                    onOpenPane = {},
                    onOpenSettings = {},
                    onOpenInstall = {},
                    onOpenLogin = {},
                    onOpenClaudeConfig = onOpenClaudeConfig,
                )
            }
        }
    }

    private val field
        get() = compose.onNode(hasSetTextAction())

    @Test
    fun localConfigCommandsAreSuggestedAndOpenForTheCurrentConversation() {
        val opened = mutableListOf<Pair<String, ClaudeConfigCommand>>()
        show { conversation, command -> opened += conversation.key to command }
        for ((prefix, name, command) in
            listOf(
                Triple("/conf", "/config", ClaudeConfigCommand.Config),
                Triple("/perm", "/permissions", ClaudeConfigCommand.Permissions),
            )) {
            field.performTextInput(prefix)
            compose.onNodeWithText(name).performClick()
            field.assertTextEquals("$name ")
            compose.onNodeWithContentDescription(Strings.composerSend()).performClick()
            compose.runOnIdle { assertEquals("s-a" to command, opened.last()) }
        }
        compose.runOnIdle { assertEquals(2, opened.size) }
    }

    @Test
    fun suggestsMidTextAndReplacesTheWord() {
        show()
        field.performTextInput("帮我 /co")
        compose.onAllNodesWithText("/review").assertCountEquals(0)
        compose.onNodeWithText("/compact").performClick()
        field.assertTextEquals("帮我 /compact ")

        // 紧挨着别的字不出
        field.performTextInput("你好/c")
        compose.onAllNodesWithText("/clear").assertCountEquals(0)

        // 光标挪回句中那个词：按 / 到光标的字列，点了只换掉那个词
        field.performTextInput(" 再 /re 完")
        val text = "帮我 /compact 你好/c 再 /re 完"
        field.performTextInputSelection(TextRange(text.indexOf("/re") + 2))
        compose.onNodeWithText("/review").performClick()
        field.assertTextEquals("帮我 /compact 你好/c 再 /review 完")
        field.performTextInput("x")
        field.assertTextEquals("帮我 /compact 你好/c 再 /review x完")
    }
}
