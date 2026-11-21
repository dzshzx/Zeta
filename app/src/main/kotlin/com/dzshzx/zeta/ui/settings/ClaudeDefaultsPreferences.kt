package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.claude.setDefaultEffort
import com.dzshzx.zeta.core.claude.setDefaultModel
import com.dzshzx.zeta.core.claude.setDefaultPermissionMode
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.core.modelLabel
import com.dzshzx.zeta.core.modelMenu
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.rememberConfigClient
import com.dzshzx.zeta.ui.setup.runConfig
import io.github.suqi8.coui.kmp.preference.ArrowPreference

/*
 * 一台主机上新对话的默认值：读写那台 Claude Code 自己的设置（~/.claude/settings.json，[ConversationHub.claudeDefaults]），
 * 和那边终端里 /model、/effort 存为默认是同一份，所以每台主机各是各的。对话里选的只管那个对话，不改这里。
 */

/** 主机页「新对话默认」那一组的说明。 */
internal const val ClaudeDefaultsFooter =
    "在这台主机上开新对话时用。存在这台主机上 Claude Code 自己的设置里，和那边终端里 /model、/effort 存为默认的是同一份；" +
        "对话里选的只管那个对话。思考强度按模型分别存。"

/** 还没读到 Claude Code 的设置（环境没就绪、读失败）时三行灰着，写这一句。 */
private const val NotRead = "还没读到 Claude Code 的设置"

/** 改主机 [backend] 上的一项默认值：在后台写那台的 settings.json，写成了更新 hub，没写成弹一句原因。 */
@Composable
private fun rememberDefaultsWriter(hub: ConversationHub, backend: String): (ClaudeConfigClient.() -> ClaudeDefaults) -> Unit {
    val client = rememberConfigClient(hub, backend)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return { change ->
        scope.runConfig({ client.change() }) { result ->
            result.onSuccess { hub.updateClaudeDefaults(backend, it) }
                .onFailure { Toast.makeText(context, "没有改成：" + it.problem(), Toast.LENGTH_LONG).show() }
        }
    }
}

/** 「默认模型」：settings.json 的 model；跟随是删掉它，用 Claude Code 内置的默认。 */
@Composable
internal fun DefaultModelPreference(hub: ConversationHub, backend: String) {
    val write = rememberDefaultsWriter(hub, backend)
    val defaults = hub.claudeDefaults(backend)
    val choices = modelChoices(hub.modelOptions(backend))
    FollowDropdownPreference(
        title = "默认模型",
        // 「默认」就是不设，和跟随那一项合成一项
        choices = choices.latest.filter { it.first != Conversation.DEFAULT_MODEL },
        more = choices.older,
        value = defaults?.model?.takeIf { it != Conversation.DEFAULT_MODEL },
        unknown = ::readableModelId,
        followLabel = "Claude Code 内置的默认",
        summary = NotRead.takeIf { defaults == null },
        enabled = defaults != null,
    ) { value -> write { setDefaultModel(value) } }
}

/** 「默认权限模式」：settings.json 的 permissions.defaultMode。完全放行不能设成默认（Claude Code 也不肯存），不列出来。 */
@Composable
internal fun DefaultPermissionModePreference(hub: ConversationHub, backend: String) {
    val write = rememberDefaultsWriter(hub, backend)
    val defaults = hub.claudeDefaults(backend)
    FollowDropdownPreference(
        title = "默认权限模式",
        choices = PermissionModes.filter { it.first != "bypassPermissions" },
        value = hub.defaultPermissionMode(backend),
        unknown = ::permissionModeLabel,
        followLabel = null,
        summary = NotRead.takeIf { defaults == null },
        enabled = defaults != null,
    ) { value -> value?.let { mode -> write { setDefaultPermissionMode(mode) } } }
}

/** 「默认思考强度」：摘要列出各模型的设置，点开逐个模型改（[DefaultEffortScreen]）。 */
@Composable
internal fun DefaultEffortEntry(hub: ConversationHub, backend: String, onOpen: () -> Unit) {
    val defaults = hub.claudeDefaults(backend)
    ArrowPreference(
        title = "默认思考强度",
        summary = defaults?.let(::effortSummary) ?: NotRead,
        enabled = defaults != null,
        onClick = onOpen,
    )
}

/**
 * 设置 › 一台主机 › 默认思考强度：每个模型一行，选的档写进 modelSettings.<完整模型 ID>.effortLevel（和 /effort 存为默认一样），
 * 「跟随 Claude Code」是删掉这个模型的设置。还没拿到 Claude Code 的模型清单（这次打开 Zeta 后还没起过进程）时，
 * 先列 settings.json 里已有的模型；当前对话也在这台主机上时，同时让它把进程起好来要清单（和输入斜杠命令时一样，不产生请求）。
 * 没设过的旧版本模型收在「更多模型」一行后面，点了才列出来。
 */
