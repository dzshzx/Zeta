package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AccountService
import com.dzshzx.zeta.core.ClaudeRelay
import com.dzshzx.zeta.core.RelayDraft
import com.dzshzx.zeta.core.RelayModel
import com.dzshzx.zeta.i18n.CoreStrings
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DialogButtonBar
import com.dzshzx.zeta.ui.components.miuix.DialogButtonBarAction
import com.dzshzx.zeta.ui.components.miuix.DialogContentPadding
import com.dzshzx.zeta.ui.components.miuix.FieldGroup
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PageDialog
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.TextField
import com.dzshzx.zeta.ui.components.miuix.TopBarTextButton
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 中转编辑页上「获取模型列表」那一摊：拉取中、拉到的列表（弹出来挑）、没成的原因。 */
private class ModelFetch {
    var fetching by mutableStateOf(false)
        private set

    var models by mutableStateOf<List<RelayModel>?>(null)

    var error by mutableStateOf<String?>(null)
        private set

    private var request: Any? = null

    fun load(service: AccountService, draft: RelayDraft) {
        val pending = Any()
        request = pending
        fetching = true
        error = null
        service.relayModels(draft) { result ->
            if (request !== pending) return@relayModels
            request = null
            fetching = false
            result
                .onSuccess {
                    if (it.isEmpty()) error = Strings.relayModelsEmpty() else models = it
                }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    fun invalidate() {
        request = null
        fetching = false
        models = null
        error = null
    }
}

/** 编辑页的状态：草稿、保存中、没做成的原因、删除确认框、拉模型列表。 */
private class RelayEditor(val service: AccountService, val original: ClaudeRelay?) {
    val create = original == null
    val keyEditable = original?.keyEditable ?: true
    val initial = original?.let(RelayDraft::of) ?: RelayDraft()
    var draft by mutableStateOf(initial)
        private set

    var saving by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
    var confirmDelete by mutableStateOf(false)
    val fetch = ModelFetch()

    val problem: String?
        get() = draft.problem(create, service.state?.names.orEmpty(), keyEditable)

    val canSave: Boolean
        get() = problem == null && !saving && draft != initial

    /** 页面底下的红字：草稿哪里不对（动过以后才说），或保存、删除没成的原因。 */
    val notice: String?
        get() = failure ?: problem?.takeIf { draft != initial }

    fun update(next: RelayDraft) {
        if (next.baseUrl != draft.baseUrl || next.key != draft.key) fetch.invalidate()
        draft = next
        failure = null
    }

    fun save(onDone: () -> Unit) {
        if (!canSave) return
        saving = true
        // 密钥框禁用时不带密钥：核心那边改不了它，带了反而被拒
        service.saveRelay(if (keyEditable) draft else draft.copy(key = ""), create) { why ->
            saving = false
            if (why == null) onDone() else failure = Strings.relaySaveFailed(why)
        }
    }

    fun delete(onDone: () -> Unit) {
        val relay = original ?: return
        confirmDelete = false
        service.removeRelay(relay) { why ->
            if (why == null) onDone() else failure = Strings.relayDeleteFailed(why)
        }
    }
}

/**
 * 添加（[original] 为 null）或修改一个中转：编辑的内容放在草稿（[RelayDraft]）里，点保存才交给那台的核心（accounts.relay_save），
 * 之前账号页的列表不变。默认模型可以手填，也可以用填好的地址、密钥拉一次模型列表挑一个。修改时名字只读，密钥留空表示不改； 中转的密钥不是从文件读的（用户自己放的）时密钥框禁用。
 */
@Composable
internal fun RelayScreen(
    service: AccountService,
    original: ClaudeRelay?,
    onBack: () -> Unit,
) {
    val editor = remember(service, original?.name) { RelayEditor(service, original) }
    val draft = editor.draft
    MiuixPage(
        title = if (editor.create) Strings.settingsRelaysAdd() else Strings.relayEditTitle(),
        onBack = onBack,
        actions = {
            TopBarTextButton(
                text = if (editor.saving) Strings.relaySaving() else Strings.commonSave(),
                enabled = editor.canSave,
                onClick = { editor.save(onBack) },
            )
        },
        dialogs = { RelayDialogs(editor, onBack) },
    ) {
        item(key = "name") { NameField(draft, editor.create, editor::update) }
        item(key = "url") { UrlField(draft, editor::update) }
        item(key = "key") { KeyField(draft, original, editor::update) }
        item(key = "model") {
            ModelGroup(
                service,
                draft,
                editor.original?.hasKey == true,
                editor.fetch,
                editor::update,
            )
        }
        editor.notice?.let { text ->
            item(key = "problem") {
                FooterHint(summary = text, summaryColor = MiuixTheme.colorScheme.error)
            }
        }
        original?.let { relay ->
            item(key = "delete") {
                DeleteGroup(relay, enabled = !editor.saving) { editor.confirmDelete = true }
            }
        }
    }
}

/** 编辑页的对话框：挑模型、删除确认。 */
@Composable
private fun RelayDialogs(editor: RelayEditor, onBack: () -> Unit) {
    val fetch = editor.fetch
    ModelPicker(
        models = fetch.models,
        current = editor.draft.model,
        onPick = {
            editor.update(editor.draft.copy(model = it))
            fetch.models = null
        },
        onDismiss = { fetch.models = null },
    )
    val relay = editor.original ?: return
    ConfirmDialog(
        show = editor.confirmDelete,
        title = Strings.relayDeleteTitle(relay.name),
        summary = Strings.relayDeleteSummary(),
        confirmText = Strings.commonDelete(),
        destructive = true,
        onConfirm = { editor.delete(onBack) },
        onDismissRequest = { editor.confirmDelete = false },
    )
}

/** 地址：要说 Anthropic Messages（Claude Code 会说的唯一协议），框下说明 OpenAI 上游怎么接。 */
@Composable
private fun UrlField(draft: RelayDraft, update: (RelayDraft) -> Unit) {
    FieldGroup(title = Strings.relayBaseUrl(), footer = Strings.relayBaseUrlFooter()) {
        TextField(
            value = draft.baseUrl,
            onValueChange = { update(draft.copy(baseUrl = it.trim())) },
            label = "https://",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
    }
}

/** 删除：危险样式，点了弹确认框；在用的中转删不了，组下说先换走。 */
@Composable
private fun DeleteGroup(relay: ClaudeRelay, enabled: Boolean, onClick: () -> Unit) {
    PreferenceGroup(footer = if (relay.current) CoreStrings.errRelayInUse() else null) {
        item {
            BasicComponent(
                title = Strings.relayDelete(),
                titleColor =
                    BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                enabled = enabled && !relay.current,
                onClick = onClick,
            )
        }
    }
}

/** 名称：添加时能填，修改时只读（claude-switch 的目标名，改名等于另建一个）。 */
@Composable
private fun NameField(draft: RelayDraft, create: Boolean, update: (RelayDraft) -> Unit) {
    if (!create) {
        PreferenceGroup {
            item { ValueRow(Strings.relayName(), draft.name) }
        }
        return
    }
    FieldGroup(title = Strings.relayName(), footer = Strings.relayNameFooter()) {
        TextField(
            value = draft.name,
            onValueChange = { update(draft.copy(name = it.trim().lowercase())) },
            label = Strings.relayNameHint(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
    }
}

/** 密钥：默认遮着，右边的按钮切换显示。修改时留空表示不改；不是从文件读的密钥 Zeta 改不了，框禁用并说明。 */
@Composable
private fun KeyField(draft: RelayDraft, original: ClaudeRelay?, update: (RelayDraft) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val editable = original?.keyEditable ?: true
    FieldGroup(
        title = Strings.relayKey(),
        footer = if (editable) null else CoreStrings.errRelayKeyReadOnly(),
    ) {
        TextField(
            value = draft.key,
            onValueChange = { update(draft.copy(key = it.trim())) },
            label = if (original?.hasKey == true) Strings.relayKeyKeep() else "sk-…",
            enabled = editable,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation =
                if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            // 密钥框禁用时没有可看的，不给切换按钮
            trailingIcon = if (editable) ({ KeyToggle(visible) { visible = !visible } }) else null,
        )
    }
}

/** 默认模型：手填，或「获取模型列表」拉到后挑一个。拉的时候用的是草稿里的地址和密钥；密钥留空时核心用这个中转已存的那份（[savedKey]：修改的是已存着密钥的中转）。 */
@Composable
private fun ModelGroup(
    service: AccountService,
    draft: RelayDraft,
    savedKey: Boolean,
    fetch: ModelFetch,
    update: (RelayDraft) -> Unit,
) {
    Column {
        FieldGroup(title = Strings.relayModel()) {
            TextField(
                value = draft.model,
                onValueChange = { update(draft.copy(model = it.trim())) },
                label = Strings.relayModelHint(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            )
        }
        val canFetch =
            !fetch.fetching &&
                RelayDraft.validBaseUrl(draft.baseUrl) &&
                (draft.key.isNotBlank() || savedKey)
        PreferenceGroup(footer = fetch.error ?: Strings.relayModelFooter()) {
            item {
                BasicComponent(
                    title =
                        if (fetch.fetching) Strings.relayFetchingModels()
                        else Strings.relayFetchModels(),
                    enabled = canFetch,
                    onClick = { fetch.load(service, draft) },
                )
            }
        }
    }
}

/** 拉到的模型列表：第一项是「不设」（清空默认模型），其余显示名下面注 id。点一项就选它并关掉。 */
@Composable
private fun ModelPicker(
    models: List<RelayModel>?,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    PageDialog(
        show = models != null,
        onDismissRequest = onDismiss,
        title = Strings.relayPickModel(),
        customContent = {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                val margin = PaddingValues(horizontal = DialogContentPadding, vertical = 10.dp)
                RadioButtonPreference(
                    title = Strings.relayModelHint(),
                    selected = current.isBlank(),
                    onClick = { onPick("") },
                    radioButtonLocation = RadioButtonLocation.End,
                    insideMargin = margin,
                )
                models.orEmpty().forEach { model ->
                    RadioButtonPreference(
                        title = model.title,
                        summary = model.id.takeIf { it != model.title },
                        selected = model.id == current,
                        onClick = { onPick(model.id) },
                        radioButtonLocation = RadioButtonLocation.End,
                        insideMargin = margin,
                    )
                }
            }
        },
    ) {
        DialogButtonBar(
            negative = DialogButtonBarAction(Strings.commonCancel(), onClick = onDismiss),
            positive = null,
        )
    }
}

/** 密钥框右边的显示、隐藏切换。 */
@Composable
private fun KeyToggle(visible: Boolean, onToggle: () -> Unit) {
    FlatIconButton(
        icon = if (visible) MiuixIcons.Hide else MiuixIcons.Show,
        contentDescription = if (visible) Strings.relayKeyHide() else Strings.relayKeyShow(),
        size = 32.dp,
        iconSize = 18.dp,
        tint = MiuixTheme.colorScheme.onBackgroundVariant,
        onClick = onToggle,
    )
}
