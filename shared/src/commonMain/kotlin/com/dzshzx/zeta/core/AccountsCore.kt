package com.dzshzx.zeta.core

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.i18n.Strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一个 Claude 账号（claude-switch 记下的身份加上套餐信息）。令牌不在这里：它们只在主机上由 claude-switch 搬。 */
@Immutable
data class ClaudeAccount(
    /** claude-switch 的账号名（claude、claude-b…）；在用的登录还没记过、认不出是哪个号时为空串。 */
    val id: String,
    val email: String,
    val organization: String,
    val displayName: String,
    /** 订阅类型（pro、max、team…）；Max 与 Ultra 都是 max，分不开。 */
    val subscription: String,
    /** 刷新令牌的到期时刻（毫秒）；过了就只能重新登录。 */
    val refreshExpiresAt: Long?,
    /** 套餐的展示名（Pro、Max、Ultra…），claude-switch 照 Claude Code 存的服务器资料报；旧存档里没有时为空串。 */
    val planName: String = "",
) {
    val title: String
        get() = email.ifBlank { displayName.ifBlank { id } }

    /** 套餐的显示名：[planName]，没有时由订阅类型写成。 */
    val plan: String
        get() = planLabel(planName, subscription)

    fun expired(now: Long): Boolean = refreshExpiresAt != null && refreshExpiresAt <= now
}

/**
 * 套餐的显示名：核心给的展示名 [plan]（Claude Code 照服务器的账号资料存的，Max 20x 现在叫 Ultra）不为空就用它，否则把订阅类型
 * [subscription]（pro、max…）首字母大写；两者都没有时是空串。
 */
fun planLabel(plan: String?, subscription: String?): String =
    plan?.trim().orEmpty().ifEmpty { subscription.orEmpty().replaceFirstChar { it.uppercase() } }

/** 一台主机上的账号（核心 accounts.list 的结果）。 */
@Immutable
data class AccountsState(
    /** 在用的登录；未登录时为 null。认不出是哪个号（claude-switch 没记过）时照样有，id 为空串。 */
    val current: ClaudeAccount?,
    /** 界面列的账号：在用的在前，再是存着的其他账号（核心排好的）。 */
    val all: List<ClaudeAccount>,
    /** 别的 Zeta 包或终端里开着的 Claude Code 挡着换号时，核心给的一句说明；没有时为 null。 */
    val blocker: String? = null,
    /** 这台上的中转（核心按名字排好的）。在用中转时 [current] 为 null，那一项的 current 为真。 */
    val relays: List<ClaudeRelay> = emptyList(),
) {
    /** 登录着一个账号（在用中转时为假，见 [relay]）。 */
    val loggedIn: Boolean
        get() = current != null

    /** 在用的中转；在用账号或未登录时为 null。 */
    val relay: ClaudeRelay?
        get() = relays.firstOrNull { it.current }

    /** 账号名和中转名：添加中转时不能和它们重名。 */
    val names: Set<String>
        get() = (all.map { it.id } + relays.map { it.name }).filter { it.isNotEmpty() }.toSet()
}

/** 开始登录（accounts.login_start）的结果：授权网址，和登录后新号会记成的名字（没登录过时为空串）。 */
@Immutable data class LoginStarted(val url: String, val name: String)

/**
 * 经一台主机上的核心管 Claude 账号（docs/core.md「账号与额度」的 accounts.*）：账号的存档、换号、加号、删号、登录都由核心替客户端跑 claude-switch 和
 * `claude auth login`，这里只发请求、读结果。每个请求带上应用里那份 claude-switch 的路径（[program]）；核心回
 * [CLAUDE_SWITCH_MISSING] 时经 [shell] 把它装上（同 zeta-helper，按内容哈希装在 `~/.cache/<包>/bin`）再发一次。
 *
 * 出错时抛 [ServeError]，核心认得出的带 code（[BUSY]、[NO_ACCOUNT]、[LOGIN_INCOMPLETE]、[NO_LOGIN]）。全是阻塞调用，别在主线程上用。
 */
