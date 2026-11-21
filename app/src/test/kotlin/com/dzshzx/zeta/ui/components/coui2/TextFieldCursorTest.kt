package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextField] 和 [TextInputDialog] 的光标：带着文字显示时在末尾，文字从外面改了也到末尾，清空以后打的字按顺序排。
 * 借截图测试的 Robolectric 环境，不出图。
 */
class TextFieldCursorTest : ShotTest() {
    private var text by mutableStateOf("")

    private fun field() = compose.onNode(hasSetTextAction())

    private fun showField(initial: String) {
        text = initial
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                TextField(value = text, onValueChange = { text = it }, label = "目录", singleLine = true, showClearButton = true)
            }
        }
    }

    @Test
    fun typedTextGoesAfterInitialText() {
        showField("/workspace")
        field().performTextInput("/claude")
        assertEquals("/workspace/claude", text)
    }

    @Test
    fun cursorMovesToEndWhenTextChangesOutside() {
        showField("/home")
        field().performTextInput("/a")
        text = "/data/local"
        compose.waitForIdle()
        field().performTextInput("/b")
        assertEquals("/data/local/b", text)
    }

    @Test
    fun typesInOrderAfterClearButton() {
        showField("/workspace")
        field().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithContentDescription("Clear text").performClick()
        assertEquals("", text)
        field().performTextInput("/")
        field().performTextInput("home")
        field().performTextInput("/work")
        assertEquals("/home/work", text)
    }

    @Test
    fun dialogOpensFocusedWithCursorAtEnd() {
        text = "/workspace"
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                CouiPage(
                    title = "设置",
                    dialogs = {
                        TextInputDialog(
                            show = true,
                            title = "默认工作目录",
                            value = text,
                            onValueChange = { text = it },
                            confirmText = "确定",
                            onConfirm = {},
                            onDismissRequest = {},
                        )
                    },
                ) {}
            }
        }
        field().assertIsFocused()
        field().performTextInput("/claude")
        assertEquals("/workspace/claude", text)
    }
}
