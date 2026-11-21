package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import io.github.suqi8.coui.kmp.basic.TextField as CouiTextField
import io.github.suqi8.coui.kmp.basic.TextFieldColors
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode

/**
 * COUI 2 的输入框，文字按 String 传进传出，参数和 COUI 的同名参数一样。
 *
 * 光标由这里管，不用 COUI 自己的 String 版本：那一版建输入状态时光标在开头，文字从外面改了（清除按钮、调用方重设）以后 光标也不跟到末尾（suqi8/coui#20）。这里用
 * COUI 的 TextFieldValue 版本：自己打字时光标和输入法的组合区照常保留； 第一次显示、文字从外面变了时，光标放到末尾。
 */
@Composable
internal fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    backgroundMode: TextFieldMode = TextFieldMode.Line,
    insideMargin: DpSize = TextFieldDefaults.insideMargin(backgroundMode),
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    minHeight: Dp = TextFieldDefaults.minHeight(backgroundMode),
    label: String = "",
    useLabelAsPlaceholder: Boolean = true,
    enabled: Boolean = true,
    showClearButton: Boolean = false,
    textStyle: TextStyle = TextFieldDefaults.textStyle(backgroundMode),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var typed by remember { mutableStateOf(atEnd(value)) }
    // 把打的字交给调用方时记下打字前的样子；下一次组合看到的 value 就是调用方的回应，看完清掉。
    // 那次组合本来就由 typed 的写入引起，所以这里用普通引用，不用 State：清掉时不必再重组一次
    val beforeEdit = remember { arrayOfNulls<TextFieldValue>(1) }
    val shown = shownValue(value, typed, beforeEdit[0])
    SideEffect {
        beforeEdit[0] = null
        typed = shown
    }
    CouiTextField(
        value = shown,
        onValueChange = { new ->
            typed = new
            if (new.text != value) {
                beforeEdit[0] = shown
                onValueChange(new.text)
            }
        },
        modifier = modifier,
        backgroundMode = backgroundMode,
        insideMargin = insideMargin,
        colors = colors,
        minHeight = minHeight,
        label = label,
        useLabelAsPlaceholder = useLabelAsPlaceholder,
        enabled = enabled,
        showClearButton = showClearButton,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailingIcon,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        visualTransformation = visualTransformation,
    )
}

/** 这一次组合给输入框的文字和光标。[typed] 是自己上次打出来的，[beforeEdit] 不为空时 [value] 是调用方对刚打的字的回应。 */
private fun shownValue(
    value: String,
    typed: TextFieldValue,
    beforeEdit: TextFieldValue?,
): TextFieldValue =
    when {
        // 和自己上次打出来的一样：沿用那时的光标和输入法的组合区
        typed.text == value -> typed
        beforeEdit == null -> atEnd(value) // 调用方自己换了文字：光标放到末尾
        beforeEdit.text == value -> beforeEdit // 调用方没收下刚打的字（滤掉、到了上限）：回到打字前
        else -> TextFieldValue(value, typed.selection) // 调用方改写了刚打的字（比如转大写）：光标留在原处
    }

private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))
