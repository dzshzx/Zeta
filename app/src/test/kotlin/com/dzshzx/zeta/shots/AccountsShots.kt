package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AccountsState
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Backends
import com.dzshzx.zeta.core.ClaudeAccount
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LoginState
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.settings.AccountsScreen
import com.dzshzx.zeta.ui.setup.LoginScreen
import org.junit.Test

class AccountsShots : ShotTest() {
    private val now = System.currentTimeMillis()

    /** 一个快到上限的账号：每周 86%（服务器说要注意），按模型分的每周 78%，5 小时窗口刚用了一点。 */
    private fun ConversationHub.fakeQuota(backend: String = Backends.LOCAL) {
        quota(backend)
            .force(
                "snapshot",
                QuotaSnapshot(
                    windows =
                        listOf(
                            QuotaWindow(
                                "session",
                                "session",
                                null,
                                0.12,
                                now + 3 * 3_600_000 + 20 * 60_000,
                                QuotaSeverity.Normal,
                                label = "5 小时",
                            ),
                            QuotaWindow(
                                "weekly_all",
                                "weekly",
                                null,
                                0.86,
                                now + 2 * 86_400_000 + 9 * 3_600_000,
                                QuotaSeverity.Warning,
                                active = true,
                                label = "每周 · 全部模型",
                            ),
                            QuotaWindow(
                                "weekly_scoped",
                                "weekly",
                                "Fable",
                                0.78,
                                now + 2 * 86_400_000 + 9 * 3_600_000,
                                QuotaSeverity.Warning,
                                label = "每周 · Fable",
                            ),
                        ),
                    subscription = "max",
                    // 一分钟前的数：打开页面时不去刷新（JVM 上进不了 Linux 环境）
                    updatedAt = now - 60_000,
                ),
            )
    }

    /** claude-switch 给的样子：id 是账号名。 */
    private fun account(id: String, email: String, org: String = "", subscription: String = "max") =
        ClaudeAccount(id, email, org, "", subscription, null)

    /** 在用的是 claude，另外存着 claude-b（团队号）。 */
    private fun accountsState(): AccountsState {
        val current = account("claude", "shots@example.invalid")
        return AccountsState(
            current = current,
            all =
                listOf(
                    current,
                    account(
                        "claude-b",
                        "work@example.invalid",
                        org = "Example Co",
                        subscription = "team",
                    ),
                ),
        )
    }

    /** 在用的登录 claude-switch 还没记过：没有名字，列成当前账号，提示添加账号时会记下。 */
    private fun unrecordedState() =
        AccountsState(
            current = account("", "new@example.invalid"),
            all =
                listOf(
                    account("", "new@example.invalid"),
                    account("claude", "shots@example.invalid"),
                ),
        )

    /** 账号页：打开页面时会去 Linux 环境里读账号，JVM 上读不到，换成摆好的 [state]；[then] 是摆好以后要做的操作。 */
    private fun shootAccounts(
        name: String,
        state: AccountsState,
        then: AndroidComposeTestRule<*, *>.() -> Unit = {},
    ) {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        shoot(
            name,
            prepare = {
                hub.accounts(Backends.LOCAL).force("state", state)
                hub.accounts(Backends.LOCAL).force("problem", null)
                then()
            },
        ) {
            AccountsScreen(hub = hub, backend = Backends.LOCAL, onBack = {})
        }
    }

    @Test fun accountsPage() = shootAccounts("accounts", accountsState())

    @Test fun accountsUnrecorded() = shootAccounts("accounts-unrecorded", unrecordedState())

    /** 点另一个账号弹出的菜单：切换、移除。 */
    @Test
    fun accountsMenu() =
        shootAccounts("accounts-menu", accountsState()) {
            settle()
            onNodeWithText("work@example.invalid").performClick()
        }

    @Test
    fun chatQuotaChipAndUsageDialog() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        hub.showChat(
            "额度",
            listOf(UserItem("u1", "还剩多少额度？"), AnswerItem("a1", "点输入栏右边的圆环看。", streaming = false)),
        )
        shoot(
            "chat-quota",
            prepare = {
                onAllNodes(hasContentDescription("上下文用量", substring = true))
                    .onFirst()
                    .performClick()
            },
        ) {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
    }

    /** 远程设备的账号页：标题带设备名，那台上的账号和额度；和手机本机一样能添加账号（登录经那台的核心）。 */
    @Test
    fun remoteHostAccounts() {
        val hub = Fixtures.readyHub(app)
        val remote = Fixtures.workstation.id
        hub.settings.remoteHosts = listOf(Fixtures.workstation)
        hub.fakeQuota(remote)
        shoot(
            "accounts-remote",
            prepare = {
                hub.accounts(remote).force("state", accountsState())
                hub.accounts(remote).force("problem", null)
            },
        ) {
            AccountsScreen(hub = hub, backend = remote, onBack = {})
        }
    }

    /** 在远程设备上添加账号：核心已把在用的号存档并退出、起了登录命令，这边打开授权网址、贴回授权码。 */
    @Test
    fun remoteHostLogin() {
        val hub = Fixtures.readyHub(app)
        val remote = Fixtures.workstation.id
        hub.settings.remoteHosts = listOf(Fixtures.workstation)
        val loggedOut = accountsState().let { it.copy(current = null, all = it.all) }
        shoot(
            "login-remote",
            prepare = {
                hub.accounts(remote).force("state", loggedOut)
                hub.accounts(remote).force("problem", null)
                hub.accounts(remote)
                    .login
                    .force(
                        "state",
                        LoginState.WaitingCode("https://claude.ai/oauth/authorize?code=true"),
                    )
            },
        ) {
            LoginScreen(hub = hub, backend = remote, onBack = {})
        }
    }
}
