package com.dzshzx.zeta.shots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.LocalContentColor

/**
 * Miuix 的两处缺口，Zeta 在本地补着（见 docs/ui.md「补上游缺口」）。这里断言缺口还在：升级 Miuix 后哪条失败，说明上游已经补上，删掉对应的本地补丁，再删掉这条断言。
 */
class MiuixGapsTest : ShotTest() {
    /** 补丁在 `IconActionButton`：不能点时自己把图标染成 disabledOnSurface，并补上按钮角色和「已停用」。 */
    @Test
    fun iconButtonLeavesDisabledLookToCaller() {
        var inner = Color.Unspecified
        var outer = Color.Unspecified
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) {
                outer = LocalContentColor.current
                IconButton(onClick = {}, enabled = false, modifier = Modifier.testTag("button")) {
                    inner = LocalContentColor.current
                }
            }
        }
        compose.waitForIdle()
        assertEquals("Miuix 的 IconButton 已经给禁用的内容换色：删掉 IconActionButton 里的 tint 换色", outer, inner)
        compose
            .onNodeWithTag("button")
            .assert(
                SemanticsMatcher("不能点时没有按钮角色") { SemanticsProperties.Role !in it.config },
            )
    }

    /** 补丁在 ui/components/miuix 的 `TextField`（TextFields.kt）：用 Miuix 的 TextFieldValue 版本，光标自己管。 */
    @Test
    fun stringTextFieldStartsWithCursorAtStart() {
        var text by mutableStateOf("/workspace")
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) {
                TextField(value = text, onValueChange = { text = it }, singleLine = true)
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("/claude")
        assertEquals(
            "Miuix 的 String 版 TextField 已经把光标放在末尾。先在设备上复验「点清除后接着打字，字按顺序排」（这里测不出来，见 docs/ui.md），" +
                "也对了再删 TextFields.kt 里的光标处理和 TextFieldSourcesTest",
            "/claude/workspace",
            text,
        )
    }
}
