package com.dzshzx.zeta.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.ElicitField
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.coui2.DialogContentPadding
import com.dzshzx.zeta.ui.components.coui2.DialogTextField
import com.dzshzx.zeta.ui.components.coui2.PageDialog
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.preference.CheckboxPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonLocation
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 回应一个 MCP 表单：请求、action（accept、decline、cancel）、各项输入框里的字（网址模式和拒绝时为 null），
 * 核心说填得不对的各项交给最后的回调（表单留着改了再交）。
 */
typealias ElicitAnswer =
    (ElicitationRequest, String, Map<String, List<String>>?, (Map<String, String>) -> Unit) -> Unit

/**
 * MCP 服务器的 elicitation，和终端版一样两种：表单（按 schema 逐项填，交给核心校验）和网址（去网页上做，点“打开网页”算同意）。 “拒绝”交回
 * decline；停止这一轮时由核心交回 cancel。
 */
@Composable
fun ElicitationSheet(
    visible: Boolean,
    request: ElicitationRequest,
    onElicit: ElicitAnswer,
) {
    val uris = LocalUriHandler.current
    val values =
        remember(request.requestId) {
            mutableStateMapOf<String, List<String>>().apply {
                request.fields.forEach { put(it.key, it.default) }
            }
        }
    var errors by remember(request.requestId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val url = request.url
    PageDialog(
        show = visible,
        onDismissRequest = {},
        title =
            if (url != null) Strings.chatElicitUrl(request.server)
            else Strings.chatElicitForm(request.server),
        summary = request.message.ifBlank { null },
        customContent = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                url?.let { UrlLine(it) }
                request.fields.forEach { field ->
                    ElicitInput(field, values[field.key].orEmpty(), errors[field.key]) {
                        values[field.key] = it
                        errors = errors - field.key
                    }
                }
            }
        },
    ) {
        DialogButtonBar(
            negative =
                DialogButtonBarAction(
                    Strings.chatDecline(),
                    onClick = { onElicit(request, "decline", null) {} },
                ),
            positive =
                DialogButtonBarAction(
                    if (url != null) Strings.chatOpenWebPage() else Strings.chatSubmit(),
                    recommended = true,
                    onClick = {
                        if (url != null)
                            openUrl(uris, url).also { onElicit(request, "accept", null) {} }
                        else onElicit(request, "accept", values.toMap()) { errors = it }
                    },
                ),
        )
    }
}

/** 网址模式下要打开的网址，小字显示在说明下面。 */
@Composable
private fun UrlLine(url: String) {
    Text(
        text = url,
        style = COUITheme.textStyles.bodyS,
        color = COUITheme.colorScheme.labelSecondary,
        modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 4.dp),
    )
}

/** 用浏览器打开 [url]；没有能打开的应用时不做什么（同意照样交回，网页上的事用户自己去做）。 */
private fun openUrl(uris: UriHandler, url: String) {
    runCatching { uris.openUri(url) }
}

/** 表单里的一项。[value] 是输入框里的字（多选是选中的值，开关是 "true"/"false"），[error] 是校验没过的说明。 */
@Composable
private fun ElicitInput(
    field: ElicitField,
    value: List<String>,
    error: String?,
    onChange: (List<String>) -> Unit,
) {
    val margin = PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp)
    val title = field.title + if (field.required) " *" else ""
    val summary =
        listOfNotNull(field.description.ifBlank { null }, error).joinToString("\n").ifBlank { null }
    when (field.kind) {
        ElicitField.Kind.Toggle ->
            SwitchPreference(
                checked = value.firstOrNull() == "true",
                onCheckedChange = { onChange(listOf(it.toString())) },
                title = title,
                summary = summary,
                insideMargin = margin,
            )
        ElicitField.Kind.Single,
        ElicitField.Kind.Multi -> {
            Label(title, summary, error != null)
            field.choices.forEach { choice ->
                if (field.kind == ElicitField.Kind.Multi) {
                    CheckboxPreference(
                        title = choice.label,
                        checked = choice.value in value,
                        onCheckedChange = {
                            onChange(
                                if (choice.value in value) value - choice.value
                                else value + choice.value,
                            )
                        },
                        insideMargin = margin,
                    )
                } else {
                    RadioButtonPreference(
                        title = choice.label,
                        selected = value.firstOrNull() == choice.value,
                        onClick = { onChange(listOf(choice.value)) },
                        radioButtonLocation = RadioButtonLocation.End,
                        insideMargin = margin,
                    )
                }
            }
        }
        else -> {
            DialogTextField(
                value = value.firstOrNull().orEmpty(),
                onValueChange = { onChange(listOf(it)) },
                modifier = Modifier.padding(horizontal = DialogContentPadding, vertical = 6.dp),
                title = title,
                hint = field.description.ifBlank { null },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardFor(field)),
            )
            if (error != null) Label(null, error, true)
        }
    }
}

@Composable
private fun Label(title: String?, summary: String?, error: Boolean) {
    Column(
        Modifier.padding(
            start = DialogContentPadding,
            end = DialogContentPadding,
            top = 8.dp,
            bottom = 2.dp,
        ),
    ) {
        if (title != null)
            Text(
                text = title,
                style = COUITheme.textStyles.bodyM,
                color = COUITheme.colorScheme.labelPrimary,
            )
        if (summary != null) {
            Text(
                text = summary,
                style = COUITheme.textStyles.bodyS,
                color =
                    if (error) COUITheme.colorScheme.error
                    else COUITheme.colorScheme.labelSecondary,
            )
        }
    }
}

private fun keyboardFor(field: ElicitField): KeyboardType =
    when {
        field.kind == ElicitField.Kind.Integer -> KeyboardType.Number
        field.kind == ElicitField.Kind.Number -> KeyboardType.Decimal
        field.format == "email" -> KeyboardType.Email
        field.format == "uri" -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
