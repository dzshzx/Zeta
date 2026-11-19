package com.dzshzx.zeta.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.claude.AccountsState
import com.dzshzx.zeta.core.claude.ClaudeAccount
import com.dzshzx.zeta.core.claude.ClaudeAccounts
import com.dzshzx.zeta.core.claude.ClaudeConfigClient
import com.dzshzx.zeta.core.claude.EnvShell
import com.dzshzx.zeta.core.claude.SwitchOutcome
import kotlin.concurrent.thread

/** 本包还有对话在进行，不能动登录状态。 */
private const val OwnConversationsBusy = "这里还有对话在进行（在回答、等你确认或有后台任务），等它结束再换"

/**
 * 账号页背后的状态：存着哪些账号、当前是哪个、正在切换。切换和登录都会改 Linux 用户的 Claude Code 登录状态，
 * 两个 Zeta 包和终端里的 claude 共用这一份，所以只在没有别的 Claude Code 进程开着时做（见 [ClaudeAccounts.blocker]）。
 */
class AccountService(private val hub: ConversationHub) {
    private val main = Handler(Looper.getMainLooper())
    private val script: String by lazy { hub.context.assets.open("zeta_accounts.sh").bufferedReader().use { it.readText() } }

    var state by mutableStateOf<AccountsState?>(null)
        private set

    /** 正在做的一件事（“正在切换到 …”），没有时为 null。 */
    var working by mutableStateOf<String?>(null)
        private set

    /** 上一件事为什么没做成；用户看过后清掉。 */
    var problem by mutableStateOf<String?>(null)
        private set

    private fun accounts(): ClaudeAccounts? = hub.env?.let { config ->
        ClaudeAccounts(ClaudeConfigClient { EnvShell(config) }, script, AppVariant.current.linuxName)
    }

    fun refresh() = refresh {}

    /** 读一遍账号；读完（读不成时是 null）在主线程上做 [then]。 */
    private fun refresh(then: (AccountsState?) -> Unit) {
        val client = accounts() ?: return then(null)
        thread(name = "zeta-accounts", isDaemon = true) {
            val listed = runCatching { client.list() }
            main.post {
                listed.onSuccess { state = it }.onFailure { if (working == null) problem = "读不了账号：${it.message}" }
                then(listed.getOrNull())
            }
        }
    }

    /** 把当前登录存一份（登录新账号之前和之后）。阻塞，别在主线程上调用；没成功就算了。 */
    internal fun saveCurrentBlocking() {
        runCatching { accounts()?.save() }
    }

    fun dismissProblem() {
        problem = null
    }

    /** 现在能不能动登录状态（切换或添加账号）；不能时给出原因。在主线程上调用。 */
    private fun localBlocker(): String? = when {
        working != null -> "上一件事还没做完"
        !hub.canSwitchEnvironment -> OwnConversationsBusy
        hub.login.state.let { it != LoginState.Idle && it != LoginState.Done && it !is LoginState.Failed } -> "正在登录，先完成或取消登录"
        else -> null
    }

    /**
     * 换成存着的 [target]：先看有没有别的 Claude Code 进程开着，再关掉本包闲着的对话进程（内容不丢，下次发消息时接上），
     * 然后在 Debian 里换文件。换好以后额度、新对话都是新账号的。在主线程上调用。
     */
    fun switchTo(target: ClaudeAccount) {
        localBlocker()?.let { problem = it; return }
        val client = accounts() ?: return
        problem = null
        working = "正在切换到 ${target.title}"
        thread(name = "zeta-accounts", isDaemon = true) {
            val listed = runCatching { client.list() }.getOrElse { error ->
                finish("读不了账号：${error.message}")
                return@thread
            }
            val blocker = ClaudeAccounts.blocker(listed.processes)
            if (blocker != null) {
                finish(blocker, listed)
                return@thread
            }
            main.post {
                // 这一刻再看一次：查进程的这会儿可能有对话开始了
                if (!hub.closeIdleConversations()) {
                    working = null
                    problem = OwnConversationsBusy
                    return@post
                }
                thread(name = "zeta-accounts", isDaemon = true) {
                    val outcome = runCatching { synchronized(hub.quota.probeLock) { client.switch(target.id) } }
                        .getOrElse { SwitchOutcome.Failed(it.message ?: "没有成功") }
                    // 换成了的话 switched() 会重读一遍账号，这里不用读
                    val after = if (outcome is SwitchOutcome.Done) null else runCatching { client.list() }.getOrNull()
                    main.post {
                        working = null
                        after?.let { state = it }
                        when (outcome) {
                            is SwitchOutcome.Done -> switched()
                            is SwitchOutcome.Busy -> problem = ClaudeAccounts.blocker(outcome.processes)
                                ?: "这里的对话进程还没退出，过一会儿再试"
                            SwitchOutcome.Missing -> problem = "存着的这个账号已经不在了"
                            is SwitchOutcome.Failed -> problem = outcome.message
                        }
                    }
                }
            }
        }
    }

    private fun finish(message: String, listed: AccountsState? = null) {
        main.post {
            working = null
            listed?.let { state = it }
            problem = message
        }
    }

    /**
     * 登录状态换了（切换或新登录了一个账号）：账号、额度跟着刷新。账号记录里有邮箱和订阅类型时直接拿来当顶部的账号状态，
     * 不再为 claude auth status 起一次 Claude Code；缺了才去问它。在主线程上调用。
     */
    internal fun switched() {
        hub.quota.forget()
        hub.quota.refresh(force = true)
        refresh { listed ->
            val current = listed?.current?.takeIf { listed.loggedIn && it.email.isNotBlank() && it.subscription.isNotBlank() }
            if (current != null) hub.account = AccountStatus(loggedIn = true, email = current.email, plan = current.subscription) else hub.refreshAccount()
        }
    }

    /** 删掉存着的一份。只删副本：那个账号在别处的登录不受影响。 */
    fun remove(target: ClaudeAccount) {
        val client = accounts() ?: return
        thread(name = "zeta-accounts", isDaemon = true) {
            runCatching { client.remove(target.id) }
            val listed = runCatching { client.list() }.getOrNull()
            main.post { listed?.let { state = it } }
        }
    }

    /**
     * 添加账号前的检查：和切换一样不能有别的 Claude Code 开着（登录也会改 ~/.claude.json，开着的进程会写回旧账号）。
     * 能开始时返回 true，并关掉本包闲着的对话进程；不能时把原因放进 [problem]。[onReady] 在主线程上调用。
     */
    fun prepareLogin(onReady: () -> Unit) {
        localBlocker()?.let { problem = it; return }
        val client = accounts() ?: return
        problem = null
        working = "正在检查"
        thread(name = "zeta-accounts", isDaemon = true) {
            val listed = runCatching { client.list() }.getOrNull()
            val blocker = listed?.let { ClaudeAccounts.blocker(it.processes) }
            main.post {
                working = null
                listed?.let { state = it }
                when {
                    blocker != null -> problem = blocker
                    !hub.closeIdleConversations() -> problem = OwnConversationsBusy
                    else -> onReady()
                }
            }
        }
    }
}
