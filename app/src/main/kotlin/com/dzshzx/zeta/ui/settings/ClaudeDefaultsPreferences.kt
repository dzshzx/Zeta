package com.dzshzx.zeta.ui.settings

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EffortLevels
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.ModelOption
import com.dzshzx.zeta.core.claude.ClaudeConfigCore
import com.dzshzx.zeta.core.claude.ClaudeDefaults
import com.dzshzx.zeta.core.effortLabel
import com.dzshzx.zeta.core.formatTokens
import com.dzshzx.zeta.core.modelMenu
import com.dzshzx.zeta.core.modelName
import com.dzshzx.zeta.core.readableModelId
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.PermissionModes
import com.dzshzx.zeta.ui.chat.permissionModeLabel
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroupScope
import com.dzshzx.zeta.ui.setup.problem
import com.dzshzx.zeta.ui.setup.rememberConfigClient
import com.dzshzx.zeta.ui.setup.runConfig
import top.yukonga.miuix.kmp.preference.ArrowPreference

/*
 * 一台主机上新对话的默认值：读写那台 Claude Code 自己的设置（~/.claude/settings.json，[ConversationHub.claudeDefaults]），
 * 和那边终端里 /model、/effort 存为默认是同一份，所以每台主机各是各的。对话里选的只管那个对话，不改这里。
 */

/** 主机页「新对话默认」那一组的说明。 */
internal val ClaudeDefaultsFooter: String
    get() = Strings.settingsDefaultsFooter()

/** 还没读到对应工具的设置（环境没就绪、读失败）时三行灰着，写这一句。 */
private val notRead: String
    get() = Strings.settingsDefaultsNotRead()

private fun notReadFor(harness: String): String =
    if (Harnesses.normalize(harness) == Harnesses.AGY) Strings.settingsDefaultsAgyNotRead()
    else notRead

private fun builtinModelLabel(harness: String): String =
    if (Harnesses.normalize(harness) == Harnesses.AGY) Strings.settingsDefaultsAgyModelBuiltin()
    else Strings.settingsDefaultsModelBuiltin()

private fun effortFooterFor(harness: String, waiting: Boolean): String =
    if (Harnesses.normalize(harness) == Harnesses.AGY) {
        if (waiting) Strings.settingsDefaultsAgyEffortFooterPending()
        else Strings.settingsDefaultsAgyEffortFooter()
    } else {
        if (waiting) Strings.settingsDefaultsEffortFooterPending()
        else Strings.settingsDefaultsEffortFooter()
    }

