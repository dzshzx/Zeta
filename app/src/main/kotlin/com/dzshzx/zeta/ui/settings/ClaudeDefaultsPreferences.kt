package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.claude.setDefaultModel
import com.dzshzx.zeta.core.claude.setDefaultPermissionMode
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.rememberConfigClient
import com.dzshzx.zeta.ui.setup.runConfig
import io.github.suqi8.coui.kmp.basic.BasicComponent

/*
 * 新对话的默认值：读写 Claude Code 自己的设置（~/.claude/settings.json，[ConversationHub.claudeDefaults]），和 TUI 里
 * /model、/effort 存为默认是同一份。对话里选的只管那个对话，不改这里。
 */

/** 设置首页那一组的说明。 */
internal const val ClaudeDefaultsFooter =
    "新对话用 Claude Code 自己设置里的默认值，和终端里 /model、/effort 存为默认的是同一份；对话里选的只管那个对话。" +
        "思考强度的默认值按模型存，在终端里用 /effort 改。"

/** 还没读到 Claude Code 的设置（环境没就绪、读失败）时三行灰着，写这一句。 */
private const val NotRead = "还没读到 Claude Code 的设置"

/** 改一项默认值：在后台写 settings.json，写成了更新 hub，没写成弹一句原因。 */
@Composable
private fun rememberDefaultsWriter(hub: ConversationHub): (ClaudeConfigClient.() -> ClaudeDefaults) -> Unit {
    val client = rememberConfigClient(hub)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return { change ->
        scope.runConfig({ client.change() }) { result ->
            result.onSuccess(hub::updateClaudeDefaults)
                .onFailure { Toast.makeText(context, "没有改成：" + it.problem(), Toast.LENGTH_LONG).show() }
        }
    }
}

/** 「默认模型」：settings.json 的 model；跟随是删掉它，用 Claude Code 内置的默认。 */
@Composable
internal fun DefaultModelPreference(hub: ConversationHub, startAction: (@Composable () -> Unit)? = null) {
    val write = rememberDefaultsWriter(hub)
    FollowDropdownPreference(
        title = "默认模型",
        // 「默认」就是不设，和跟随那一项合成一项
        choices = modelChoices(hub).filter { it.first != Conversation.DEFAULT_MODEL },
        value = hub.claudeDefaults?.model?.takeIf { it != Conversation.DEFAULT_MODEL },
        unknown = ::readableModelId,
        followLabel = "Claude Code 内置的默认",
        startAction = startAction,
        summary = NotRead.takeIf { hub.claudeDefaults == null },
        enabled = hub.claudeDefaults != null,
    ) { value -> write { setDefaultModel(value) } }
}

/** 「默认权限模式」：settings.json 的 permissions.defaultMode。完全放行不能设成默认（Claude Code 也不肯存），不列出来。 */
@Composable
internal fun DefaultPermissionModePreference(hub: ConversationHub, startAction: (@Composable () -> Unit)? = null) {
    val write = rememberDefaultsWriter(hub)
    FollowDropdownPreference(
        title = "默认权限模式",
        choices = PermissionModes.filter { it.first != "bypassPermissions" },
        value = hub.claudeDefaults?.let { it.permissionMode ?: "default" },
        unknown = ::permissionModeLabel,
        followLabel = null,
        startAction = startAction,
        summary = NotRead.takeIf { hub.claudeDefaults == null },
        enabled = hub.claudeDefaults != null,
    ) { value -> value?.let { mode -> write { setDefaultPermissionMode(mode) } } }
}

/** 「默认思考强度」：只显示。Claude Code 按模型存（modelSettings），在终端里用 /effort 改。 */
@Composable
internal fun DefaultEffortSummary(hub: ConversationHub, startAction: (@Composable () -> Unit)? = null) {
    BasicComponent(title = "默认思考强度", summary = hub.claudeDefaults?.let(::effortSummary) ?: NotRead, startAction = startAction)
}

/** 「Opus 5.5 超高，Fable 5.2 高」；只有顶层的就是那一档，都没设时说由 Claude Code 定。 */
internal fun effortSummary(defaults: ClaudeDefaults): String {
    val perModel = defaults.efforts.map { (model, level) -> readableModelId(model) + " " + (effortLabel(level) ?: level) }
    val rest = defaults.effort?.let { effortLabel(it) ?: it }
    return when {
        perModel.isEmpty() -> rest ?: "没有设，按 Claude Code 的默认"
        rest == null -> perModel.joinToString("，")
        else -> (perModel + "其他模型 $rest").joinToString("，")
    }
}
