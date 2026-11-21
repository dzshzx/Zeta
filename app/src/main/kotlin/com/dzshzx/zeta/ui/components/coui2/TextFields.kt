package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
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
import io.github.suqi8.coui.kmp.basic.TextFieldColors
import io.github.suqi8.coui.kmp.basic.TextFieldDefaults
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.basic.TextField as CouiTextField

/**
 * COUI 2 的输入框，文字按 String 传进传出，参数和 COUI 的同名参数一样。
 *
 * 光标由这里管，不用 COUI 自己的 String 版本：那一版建输入状态时光标在开头，文字从外面改了（清除按钮、调用方重设）以后
 * 光标也不跟到末尾（suqi8/coui#20）。这里用 COUI 的 TextFieldValue 版本：自己打字时光标和输入法的组合区照常保留；
 * 第一次显示、文字从外面变了时，光标放到末尾。
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
    // 文字和自己上次打出来的一样时沿用那时的光标；不一样就是外面改的
    val shown = if (typed.text == value) typed else atEnd(value)
    CouiTextField(
        value = shown,
        onValueChange = { new ->
            typed = new
            if (new.text != value) onValueChange(new.text)
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

private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))
