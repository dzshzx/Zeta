package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.i18n.Strings
import kotlin.concurrent.thread

/**
 * 一台主机（[backend]）的账号页背后的状态：那台上 claude-switch 管着哪些账号、当前是哪个、正在切换，以及添加账号的登录（[login]）。
 * 账号的读、换、删和登录都经那台的核心（[AccountsCore]，docs/core.md「账号与额度」），手机本机和远程主机一样。 切换和登录都会改那台上 Linux 用户的 Claude
 * Code 登录状态，两个 Zeta 包和终端里的 claude 共用这一份，所以只在没有别的 Claude Code 进程开着时做：claude-switch 自己会拒绝，这里先看一遍核心给的
 * blocker，是为了说清是谁挡着。
 */
class AccountService(private val hub: ConversationHub, private val backend: String) {
    private val main = MainHandler()

    /** 经那台的核心管账号；测试里换成对着临时 HOME 上真核心的。 */
    var core =
        AccountsCore(
            HelperProgram(
                hub.platform::asset,
                AppVariant.current.linuxName,
                HelperProgram.CLAUDE_SWITCH,
            ),
            call = { method, params, timeoutMs ->
                hub.callCore(backend, method, params, timeoutMs)
            },
            shell = {
                hub.shell(backend)
                    ?: throw ServeError(hub.unavailable(backend) ?: Strings.coreNoLinuxEnv())
            },
        )

    var state by mutableStateOf<AccountsState?>(null)
        private set

    /** 正在做的一件事（“正在切换到 …”），没有时为 null。 */
    var working by mutableStateOf<String?>(null)
        private set

    /** 上一件事为什么没做成；用户看过后清掉。 */
    var problem by mutableStateOf<String?>(null)
        private set

    /** 在这台上添加账号（登录一个新号）。 */
    val login = LoginFlow(this)

    private val usable: Boolean
        get() = hub.shell(backend) != null

    fun refresh() = refresh {}

    /** 读一遍账号；读完（读不成时是 null）在主线程上做 [then]。 */
    internal fun refresh(then: (AccountsState?) -> Unit) {
        if (!usable) return then(null)
        background("zeta-accounts") { core ->
            val listed = runCatching { core.list() }
            main.post {
                listed
                    .onSuccess { state = it }
                    .onFailure {
                        if (working == null)
                            problem = Strings.coreAccountsReadFailed(it.message.toString())
                    }
                then(listed.getOrNull())
            }
        }
    }

    fun dismissProblem() {
        problem = null
    }

    /** 现在能不能动登录状态（切换或添加账号）；不能时给出原因。在主线程上调用。 */
    private fun localBlocker(): String? =
        when {
            working != null -> Strings.coreAccountsBusy()
            !hub.canChangeLogin(backend) -> Strings.coreAccountsOwnBusy()
            login.inProgress -> Strings.coreAccountsLoggingIn()
            else -> null
        }

    /**
     * 换成存着的 [target]：先看核心说有没有别的 Claude Code 进程开着，再关掉本包闲着的对话进程（内容不丢，下次发消息时接上），
     * 然后请核心换。换好以后额度、新对话都是新账号的。在主线程上调用。
     */
    fun switchTo(target: ClaudeAccount) = switchTo(target.id, target.title)

    /** 换成中转 [target]：和换号一样（先收本包闲着的对话进程，忙时不换）。之后这台上没有订阅额度。在主线程上调用。 */
    fun switchTo(target: ClaudeRelay) = switchTo(target.name, target.name)

    private fun switchTo(name: String, title: String) {
        checked(Strings.coreAccountsSwitching(title)) {
            background("zeta-accounts") { core ->
                val done = runCatching { synchronized(probeLock) { core.switch(name) } }
                // 换成了的话 switched() 会重读一遍账号，这里不用读
                val after = if (done.isSuccess) null else runCatching { core.list() }.getOrNull()
                main.post {
                    working = null
                    after?.let { state = it }
                    done
                        .onSuccess { switched() }
                        .onFailure {
                            problem =
                                if ((it as? ServeError)?.code == AccountsCore.NO_ACCOUNT)
                                    Strings.coreAccountsGone()
                                else it.message ?: Strings.coreAccountsSwitchFailed()
                        }
                }
            }
        }
    }

    /**
     * 要改登录状态前的检查（切换、添加账号共用）：本包有对话在进行、别的 Claude Code 开着（核心 accounts.list 的 blocker）时不做， 原因放进
     * [problem]；能做时关掉本包闲着的对话进程，再在主线程上调 [then]。[working] 这时还是 [what]，由 [then] 收尾。
     */
    private fun checked(what: String, then: () -> Unit) {
        localBlocker()?.let {
            problem = it
            return
        }
        if (!usable) return
        problem = null
        working = what
        background("zeta-accounts") { core ->
            val listed = runCatching { core.list() }
            main.post {
                listed.getOrNull()?.let { state = it }
                val blocker =
                    listed.fold(
                        { it.blocker },
                        { Strings.coreAccountsReadFailed(it.message.toString()) },
                    )
                when {
                    blocker != null -> fail(blocker)
                    // 这一刻再看一次：查进程的这会儿可能有对话开始了
                    !hub.closeIdleConversations(backend) -> fail(Strings.coreAccountsOwnBusy())
                    else -> then()
                }
            }
        }
    }

