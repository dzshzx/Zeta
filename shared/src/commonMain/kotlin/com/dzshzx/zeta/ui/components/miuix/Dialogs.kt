package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.i18n.Strings
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 页面上的对话框：Miuix 的玻璃对话框（HyperOS 4），模糊的是当前页面录下的背景（见 MiuixBlur.kt）。
// 要放在 MiuixPage 的 dialogs 里，或 MiuixPageScaffold 内容里和列表并排；不放进列表项，否则列表项滚出屏幕时对话框跟着没了。
// 自己的内容放 customContent（在说明和按钮栏之间）：面板不够高时它自己滚动，按钮栏始终看得见。content 只放按钮栏（DialogButtonBar）。
// 面板左右不留白，标题、说明、按钮栏和内容各自留 DialogContentPadding：内容里的行（确认弹层的选项）能铺满整宽、按下时整行高亮。

/** 对话框内容的左右留白，和标题、说明对齐（Miuix 对话框的内边距）。 */
val DialogContentPadding = DialogDefaults.insideMargin.width

/** 带页面毛玻璃的对话框：[customContent] 是自己的内容，[content] 放按钮栏（DialogButtonBar）。标题、说明照 Miuix 对话框的样子居中。 */
@Composable
fun PageDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    customContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        backdrop = LocalPageBackdrop.current,
        modifier = modifier,
        insideMargin = DpSize(0.dp, DialogDefaults.insideMargin.height),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (title != null) DialogHeading(title, title = true)
            if (summary != null) DialogHeading(summary, title = false)
            if (customContent != null) {
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    customContent()
                }
            }
            Box(Modifier.padding(horizontal = DialogContentPadding).padding(top = 12.dp)) {
                content()
            }
        }
    }
}

/** 对话框的标题（居中、title4 字号、中粗）或说明（居中、body1 字号），照 Miuix 对话框自己画的样子。 */
@Composable
private fun DialogHeading(text: String, title: Boolean) {
    Text(
        text = text,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = DialogContentPadding)
                .padding(bottom = 12.dp),
        fontSize =
            if (title) MiuixTheme.textStyles.title4.fontSize
            else MiuixTheme.textStyles.body1.fontSize,
        fontWeight = if (title) FontWeight.Medium else null,
        textAlign = TextAlign.Center,
        color = if (title) DialogDefaults.titleColor() else DialogDefaults.summaryColor(),
    )
}

/** 对话框按钮栏上的一个按钮：[recommended] 是主操作（主题色），[destructive] 是危险操作（红色）。 */
class DialogButtonBarAction(
    val text: String,
    val enabled: Boolean = true,
    val recommended: Boolean = false,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 对话框底部的按钮栏（照 Eta 的 DialogActions）：取消在左、确认在右，平分整行。 */
@Composable
fun DialogButtonBar(
    negative: DialogButtonBarAction?,
    positive: DialogButtonBarAction?,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOfNotNull(negative, positive).forEach { action ->
            GlassDialogButton(
                onClick = action.onClick,
                modifier = Modifier.weight(1f),
                enabled = action.enabled,
                role =
                    when {
                        action.destructive -> GlassDialogButtonRole.Destructive
                        action.recommended -> GlassDialogButtonRole.Primary
                        else -> GlassDialogButtonRole.Normal
                    },
            ) {
                Text(
                    text = action.text,
                    style = MiuixTheme.textStyles.button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 确认对话框：取消 + 确认。确认是主操作（主题色）；[destructive] 时确认画成红色。点取消和点外面、按返回一样走 [onDismissRequest]。 [content]
 * 是说明下面的额外内容（放进 customContent，已留好左右边距）。
 */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String?,
    summary: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String = Strings.commonCancel(),
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
        customContent = padded(content),
    ) {
        val confirm =
            DialogButtonBarAction(
                confirmText,
                enabled = confirmEnabled,
                recommended = !destructive,
                destructive = destructive,
                onClick = onConfirm,
            )
        DialogButtonBar(
            negative = DialogButtonBarAction(cancelText, onClick = onDismissRequest),
            positive = confirm,
        )
    }
}

/** 只有一个关闭按钮的告知对话框。 */
@Composable
fun NoticeDialog(
    show: Boolean,
    title: String?,
    summary: String?,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    closeText: String = Strings.commonClose(),
    content: (@Composable () -> Unit)? = null,
) {
    PageDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        summary = summary,
        customContent = padded(content),
    ) {
        DialogButtonBar(
            negative = null,
            positive =
                DialogButtonBarAction(closeText, recommended = true, onClick = onDismissRequest),
        )
    }
}

/** 说明下面的额外内容：左右留出 [DialogContentPadding]。 */
private fun padded(content: (@Composable () -> Unit)?): (@Composable () -> Unit)? = content?.let {
    { Box(Modifier.padding(horizontal = DialogContentPadding)) { it() } }
}

/** 单行输入对话框的输入框最多几行，再长就在框里滚动。 */
private const val TextInputMaxLines = 5

/**
 * 单行输入对话框：取消 + 确认，[label] 是输入框里的提示。打开时输入框自动聚焦、弹出键盘，光标在已有文字的末尾。搭在 [ConfirmDialog] 上， 输入框是光标由 Zeta 管的
 * [TextField]（带清除键，最多 [TextInputMaxLines] 行），上面离说明 8dp。
 */
@Composable
fun TextInputDialog(
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
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    ConfirmDialog(
        show = show,
        title = title,
        summary = summary,
        confirmText = confirmText,
        onConfirm = onConfirm,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        confirmEnabled = confirmEnabled,
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().focusRequester(focus),
            label = label,
            showClearButton = true,
            keyboardOptions = keyboardOptions,
            maxLines = TextInputMaxLines,
        )
    }
}

/**
 * 对话框里的一个输入框：上面一行小字说明填什么（[title]），框里是占位提示（[hint]，不给就用 [title]）。输入框的提示 只能在框里，这几个对话框（添加 MCP
 * 服务器、规则、共享文件夹）要同时说清字段和举例，所以上面另放一行。
 */
@Composable
fun DialogTextField(
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
        if (title != null)
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
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
