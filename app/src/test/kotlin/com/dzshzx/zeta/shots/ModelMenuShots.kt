package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.ReportedModels
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.settings.DefaultEffortScreen
import com.dzshzx.zeta.ui.settings.HostSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型菜单只直接列各系列最新的，旧版本在「更多模型」二级菜单里；清单是 Claude Code 2.1.329 实际报的那 14 项。 */
class ModelMenuShots : ShotTest() {
    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.models = mapOf(Backends.LOCAL to ReportedModels) }

    /** 顶栏的模型菜单：默认和四个系列最新的，最后是「更多模型」。 */
    @Test
    fun chatMenu() {
        val hub = hub()
        shoot("model-menu", prepare = { tap("Claude · Opus 5.5") }) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 点开「更多模型」：旧版本按系列、从新到旧；这个对话选的是 Opus 4.8，「更多模型」下面写着它。 */
    @Test
    fun chatMenuOlder() {
        val hub = hub()
        hub.current.chooseModel("claude-opus-4-8")
        shoot(
            "model-menu-older",
            prepare = {
                tap("Claude · Opus 4.8")
                tap("更多模型")
            },
        ) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 在「更多模型」里点一个旧版本：这个对话换成它，菜单收起。 */
    @Test
    fun choosingAnOlderModel() {
        val hub = hub()
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
        compose.tap("Claude · Opus 5.5")
        compose.tap("更多模型")
        compose.tap("Opus 4.7")
        assertEquals("claude-opus-4-7", hub.current.model)
        assertTrue(
            "顶栏换成 Opus 4.7",
            runCatching { compose.onNodeWithText("Claude · Opus 4.7").fetchSemanticsNode() }
                .isSuccess,
        )
        assertTrue("菜单收起了", compose.onAllNodesWithText("Opus 4.6").fetchSemanticsNodes().isEmpty())
    }

    /** 设置 › 手机里的默认模型：同样两层。 */
    @Test
    fun settingsDefaultModel() {
        val hub = hub()
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults(
                model = null,
                permissionMode = null,
                efforts = emptyMap(),
                effort = null,
            ),
        )
        shoot(
            "model-settings-older",
            prepare = {
                tap("默认模型")
                tap("更多模型")
            },
        ) {
            HostSettingsScreen(hub = hub, backend = Backends.LOCAL, onBack = {}, onOpenLinux = {})
        }
    }

    /** 默认思考强度：各系列最新的和设过的旧版本（Opus 4.6）直接列出，其余旧版本收在「更多模型」一行后面。 */
    @Test
    fun defaultEffortFolded() {
        val hub = hub()
        hub.updateClaudeDefaults(
            Backends.LOCAL,
            ClaudeDefaults(
                model = null,
                permissionMode = null,
                efforts = mapOf("claude-opus-4-6" to "high"),
                effort = null,
            ),
        )
        shoot("model-default-effort") {
            DefaultEffortScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }
}
