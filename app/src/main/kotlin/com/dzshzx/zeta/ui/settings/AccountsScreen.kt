package com.dzshzx.zeta.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AccountService
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ClaudeAccount
import com.dzshzx.zeta.core.ClaudeRelay
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.QuotaService
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.chat.QuotaWindows
import com.dzshzx.zeta.ui.chat.quotaFootnote
import com.dzshzx.zeta.ui.chat.severityColor
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.miuix.ConfirmDialog
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference
import com.dzshzx.zeta.ui.components.miuix.FlatIconButton
import com.dzshzx.zeta.ui.components.miuix.FooterHint
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.MiuixPage
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.components.miuix.PreferenceValue
import com.dzshzx.zeta.ui.components.miuix.SettingsIcon
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.setup.LoginScreen
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ContactsBook
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 入口上写的账号状态：登没登录、是哪个号、在用的中转（[relay]，这时没有账号登录）；还没查到是 null。 */
private class EntryAccount(val loggedIn: Boolean, val email: String?, val relay: String? = null)

/** 主机 [backend] 上登录着的账号：手机本机的是环境检查时查到的，远程主机的看那台的账号清单（没读过就读一次）。 */
@Composable
private fun entryAccount(hub: ConversationHub, backend: String, ready: Boolean): EntryAccount? {
    if (backend == Backends.LOCAL)
        return hub.environment.account?.let { EntryAccount(it.loggedIn, it.email, it.relay) }
    val service = hub.accounts(backend)
    LaunchedEffect(backend, ready) { if (ready && service.state == null) service.refresh() }
    return service.state?.let {
        EntryAccount(
            it.loggedIn || it.relay != null,
            it.current?.email?.ifBlank { null },
            it.relay?.name,
        )
    }
}

/** 入口的摘要：用不了的原因、查询中、未登录、中转名，或登录着的邮箱。 */
private fun entrySummary(backend: String, ready: Boolean, account: EntryAccount?): String =
    when {
        !ready ->
            if (backend == Backends.LOCAL) Strings.settingsAccountsLinuxNotReady()
            else Strings.settingsAccountsDeviceUnavailable()
        account == null -> Strings.commonQuerying()
        account.relay != null -> Strings.settingsAccountsRelayEntry(account.relay)
        !account.loggedIn -> Strings.settingsAccountsSignedOut()
        else -> account.email ?: Strings.settingsAccountsSignedIn()
    }

/** 各台设备页上的入口：那台上当前的账号，和最要紧的一个额度窗口。 */
@Composable
internal fun AccountsEntry(hub: ConversationHub, backend: String, onOpen: () -> Unit) {
    val ready =
        if (backend == Backends.LOCAL)
            hub.environment.setup == SetupState.Ready && hub.settings.env != null
        else hub.usable(backend)
    val now = rememberNow()
    val account = entryAccount(hub, backend, ready)
    val quota = hub.quota(backend)
    LaunchedEffect(backend, ready) { if (ready) quota.watch() }
    val headline = quota.snapshot?.current(now)?.headline
    // 摘要只放账号，最要紧的额度窗口用了多少放右侧（按紧张程度上色）；是哪个窗口点进去看，摘要才不折行
    ArrowPreference(
        title = Strings.settingsAccountsTitle(),
        summary = entrySummary(backend, ready, account),
        startAction = { SettingsIcon(MiuixIcons.ContactsBook, enabled = ready) },
        endActions = {
            // 在用中转时没有订阅额度，也不显示上一个账号留下的数
            if (ready && account?.loggedIn == true && quota.subscribed && headline != null) {
                PreferenceValue(
                    Strings.settingsAccountsUsedPercent(headline.percent),
                    color = severityColor(headline.severity, Color.Unspecified),
                )
            }
        },
        enabled = ready,
        onClick = onOpen,
    )
}

/**
 * 主机 [backend] 的账号与额度：那台上当前账号的订阅额度（Claude Code 给的，和 /usage 同一份），claude-switch 管着的几个 Claude
 * 账号，切换、添加、移除。 切换改的是那台上 Linux 用户的 Claude Code 登录状态：两个 Zeta 和终端里的 claude 用的都是这一份。各台设备各管各的，
 * 可以登录不同的账号。都经那台的核心做（[AccountService]），手机本机和远程设备一样。
 *
 * 账号和终端里的 claude-switch 是同一份：存在那台的 ~/.local/state/claude-switch 里，每个账号一份登录令牌。
 * 切换前关掉本包闲着的对话进程（内容不丢，下次发消息时接着用新账号）；另一个 Zeta 或终端里开着 Claude Code 时 claude-switch 不让换。
 * 添加账号：核心先把在用的号存档并退出，再在那台上起 Claude Code 的登录（claude auth login），这边打开授权网址、贴回授权码； 新号的名字自动起成
 * claude-b、claude-c 这样。移除只删那台设备上的存档和记录，服务器上的登录还在。
 */
