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
    "在这台设备上开新对话时用。存在这台设备上 Claude Code 自己的设置里，和那边终端里 /model、/effort 存为默认的是同一份；" +
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
 * 「跟随 Claude Code」是删掉这个模型的设置。直接列的是各系列最新的模型和「其他模型」，旧版本和清单里已经没有的收在「更多模型」一行里，
 * 点了才列出来（[effortRows]）。还没拿到这台 Claude Code 的模型清单（这次打开 Zeta 后那台上还没起过进程）时先列 settings.json 里
 * 已经设过的，同时去要清单（[ConversationHub.requestModels]）。
 */
@Composable
internal fun DefaultEffortScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val write = rememberDefaultsWriter(hub, backend)
    val defaults = hub.claudeDefaults(backend)
    val models = hub.models[backend].orEmpty()
    val waiting = models.isEmpty()
    val ready = hub.usable(backend)
    var showOlder by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, ready) { if (waiting && ready) hub.requestModels(backend) }
    CouiPage(title = "默认思考强度", onBack = onBack) {
        item(key = "efforts") {
            PreferenceGroup(
                footer = "这台设备上的新对话按所用的模型取这里的设置，和那边终端里 /effort 存为默认的是同一份；对话里选的只管那个对话。" +
                    if (waiting) "正在向 Claude Code 要模型清单，先列出已经设过的。" else "",
            ) {
                if (defaults == null) {
                    item { ArrowPreference(title = NotRead, enabled = false) }
                } else {
                    val rows = effortRows(models, defaults)
                    val folded = rows.filter { it.older }
                    rows.forEach { row ->
                        when {
                            showOlder || !row.older -> item {
                                FollowDropdownPreference(
                                    title = row.title,
                                    choices = row.choices,
                                    value = row.value,
                                    followLabel = "跟随 Claude Code",
                                ) { level -> write { setDefaultEffort(row.model, level) } }
                            }
                            // 收起时整组换成一行，放在第一个收起的那一行的位置（「其他模型」前面）
                            row === folded.first() -> item {
                                ArrowPreference(title = "更多模型", summary = moreModelsSummary(folded), onClick = { showOlder = true })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 默认思考强度页的一行：[model] 是完整模型 ID，null 是没单独设的模型共用的那一档（顶层 effortLevel）。
 * [older]：收在「更多模型」里的：清单里的旧版本，和清单里已经没有、只在 settings.json 里设过的。
 */
internal data class EffortRow(
    val model: String?,
    val title: String,
    val choices: List<Pair<String, String>>,
    val value: String?,
    val older: Boolean = false,
)

/**
 * 默认思考强度页有哪几行，依次是：
 * - Claude Code 清单里各系列最新的模型（[modelMenu] 的 latest），同一个完整 ID 只列一次（「默认」和它指向的那个是同一个）；
 * - 收进「更多模型」的（标上 [EffortRow.older]，设没设过都收）：清单里的旧版本（[modelMenu] 的 older，按它的顺序），然后是清单里没有、
 *   只在 settings.json 里设过的（下线的模型），在那里仍能改掉或删掉旧设置；
 * - 顶层设过一档时是「其他模型」。
 * 档位只列这个模型支持的；不支持思考强度、也没设过的不列。还没拿到清单（[models] 为空）时只有设过的，直接列出，不收。
 */
internal fun effortRows(models: List<ModelOption>, defaults: ClaudeDefaults): List<EffortRow> {
    val menu = modelMenu(models)
    val latest = menu.latest.filter { it.resolvedModel != null }.distinctBy { it.resolvedModel }
    val latestIds = latest.mapTo(HashSet()) { it.resolvedModel }
    val older = menu.older.filter { it.resolvedModel != null && it.resolvedModel !in latestIds }.distinctBy { it.resolvedModel }
    val listedIds = latestIds + older.map { it.resolvedModel }
    fun row(option: ModelOption, folded: Boolean): EffortRow? {
        val id = option.resolvedModel!!
        val choices = EffortLevels.filter { level -> option.effortLevels?.contains(level.first) ?: true }
        val value = defaults.efforts[id]
        return if (choices.isEmpty() && value == null) null else EffortRow(id, readableModelId(id), choices, value, folded)
    }
    val unlisted = defaults.efforts.filterKeys { it !in listedIds }
        .map { (id, level) -> EffortRow(id, readableModelId(id), EffortLevels, level, older = models.isNotEmpty()) }
    val rest = defaults.effort?.let { EffortRow(null, "其他模型", EffortLevels, it) }
    return latest.mapNotNull { row(it, folded = false) } + older.mapNotNull { row(it, folded = true) } + unlisted + listOfNotNull(rest)
}

/** 「更多模型」一行的摘要：收在里面的模型（[rows]），设过的带上档位，比如「Opus 4.6 高、Fable 5」。 */
internal fun moreModelsSummary(rows: List<EffortRow>): String =
    rows.joinToString("、") { row -> row.title + (row.value?.let { " " + (effortLabel(it) ?: it) } ?: "") }

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
