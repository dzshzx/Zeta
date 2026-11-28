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

/** Linux 环境页的单个 CLI（Claude Code / Antigravity CLI）一组：版本、安装或更新、账号与可选的退出登录。 */
@Composable
internal fun CliEnvGroup(
    title: String,
    version: String?,
    missing: Boolean,
    account: AccountStatus?,
    maintenanceRunning: Boolean,
    updateEnabled: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onOpenLogin: () -> Unit,
    onLogout: (() -> Unit)? = null,
) {
    PreferenceGroup(title = title) {
        item {
            ValueRow(
                Strings.setupVersion(),
                version?.ifBlank { Strings.setupNotInstalled() } ?: "…",
            )
        }
        if (missing) {
            item {
                BasicComponent(
                    title = Strings.setupInstallNow(),
                    summary = Strings.setupInstallNowSummary(),
                    enabled = !maintenanceRunning,
                    onClick = onInstall,
                )
            }
        } else {
            item {
                BasicComponent(
                    title = Strings.setupUpdate(),
                    summary = Strings.setupUpdateLatest(),
                    enabled = updateEnabled,
                    onClick = onUpdate,
                )
            }
            item {
                ArrowPreference(
                    title = Strings.setupAccount(),
                    summary = cliAccountSummary(account),
                    onClick = onOpenLogin,
                )
            }
            if (onLogout != null && account?.loggedIn == true && account.relay == null) {
                item {
                    BasicComponent(
                        title = Strings.setupLogout(),
                        enabled = !maintenanceRunning,
                        onClick = onLogout,
                    )
                }
            }
        }
    }
}

/** Linux 环境页的「Antigravity CLI」一组：版本、安装或更新、账号。 */
@Composable
internal fun AgyGroup(
    hub: ConversationHub,
    health: EnvHealth?,
    maintenanceRunning: Boolean,
    onOpenLogin: () -> Unit,
) {
    CliEnvGroup(
        title = "Antigravity CLI",
        version = health?.agy,
        missing = health != null && health.agy.isBlank(),
        account = hub.environment.agyAccount,
        maintenanceRunning = maintenanceRunning,
        updateEnabled = !maintenanceRunning && health != null,
        onInstall = hub.environment::installAgy,
        onUpdate = hub.environment::updateAgy,
        onOpenLogin = onOpenLogin,
        onLogout = hub.environment::logoutAgy,
    )
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