@Composable
internal fun DefaultEffortScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val write = rememberDefaultsWriter(hub, backend)
    val defaults = hub.claudeDefaults(backend)
    val models = hub.models[backend].orEmpty()
    val waiting = models.isEmpty()
    var showOlder by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) { if (waiting && hub.current.backend == backend) hub.current.prepare() }
    CouiPage(title = "默认思考强度", onBack = onBack) {
        item(key = "efforts") {
            PreferenceGroup(
                footer = "这台主机上的新对话按所用的模型取这里的设置，和那边终端里 /effort 存为默认的是同一份；对话里选的只管那个对话。" +
                    if (waiting) "正在向 Claude Code 要模型清单，先列出已经设过的。" else "",
            ) {
                if (defaults == null) {
                    item { ArrowPreference(title = NotRead, enabled = false) }
                } else {
                    val (shown, folded) = effortRows(models, defaults).partition { showOlder || !it.older }
                    shown.forEach { row ->
                        item {
                            FollowDropdownPreference(
                                title = row.title,
                                choices = row.choices,
                                value = row.value,
                                followLabel = "跟随 Claude Code",
                            ) { level -> write { setDefaultEffort(row.model, level) } }
                        }
                    }
                    if (folded.isNotEmpty()) {
                        item { ArrowPreference(title = "更多模型", summary = folded.joinToString("、") { it.title }, onClick = { showOlder = true }) }
                    }
                }
            }
        }
    }
}

/**
 * 默认思考强度页的一行：[model] 是完整模型 ID，null 是没单独设的模型共用的那一档（顶层 effortLevel）。
 * [older]：没设过的旧版本模型，收在「更多模型」后面。
 */
internal data class EffortRow(
    val model: String?,
    val title: String,
    val choices: List<Pair<String, String>>,
    val value: String?,
    val older: Boolean = false,
)

/**
 * 默认思考强度页有哪几行：Claude Code 清单里的模型按模型菜单的顺序（[modelMenu]：各系列最新的在前，旧版本在后），
 * 同一个完整 ID 只列一次（「默认」和它指向的那个是同一个），档位只列这个模型支持的；不支持思考强度、也没设过的不列。
 * 没设过的旧版本标上 [EffortRow.older]。settings.json 里设过、清单里没有的（下线的模型）接在后面，好让人改掉或删掉。
 * 顶层设过一档时最后一行是「其他模型」。
 */
internal fun effortRows(models: List<ModelOption>, defaults: ClaudeDefaults): List<EffortRow> {
    val menu = modelMenu(models)
    val olderIds = menu.older.mapNotNullTo(HashSet()) { it.resolvedModel }
    val listed = (menu.latest + menu.older).filter { it.resolvedModel != null }.distinctBy { it.resolvedModel }
    val rows = listed.mapNotNull { option ->
        val id = option.resolvedModel!!
        val choices = EffortLevels.filter { level -> option.effortLevels?.contains(level.first) ?: true }
        val value = defaults.efforts[id]
        if (choices.isEmpty() && value == null) null else EffortRow(id, readableModelId(id), choices, value, older = id in olderIds && value == null)
    }
    val others = defaults.efforts.filterKeys { id -> listed.none { it.resolvedModel == id } }
        .map { (id, level) -> EffortRow(id, readableModelId(id), EffortLevels, level) }
    val rest = defaults.effort?.let { EffortRow(null, "其他模型", EffortLevels, it) }
    return rows + others + listOfNotNull(rest)
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

/**
 * 设置首页一台主机那一行右边的值：默认模型和它的默认思考强度，比如「Opus 5.5 · 超高」。别名（opus、没设时的默认）按这台的
 * 模型清单 [models] 换成它指向的完整 ID 再写名字；还没拿到清单时写别名的显示名。这个模型没单独设思考强度时用没单独设的
 * 模型共用的那一档，都没设时只写模型。
 */
internal fun hostDefaultsSummary(defaults: ClaudeDefaults, models: List<ModelOption>): String {
    val model = defaults.model ?: Conversation.DEFAULT_MODEL
    val id = models.firstOrNull { it.value == model }?.resolvedModel
    val name = id?.let(::readableModelId) ?: if (model == Conversation.DEFAULT_MODEL) "默认模型" else modelLabel(model, models)
    val effort = effortLabel(defaults.efforts[id ?: model] ?: defaults.effort)
    return listOfNotNull(name, effort).joinToString(" · ")
}
