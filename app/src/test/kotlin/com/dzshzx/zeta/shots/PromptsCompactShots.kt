package com.dzshzx.zeta.shots

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatUsage
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.PromptStore
import com.dzshzx.zeta.core.SavedPrompts
import com.dzshzx.zeta.core.TokenCounts
import com.dzshzx.zeta.core.TurnUsage
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.SettingsScreen
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.chat.PromptsUi
import com.dzshzx.zeta.ui.chat.SavedPromptsDialogs
import org.junit.Test

/** 常用指令（列表、编辑）和自动压缩（设置页一组、用量弹窗里的压缩点）。 */
class PromptsCompactShots : ShotTest() {
    private fun hub(): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.showChat(
            "看看磁盘",
            listOf(
                UserItem("u1", "看看 /workspace 还剩多少空间，哪些目录最大"),
                AnswerItem("a1", "还剩 **38 GB**（共 220 GB）。最大的三个目录：\n\n- `claude/ref` 61 GB\n- `claude/zeta` 9 GB\n- `tmp` 4 GB", streaming = false),
            ),
        )
        PromptStore.reload(app)
        PromptStore.update(app) { list ->
            var next = SavedPrompts.add(list, "p3", "", "看看 /workspace 还剩多少空间，哪些目录最大")
            next = SavedPrompts.add(next, "p2", "周报", "汇总这周的提交和改动，按项目分组，每组三五行。\n最后列出没做完的事。")
            SavedPrompts.add(next, "p1", "", "检查 Git 状态和最近的提交，告诉我有哪些没提交的改动")
        }
        // 40 万压缩窗口，Zeta 设了 70%：压缩点 26.6 万
        hub.current.force(
            "usage",
            ChatUsage(
                contextTokens = 214_300,
                contextWindow = 400_000,
                model = "claude-opus-5-5",
                turns = listOf(TurnUsage(TokenCounts(1200, 860, 205_000, 7_240), 0.41, 18_200)),
                autoCompact = true,
                compactAt = 266_000,
            ),
        )
        return hub
    }

    private fun chat(hub: ConversationHub) = @Composable {
        ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
    }

    /** COUI 的菜单画不进截图（“+”菜单、长按菜单），对话框由测试直接打开。 */
    private fun withPrompts(hub: ConversationHub, open: PromptsUi.() -> Unit) = @Composable {
        val ui = remember { PromptsUi(app, TextFieldState()).apply(open) }
        ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        SavedPromptsDialogs(ui)
    }

    @Test
    fun promptList() {
        shoot("prompts-list", content = withPrompts(hub()) { open() })
    }

    @Test
    fun promptEditor() {
        shoot("prompts-editor", content = withPrompts(hub()) { open(); startEdit(prompts[1]) })
    }

    @Test
    fun usageWithCompactPoint() {
        val hub = hub()
        shoot("usage-compact-point", prepare = {
            onNode(hasContentDescription("上下文用量", substring = true)).performClick()
        }, content = chat(hub))
    }

    @Test
    fun settingsGroup() {
        val hub = Fixtures.readyHub(app)
        hub.settings.autoCompactPercent = 70
        shoot("settings-compact", prepare = { onNodeWithText("压缩时机").performScrollTo() }) {
            SettingsScreen(hub = hub, onBack = {}, onOpenLinux = {}, onOpenPhone = {})
        }
    }
}
