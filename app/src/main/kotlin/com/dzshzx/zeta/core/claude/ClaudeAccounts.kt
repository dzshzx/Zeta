package com.dzshzx.zeta.core.claude

import androidx.compose.runtime.Immutable
import com.dzshzx.zeta.core.AppVariant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 一个 Claude 账号（claude-switch 记下的身份加上套餐信息）。令牌不在这里：它们只在 Debian 里由 claude-switch 搬。 */
@Immutable
data class ClaudeAccount(
    /** claude-switch 的账号名（claude、claude-b…）；在用的登录还没记过、认不出是哪个号时为空串。 */
    val id: String,
    val email: String,
    val organization: String,
    val displayName: String,
    /** 订阅类型（pro、max、team…）。 */
    val subscription: String,
    /** 刷新令牌的到期时刻（毫秒）；过了就只能重新登录。 */
    val refreshExpiresAt: Long?,
) {
    val title: String get() = email.ifBlank { displayName.ifBlank { id } }

    /** 套餐的显示名，按订阅类型推（claude-switch 不给显示名）。 */
    val plan: String
        get() = when (subscription) {
            "" -> ""
            "pro" -> "Pro"
            "max" -> "Max"
            "team" -> "Team"
            "enterprise" -> "Enterprise"
            else -> subscription.replaceFirstChar { it.uppercase() }
        }

    fun expired(now: Long): Boolean = refreshExpiresAt != null && refreshExpiresAt <= now
}

/** 一个还活着的 Claude Code 进程（来自 ~/.claude/sessions）。[owner] 是托管它的 Zeta 包的目录名，别处起的为空。 */
@Immutable
data class ClaudeProc(val pid: Int, val owner: String, val status: String, val kind: String) {
    val busy: Boolean get() = status.isNotEmpty() && status != "idle"
}

@Immutable
data class AccountsState(
    /** 在用的登录；未登录时为 null。认不出是哪个号（claude-switch 没记过）时照样有，id 为空串。 */
    val current: ClaudeAccount?,
    /** claude-switch 管着的账号：有存档的，加上在用的那个（如果记过）。 */
    val saved: List<ClaudeAccount>,
    val processes: List<ClaudeProc>,
) {
    val loggedIn: Boolean get() = current != null

    /** 界面列的账号：当前的在前，再是存着的其他账号。 */
    val all: List<ClaudeAccount>
        get() = listOfNotNull(current) + saved.filter { it.id != current?.id }.sortedBy { it.title.lowercase() }
}

sealed interface SwitchOutcome {
    data class Done(val id: String) : SwitchOutcome

    /** 还有 Claude Code 进程开着，没换。[message] 是 claude-switch 的原话（列着它看到的进程）。 */
    data class Busy(val processes: List<ClaudeProc>, val message: String = "") : SwitchOutcome

    /** 要换的那个号已经不在了。 */
    data object Missing : SwitchOutcome

    data class Failed(val message: String) : SwitchOutcome
}

/**
 * 在 Linux 环境里管理多个 Claude 账号：账号的存档、换号、加号、删号都交给 claude-switch（`~/.local/bin/claude-switch`，
 * dotfiles 里的 Go 程序；它换掉 ~/.claude.json 里跟账号走的字段和凭据里除 MCP 授权外的全部字段，每个号的令牌只留一份，
 * 有 Claude Code 进程在跑就拒绝）。Zeta 经 assets/zeta_accounts.sh 以 Claude Code 所属的用户身份跑它，脚本另外列出开着的
 * Claude Code 进程，好跟用户说清是谁挡着。全是阻塞调用，别在主线程上用。
 */
class ClaudeAccounts(private val client: ClaudeConfigClient, private val script: String) {
    /** claude-switch 一次运行的结果。[processes] 只在没成功时列。 */
    private class Run(val code: Int, val stdout: String, val stderr: String, val processes: List<ClaudeProc>) {
        val message: String get() = message(stderr).ifBlank { "没有成功（claude-switch 退出码 $code）" }
        val busy: Boolean get() = code == 1 && BUSY_MARK in stderr
        val missing: Boolean get() = code == 2 && MISSING_MARK in stderr
    }

    private fun run(vararg args: String): Run = result(client.runScript(script, listOf("run") + args, timeoutMs = 60_000))

