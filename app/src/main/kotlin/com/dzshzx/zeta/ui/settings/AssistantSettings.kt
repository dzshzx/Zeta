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
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.assistant.PowerKeyStatus
import com.dzshzx.zeta.xposed.PowerKeyPolicy
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Mic
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
 * 设置首页的「数字助理」：Zeta 是不是默认数字助理，点开是 [AssistantSettingsScreen]（默认助理、模型、思考强度）。
 * [owner] 给了就不查（截图测试用）。
 */
@Composable
internal fun AssistantEntry(owner: AssistantOwner? = null, onOpen: () -> Unit) {
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    ArrowPreference(
        title = "数字助理",
        startAction = { SettingsIcon(COUIIcons.Mic, SettingsHue.System) },
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
        onClick = onOpen,
    )
}

/**
 * 数字助理页：默认数字助理是谁，以及数字助理新开的对话用的模型和思考强度（跟随当前设置，或单独定一个；
 * 只影响数字助理开的对话）。[owner] 给了就不查（截图测试用）。
 */
@Composable
internal fun AssistantSettingsScreen(hub: ConversationHub, onBack: () -> Unit, owner: AssistantOwner? = null) {
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    var model by remember { mutableStateOf(hub.settings.assistantModel) }
    var effort by remember { mutableStateOf(hub.settings.assistantEffort) }
    CouiPage(title = "数字助理", onBack = onBack) {
        item(key = "owner") {
            PreferenceGroup(footer = "设为默认数字助理后，系统的助理入口（HyperOS 上长按电源键要配合 LSPosed 模块，见 诊断 › 数字助理）唤起 Zeta 浮窗，带上当前屏幕问 Claude。") {
                item { DefaultAssistantPreference(current) }
            }
        }
        item(key = "conversation") {
            PreferenceGroup(
                title = "数字助理的对话",
                footer = "数字助理新开的对话用这里的模型和思考强度。跟随当前设置时和新对话一样（现在是 " +
                    "${hub.settings.model?.let { modelName(hub, it) } ?: "Claude Code 的默认模型"}、思考强度${effortLabel(hub.settings.effort) ?: "按 Claude Code 的设置"}）；" +
                    "单独选的只用在数字助理开的对话上，不改新对话的设置。",
            ) {
                item {
                    ModelPreference(hub, model) { value ->
                        model = value
                        hub.settings.assistantModel = value
                    }
                }
                item {
                    EffortPreference(effort) { value ->
                        effort = value
                        hub.settings.assistantEffort = value
                    }
                }
            }
        }
    }
}

/** 「默认数字助理」：现在是谁，点了打开系统的默认助理设置让用户自己选（不替用户换）。设置页和诊断页共用。 */
@Composable
private fun DefaultAssistantPreference(current: AssistantOwner?) {
    val context = LocalContext.current
    ArrowPreference(
        title = "默认数字助理",
        summary = "在系统设置里选",
        endActions = {
            PreferenceValue(
                text = current?.summary ?: "查询中",
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
            recent.isEmpty() -> "HyperOS 长按电源键直接拉起小爱，要交给本包还得在 LSPosed 里启用本包（${AppVariant.current.packageName}）的模块" +
                "（作用域「系统框架」）并重启；正式版、测试版的模块各管各的。现在 LSPosed 和 logcat 里都没有本包模块的记录：没启用，或者启用后还没重启。"
            else -> "最近的记录（LSPosed 模块日志，标签 ${PowerKeyPolicy.LOG_TAG}）：\n" + recent.takeLast(8).joinToString("\n")
        },
    ) {
        item { DefaultAssistantPreference(current) }
        item {
            BasicComponent(
                title = "系统语音识别",
                summary = "默认助理是 Zeta 时要是 Zeta 自己的，开机时系统才不会把默认助理换掉".takeIf { current?.keepsOnBoot == false },
                endActions = {
                    PreferenceValue(
                        text = current?.recognizerSummary ?: "查询中",
                        color = if (current?.keepsOnBoot == false) COUITheme.colorScheme.error else Color.Unspecified,
                    )
                },
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
