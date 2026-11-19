package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.dzshzx.zeta.assistant.AssistantOwner
import com.dzshzx.zeta.assistant.AssistantRole
import com.dzshzx.zeta.assistant.PowerKeyLog
import com.dzshzx.zeta.assistant.PowerKeyStatus
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 默认数字助理现在是谁；每次回到页面（从系统设置回来）重新查。 */
@Composable
private fun rememberAssistantOwner(load: (android.content.Context) -> AssistantOwner): AssistantOwner? {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    return produceState<AssistantOwner?>(null, resumes) { value = withContext(Dispatchers.IO) { load(context) } }.value
}

/**
 * 设置 › 能力 里的「数字助理」：显示 Zeta 是不是默认数字助理，点了打开系统的默认助理设置，让用户自己选。
 * [owner] 给了就不查（截图测试用）。
 */
@Composable
internal fun AssistantEntry(owner: AssistantOwner? = null) {
    val context = LocalContext.current
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    ArrowPreference(
        title = "数字助理",
        summary = "设为默认后，系统的助理入口唤起 Zeta 浮窗，带上当前屏幕问 Claude",
        endActions = {
            PreferenceValue(
                text = when {
                    current == null -> "查询中"
                    current.self -> "已是默认"
                    else -> "未设置"
                },
                color = if (current?.self == true) Color.Unspecified else COUITheme.colorScheme.labelTertiary,
            )
        },
        onClick = {
            if (!AssistantRole.openSettings(context)) Toast.makeText(context, "打不开系统的默认助理设置", Toast.LENGTH_SHORT).show()
        },
    )
}

/**
 * 诊断页的「数字助理」：默认助理是谁、电源键接管模块装没装上，以及模块最近的记录（每次长按电源键走了哪条路）。
 * [status] 给了就不读日志（截图测试用）。
 */
@Composable
internal fun AssistantDiagnosticsGroup(
    reloads: Int,
    owner: AssistantOwner? = null,
    status: PowerKeyStatus? = null,
    loaded: Boolean = status != null,
) {
    val context = LocalContext.current
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    var reading by remember { mutableStateOf(!loaded) }
    val log = status ?: produceState<PowerKeyStatus?>(null, reloads) {
        reading = true
        value = withContext(Dispatchers.IO) { PowerKeyLog.read() }
        reading = false
    }.value
    val recent = log?.recent.orEmpty()
    PreferenceGroup(
        title = "数字助理",
        footer = when {
            reading && log == null -> null
            log == null -> "读不到模块日志（没有 root）。"
            recent.isEmpty() -> "HyperOS 长按电源键直接拉起小爱，要交给 Zeta 还得在 LSPosed 里启用 Zeta 模块（作用域「系统框架」）并重启。" +
                "现在 LSPosed 和 logcat 里都没有这个模块的记录：没启用，或者启用后还没重启。"
            else -> "最近的记录（LSPosed 模块日志，标签 ZetaPowerKey）：\n" + recent.takeLast(8).joinToString("\n")
        },
    ) {
        item {
            BasicComponent(
                title = "默认数字助理",
                endActions = { PreferenceValue(current?.summary ?: "查询中", color = if (current?.self == false) COUITheme.colorScheme.labelTertiary else Color.Unspecified) },
                onClick = { AssistantRole.openSettings(context) },
            )
        }
        item {
            BasicComponent(
                title = "长按电源键接管",
                summary = log?.installNote?.takeIf { log.installed == false },
                endActions = {
                    PreferenceValue(
                        text = if (log == null) (if (reading) "查询中" else "查不到") else log.summary,
                        color = if (log?.installed == false) COUITheme.colorScheme.error else Color.Unspecified,
                    )
                },
            )
        }
    }
}
