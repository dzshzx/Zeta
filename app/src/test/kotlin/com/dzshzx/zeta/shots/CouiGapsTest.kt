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
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.LocalContentColor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * COUI 2 的三处缺口，Zeta 在本地补着（见 docs/ui.md「补上游缺口」）。这里断言缺口还在：
 * 升级 COUI 后哪条失败，说明上游已经补上，删掉对应的本地补丁，再删掉这条断言。
 */
class CouiGapsTest : ShotTest() {
    /** 补丁在 `IconActionButton`：不能点时自己把图标染成 disabledNeutral，并补上按钮角色和「已停用」。 */
    @Test
    fun iconButtonLeavesDisabledLookToCaller() {
        var inner = Color.Unspecified
        var outer = Color.Unspecified
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) {
                outer = LocalContentColor.current
                IconButton(onClick = {}, enabled = false, modifier = Modifier.testTag("button")) { inner = LocalContentColor.current }
            }
        }
        compose.waitForIdle()
        assertEquals("COUI 的 IconButton 已经给禁用的内容换色：删掉 IconActionButton 里的 tint 换色", outer, inner)
        compose.onNodeWithTag("button").assert(
            SemanticsMatcher("不能点时没有按钮角色") { SemanticsProperties.Role !in it.config },
        )
    }

    /** 补丁在 `menuItem`：图标颜色照 DropdownColors 按可用、警示状态自己取。 */
    @Test
    fun dropdownRowLeavesIconColorToCaller() {
        var inner = Color.Unspecified
        var primary = Color.Unspecified
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) {
                primary = COUITheme.colorScheme.labelPrimary
                DropdownImpl(
                    item = DropdownItem(text = "删除", enabled = false, alert = true, icon = { inner = LocalContentColor.current }),
                    optionSize = 1,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {},
                )
            }
        }
        compose.waitForIdle()
        assertEquals("COUI 的菜单行已经给图标上色：删掉 menuItem 里的 tint", primary, inner)
    }

    /** 补丁在 ui/components/coui2 的 `TextField`（TextFields.kt）：用 COUI 的 TextFieldValue 版本，光标自己管（suqi8/coui#20）。 */
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
            "COUI 的 String 版 TextField 已经把光标放在末尾：删掉 TextFields.kt 和 TextFieldSourcesTest，TextInputDialog 可以改回 OverlayInputDialog",
            "/claude/workspace",
            text,
        )
    }
}