class AccountsCore(
    private val program: HelperProgram,
    /** 发一个请求：方法、参数、最多等多久；ok 时返回 result（同 [ServeRequests.call]）。 */
    private val call: (method: String, params: JsonObject, timeoutMs: Long) -> JsonElement?,
    /** 那台主机的 Linux shell，装 claude-switch 用；用不了时抛出原因。 */
    private val shell: () -> LinuxShell,
    val harness: String = Harnesses.CLAUDE,
) {
    fun list(): AccountsState = parse(request("accounts.list"))

    /** 换成存着的账号或中转 [name]。 */
    fun switch(name: String) {
        request("accounts.switch", "name" to name)
    }

    /** 在用的登录还没记过时给它起个空闲的名字记下，返回它的名字。 */
    fun save(): String = nameOf(request("accounts.save"))

    /** 删掉一个不在用的号在这台主机上的存档与记录（服务端仍是登录状态）。 */
    fun remove(name: String) {
        request("accounts.remove", "name" to name)
    }

    /** 把在用的号存档并退出登录，在主机上起 `claude auth login`，返回授权网址。 */
    fun loginStart(): LoginStarted {
        val result = request("accounts.login_start") as? JsonObject
        val url = result?.str("url") ?: throw ServeError(Strings.coreAccountsNoAuthUrl())
        return LoginStarted(url, result.str("name").orEmpty())
    }

    /** 把授权码 [code] 交给在等的登录命令；成功时返回记下的新号的名字。 */
    fun loginCode(code: String): String = nameOf(request("accounts.login_code", "code" to code))

    /** 结束正在进行的登录，没有时什么也不做。 */
    fun loginCancel() {
        request("accounts.login_cancel")
    }

    /**
     * 添加（[create]）或修改一个中转（accounts.relay_save）。修改时 [RelayDraft.key] 为空表示不改。密钥只经到核心的这条连接的标准输入发出，
     * 不进日志；核心出错的话里也不带它。
     */
    fun saveRelay(draft: RelayDraft, create: Boolean) {
        requestWith(
            "accounts.relay_save",
            relayParams(draft) +
                mapOf(
                    "model" to JsonPrimitive(draft.model.trim()),
                    "create" to JsonPrimitive(create),
                ),
        )
    }

    /** 删掉一个不在用的中转（在用的核心会拒绝）。 */
    fun removeRelay(name: String) {
        request("accounts.relay_remove", "name" to name)
    }

    /** 用草稿里的地址和密钥拉中转的模型列表（核心两个鉴权头一起带）；密钥为空时核心用已保存的中转 [RelayDraft.name] 的密钥。 */
    fun relayModels(draft: RelayDraft): List<RelayModel> {
        val result = requestWith("accounts.relay_models", relayParams(draft)) as? JsonObject
        return (result?.get("models") as? JsonArray).orEmpty().mapNotNull(::relayModel)
    }

    private fun relayParams(draft: RelayDraft): Map<String, JsonPrimitive> =
        mapOf(
            "name" to JsonPrimitive(draft.name.trim()),
            "baseUrl" to JsonPrimitive(draft.baseUrl.trim()),
            "key" to JsonPrimitive(draft.key),
        )

    private fun nameOf(result: JsonElement?): String =
        (result as? JsonObject)?.str("name").orEmpty()

    private fun request(method: String, vararg args: Pair<String, String>): JsonElement? =
        requestWith(method, args.associate { it.first to JsonPrimitive(it.second) })

    private fun requestWith(method: String, args: Map<String, JsonPrimitive>): JsonElement? {
        if (Harnesses.normalize(harness) == Harnesses.AGY) {
            return call(
                method,
                JsonObject(args + ("harness" to JsonPrimitive(Harnesses.AGY))),
                TIMEOUT_MS,
            )
        }
        val params = JsonObject(args + ("program" to JsonPrimitive(program.relativePath)))
        return try {
            call(method, params, TIMEOUT_MS)
        } catch (e: ServeError) {
            if (e.code != CLAUDE_SWITCH_MISSING) throw e
            install()
            call(method, params, TIMEOUT_MS)
        }
    }

    /** 把应用里那份 claude-switch 装进那台主机（没装就装上）。装不上时抛 [ServeError]。 */
    private fun install() {
        val shell = shell()
        val result =
            runCatching {
                program.ensure { command, stdin -> shell.run(command, stdin, 60_000, plain = true) }
            }
                .getOrElse {
                    throw ServeError(Strings.coreAccountsSwitchInstallFailed(it.message.toString()))
                }
                ?: throw ServeError(
                    Strings.coreAccountsSwitchInstallFailed(Strings.coreCantEnterLinuxEnv()),
                )
        if (result.exitCode != 0)
            throw ServeError(
                Strings.coreAccountsSwitchInstallFailed(
                    result.stderr.trim().ifEmpty {
                        Strings.coreExitCode(result.exitCode.toString())
                    },
                ),
            )
    }

    companion object {
        /** 主机上还没装这个版本的 claude-switch：装上再试。 */
        const val CLAUDE_SWITCH_MISSING = "claude_switch_missing"

        /** 有别的 Claude Code 开着，claude-switch 没换。 */
        const val BUSY = "busy"

        /** 要换、要删的号不在了。 */
        const val NO_ACCOUNT = "no_account"

        /** 授权码不完整，登录还在等：改了再交。 */
        const val LOGIN_INCOMPLETE = "login_incomplete"

        /** 没有在等的登录（没开始、取消了、超时）：重新开始。 */
        const val NO_LOGIN = "no_login"

        /** 一个请求最多等多久：核心自己最多做 2 分钟（helper/accounts.go 的 accountsTimeout），这里多留半分钟。 */
        const val TIMEOUT_MS = 150_000L

        /** accounts.list 的结果 → 账号状态。读不懂时抛 [ServeError]。 */
        fun parse(result: JsonElement?): AccountsState {
            val o = result as? JsonObject ?: throw ServeError(Strings.coreAccountsBadReply())
            return AccountsState(
                current = o.obj("current")?.let(::account),
                all =
                    (o["accounts"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonObject)?.let(::account)
                    },
                blocker = o.str("blocker")?.ifBlank { null },
                relays =
                    (o["relays"] as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonObject)?.let(::relay)
                    },
            )
        }

        private fun relay(o: JsonObject): ClaudeRelay? {
            val name = o.str("name")?.ifBlank { null } ?: return null
            return ClaudeRelay(
                name = name,
                baseUrl = o.str("baseUrl").orEmpty(),
                model = o.str("model").orEmpty(),
                hasKey = o.bool("hasKey") == true,
                keyEditable = o.bool("keyEditable") == true,
                current = o.bool("current") == true,
            )
        }

        private fun account(o: JsonObject): ClaudeAccount =
            ClaudeAccount(
                id = o.str("name").orEmpty(),
                email = o.str("email").orEmpty(),
                organization = o.str("organization").orEmpty(),
                displayName = o.str("displayName").orEmpty(),
                subscription = o.str("subscription").orEmpty(),
                refreshExpiresAt = o.long("refreshExpiresAt")?.takeIf { it > 0 },
                planName = o.str("plan").orEmpty(),
            )
    }
}