    private fun status(): Status {
        val records = client.runScript(script, listOf("list"))
        val run = result(records)
        if (run.code != 0) throw ClaudeConfigException(run.message)
        return status(run.stdout, processes(records))
    }

    fun list(): AccountsState = status().state

    /**
     * 在用的登录还没记过（claude-switch 认不出是哪个号）时，挑个空闲的名字把它记下（`claude-switch add`，不改登录）。
     * 返回在用的账号名；没登录时为 null。记不下来时抛 [ClaudeConfigException]。
     */
    fun save(): String? {
        val status = status()
        val live = status.state.current ?: return null
        if (live.id.isNotEmpty()) return live.id
        val name = freeName(status.names + BUILTIN)
        val run = run("add", name)
        if (run.code != 0) throw ClaudeConfigException(run.message)
        return name
    }

    /**
     * 登录另一个号之前：挑个空闲的名字，把在用的号存档并退出登录，登录后的新号由 claude-switch 下次运行时记成这个名字。
     * 名字是固定的 claude 时用 `claude-switch claude`（换到一个没有存档的号就是存档并退出），
     * 别的用 `claude-switch add <名字>`（add 不收固定的号）。在用的登录还没记过时先 add 一个名字把它记下，不丢它的令牌。
     * 没登录时什么都不做。成功时 [SwitchOutcome.Done] 带新号的名字（没登录时为空串）。
     */
    fun prepareLogin(): SwitchOutcome = failSoft {
        val status = status()
        val live = status.state.current ?: return@failSoft SwitchOutcome.Done("")
        val taken = status.names.toMutableSet()
        if (live.id.isEmpty()) {
            val name = freeName(taken + BUILTIN)
            outcome(run("add", name), name).let { if (it !is SwitchOutcome.Done) return@failSoft it }
            taken += name
        }
        val name = freeName(taken)
        outcome(if (name in BUILTIN) run(name) else run("add", name), name)
    }

    fun switch(id: String): SwitchOutcome = failSoft {
        if (id.isBlank()) SwitchOutcome.Missing else outcome(run(id), id)
    }

    /** 没装 claude-switch、进不了环境这类错误也当作没换成，不往外抛。 */
    private inline fun failSoft(block: () -> SwitchOutcome): SwitchOutcome = try {
        block()
    } catch (error: ClaudeConfigException) {
        SwitchOutcome.Failed(error.message ?: "没有成功")
    }

    /** 删掉一个不在用的号的存档与记录（只删本机这一份，不注销那个账号）。没删成时抛 [ClaudeConfigException]。 */
    fun remove(id: String) {
        val run = run("remove", id)
        if (run.code != 0) throw ClaudeConfigException(run.message)
    }

    private fun outcome(run: Run, id: String): SwitchOutcome = when {
        run.code == 0 -> SwitchOutcome.Done(id)
        run.busy -> SwitchOutcome.Busy(run.processes, run.message)
        run.missing -> SwitchOutcome.Missing
        else -> SwitchOutcome.Failed(run.message)
    }

    /** claude-switch --json 读出来的状态；[names] 是已经被占的账号名（列出的账号和切换记录里的当前号）。 */
    internal class Status(val state: AccountsState, val names: Set<String>)