/** 改主机 [backend] 上的一项默认值：在后台写那台的 settings.json，写成了更新 hub，没写成弹一句原因。 */
@Composable
private fun rememberDefaultsWriter(
    hub: ConversationHub,
    backend: String,
    harness: String = Harnesses.CLAUDE,
): (ClaudeConfigCore.() -> ClaudeDefaults) -> Unit {
    val client = rememberConfigClient(hub, backend, harness)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return { change ->
        val epoch = hub.modelEpoch(backend, harness)
        scope.runConfig({ client.change() }) { result ->
            result
                .onSuccess {
                    if (hub.isCurrentModelEpoch(backend, epoch, harness) && backend in hub.backends)
                        hub.updateClaudeDefaults(backend, it, harness)
                    else if (backend in hub.backends) hub.refreshClaudeDefaults(backend, harness)
                }
                .onFailure {
                    Toast.makeText(
                            context,
                            Strings.settingsDefaultsChangeFailed(it.problem()),
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
        }
    }
}

/** 「默认模型」由核心按来源写入；跟随是清除当前来源的覆盖。 */
@Composable
internal fun DefaultModelPreference(
    hub: ConversationHub,
    backend: String,
    harness: String = Harnesses.CLAUDE,
) {
    val write = rememberDefaultsWriter(hub, backend, harness)
    val defaults = hub.claudeDefaults(backend, harness)
    val changing = hub.modelSourceChanging(backend, harness)
    val choices = modelChoices(hub.modelOptions(backend, harness))
    FollowDropdownPreference(
        title = Strings.settingsDefaultsModel(),
        // 「默认」就是不设，和跟随那一项合成一项
        choices = choices.latest.filter { it.first != Conversation.DEFAULT_MODEL },
        more = choices.older,
        value = defaults?.model?.takeIf { it != Conversation.DEFAULT_MODEL },
        unknown = ::readableModelId,
        followLabel =
            if (hub.usesRelayModels(backend, harness)) harnessFollowLabel(harness)
            else builtinModelLabel(harness),
        summary = notReadFor(harness).takeIf { defaults == null || changing },
        enabled = defaults != null && !changing,
    ) { value ->
        if (hub.modelSelectable(backend, value, harness)) write { setDefaultModel(value) }
    }
}

/** 「上下文窗口」下拉里给的几档（token 数）。 */
internal val CompactWindows: List<Long> =
    listOf(200_000, 300_000, 400_000, 500_000, 600_000, 800_000, 1_000_000)

/**
 * 「上下文窗口」：settings.json 的 autoCompactWindow，会话用到这么多 token 就算满，自动压缩按它算；跟随是删掉它，按模型本身的窗口。 设置里是别的数
 * （终端里改的）时照样列出来。已经在跑的对话下次起进程时生效。
 */
@Composable
internal fun CompactWindowPreference(
    hub: ConversationHub,
    backend: String,
    harness: String = Harnesses.CLAUDE,
) {
    val write = rememberDefaultsWriter(hub, backend, harness)
    val defaults = hub.claudeDefaults(backend, harness)
    FollowDropdownPreference(
        title = Strings.settingsDefaultsContextWindow(),
        choices = CompactWindows.map { it.toString() to formatTokens(it) },
        value = defaults?.compactWindow?.toString(),
        unknown = { it.toLongOrNull()?.let(::formatTokens) ?: it },
        followLabel = Strings.settingsDefaultsContextWindowModel(),
        summary = notRead.takeIf { defaults == null },
        enabled = defaults != null,
    ) { value ->
        write { setCompactWindow(value?.toLong()) }
    }
}

/** 「默认权限模式」：settings.json 的 permissions.defaultMode。 */
@Composable
internal fun DefaultPermissionModePreference(
    hub: ConversationHub,
    backend: String,
    harness: String = Harnesses.CLAUDE,
) {
    val write = rememberDefaultsWriter(hub, backend, harness)
    val defaults = hub.claudeDefaults(backend, harness)
    FollowDropdownPreference(
        title = Strings.settingsDefaultsMode(),
        choices = PermissionModes,
        value = hub.defaultPermissionMode(backend, harness),
        unknown = ::permissionModeLabel,
        followLabel = null,
        summary = notReadFor(harness).takeIf { defaults == null },
        enabled = defaults != null,
    ) { value ->
        value?.let { mode -> write { setDefaultPermissionMode(mode) } }
    }
}

/** 「默认思考强度」：摘要列出各模型的设置，点开逐个模型改（[DefaultEffortScreen]）。 */
@Composable
internal fun DefaultEffortEntry(
    hub: ConversationHub,
    backend: String,
    harness: String = Harnesses.CLAUDE,
    onOpen: () -> Unit,
) {
    val defaults = hub.claudeDefaults(backend, harness)
    ArrowPreference(
        title = Strings.settingsDefaultsEffort(),
        summary = defaults?.let { effortSummary(it, harness) } ?: notReadFor(harness),
        enabled = defaults != null,
        onClick = onOpen,
    )
}

/**
 * 设置 › 一台主机 › 默认思考强度：每个模型一行，选的档写进 modelSettings.<完整模型 ID>.effortLevel（和 /effort 存为默认一样）， 「跟随 Claude
 * Code」是删掉这个模型的设置。还没拿到 Claude Code 的模型清单（这次打开 Zeta 后还没起过进程）时， 先列 settings.json
 * 里已有的模型；当前对话也在这台主机上时，同时让它把进程起好来要清单（和输入斜杠命令时一样，不产生请求）。 没设过的旧版本模型收在「更多模型」一行后面，点了才列出来。
 */
@Composable
internal fun DefaultEffortScreen(
    hub: ConversationHub,
    backend: String,
    onBack: () -> Unit,
    harness: String = Harnesses.CLAUDE,
) {
    val write = rememberDefaultsWriter(hub, backend, harness)
    val defaults = hub.claudeDefaults(backend, harness)
    val models = hub.reportedModelOptions(backend, harness)
    val relay = hub.usesRelayModels(backend, harness)
    val isAgy = Harnesses.normalize(harness) == Harnesses.AGY
    val waiting = models.isEmpty() && !relay
    var showOlder by remember { mutableStateOf(false) }
    val changing = hub.modelSourceChanging(backend, harness)
    LaunchedEffect(hub, backend, harness, waiting, changing) {
        if (
            waiting &&
                !changing &&
                hub.current.backend == backend &&
                hub.current.harness == Harnesses.normalize(harness)
        ) {
            hub.current.prepare()
        }
    }
    MiuixPage(title = Strings.settingsDefaultsEffort(), onBack = onBack) {
        item(key = "efforts") {
            PreferenceGroup(footer = effortFooterFor(harness, waiting)) {
                if (defaults == null) {
                    item { ArrowPreference(title = notReadFor(harness), enabled = false) }
                } else {
                    effortItems(
                        effortRows(models, defaults, relay || isAgy),
                        showOlder,
                        { showOlder = true },
                        harnessFollowLabel(harness),
                        write,
                    )
                }
            }
        }
    }
}

/** 各模型一行，选的档交给 [write] 写进去；收起时（不 [showOlder]）收着的那些整组换成最后一行「更多模型」。 */
private fun PreferenceGroupScope.effortItems(
    rows: List<EffortRow>,
    showOlder: Boolean,
    onShowOlder: () -> Unit,
    followLabel: String,
    write: (ClaudeConfigCore.() -> ClaudeDefaults) -> Unit,
) {
    val (shown, folded) = rows.partition { showOlder || !it.older }
    shown.forEach { row ->
        item {
            FollowDropdownPreference(
                title = row.title,
                choices = row.choices,
                value = row.value,
                followLabel = followLabel,
            ) { level ->
                write { setDefaultEffort(row.model, level) }
            }
        }
    }
    if (folded.isNotEmpty()) {
        item {
            ArrowPreference(
                title = Strings.settingsModelMore(),
                summary = folded.joinToString(Strings.commonListSeparator()) { it.title },
                onClick = onShowOlder,
            )
        }
    }
}

/**
 * 默认思考强度页的一行：[model] 是完整模型 ID，null 是没单独设的模型共用的那一档（顶层 effortLevel）。 [older]：没设过的旧版本模型，收在「更多模型」后面。
 */
internal data class EffortRow(
    val model: String?,
    val title: String,
    val choices: List<Pair<String, String>>,
    val value: String?,
    val older: Boolean = false,
)

/**
 * 默认思考强度页有哪几行：Claude Code 清单里的模型按模型菜单的顺序（[modelMenu]：各系列最新的在前，旧版本在后）， 同一个完整 ID
 * 只列一次（「默认」和它指向的那个是同一个），档位只列这个模型支持的；不支持思考强度、也没设过的不列。 没设过的旧版本标上 [EffortRow.older]。settings.json
 * 里设过、清单里没有的（下线的模型）接在后面，好让人改掉或删掉。 顶层设过一档时最后一行是「其他模型」。
 */
internal fun effortRows(
    models: List<ModelOption>,
    defaults: ClaudeDefaults,
    relay: Boolean = false,
): List<EffortRow> {
    val menu = modelMenu(if (relay) models.map { it.copy(resolvedModel = it.value) } else models)
    val olderIds = menu.older.mapNotNullTo(HashSet()) { it.resolvedModel }
    val listed =
        (menu.latest + menu.older)
            .filter { it.resolvedModel != null }
            .distinctBy { it.resolvedModel }
    val rows = listed.mapNotNull { option ->
        val id = option.resolvedModel!!
        val choices = EffortLevels.filter { level ->
            option.effortLevels?.contains(level.first) ?: true
        }
        val value = defaults.efforts[id]
        if (choices.isEmpty() && value == null) null
        else
            EffortRow(
                id,
                if (relay) option.displayName else readableModelId(id),
                choices,
                value,
                older = id in olderIds && value == null,
            )
    }
    val others =
        defaults.efforts
            .filterKeys { id -> listed.none { it.resolvedModel == id } }
            .map { (id, level) -> EffortRow(id, readableModelId(id), EffortLevels, level) }
    val rest =
        defaults.effort?.let {
            EffortRow(null, Strings.settingsDefaultsOtherModels(), EffortLevels, it)
        }
    return rows + others + listOfNotNull(rest)
}

/** 「Opus 5.5 超高，Fable 5.2 高」；只有顶层的就是那一档，都没设时说由对应工具定。 */
internal fun effortSummary(
    defaults: ClaudeDefaults,
    harness: String = Harnesses.CLAUDE,
): String {
    val perModel =
        defaults.efforts.map { (model, level) ->
            Strings.settingsDefaultsModelEffort(readableModelId(model), effortLabel(level) ?: level)
        }
    val rest = defaults.effort?.let { effortLabel(it) ?: it }
    val unset =
        if (Harnesses.normalize(harness) == Harnesses.AGY) Strings.settingsDefaultsAgyEffortUnset()
        else Strings.settingsDefaultsEffortUnset()
    return when {
        perModel.isEmpty() -> rest ?: unset
        rest == null -> perModel.joinToString(Strings.settingsDefaultsCommaSeparator())
        else ->
            (perModel +
                    Strings.settingsDefaultsModelEffort(
                        Strings.settingsDefaultsOtherModels(),
                        rest,
                    ))
                .joinToString(Strings.settingsDefaultsCommaSeparator())
    }
}

/**
 * 设置首页一台主机那一行右边的值：默认模型和它的默认思考强度，比如「Opus 5.5 · 超高」。别名（opus、没设时的默认）按这台的 模型清单 [models] 换成它指向的完整 ID
 * 再写名字；还没拿到清单时写别名的显示名。这个模型没单独设思考强度时用没单独设的 模型共用的那一档，都没设时只写模型。
 */
internal fun hostDefaultsSummary(defaults: ClaudeDefaults, models: List<ModelOption>): String {
    val model = defaults.model ?: Conversation.DEFAULT_MODEL
    val id = models.firstOrNull { it.value == model }?.resolvedModel
    val name = modelName(model, models) ?: Strings.settingsDefaultsModel()
    val effort = effortLabel(defaults.efforts[id ?: model] ?: defaults.effort)
    return listOfNotNull(name, effort).joinToString(" · ")
}
