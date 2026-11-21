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
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.QuotaService
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.claude.ClaudeAccount
import com.dzshzx.zeta.ui.chat.QuotaWindows
import com.dzshzx.zeta.ui.chat.quotaFootnote
import com.dzshzx.zeta.ui.chat.severityColor
import com.dzshzx.zeta.ui.components.SlideInPage
import com.dzshzx.zeta.ui.components.coui2.ConfirmDialog
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.PreferenceValue
import com.dzshzx.zeta.ui.components.coui2.SettingsHue
import com.dzshzx.zeta.ui.components.coui2.SettingsIcon
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.setup.LoginScreen
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.ContactsBook
import io.github.suqi8.coui.kmp.menu.OverlayDropdownMenu
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.PagerFooterPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 设置首页的入口：当前账号，和最要紧的一个额度窗口。 */
@Composable
internal fun AccountsEntry(hub: ConversationHub, onOpen: () -> Unit) {
    val ready = hub.environment.setup == SetupState.Ready && hub.settings.env != null
    val now = rememberNow()
    val account = hub.environment.account
    val headline = hub.quota.snapshot?.current(now)?.headline
    // 摘要只放账号，最要紧的额度窗口用了多少放右侧（按紧张程度上色）；是哪个窗口点进去看，摘要才不折行
    ArrowPreference(
        title = "账号与额度",
        summary =
            when {
                !ready -> "Linux 环境未就绪"
                account == null -> "查询中"
                !account.loggedIn -> "未登录"
                else -> account.email ?: "已登录"
            },
        startAction = { SettingsIcon(COUIIcons.ContactsBook, SettingsHue.Claude, enabled = ready) },
        endActions = {
            if (ready && account?.loggedIn == true && headline != null) {
                PreferenceValue(
                    "已用 ${headline.percent}%",
                    color = severityColor(headline.severity, Color.Unspecified),
                )
            }
        },
        enabled = ready,
        onClick = onOpen,
    )
}

/**
 * 账号与额度：当前账号的订阅额度（Claude Code 给的，和 /usage 同一份），claude-switch 管着的几个 Claude 账号，切换、添加、移除。 切换改的是 Linux
 * 用户的 Claude Code 登录状态：两个 Zeta 和终端里的 claude 用的都是这一份。
 *
 * 账号和终端里的 claude-switch 是同一份：存在 Linux 环境的 ~/.local/state/claude-switch 里，每个账号一份登录令牌。
 * 切换前关掉本包闲着的对话进程（内容不丢，下次发消息时接着用新账号）；另一个 Zeta 或终端里开着 Claude Code 时 claude-switch 不让换。
 * 添加账号：先把在用的号存档并退出，再走 Claude Code 的登录（claude auth login），新号的名字自动起成 claude-b、claude-c 这样。
 * 移除只删这台手机上的存档和记录，服务器上的登录还在。
 */
@Composable
internal fun AccountsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val service = hub.accounts
    val quota = hub.quota
    val now = rememberNow()
    var adding by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ClaudeAccount?>(null) }
    var removing by remember { mutableStateOf<ClaudeAccount?>(null) }
    LaunchedEffect(Unit) {
        service.refresh()
        quota.refresh()
    }
    Box(Modifier.fillMaxSize()) {
        CouiPage(
            title = "账号与额度",
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
            service.problem?.let { problem ->
                item(key = "problem") {
                    PagerFooterPreference(
                        summary = problem,
                        summaryColor = COUITheme.colorScheme.error,
                    )
                }
            }
        }
        SlideInPage(adding) {
            LoginScreen(hub = hub, onBack = { adding = false })
        }
    }
    BackHandler(enabled = adding) {
        hub.environment.login.cancel()
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
        title = "切换到 ${confirming?.title.orEmpty()}？",
        summary = "Zeta 会先关掉闲着的对话进程（对话内容不丢）。之后新对话、额度都用这个账号；终端里的 claude 也一样。",
        confirmText = "切换",
        onConfirm = { if (confirming != null) onSwitch(confirming) else onDismissSwitch() },
        onDismissRequest = onDismissSwitch,
    )
    ConfirmDialog(
        show = removing != null,
        title = "移除 ${removing?.title.orEmpty()}？",
        summary = "只删这台手机上存着的这份登录和记录，服务器上的登录还在。以后要用得重新登录。",
        confirmText = "移除",
        destructive = true,
        onConfirm = { if (removing != null) onRemove(removing) else onDismissRemove() },
        onDismissRequest = onDismissRemove,
    )
}

/** 订阅额度：各个时间窗的用量（有的话）和刷新。 */
@Composable
private fun QuotaGroup(quota: QuotaService, now: Long) {
    val snapshot = quota.snapshot
    PreferenceGroup(
        title = "订阅额度",
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
                title = if (quota.refreshing) "正在刷新…" else "刷新",
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
        title = "账号",
        footer =
            "两个 Zeta 和终端里的 claude 共用一份登录，有对话在进行或别处开着 Claude Code 时不能切换。" +
                "添加账号会先退出当前账号，中途取消的话，在这里切回去。",
    ) {
        when {
            state == null -> item { BasicComponent(title = "正在读取…") }
            !state.loggedIn && state.saved.isEmpty() -> item { BasicComponent(title = "还没有登录") }
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
            ArrowPreference(title = "添加账号", enabled = working == null, onClick = onAdd)
        }
    }
}

/** 账号一行的说明。名字和终端里 claude-switch 列的一样（claude、claude-b…）；在用的登录还没记过时没有名字，添加账号时会自动记下。 */
private fun accountSummary(account: ClaudeAccount, now: Long): String? =
    listOfNotNull(
            account.id.ifBlank { null },
            account.organization.ifBlank { null },
            account.plan.ifBlank { null },
            if (account.expired(now)) "登录已过期" else null,
            if (account.id.isBlank()) "还没记下名字" else null,
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
        BasicComponent(
            title = account.title,
            summary = summary,
            endActions = { PreferenceValue("当前") },
        )
        return
    }
    OverlayDropdownMenu(
        entry =
            DropdownEntry(
                listOf(
                    DropdownItem(text = "切换到这个账号", onClick = { onSwitch(account) }),
                    DropdownItem(text = "移除", alert = true, onClick = { onRemove(account) }),
                ),
            ),
        title = account.title,
        summary = summary,
        enabled = enabled,
    )
}