    private fun fail(message: String) {
        working = null
        problem = message
    }

    /**
     * 登录状态换了（切换或新登录了一个账号）：账号、额度跟着刷新。账号记录里有邮箱和订阅类型时直接拿来当顶部的账号状态， 不再为 claude auth status 起一次 Claude
     * Code；缺了才去问它。在主线程上调用。
     */
    fun switched() {
        hub.quota(backend).forget()
        refresh { listed ->
            // 读到账号以后再问额度：换到中转时没有订阅额度，不单起 Claude Code 去问（QuotaService.refresh 看它的 relay）
            val quota = hub.quota(backend)
            if (listed != null) quota.noteRelay(listed.relay?.name)
            quota.refresh(force = true)
            // 环境那份账号状态（聊天页的登录提示、手机设备页的账号入口读它）是手机本机的
            if (backend != Backends.LOCAL) return@refresh
            val local = hub.platform.local ?: return@refresh
            listed?.relay?.let {
                local.showRelay(it.name, it.baseUrl)
                return@refresh
            }
            val current =
                listed?.current?.takeIf {
                    listed.loggedIn && it.email.isNotBlank() && it.subscription.isNotBlank()
                }
            if (current != null) local.showAccount(current.email, current.plan)
            else local.refreshAccount()
        }
    }