@Composable
internal fun AccountsScreen(hub: ConversationHub, backend: String, onBack: () -> Unit) {
    val service = hub.accounts(backend)
    val quota = hub.quota(backend)
    val now = rememberNow()
    var adding by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ClaudeAccount?>(null) }
    var removing by remember { mutableStateOf<ClaudeAccount?>(null) }
    var switchingRelay by remember { mutableStateOf<ClaudeRelay?>(null) }
    var editing by remember { mutableStateOf<RelayEdit?>(null) }
    LaunchedEffect(Unit) {
        service.refresh()
        quota.refresh()
    }
    Box(Modifier.fillMaxSize()) {
        MiuixPage(
            title =
                if (backend == Backends.LOCAL) Strings.settingsAccountsTitle()
                else Strings.settingsAccountsTitleOnDevice(hub.backendName(backend)),
            onBack = onBack,
            dialogs = {
                AccountDialogs(
                    confirming = confirming,
                    removing = removing,
                    onSwitch = { account ->
                        service.switchTo(account)
                        confirming = null
                    },
                    onRemove = { account ->
                        service.remove(account)
                        removing = null
                    },
                    onDismissSwitch = { confirming = null },
                    onDismissRemove = { removing = null },
                )
                ConfirmDialog(
                    show = switchingRelay != null,
                    title = Strings.settingsAccountsSwitchTitle(switchingRelay?.name.orEmpty()),
                    summary = Strings.settingsRelaysSwitchSummary(),
                    confirmText = Strings.settingsAccountsSwitch(),
                    onConfirm = {
                        switchingRelay?.let(service::switchTo)
                        switchingRelay = null
                    },
                    onDismissRequest = { switchingRelay = null },
                )
            },
        ) {
            item(key = "quota") { QuotaGroup(quota, now) }
            item(key = "accounts") {
                AccountsGroup(
                    service = service,
                    now = now,
                    onSwitch = { confirming = it },
                    onRemove = { removing = it },
                    onAdd = { service.prepareLogin { adding = true } },
                )
            }
            item(key = "relays") {
                RelaysGroup(
                    service = service,
                    onSwitch = { switchingRelay = it },
                    onEdit = { editing = RelayEdit(it) },
                )
            }
            service.problem?.let { problem ->
                item(key = "problem") {
                    FooterHint(
                        summary = problem,
                        summaryColor = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        SlideInPage(adding) {
            LoginScreen(hub = hub, backend = backend, onBack = { adding = false })
        }
        SlideInPage(editing, onBack = { editing = null }) {
            RelayScreen(service = service, original = it.relay, onBack = { editing = null })
        }
    }
    BackHandler(enabled = adding) {
        service.login.cancel()
        adding = false
    }
    // 离开页面时，上一次没做成的原因不再挂着
    DisposableEffect(Unit) { onDispose { service.dismissProblem() } }
}

/** 切换、移除账号前的确认框。 */
@Composable
private fun AccountDialogs(
    confirming: ClaudeAccount?,
    removing: ClaudeAccount?,
    onSwitch: (ClaudeAccount) -> Unit,
    onRemove: (ClaudeAccount) -> Unit,
    onDismissSwitch: () -> Unit,
    onDismissRemove: () -> Unit,
) {
    ConfirmDialog(
        show = confirming != null,
        title = Strings.settingsAccountsSwitchTitle(confirming?.title.orEmpty()),
        summary = Strings.settingsAccountsSwitchSummary(),
        confirmText = Strings.settingsAccountsSwitch(),
        onConfirm = { if (confirming != null) onSwitch(confirming) else onDismissSwitch() },
        onDismissRequest = onDismissSwitch,
    )
    ConfirmDialog(
        show = removing != null,
        title = Strings.settingsAccountsRemoveTitle(removing?.title.orEmpty()),
        summary = Strings.settingsAccountsRemoveSummary(),
        confirmText = Strings.settingsAccountsRemove(),
        destructive = true,
        onConfirm = { if (removing != null) onRemove(removing) else onDismissRemove() },
        onDismissRequest = onDismissRemove,
    )
}

/** 订阅额度：各个时间窗的用量（有的话）和刷新。在用中转（核心说的 [QuotaService.relay]）时没有订阅额度，只说这一句。 */
@Composable
private fun QuotaGroup(quota: QuotaService, now: Long) {
    val relay = quota.relay
    if (relay != null) {
        PreferenceGroup(title = Strings.settingsAccountsQuota()) {
            item(key = "relay") {
                BasicComponent(title = Strings.settingsAccountsQuotaRelay(relay))
            }
        }
        return
    }
    val snapshot = quota.snapshot
    PreferenceGroup(
        title = Strings.settingsAccountsQuota(),
        footer = quotaFootnote(snapshot, quota.refreshing, quota.error, now),
    ) {
        if (snapshot != null && snapshot.available && snapshot.windows.isNotEmpty()) {
            item(key = "windows") {
                Column(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    QuotaWindows(snapshot, now)
                }
            }
        }
        item(key = "refresh") {
            BasicComponent(
                title =
                    if (quota.refreshing) Strings.settingsAccountsRefreshing()
                    else Strings.settingsAccountsRefresh(),
                enabled = !quota.refreshing,
                onClick = { quota.refresh(force = true) },
            )
        }
    }
}

/** 账号：claude-switch 管着的几个账号，在用的标“当前”，别的点开可以切换或移除；最后是添加账号。 */
@Composable
private fun AccountsGroup(
    service: AccountService,
    now: Long,
    onSwitch: (ClaudeAccount) -> Unit,
    onRemove: (ClaudeAccount) -> Unit,
    onAdd: () -> Unit,
) {
    val state = service.state
    val working = service.working
    PreferenceGroup(
        title = Strings.settingsAccountsGroup(),
        footer = Strings.settingsAccountsFooter(),
    ) {
        when {
            state == null -> item { BasicComponent(title = Strings.settingsAccountsLoading()) }
            !state.loggedIn && state.relay == null && state.all.isEmpty() ->
                item { BasicComponent(title = Strings.settingsAccountsNone()) }
        }
        state?.all?.forEach { account ->
            val isCurrent = account.id == state.current?.id
            val summary = accountSummary(account, now)
            item(key = account.id) {
                AccountRow(
                    account,
                    summary,
                    isCurrent,
                    enabled = working == null,
                    onSwitch,
                    onRemove,
                )
            }
        }
        working?.let { item(key = "working") { BasicComponent(title = "$it…") } }
        item(key = "add") {
            ArrowPreference(
                title = Strings.settingsAccountsAdd(),
                enabled = working == null,
                onClick = onAdd,
            )
        }
    }
}

/** 账号一行的说明。名字和终端里 claude-switch 列的一样（claude、claude-b…）；在用的登录还没记过时没有名字，添加账号时会自动记下。 */
private fun accountSummary(account: ClaudeAccount, now: Long): String? =
    listOfNotNull(
            account.id.ifBlank { null },
            account.organization.ifBlank { null },
            account.plan.ifBlank { null },
            if (account.expired(now)) Strings.settingsAccountsExpired() else null,
            if (account.id.isBlank()) Strings.settingsAccountsUnnamed() else null,
        )
        .joinToString(" · ")
        .ifBlank { null }

/** 一个账号：在用的只标“当前”，别的点开是切换和移除的菜单。 */
@Composable
private fun AccountRow(
    account: ClaudeAccount,
    summary: String?,
    isCurrent: Boolean,
    enabled: Boolean,
    onSwitch: (ClaudeAccount) -> Unit,
    onRemove: (ClaudeAccount) -> Unit,
) {
    if (isCurrent) {
        ValueRow(account.title, Strings.commonCurrent(), summary = summary)
        return
    }
    DropdownValuePreference(
        entries =
            listOf(
                MenuEntry(
                    listOf(
                        MenuItem(
                            text = Strings.settingsAccountsSwitchTo(),
                            onClick = { onSwitch(account) },
                        ),
                        MenuItem(
                            text = Strings.settingsAccountsRemove(),
                            onClick = { onRemove(account) },
                            alert = true,
                        ),
                    ),
                ),
            ),
        title = account.title,
        summary = summary,
        enabled = enabled,
    )
}

/** 打开着的中转编辑页：[relay] 为 null 是添加。 */
private class RelayEdit(val relay: ClaudeRelay?)

/** 中转：每行名字、地址（有默认模型时附上），在用的标“当前”；点行切换过去，右边的按钮进编辑页；最后是添加中转。 */
@Composable
private fun RelaysGroup(
    service: AccountService,
    onSwitch: (ClaudeRelay) -> Unit,
    onEdit: (ClaudeRelay?) -> Unit,
) {
    val state = service.state ?: return
    val enabled = service.working == null
    PreferenceGroup(
        title = Strings.settingsRelaysGroup(),
        footer = Strings.settingsRelaysFooter(),
    ) {
        state.relays.forEach { relay ->
            item(key = "relay:" + relay.name) {
                BasicComponent(
                    title = relay.name,
                    summary =
                        listOf(relay.baseUrl, relay.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                    enabled = enabled,
                    // 在用的不用再切
                    onClick = if (relay.current) null else ({ onSwitch(relay) }),
                    endActions = {
                        if (relay.current)
                            PreferenceValue(Strings.commonCurrent(), clickable = false)
                        FlatIconButton(
                            icon = MiuixIcons.Edit,
                            contentDescription = Strings.settingsRelaysEdit(relay.name),
                            size = 36.dp,
                            iconSize = 20.dp,
                            enabled = enabled,
                            tint = MiuixTheme.colorScheme.onBackgroundVariant,
                        ) {
                            onEdit(relay)
                        }
                    },
                )
            }
        }
        item(key = "relay-add") {
            ArrowPreference(
                title = Strings.settingsRelaysAdd(),
                enabled = enabled,
                onClick = { onEdit(null) },
            )
        }
    }
}
