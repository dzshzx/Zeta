package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.R
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.blur.MaterialBlurDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.layout.InputDialogDefaults
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme

// 页面上的对话框：COUI 2 的 Overlay 对话框，加上当前页面的毛玻璃（见 CouiBlur.kt）。
// 要放在 CouiPage 的 dialogs 里，或 CouiPageScaffold 内容里和列表并排；不放进列表项，否则列表项滚出屏幕时对话框跟着没了。
// 自己的内容放 customContent（在说明和按钮栏之间，左右留 DialogContentPadding 和标题、说明对齐）：放在那里，只有标题的对话框
// 不会多出 16dp 空白，内容也会在面板不够高时自己缩小滚动，按钮栏始终看得见。content 只放按钮栏（DialogButtonBar）。

/** 对话框内容的左右留白，和 COUI 对话框的标题、说明对齐。 */
internal val DialogContentPadding = 24.dp

/** 带页面毛玻璃的 OverlayDialog：[customContent] 是自己的内容，[content] 放按钮栏（DialogButtonBar）。 */
@Composable
internal fun PageDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    customContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val blur = panelBlur(MaterialBlurDefaults::dialog)
    OverlayDialog(
        show = show,
        modifier = modifier,
        backgroundModifier = blur?.backgroundModifier ?: Modifier,
        title = title,
        summary = summary,
        backgroundColor = blur?.backgroundColor ?: DialogDefaults.backgroundColor(),
        onDismissRequest = onDismissRequest,
        materialStroke = blur != null,
        customContent = customContent,
        content = content,
    )
}

/**
 * 确认对话框：取消 + 确认。确认是主操作（主题色实心）；[destructive] 时确认放进 COUI 画成红色的 neutral 位置。
 * 点取消默认和点外面、按返回一样走 [onDismissRequest]；要区分「明确拒绝」和「随手关掉」时另给 [onCancel]。
 * [content] 是说明下面的额外内容（放进 customContent，已留好左右边距）。
 */
@Composable
internal fun ConfirmDialog(
    show: Boolean,
    title: String?,
    summary: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = stringResource(R.string.action_cancel),
    onCancel: () -> Unit = onDismissRequest,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    PageDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        summary = summary,
        customContent = content?.let { { Box(Modifier.padding(horizontal = DialogContentPadding)) { it() } } },
    ) {
        val confirm = DialogButtonBarAction(confirmText, enabled = confirmEnabled, recommended = !destructive, onClick = onConfirm)
        DialogButtonBar(
            negative = DialogButtonBarAction(cancelText, onClick = onCancel),
            positive = if (destructive) null else confirm,
            neutral = if (destructive) confirm else null,
        )
    }
}

/** 只有一个关闭按钮的告知对话框。 */
@Composable
internal fun NoticeDialog(
    show: Boolean,
    title: String?,
    summary: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    closeText: String = stringResource(R.string.action_close),
    content: (@Composable () -> Unit)? = null,
) {
    PageDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        summary = summary,
        customContent = content?.let { { Box(Modifier.padding(horizontal = DialogContentPadding)) { it() } } },
    ) {
        DialogButtonBar(negative = null, positive = DialogButtonBarAction(closeText, recommended = true, onClick = onDismissRequest))
    }
}

/**
 * 单行输入对话框：取消 + 确认，[label] 是输入框里的提示。打开时输入框自动聚焦、弹出键盘，光标在已有文字的末尾。
 * 样子照 COUI 2 的 OverlayInputDialog（COUI 的 SingleEdit：线条输入框、带清除按钮、最多 5 行），输入框换成光标由 Zeta 管的
 * [TextField]。普通对话框在自己的内容下面留 8dp，正好是 SingleEdit 输入框下面的那段，所以输入框只留上面的 8dp。
 */
@Composable
internal fun TextInputDialog(
    show: Boolean,
    title: String?,
    value: String,
    onValueChange: (String) -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    label: String = "",
    confirmEnabled: Boolean = true,
    cancelText: String = stringResource(R.string.action_cancel),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    PageDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        summary = summary,
        customContent = {
            val focus = remember { FocusRequester() }
            LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .padding(horizontal = InputDialogDefaults.FieldMarginHorizontal)
                    .padding(top = InputDialogDefaults.EditMarginVertical)
                    .fillMaxWidth()
                    .focusRequester(focus),
                label = label,
                showClearButton = true,
                keyboardOptions = keyboardOptions,
                maxLines = InputDialogDefaults.MaxLines,
            )
        },
    ) {
        DialogButtonBar(
            negative = DialogButtonBarAction(cancelText, onClick = onDismissRequest),
            positive = DialogButtonBarAction(confirmText, enabled = confirmEnabled, recommended = true, onClick = onConfirm),
        )
    }
}

/**
 * 对话框里的一个输入框：上面一行小字说明填什么（[title]），框里是占位提示（[hint]，不给就用 [title]）。
 * COUI 2 的 TextField 只有框里的提示，这几个对话框（添加 MCP 服务器、规则、共享文件夹）要同时说清字段和举例，所以上面另放一行。
 */
@Composable
internal fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) Text(text = title, style = COUITheme.textStyles.bodyS, color = COUITheme.colorScheme.labelSecondary)
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = hint ?: title.orEmpty(),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
    }
}