    /**
     * 添加（[create]）或修改中转：保存成了重读账号，在主线程上调 [onDone]（没成时带核心给的原因）。保存前列表不变。
     * 改的是正在用的中转时，之后起的对话进程才用上。在主线程上调用。
     */
    fun saveRelay(draft: RelayDraft, create: Boolean, onDone: (String?) -> Unit) {
        if (!usable) return onDone(hub.unavailable(backend) ?: Strings.coreNoLinuxEnv())
        background("zeta-relays") { core ->
            val saved = runCatching { core.saveRelay(draft, create) }
            val listed = runCatching { core.list() }.getOrNull()
            main.post {
                listed?.let { state = it }
                onDone(saved.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            }
        }
    }

    /** 删掉一个不在用的中转；做完在主线程上调 [onDone]（没成时带原因）。在主线程上调用。 */
    fun removeRelay(target: ClaudeRelay, onDone: (String?) -> Unit) {
        if (!usable) return onDone(hub.unavailable(backend) ?: Strings.coreNoLinuxEnv())
        background("zeta-relays") { core ->
            val removed = runCatching { core.removeRelay(target.name) }
            val listed = runCatching { core.list() }.getOrNull()
            main.post {
                listed?.let { state = it }
                onDone(removed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            }
        }
    }

    /** 用草稿里的地址和密钥拉模型列表，在主线程上把结果交给 [onDone]；没成时的原因是核心按界面语言写好的。在主线程上调用。 */
    fun relayModels(draft: RelayDraft, onDone: (Result<List<RelayModel>>) -> Unit) {
        if (!usable)
            return onDone(
                Result.failure(ServeError(hub.unavailable(backend) ?: Strings.coreNoLinuxEnv())),
            )
        background("zeta-relays") { core ->
            val models = runCatching { core.relayModels(draft) }
            main.post { onDone(models) }
        }
    }

    /**
     * 开始登录时核心已把在用的号存档并退出：旧号的额度作废，账号列表重读，手机本机那份登录状态（登录页顶部的「当前状态」读它）直接记成未登录， 不起 `claude auth status`
     * 去问（登录命令这时开着，两个 Claude Code 同时读写 `~/.claude.json`）。登录成功后由 [switched] 再刷新。在主线程上调用。
     */
    internal fun loggedOut() {
        hub.quota(backend).forget()
        refresh()
        if (backend == Backends.LOCAL) hub.platform.local?.showLoggedOut()
    }

    /** 删掉一个不在用的号的存档与记录（claude-switch remove）。只删这台主机上的这一份：那个账号在服务端仍是登录状态。 */
    fun remove(target: ClaudeAccount) {
        if (!usable) return
        background("zeta-accounts") { core ->
            val removed = runCatching { core.remove(target.id) }
            val listed = runCatching { core.list() }.getOrNull()
            main.post {
                listed?.let { state = it }
                removed.onFailure {
                    problem = Strings.coreAccountsRemoveFailed(it.message.toString())
                }
            }
        }
    }

    /**
     * 添加账号前的检查：和切换一样不能有别的 Claude Code 开着（登录前核心要把在用的号交给 claude-switch 存档并退出，开着的进程会拦着它）。
     * 能开始时关掉本包闲着的对话进程，再在主线程上调 [onReady]（打开登录页）；不能时把原因放进 [problem]。
     * 真正的存档在用户点开始登录时由核心做（[LoginFlow.start] 经 accounts.login_start）。
     */
    fun prepareLogin(onReady: () -> Unit) {
        checked(Strings.coreAccountsChecking()) {
            working = null
            onReady()
        }
    }

    /** 在后台线程上用这一刻的 [core] 做 [block]。 */
    internal fun background(name: String, block: (AccountsCore) -> Unit) {
        val core = core
        thread(name = name, isDaemon = true) { block(core) }
    }

    internal fun post(block: () -> Unit) = main.post(block)

    /** 开始登录前（在主线程上）：本包有对话在进行时不能登录，返回原因；能时关掉闲着的对话进程（它们开着时 claude-switch 不存档）。 */
    internal fun closeForLogin(): String? =
        if (hub.closeIdleConversations(backend)) null else Strings.coreAccountsOwnBusy()

    /** 单起的问额度的进程和改登录状态不能同时进行（[QuotaService.probeLock]）。 */
    internal val probeLock: Any
        get() = hub.quota(backend).probeLock
}

sealed interface LoginState {
    data object Idle : LoginState

    data object Starting : LoginState

    /** [notice] 是上一次提交被退回的原因（比如授权码没复制全）。 */
    data class WaitingCode(val url: String, val notice: String? = null) : LoginState

    data object Verifying : LoginState

    data object Done : LoginState

    data class Failed(val message: String) : LoginState
}

/**
 * 在一台主机上登录一个新的 Claude 账号（添加账号）。登录命令 `claude auth login` 由那台的核心起（accounts.login_start）：
 * 它先把在用的号存档并退出，再给出授权网址；这里把网址交给界面去打开，再把用户贴回来的授权码交给核心（accounts.login_code）。 状态在主线程上读写。
 */
class LoginFlow internal constructor(private val service: AccountService) {
    var state by mutableStateOf<LoginState>(LoginState.Idle)
        private set

    /** 第几次登录：取消、重新开始后，上一次还在路上的结果不再算数。 */
    private var generation = 0

    /** 登录开始了、还没完成或取消。 */
    val inProgress: Boolean
        get() = state.let {
            it != LoginState.Idle && it != LoginState.Done && it !is LoginState.Failed
        }

    /** 开始登录：关掉本包闲着的对话进程，请核心存档在用的号、起登录命令，拿到授权网址后等授权码。在主线程上调用。 */
    fun start() {
        cancel()
        service.closeForLogin()?.let {
            state = LoginState.Failed(it)
            return
        }
        val mine = ++generation
        state = LoginState.Starting
        service.background("zeta-login") { core ->
            val started = runCatching { synchronized(service.probeLock) { core.loginStart() } }
            service.post {
                // 开始了就说明在用的号已经存档并退出；没开始成，存档那一步也可能做了，账号重读一遍
                if (started.isSuccess) service.loggedOut() else service.refresh()
                if (mine != generation) {
                    // 开始的这几秒里用户取消了：核心那边的登录命令也结束掉
                    if (started.isSuccess)
                        service.background("zeta-login") { runCatching { it.loginCancel() } }
                    return@post
                }
                state =
                    started.fold(
                        { LoginState.WaitingCode(it.url) },
                        {
                            LoginState.Failed(
                                Strings.coreAccountsLoginStartFailed(it.message.toString()),
                            )
                        },
                    )
            }
        }
    }

    /** 把授权码交给核心里在等的登录命令。在主线程上调用。 */
    fun submit(code: String) {
        val waiting = state as? LoginState.WaitingCode ?: return
        // 授权码是“两段中间夹一个 #”，少了后半段登录命令不会收（核心也会退回，这里先拦下省一趟）
        if ('#' !in code) {
            state = waiting.copy(notice = Strings.coreAccountsCodeIncomplete())
            return
        }
        val mine = generation
        state = LoginState.Verifying
        service.background("zeta-login") { core ->
            val done = runCatching { core.loginCode(code.trim()) }
            service.post {
                if (mine != generation) return@post
                done
                    .onSuccess {
                        state = LoginState.Done
                        service.switched()
                    }
                    .onFailure {
                        state =
                            if ((it as? ServeError)?.code == AccountsCore.LOGIN_INCOMPLETE)
                                waiting.copy(notice = it.message)
                            else LoginState.Failed(it.message ?: Strings.coreAccountsLoginFailed())
                    }
            }
        }
    }

    /** 不登录了：核心里还在等的登录命令结束掉。在用的号开始登录时已经存档，账号页里能换回去。在主线程上调用。 */
    fun cancel() {
        val waiting = state is LoginState.WaitingCode || state == LoginState.Verifying
        generation++
        state = LoginState.Idle
        if (waiting)
            service.background("zeta-login") { core ->
                runCatching { core.loginCancel() }
                service.post { service.refresh() }
            }
    }

    /** 用户改了授权码，上一次被退回的提示就不该再挂着。 */
    fun clearNotice() {
        (state as? LoginState.WaitingCode)?.let {
            if (it.notice != null) state = it.copy(notice = null)
        }
    }
}
