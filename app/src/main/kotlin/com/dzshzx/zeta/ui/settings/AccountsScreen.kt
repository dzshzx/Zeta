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
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.SetupState
import com.dzshzx.zeta.core.claude.ClaudeAccount
import com.dzshzx.zeta.ui.chat.QuotaWindows
import com.dzshzx.zeta.ui.chat.quotaFootnote
import com.dzshzx.zeta.ui.components.rememberNow
import com.dzshzx.zeta.ui.components.StatusError
import com.dzshzx.zeta.ui.components.StatusWarning
import com.dzshzx.zeta.ui.components.coui.CouiConfirmDialog
import com.dzshzx.zeta.ui.components.coui.CouiPopupPreference
import com.dzshzx.zeta.ui.components.coui.CouiPreference
import com.dzshzx.zeta.ui.components.coui.CouiScaffoldPage
import com.dzshzx.zeta.ui.setup.LoginScreen
import com.itos.coui.layout.Group
import com.itos.coui.menu.CouiMenuItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置首页的入口：当前账号，和最要紧的一个额度窗口。 */
@Composable
internal fun AccountsEntry(hub: ConversationHub, onOpen: () -> Unit) {
    val ready = hub.setup == SetupState.Ready && hub.env != null
    val now = rememberNow()
    val account = hub.account
    val headline = hub.quota.snapshot?.current(now)?.headline
    Group(title = "Claude 账号") {
        CouiPreference(
            title = "账号与额度",
            summary = when {
                !ready -> "Linux 环境准备好以后才能用"
                account == null -> "正在查询…"
                !account.loggedIn -> "未登录"
                else -> listOfNotNull(account.email, headline?.let { "${it.label}已用 ${it.percent}%" }).joinToString(" · ").ifBlank { "已登录" }
            },
            summaryColor = when (headline?.severity) {
                QuotaSeverity.Critical -> StatusError
                QuotaSeverity.Warning -> StatusWarning
                else -> Color.Unspecified
            },
            enabled = ready,
            onClick = onOpen,
        )
    }
}

/**
 * 账号与额度：当前账号的订阅额度（Claude Code 给的，和 /usage 同一份），Zeta 存着的几个 Claude 账号，切换、添加、移除。
 * 切换改的是 Linux 用户的 Claude Code 登录状态：两个 Zeta 和终端里的 claude 用的都是这一份。
 */
@Composable
internal fun AccountsScreen(hub: ConversationHub, onBack: () -> Unit) {
    val service = hub.accounts
    val quota = hub.quota
    val now = rememberNow()
    var adding by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<ClaudeAccount?>(null) }
    var removing by remember { mutableStateOf<ClaudeAccount?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        service.refresh()
        quota.refresh()
    }
    Box(Modifier.fillMaxSize()) {
        CouiScaffoldPage(title = "账号与额度", onBack = onBack) {
            Group(title = "订阅额度", footer = quotaFootnote(quota.snapshot, quota.refreshing, quota.error, now)) {
                val snapshot = quota.snapshot
                if (snapshot != null && snapshot.available && snapshot.windows.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { QuotaWindows(snapshot, now) }
                }
                CouiPreference(
                    title = if (quota.refreshing) "正在刷新…" else "刷新",
                    enabled = !quota.refreshing,
                    showChevron = false,
                    onClick = { quota.refresh(force = true) },
                )
            }

            val state = service.state
            val working = service.working
            Group(
                title = "账号",
                footer = "切换前 Zeta 会先关掉这里闲着的对话进程，对话内容不丢，下次发消息时接着用新账号。" +
                    "两个 Zeta 和终端里的 claude 共用同一份登录：有对话在进行，或另一个 Zeta、终端里还开着 Claude Code 时不能换。" +
                    "存着的登录状态只放在这台手机的 Linux 环境里（~/.config 下 Zeta 自己的目录，只有这个用户能读）；移除只删这份副本，不会注销那个账号。",
            ) {
                when {
                    state == null -> CouiPreference(title = "正在读取…", showChevron = false)
                    !state.loggedIn && state.saved.isEmpty() -> CouiPreference(title = "还没有登录", showChevron = false)
                }
                state?.all?.forEach { account ->
                    val isCurrent = account.id == state.current?.id
                    val summary = listOfNotNull(
                        account.organization.ifBlank { null },
                        account.plan.ifBlank { null },
                        if (account.expired(now)) "登录已过期" else null,
                        if (isCurrent && state.saved.none { it.id == account.id }) "还没存过" else null,
                    ).joinToString(" · ").ifBlank { null }
                    if (isCurrent) {
                        CouiPreference(title = account.title, summary = summary, value = "当前", showChevron = false)
                    } else {
                        CouiPopupPreference(
                            title = account.title,
                            summary = summary,
                            value = "切换",
                            enabled = working == null,
                            expanded = menuFor == account.id,
                            onExpandedChange = { menuFor = if (it) account.id else null },
                            items = listOf(CouiMenuItem(value = "switch", text = "切换到这个账号"), CouiMenuItem(value = "remove", text = "移除")),
                            onItemClick = {
                                menuFor = null
                                if (it.value == "switch") confirming = account else removing = account
                            },
                        )
                    }
                }
                if (state != null && state.loggedIn && state.current == null) {
                    CouiPreference(title = "已登录", summary = "认不出是哪个账号（~/.claude.json 里没有账号信息），存不了，也切换不了", showChevron = false)
                }
                working?.let { CouiPreference(title = it + "…", showChevron = false) }
                CouiPreference(
                    title = "添加账号",
                    summary = "用 Claude Code 的登录（claude auth login）登另一个账号，当前的会先存起来",
                    enabled = working == null,
                    onClick = { service.prepareLogin { adding = true } },
                )
            }
            service.problem?.let { problem ->
                Text(
                    text = problem,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp),
                )
            }
        }
        SettingsSubPageHost(adding) {
            LoginScreen(hub = hub, onBack = { adding = false })
        }
    }
    BackHandler(enabled = adding) {
        hub.login.cancel()
        adding = false
    }

    CouiConfirmDialog(
        visible = confirming != null,
        title = "切换到 ${confirming?.title.orEmpty()}？",
        message = "Zeta 会先关掉闲着的对话进程（对话内容不丢）。之后新对话、额度都用这个账号；终端里的 claude 也一样。",
        confirmText = "切换",
        onConfirm = {
            confirming?.let(service::switchTo)
            confirming = null
        },
        onDismissRequest = { confirming = null },
    )
    CouiConfirmDialog(
        visible = removing != null,
        title = "移除 ${removing?.title.orEmpty()}？",
        message = "只删 Zeta 存着的这份登录状态，不会注销这个账号。以后要用得重新登录。",
        confirmText = "移除",
        destructive = true,
        onConfirm = {
            removing?.let(service::remove)
            removing = null
        },
        onDismissRequest = { removing = null },
    )
    // 离开页面时，上一次没做成的原因不再挂着
    DisposableEffect(Unit) { onDispose { service.dismissProblem() } }
}
