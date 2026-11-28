package com.dzshzx.zeta.ui.setup

import androidx.compose.runtime.Composable
import com.dzshzx.zeta.core.AccountStatus
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.EnvHealth
import com.dzshzx.zeta.core.Harnesses
import com.dzshzx.zeta.core.InstallCli
import com.dzshzx.zeta.core.environment
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.components.miuix.DropdownValuePreference
import com.dzshzx.zeta.ui.components.miuix.MenuEntry
import com.dzshzx.zeta.ui.components.miuix.MenuItem
import com.dzshzx.zeta.ui.components.miuix.PreferenceGroup
import com.dzshzx.zeta.ui.settings.ValueRow
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

private fun installCliLabel(cli: InstallCli): String =
    when (cli) {
        InstallCli.Claude -> Harnesses.cliName(Harnesses.CLAUDE)
        InstallCli.Agy -> Harnesses.cliName(Harnesses.AGY)
        InstallCli.Both -> Strings.setupInstallCliBoth()
    }

/** 安装向导里选择安装 Claude Code、Antigravity CLI 或两者都装。 */
@Composable
internal fun InstallCliRow(
    cli: InstallCli,
    enabled: Boolean,
    onSelect: (InstallCli) -> Unit,
) {
    DropdownValuePreference(
        title = Strings.setupInstallCli(),
        summary = Strings.setupInstallCliSummary(),
        value = installCliLabel(cli),
        enabled = enabled,
        entries =
            listOf(
                MenuEntry(
                    items =
                        InstallCli.entries.map { item ->
                            MenuItem(
                                text = installCliLabel(item),
                                selected = item == cli,
                                onClick = { onSelect(item) },
                            )
                        },
                ),
            ),
    )
}

/** Linux 环境页的「Antigravity CLI」一组：版本、安装或更新、账号。 */
@Composable
internal fun AgyGroup(
    hub: ConversationHub,
    health: EnvHealth?,
    maintenanceRunning: Boolean,
    onOpenLogin: () -> Unit,
) {
    val installed = health?.agy?.isNotBlank() == true
    val account = hub.environment.agyAccount
    PreferenceGroup(title = "Antigravity CLI") {
        item {
            ValueRow(
                Strings.setupVersion(),
                health?.agy?.ifBlank { Strings.setupNotInstalled() } ?: "…",
            )
        }
        if (health != null && !installed) {
            item {
                BasicComponent(
                    title = Strings.setupInstallNow(),
                    summary = Strings.setupInstallNowSummary(),
                    enabled = !maintenanceRunning,
                    onClick = hub.environment::installAgy,
                )
            }
        } else {
            item {
                BasicComponent(
                    title = Strings.setupUpdate(),
                    summary = Strings.setupUpdateLatest(),
                    enabled = !maintenanceRunning && health != null,
                    onClick = hub.environment::updateAgy,
                )
            }
            item {
                ArrowPreference(
                    title = Strings.setupAccount(),
                    summary = cliAccountSummary(account),
                    onClick = onOpenLogin,
                )
            }
        }
    }
}

internal fun cliAccountSummary(account: AccountStatus?): String =
    when {
        account == null -> Strings.setupQuerying()
        account.relay != null -> Strings.settingsAccountsRelayEntry(account.relay)
        !account.loggedIn -> Strings.setupNotLoggedIn()
        else ->
            listOfNotNull(account.email, account.plan).joinToString(" · ").ifBlank {
                Strings.setupLoggedIn()
            }
    }
