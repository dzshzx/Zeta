package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.dzshzx.zeta.shots.ShotTest
import com.dzshzx.zeta.ui.app.ZetaTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TextField] 和 [TextInputDialog] 的光标：带着文字显示时在末尾，文字从外面改了也到末尾。借截图测试的 Robolectric 环境，不出图。
 * 「点清除后打完第一个字光标跳回开头」要真的输入法才出现，这里复现不了（COUI 原版在这里也是对的），见 [com.dzshzx.zeta.shots.CouiGapsTest]。
 */
class TextFieldCursorTest : ShotTest() {
    private var text by mutableStateOf("")

    private fun field() = compose.onNode(hasSetTextAction())

    private fun showField(initial: String, rewrite: (String) -> String = { it }) {
        text = initial
        compose.setContent {
            ZetaTheme(windowBackdrop = false) {
                TextField(
                    value = text,
                    onValueChange = { text = rewrite(it) },
                    label = "目录",
                    singleLine = true,
                    showClearButton = true,
                )
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
        compose.runOnIdle { text = "/data/local" }
        field().performTextInput("/b")
        assertEquals("/data/local/b", text)
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

    /** 调用方改写刚打的字时光标留在原处：在中间接着打的字按顺序排，不跑到末尾（suqi8/coui#20 的例子）。 */
    @Test
    fun cursorStaysWhenCallerUppercasesTypedText() {
        showField("AB") { it.uppercase() }
        field().performTextInputSelection(TextRange(1))
        field().performTextInput("x")
        field().performTextInput("y")
        assertEquals("AXYB", text)
    }

    /** 调用方滤掉刚打的字（文字没变）时光标也留在原处。 */
    @Test
    fun cursorStaysWhenCallerDropsTypedText() {
        showField("19") { value -> value.filter(Char::isDigit) }
        field().performTextInputSelection(TextRange(1))
        field().performTextInput("a")
        field().performTextInput("0")
        assertEquals("109", text)
    }
}
