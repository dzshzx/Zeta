package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.RemoteHosts
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.TextInputDialog
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// 设置里几处共用的行：标题加当前值的一行、删除行、设备名一行和改名对话框。

/**
 * 标题和右边当前值（[PreferenceValue]）的一行。值的颜色跟着行走：能点（[enabled] 且有 [onClick]）是实色，只读或禁用是虚色，调用方不另说。
 * 按状态上色（出错）时给 [color]。
 */
@Composable
fun ValueRow(
    title: String,
    value: String,
    summary: String? = null,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            PreferenceValue(value, color = color, clickable = enabled && onClick != null)
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** 编辑页最下面的“删除…”：错误色的标题，点了弹确认框。 */
@Composable
fun DangerRow(title: String, onClick: () -> Unit) {
    BasicComponent(
        title = title,
        titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
        onClick = onClick,
    )
}

/** 「自定义设备名」一行：右边是设备现在的名字（没起名字时是设备自己的）。点了改名（[DeviceNameDialog]）。 */
@Composable
fun DeviceNameRow(hub: ConversationHub, backend: String, onClick: () -> Unit) {
    ArrowPreference(
        title = Strings.settingsRowsDeviceName(),
        endActions = { PreferenceValue(hub.backendName(backend)) },
        onClick = onClick,
    )
}

/** 给设备 [backend] 起名字：留空是不起，显示设备自己的名字；和别的设备重名、太长时不让确定。放在页面的 dialogs 里。 */
@Composable
fun DeviceNameDialog(
    hub: ConversationHub,
    backend: String,
    show: Boolean,
    onDismiss: () -> Unit,
) {
    var draft by remember(show) { mutableStateOf(hub.customName(backend).orEmpty()) }
    val original = hub.originalName(backend)
    val problem =
        RemoteHosts.nameProblem(draft, hub.backends.filter { it != backend }.map(hub::backendName))
    TextInputDialog(
        show = show,
        title = Strings.settingsRowsDeviceName(),
        summary = problem ?: Strings.settingsRowsDeviceNameHint(original),
        value = draft,
        onValueChange = { draft = it },
        label = original,
        confirmText = Strings.commonOk(),
        confirmEnabled = problem == null,
        onConfirm = {
            hub.rename(backend, draft)
            onDismiss()
        },
        onDismissRequest = onDismiss,
    )
}