    companion object {
        const val NOT_INSTALLED = "手机 Debian 里没有 claude-switch（~/.local/bin/claude-switch）"

        /** claude-switch 里总能用作目标的号（没有切换记录时默认配置目录里那个号）：直接换过去，add 不收它。 */
        private val BUILTIN = setOf("claude")
        private const val BUSY_MARK = "还有 Claude Code 进程在跑"
        private const val MISSING_MARK = "没有叫"
        private const val PREFIX = "claude-switch: "
        private val json = Json { ignoreUnknownKeys = true }

        /** claude-switch --json 的输出 → 账号状态。 */
        fun parse(output: String, processes: List<ClaudeProc> = emptyList()): AccountsState = status(output, processes).state

        internal fun status(output: String, processes: List<ClaudeProc>): Status {
            val root = runCatching { json.parseToJsonElement(output.trim()) }.getOrNull() as? JsonObject
                ?: throw ClaudeConfigException("读不懂 claude-switch 的输出")
            val live = (root["live"] as? JsonObject)?.let(::account)
            val listed = (root["accounts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val names = (listed.map { it.str("name") } + root.str("current")).filter { it.isNotEmpty() }.toSet()
            return Status(
                AccountsState(
                    current = live,
                    saved = listed.filter { (it.bool("parked") || it.bool("current")) && it.str("name").isNotEmpty() }.map(::account),
                    processes = processes,
                ),
                names,
            )
        }

        private fun account(o: JsonObject): ClaudeAccount = ClaudeAccount(
            id = o.str("name"),
            email = o.str("email"),
            organization = o.str("organization"),
            displayName = o.str("displayName"),
            subscription = o.str("subscription"),
            refreshExpiresAt = (o["refreshExpiresAt"] as? JsonPrimitive)?.longOrNull,
        )

        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

        /** 新号的名字：claude、claude-b、claude-c……里第一个不在 [taken] 里的。 */
        internal fun freeName(taken: Set<String>): String = generateSequence(0) { it + 1 }
            .map { i ->
                when {
                    i == 0 -> "claude"
                    i < 26 -> "claude-" + ('a' + i)
                    else -> "claude-${i + 1}"
                }
            }
            .first { it !in taken }

        /** claude-switch 的提示（一行或几行，以 `claude-switch: ` 开头）去掉前缀。 */
        internal fun message(stderr: String): String = stderr.trim().removePrefix(PREFIX).trim()

        /** zeta_accounts.sh 的记录 → claude-switch 这次运行的结果；没装时抛 [ClaudeConfigException]。 */
        private fun result(records: List<ShellRecord>): Run {
            if (records.any { it.tag == "NONE" }) throw ClaudeConfigException(NOT_INSTALLED)
            val code = records.firstOrNull { it.tag == "X" }?.get(1)?.toIntOrNull() ?: throw ClaudeConfigException("claude-switch 没有跑完")
            return Run(code, decode(records, "O"), decode(records, "E"), processes(records))
        }

        private fun decode(records: List<ShellRecord>, tag: String): String =
            records.firstOrNull { it.tag == tag }?.get(1)?.let { runCatching { Base64.getDecoder().decode(it).toString(Charsets.UTF_8) }.getOrNull() }.orEmpty()

        private fun processes(records: List<ShellRecord>): List<ClaudeProc> =
            records.filter { it.tag == "P" }.mapNotNull { r -> r[1].toIntOrNull()?.let { ClaudeProc(it, r[2], r[3], r[4]) } }

        /**
         * 别的 Claude Code 进程挡着换账号的原因；能换时为 null。[mine] 是本包的目录名。本包自己的对话由调用方先看
         * （[com.dzshzx.zeta.core.AccountService]），闲着的 Zeta 自己会关掉，不算阻碍；别的 Claude Code 进程哪怕闲着也不行：
         * 它们把令牌缓存在内存里，还会把旧账号写回 ~/.claude.json。claude-switch 自己也会拒绝，这里只是为了说清是谁挡着。
         */
        fun blocker(processes: List<ClaudeProc>, mine: String = AppVariant.current.linuxName): String? {
            val others = processes.filter { it.owner != mine }
            val otherZeta = others.filter { it.owner.isNotEmpty() }
            val elsewhere = others.filter { it.owner.isEmpty() }
            val reasons = buildList {
                if (otherZeta.isNotEmpty()) {
                    val busy = otherZeta.count { it.busy }
                    add(
                        "另一个 Zeta（${otherZeta.map { zetaName(it.owner) }.distinct().joinToString("、")}）开着 ${otherZeta.size} 个对话" +
                            (if (busy > 0) "，其中 $busy 个在进行" else "") + "，在那边结束对话或关掉它",
                    )
                }
                if (elsewhere.isNotEmpty()) {
                    val busy = elsewhere.count { it.busy }
                    add("还有 ${elsewhere.size} 个别处开着的 Claude Code（比如终端里的 claude）" + (if (busy > 0) "，其中 $busy 个在进行" else "") + "，先退出它们")
                }
            }
            return reasons.takeIf { it.isNotEmpty() }?.joinToString("；")?.plus("。")
        }

        /** Zeta 包在 Linux 里的目录名 → 界面上的叫法：zeta 是正式版，zeta-dev 是测试版。 */
        private fun zetaName(owner: String): String = when {
            owner == "zeta" -> "正式版"
            owner.startsWith("zeta-dev") -> "测试版" + owner.removePrefix("zeta-dev")
            else -> owner
        }
    }
}
