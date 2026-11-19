package com.dzshzx.zeta.shots

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.QuotaSeverity
import com.dzshzx.zeta.core.QuotaSnapshot
import com.dzshzx.zeta.core.QuotaWindow
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.claude.AccountsState
import com.dzshzx.zeta.core.claude.ClaudeAccount
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.settings.AccountsScreen
import org.junit.Test

class AccountsShots : ShotTest() {
    private val now = System.currentTimeMillis()

    /** 一个快到上限的账号：每周 86%（服务器说要注意），按模型分的每周 78%，5 小时窗口刚用了一点。 */
    private fun ConversationHub.fakeQuota() {
        quota.force(
            "snapshot",
            QuotaSnapshot(
                windows = listOf(
                    QuotaWindow("session", "session", null, 0.12, now + 3 * 3_600_000 + 20 * 60_000, QuotaSeverity.Normal),
                    QuotaWindow("weekly_all", "weekly", null, 0.86, now + 2 * 86_400_000 + 9 * 3_600_000, QuotaSeverity.Warning, active = true),
                    QuotaWindow("weekly_scoped", "weekly", "Fable", 0.78, now + 2 * 86_400_000 + 9 * 3_600_000, QuotaSeverity.Warning),
                ),
                subscription = "max",
                // 一分钟前的数：打开页面时不去刷新（JVM 上进不了 Linux 环境）
                updatedAt = now - 60_000,
            ),
        )
    }

    /** claude-switch 给的样子：id 是账号名，没有存档时间、套餐显示名。 */
    private fun account(id: String, email: String, org: String = "", subscription: String = "max") =
        ClaudeAccount(id, email, org, "", "", subscription, null, null)

    /** 在用的是 claude，另外存着 claude-b（团队号）。 */
    private fun accountsState(): AccountsState {
        val current = account("claude", "shots@example.invalid")
        return AccountsState(
            loggedIn = true,
            current = current,
            saved = listOf(current, account("claude-b", "work@example.invalid", org = "Example Co", subscription = "team")),
            processes = emptyList(),
        )
    }

    /** 在用的登录 claude-switch 还没记过：没有名字，列成当前账号，提示添加账号时会记下。 */
    private fun unrecordedState() = AccountsState(
        loggedIn = true,
        current = account("", "new@example.invalid"),
        saved = listOf(account("claude", "shots@example.invalid")),
        processes = emptyList(),
    )

    @Test
    fun accountsPage() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        val state = accountsState()
        shoot(
            "accounts",
            prepare = {
                // 打开页面时会去 Linux 环境里读账号，JVM 上读不到：换成摆好的数据
                hub.accounts.force("state", state)
                hub.accounts.force("problem", null)
            },
        ) { AccountsScreen(hub = hub, onBack = {}) }
    }

    @Test
    fun accountsUnrecorded() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        val state = unrecordedState()
        shoot(
            "accounts-unrecorded",
            prepare = {
                hub.accounts.force("state", state)
                hub.accounts.force("problem", null)
            },
        ) { AccountsScreen(hub = hub, onBack = {}) }
    }

    /** 点另一个账号弹出的菜单：切换、移除。 */
    @Test
    fun accountsMenu() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        val state = accountsState()
        shoot(
            "accounts-menu",
            prepare = {
                hub.accounts.force("state", state)
                hub.accounts.force("problem", null)
                settle()
                onNodeWithText("work@example.invalid").performClick()
            },
        ) { AccountsScreen(hub = hub, onBack = {}) }
    }

    /** 输入栏右边的用量按钮在额度快到上限时换成额度提醒。 */
    @Test
    fun chatQuotaChip() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        hub.showChat("额度", listOf(UserItem("u1", "还剩多少额度？"), AnswerItem("a1", "看输入栏右边的提醒。", streaming = false)))
        shoot("chat-quota-chip") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    @Test
    fun chatQuotaChipAndUsageDialog() {
        val hub = Fixtures.readyHub(app)
        hub.fakeQuota()
        hub.showChat("额度", listOf(UserItem("u1", "还剩多少额度？"), AnswerItem("a1", "看输入栏右边的提醒。", streaming = false)))
        shoot("chat-quota", prepare = { onAllNodes(hasContentDescription("额度已用", substring = true)).onFirst().performClick() }) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }
}
