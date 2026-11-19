package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.R
import io.github.suqi8.coui.kmp.blur.MaterialBlurDefaults
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.layout.DialogDefaults
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.overlay.OverlayInputDialog

// 页面上的对话框：COUI 2 的 Overlay 对话框，加上当前页面的毛玻璃（见 CouiBlur.kt）。
// 要放在 CouiPage 的 overlays 里，或 CouiPageScaffold 内容里和列表并排；不放进列表项，否则列表项滚出屏幕时对话框跟着没了。
// COUI 的对话框不给内容加内边距（按钮栏要通栏），自己的内容左右留 24dp，和标题、说明对齐。

/** 对话框内容的左右留白，和 COUI 对话框的标题、说明对齐。 */
internal val DialogContentPadding = 24.dp

/** 带页面毛玻璃的 OverlayDialog，内容和按钮栏（DialogButtonBar）自己放。 */
@Composable
internal fun PageDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
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
        content = content,
    )
}

/**
 * 确认对话框：取消 + 确认。确认是主操作（主题色实心）；[destructive] 时确认放进 COUI 画成红色的 neutral 位置。
 * [content] 是说明下面的额外内容（已留好左右边距）。
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
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: (@Composable () -> Unit)? = null,
) {
    PageDialog(show = show, onDismissRequest = onDismissRequest, modifier = modifier, title = title, summary = summary) {
        Column {
            if (content != null) {
                Box(Modifier.padding(start = DialogContentPadding, end = DialogContentPadding, bottom = 16.dp)) { content() }
            }
            val confirm = DialogButtonBarAction(confirmText, enabled = confirmEnabled, recommended = !destructive, onClick = onConfirm)
            DialogButtonBar(
                negative = DialogButtonBarAction(cancelText, onClick = onDismissRequest),
                positive = if (destructive) null else confirm,
                neutral = if (destructive) confirm else null,
            )
        }
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
    PageDialog(show = show, onDismissRequest = onDismissRequest, modifier = modifier, title = title, summary = summary) {
        Column {
            if (content != null) {
                Box(Modifier.padding(start = DialogContentPadding, end = DialogContentPadding, bottom = 16.dp)) { content() }
            }
            DialogButtonBar(negative = null, positive = DialogButtonBarAction(closeText, recommended = true, onClick = onDismissRequest))
        }
    }
}

/** 单行输入对话框（OverlayInputDialog 加页面毛玻璃）：取消 + 确认，[label] 是输入框里的提示，[maxCount] 显示字数计数。 */
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
    maxCount: Int? = null,
    confirmEnabled: Boolean = true,
    cancelText: String = stringResource(R.string.action_cancel),
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val blur = panelBlur(MaterialBlurDefaults::dialog)
    OverlayInputDialog(
        show = show,
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        backgroundModifier = blur?.backgroundModifier ?: Modifier,
        title = title,
        summary = summary,
        label = label,
        maxCount = maxCount,
        keyboardOptions = keyboardOptions,
        negative = DialogButtonBarAction(cancelText, onClick = onDismissRequest),
        positive = DialogButtonBarAction(confirmText, enabled = confirmEnabled, recommended = true, onClick = onConfirm),
        backgroundColor = blur?.backgroundColor ?: DialogDefaults.backgroundColor(),
        onDismissRequest = onDismissRequest,
        materialStroke = blur != null,
    )
}
