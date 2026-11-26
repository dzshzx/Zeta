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
import com.dzshzx.zeta.core.AppVariant
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 默认数字助理现在是谁；每次回到页面（从系统设置回来）重新查。 */
@Composable
private fun rememberAssistantOwner(
    load: (android.content.Context) -> AssistantOwner,
): AssistantOwner? {
    val context = LocalContext.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose {}
    }
    return produceState<AssistantOwner?>(null, resumes) {
            value = withContext(Dispatchers.IO) { load(context) }
        }
        .value
}

/** 设置首页的「数字助理」：Zeta 是不是默认数字助理，点开是 [AssistantSettingsScreen]（默认助理、模型、思考强度）。 [owner] 给了就不查（截图测试用）。 */
@Composable
internal fun AssistantEntry(owner: AssistantOwner? = null, onOpen: () -> Unit) {
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    ArrowPreference(
        title = Strings.settingsAssistantTitle(),
        startAction = { SettingsIcon(MiuixIcons.Mic) },
        endActions = {
            PreferenceValue(
                text =
                    when {
                        current == null -> Strings.commonQuerying()
                        current.self -> Strings.settingsAssistantIsDefault()
                        else -> Strings.settingsAssistantNotSet()
                    },
            )
        },
        onClick = onOpen,
    )
}

/**
 * 数字助理页：默认数字助理是谁，以及数字助理新开的对话用的模型和思考强度（跟随 Claude Code 的默认，或单独定一个； 只影响数字助理开的对话）。[owner] 给了就不查（截图测试用）。
 */
@Composable
internal fun AssistantSettingsScreen(
    hub: ConversationHub,
    onBack: () -> Unit,
    owner: AssistantOwner? = null,
) {
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    var model by remember { mutableStateOf(hub.settings.assistantModel) }
    var effort by remember { mutableStateOf(hub.settings.assistantEffort) }
    MiuixPage(title = Strings.settingsAssistantTitle(), onBack = onBack) {
        // HyperOS 的长按电源键默认直接拉起小爱，要靠 Zeta 自带的 LSPosed 模块交给
        // Zeta；模块装没装上、最近的记录在诊断页（AssistantDiagnosticsGroup）
        item(key = "owner") {
            PreferenceGroup(
                footer = Strings.settingsAssistantFooter(),
            ) {
                item { DefaultAssistantPreference(current) }
            }
        }
        item(key = "conversation") {
            PreferenceGroup(
                title = Strings.settingsAssistantConversations(),
                footer = Strings.settingsAssistantConversationsFooter(),
            ) {
                item {
                    ModelPreference(hub, Backends.LOCAL, model) { value ->
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
        title = Strings.settingsAssistantDefault(),
        summary = Strings.settingsAssistantDefaultSummary(),
        endActions = {
            PreferenceValue(
                text = current?.summary ?: Strings.commonQuerying(),
            )
        },
        onClick = {
            if (!AssistantRole.openSettings(context))
                Toast.makeText(
                        context,
                        Strings.settingsAssistantCantOpenSettings(),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
        },
    )
}

/** 诊断页的「数字助理」：默认助理是谁、电源键接管模块装没装上，以及模块最近的记录（每次长按电源键走了哪条路）。 [status] 给了就不读日志（截图测试用）。 */
@Composable
internal fun AssistantDiagnosticsGroup(
    reloads: Int,
    owner: AssistantOwner? = null,
    status: PowerKeyStatus? = null,
    loaded: Boolean = status != null,
) {
    val current = owner ?: rememberAssistantOwner(AssistantRole::owner)
    var reading by remember { mutableStateOf(!loaded) }
    val log =
        status
            ?: produceState<PowerKeyStatus?>(null, reloads) {
                    reading = true
                    value = withContext(Dispatchers.IO) { PowerKeyLog.read() }
                    reading = false
                }
                .value
    val recent = log?.recent.orEmpty()
    // HyperOS 长按电源键默认直接拉起小爱，要交给本包得在 LSPosed 里启用本包的模块（作用域「系统框架」）并重启；
    // 正式版、测试版的模块各管各的。记录取自 LSPosed 的模块日志和 logcat，标签 PowerKeyPolicy.LOG_TAG
    // HyperOS 长按电源键默认直接拉起小爱，要交给本包得在 LSPosed 里启用本包的模块（作用域「系统框架」）并重启；
    // 正式版、测试版的模块各管各的。记录取自 LSPosed 的模块日志和 logcat，标签 PowerKeyPolicy.LOG_TAG
    PreferenceGroup(
        title = Strings.settingsAssistantTitle(),
        footer = assistantFootnote(reading, log, recent),
    ) {
        item { DefaultAssistantPreference(current) }
        item { RecognizerPreference(current) }
        item { PowerKeyPreference(log, reading) }
    }
}

/** 数字助理一组下面的说明：模块最近的记录，没有记录时说怎么启用；还在读时不写。 */
private fun assistantFootnote(
    reading: Boolean,
    log: PowerKeyStatus?,
    recent: List<String>,
): String? =
    when {
        reading && log == null -> null
        log == null -> Strings.settingsAssistantNoLog()
        recent.isEmpty() -> Strings.settingsAssistantEnableModule(AppVariant.current.packageName)
        else -> Strings.settingsAssistantRecentLog(recent.takeLast(8).joinToString("\n"))
    }

/**
 * 系统语音识别：默认助理是 Zeta 时，系统默认语音识别也要是本包自己的识别服务（测试版就要是测试版的），开机时系统才不会把默认助理换掉
 * （docs/assistant.md「开机保持默认助理」）。 别家的识别服务显示的是包名，太长，出问题时右边只写「不是 Zeta」。
 */
@Composable
private fun RecognizerPreference(current: AssistantOwner?) {
    val broken = current?.keepsOnBoot == false
    ValueRow(
        title = Strings.settingsAssistantRecognizer(),
        value =
            if (broken) Strings.settingsAssistantNotZeta()
            else current?.recognizerSummary ?: Strings.commonQuerying(),
        summary = Strings.settingsAssistantRecognizerBroken().takeIf { broken },
        color = if (broken) MiuixTheme.colorScheme.error else Color.Unspecified,
    )
}

/** 长按电源键接管：模块装没装上（没装上时说明怎么装）。 */
@Composable
private fun PowerKeyPreference(log: PowerKeyStatus?, reading: Boolean) {
    ValueRow(
        title = Strings.settingsAssistantPowerKey(),
        value = powerKeyValue(log, reading),
        summary = log?.installNote?.takeIf { log.installed == false },
        color = if (log?.installed == false) MiuixTheme.colorScheme.error else Color.Unspecified,
    )
}

private fun powerKeyValue(log: PowerKeyStatus?, reading: Boolean): String =
    when {
        log != null -> log.summary
        reading -> Strings.commonQuerying()
        else -> Strings.commonUnknown()
    }
