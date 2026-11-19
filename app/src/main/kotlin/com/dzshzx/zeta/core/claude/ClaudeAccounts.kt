package com.dzshzx.zeta.core.claude

import androidx.compose.runtime.Immutable

/** 一个 Claude 账号（Claude Code 的 oauthAccount 加上套餐信息）。令牌不在这里：它们只在 Debian 里的文件之间搬。 */
@Immutable
data class ClaudeAccount(
    /** oauthAccount.accountUuid。 */
    val id: String,
    val email: String,
    val organization: String,
    val displayName: String,
    /** 套餐的显示名（planDisplayName，旧版 Claude Code 没有）。 */
    val planName: String,
    /** 订阅类型（pro、max、team…）。 */
    val subscription: String,
    /** 刷新令牌的到期时刻（毫秒）；过了就只能重新登录。 */
    val refreshExpiresAt: Long?,
    /** 这一份是什么时候存下的（毫秒）；当前登录没有。 */
    val savedAt: Long?,
) {
    val title: String get() = email.ifBlank { displayName.ifBlank { id } }

    val plan: String
        get() = planName.ifBlank {
            when (subscription) {
                "" -> ""
                "pro" -> "Pro"
                "max" -> "Max"
                "team" -> "Team"
                "enterprise" -> "Enterprise"
                else -> subscription.replaceFirstChar { it.uppercase() }
            }
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
    val loggedIn: Boolean,
    /** 当前登录的账号；登录着却认不出是谁（~/.claude.json 里没有 oauthAccount）时为 null。 */
    val current: ClaudeAccount?,
    /** Zeta 存着的账号，含当前这个（如果存过）。 */
    val saved: List<ClaudeAccount>,
    val processes: List<ClaudeProc>,
) {
    /** 界面列的账号：当前的在前，再是存着的其他账号。 */
    val all: List<ClaudeAccount>
        get() = listOfNotNull(current) + saved.filter { it.id != current?.id }.sortedBy { it.title.lowercase() }
}

sealed interface SwitchOutcome {
    data class Done(val id: String) : SwitchOutcome

    /** 还有 Claude Code 进程开着，没换。 */
    data class Busy(val processes: List<ClaudeProc>) : SwitchOutcome

    /** 要换的那一份已经不在了。 */
    data object Missing : SwitchOutcome

    data class Failed(val message: String) : SwitchOutcome
}

/**
 * 在 Linux 环境里管理多个 Claude 账号的登录状态：脚本是 assets/zeta_accounts.sh（bash + jq），以 Claude Code 所属的用户身份跑。
 * 每个账号在 `~/.config/<Zeta 的目录名>/accounts/` 下存一份（权限 600），切换时把当前登录存回去，再把目标那份换进
 * `~/.claude/.credentials.json`（claudeAiOauth）和 `~/.claude.json`（oauthAccount）。添加账号走 Claude Code 自己的
 * `claude auth login`（[com.dzshzx.zeta.core.LoginFlow]），登录前后各存一次。全是阻塞调用，别在主线程上用。
 */
class ClaudeAccounts(private val client: ClaudeConfigClient, private val script: String, private val linuxName: String) {
    fun list(): AccountsState = parse(client.runScript(script, listOf("list", linuxName)))

    /** 把当前登录存一份；返回账号 uuid，没登录或认不出是谁时为 null。 */
    fun save(): String? = client.runScript(script, listOf("save", linuxName)).firstOrNull { it.tag == "OK" }?.get(1)?.ifBlank { null }

    fun switch(id: String): SwitchOutcome {
        val records = client.runScript(script, listOf("switch", linuxName, id), timeoutMs = 60_000)
        return when {
            records.any { it.tag == "OK" } -> SwitchOutcome.Done(id)
            records.any { it.tag == "BUSY" } -> SwitchOutcome.Busy(processes(records))
            records.any { it.tag == "MISSING" } -> SwitchOutcome.Missing
            else -> SwitchOutcome.Failed(
                when (records.firstOrNull { it.tag == "FAILED" }?.get(1)) {
                    "save" -> "存不下当前的登录状态（认不出当前登录的是哪个账号）"
                    "config" -> "改不了 ~/.claude.json"
                    "credentials" -> "改不了 ~/.claude/.credentials.json"
                    else -> "没有成功"
                },
            )
        }
    }

    /** 删掉存着的一份。只删副本，不注销那个账号。 */
    fun remove(id: String) {
        client.runScript(script, listOf("remove", linuxName, id))
    }

    companion object {
        fun parse(records: List<ShellRecord>): AccountsState {
            val live = records.firstOrNull { it.tag == "L" }
            val loggedIn = live != null && live.fields.size > 1
            return AccountsState(
                loggedIn = loggedIn,
                current = live?.takeIf { loggedIn }?.let(::account),
                saved = records.filter { it.tag == "A" }.mapNotNull(::account),
                processes = processes(records),
            )
        }

        private fun account(r: ShellRecord): ClaudeAccount? {
            val id = r[1].ifBlank { return null }
            return ClaudeAccount(
                id = id,
                email = r[2],
                organization = r[3],
                displayName = r[4],
                planName = r[5],
                subscription = r[6],
                refreshExpiresAt = r[7].toLongOrNull(),
                savedAt = r[8].toLongOrNull(),
            )
        }

        private fun processes(records: List<ShellRecord>): List<ClaudeProc> =
            records.filter { it.tag == "P" }.mapNotNull { r -> r[1].toIntOrNull()?.let { ClaudeProc(it, r[2], r[3], r[4]) } }

        /**
         * 换账号前，为什么还不能换；能换时为 null。[mine] 是本包的目录名，[ownWorking] 是本包有对话在回答、等确认或有后台任务。
         * 本包闲着的对话 Zeta 自己会先关掉，不算阻碍；别的 Claude Code 进程哪怕闲着也不行：它们会把旧账号写回 ~/.claude.json。
         */
        fun blocker(processes: List<ClaudeProc>, mine: String, ownWorking: Boolean): String? {
            val others = processes.filter { it.owner != mine }
            val otherZeta = others.filter { it.owner.isNotEmpty() }
            val elsewhere = others.filter { it.owner.isEmpty() }
            val reasons = buildList {
                if (ownWorking) add("这里还有对话在进行（在回答、等你确认或有后台任务），等它结束")
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
