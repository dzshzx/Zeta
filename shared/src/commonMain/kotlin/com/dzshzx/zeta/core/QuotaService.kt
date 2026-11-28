package com.dzshzx.zeta.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.i18n.Strings
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 一台主机（[backend]）上登录着的账号的订阅额度，那台上的对话共用一份；各台登录的可能是不同的账号，各有各的一份。数都是 Claude Code
 * 给的，由那台上的核心接、存、读成额度（docs/core.md「账号与额度」：对话里的 rate_limit_event、起对话进程时顺便问的 get_usage）， 这里经
 * `quota.watch` 订阅，照推来的 `quota.snapshot` 显示。订阅的开关同会话列表：界面用到额度时（[watch]）连上，退到后台断开，回到前台再连。
 *
 * 要马上刷新时（设置页、用量弹窗打开时数不够新，或者点了刷新）向 Claude Code 要一次 get_usage：有开着的对话进程就借它问（`conv.quota`， 应答经
 * [usageAnswered] 回来），没有就单起一个只答这一个问题的 Claude Code（[QuotaProbe]）；应答原样交给核心的 `quota.usage`。
 * 推来的数另存一份在应用自己的 SharedPreferences 里（核心给的原文），下次打开先显示它；换账号时清掉。
 */
class QuotaService(
    private val hub: ConversationHub,
    private val backend: String,
    val harness: String = Harnesses.CLAUDE,
) {
    private val normalizedHarness = Harnesses.normalize(harness)
    private val isAgy = normalizedHarness == Harnesses.AGY
    private val prefs = hub.platform.prefs("quota")

    /** 存上次的数用的键：各台主机与各工具分开（[HostKeys]）。 */
    private val key = HostKeys.key(backend, if (isAgy) "agy_$KEY" else KEY)
    private val relayKey = HostKeys.key(backend, if (isAgy) "agy_$RELAY_KEY" else RELAY_KEY)
    private val main = MainHandler()

    /**
     * 这台在用的中转名（核心 quota.get、quota.snapshot 推送、quota.usage 的 `relay`）；在用订阅账号时为 null。界面和问额度都只看这一处： 不为
     * null 时没有订阅额度，各处的额度、用量百分比、重置时间都不显示，也不向 Claude Code 要额度。存一份，重开应用先按它显示。
     */
    var relay by mutableStateOf(prefs.getString(relayKey, null))
        private set

    /** 在用订阅账号（不是中转）：有订阅额度可显示。 */
    val subscribed: Boolean
        get() = relay == null

    var snapshot by mutableStateOf(Quota.fromSaved(prefs.getString(key, null)))
        private set

    var refreshing by mutableStateOf(false)
        private set

    /** 上一次刷新没取到数的原因；取到了就清掉。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 最近一次发出 get_usage 的时刻。 */
    private var lastAsked = 0L

    /** 单起的问额度的进程和换账号不能同时进行：换账号时要等所有 Claude Code 进程都退出。 */
    val probeLock = Any()

    /** 订阅额度的那条到那台 `zeta-helper serve` 的连接；没订阅、退到后台时为 null。下面几项都只在主线程上碰。 */
    private var serve: HelperServe? = null

    /** [serve] 断着：第一次就没连上的不会自己重连，下次 [watch] 时重连。 */
    private var broken = false

    /** 这次连上以后核心推来过额度。推来之前显示的是本机存的那份，可能比核心的旧，够不够新等推来再判断（[staleCheck]）。 */
    private var synced = false

    /** 打开页面时还没推来：推来后再看数够不够新、要不要问。 */
    private var staleCheck = false

    /** 退到后台时断开了订阅（[pause]）：回到前台时再订阅（[resume]）。 */
    private var paused = false

    private val giveUp = Runnable {
        if (refreshing) {
            refreshing = false
            error = Strings.coreQuotaTimedOut()
        }
    }

    /** 订阅那台的额度：还没订阅或断着的连上，连上就推来一份。界面用到额度时调用，在主线程上。 */
    fun watch() {
        paused = false
        if (serve != null && !broken) return
        serve?.close()
        serve = null
        if (hub.shell(backend) == null) return
        broken = false
        synced = false
        serve = connect()
    }

    private fun connect(): HelperServe {
        lateinit var self: HelperServe
        val watchParams =
            if (isAgy) {
                mapOf(
                    "quota.watch" to
                        buildJsonObject {
                            put("watch", true)
                            put("harness", Harnesses.AGY)
                        },
                )
            } else {
                emptyMap()
            }
        self =
            HelperServe(
                name = if (isAgy) "quota-$backend-agy" else "quota-$backend",
                open = {
                    val shell =
                        hub.shell(backend)
                            ?: error(hub.unavailable(backend) ?: Strings.coreNoLinuxEnv())
                    HelperServe.open(shell, hub.helperProgram) {
                        hub.linkProblem(backend, it.exitCode, it.stderr)
                    }
                },
                subscriptions = listOf("quota.watch"),
                subscriptionParams = watchParams,
                onHello = hub.helloFrom(backend),
                onMessage = { o ->
                    if (
                        o.str("type") == "quota.snapshot" &&
                            Harnesses.normalize(o.str("harness")) == normalizedHarness
                    ) {
                        val pushed = o["snapshot"] as? JsonObject
                        val relay = o.str("relay")
                        main.post { if (serve === self) received(pushed, relay) }
                    }
                },
                onProblem = { why -> main.post { if (serve === self) broken = why != null } },
            )
        return self
    }

    /** 界面退到后台：断开订阅，那边的 serve 随之退出，后台不留连接。在主线程上调用。 */
    fun pause() {
        val link = serve ?: return
        serve = null
        link.close()
        paused = true
        staleCheck = false
        main.removeCallbacks(giveUp)
        refreshing = false
    }

    /** 回到前台：退到后台时订阅着的话，重新订阅。在主线程上调用。 */
    fun resume() {
        if (paused) watch()
    }

    /** 这台主机删了：断开订阅。在主线程上调用。 */
    fun close() {
        serve?.close()
        serve = null
        paused = false
    }

    /**
     * 核心给的额度（推来的，或 quota.usage 的结果里的）；null 是在用的账号还没有数，或在用中转（[relay] 是中转名，空串或没有这一项是在用账号）。 在主线程上调用。
     */
    fun received(core: JsonObject?, relay: String? = null) {
        noteRelay(relay)
        val next = if (this.relay != null) null else Quota.fromCore(core)
        snapshot = next
        if (next == null) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, core.toString()).apply()
        if (synced) return
        synced = true
        if (staleCheck) {
            staleCheck = false
            refresh()
        }
    }

    /** 借对话进程问的 get_usage 答了（`{"response": control_response 里的 response}`，主线程上）：交给核心读。 */
    fun usageAnswered(answer: JsonObject) {
        val response = answer["response"] ?: return
        val link = serve ?: return
        thread(name = "zeta-quota", isDaemon = true) {
            val result = runCatching { usage(link, response) }
            main.post { answered(result) }
        }
    }

    /** 把 get_usage 的应答 [response] 交给核心（quota.usage），返回它的结果 `{snapshot, fresh}`。阻塞。 */
    private fun usage(link: HelperServe, response: JsonElement): JsonObject? {
        val params =
            if (isAgy) {
                JsonObject(mapOf("response" to response, "harness" to JsonPrimitive(Harnesses.AGY)))
            } else {
                JsonObject(mapOf("response" to response))
            }
        return link.call("quota.usage", params) as? JsonObject
    }

    /** 问 get_usage、交给核心这一趟做完了（主线程上）。 */
    private fun answered(result: Result<JsonObject?>) {
        if (paused) return
        main.removeCallbacks(giveUp)
        refreshing = false
        val done = result.getOrElse {
            // 自己抛的（Claude Code 没答、答了错误）和 serve 给的带原因；别的（进不了环境、管道断了）只说没取到
            val why = (it as? IllegalStateException ?: it as? ServeError)?.message
            error = why?.let(Strings::coreQuotaFetchFailedWhy) ?: Strings.coreQuotaFetchFailed()
            return
        }
        received(done?.obj("snapshot"), done?.str("relay"))
        val shown = snapshot
        // Claude Code 这次没取到服务器的数：核心留着已有的窗口
        error =
            if (done?.bool("fresh") == false && shown != null)
                Strings.coreQuotaStale(Quota.ageText(shown.updatedAt, System.currentTimeMillis()))
            else null
    }

    /** 设置页、用量弹窗打开时（[force] 为 false：数够新就不问），或者点了刷新。在主线程上调用。 */
    fun refresh(force: Boolean = false) {
        watch()
        if (refreshing) return
        val link = serve ?: return
        // 在用中转时没有订阅额度：不借对话进程问（conv.quota），也不单起 Claude Code
        if (relay != null) {
            error = null
            return
        }
        if (!force && !synced) {
            staleCheck = true
            return
        }
        val now = System.currentTimeMillis()
        val age = snapshot?.let { now - it.updatedAt } ?: Long.MAX_VALUE
        if (!force && age < STALE_MS) return
        if (now - lastAsked < MANUAL_MS) return
        val shell = hub.shell(backend) ?: return
        lastAsked = now
        refreshing = true
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 60_000)
        // 借这台上开着的对话进程问（当前的优先，再是后台的），应答交给 usageAnswered；都没有进程才单起一个
        if (
            hub.liveConversations.any {
                it.backend == backend && it.harness == normalizedHarness && it.queryQuota()
            }
        )
            return
        val cwd = hub.defaultCwd(backend)
        thread(name = "zeta-quota", isDaemon = true) {
            val result = runCatching {
                if (isAgy) {
                    link.call(
                        "quota.usage",
                        buildJsonObject { put("harness", Harnesses.AGY) },
                    ) as? JsonObject
                } else {
                    usage(link, synchronized(probeLock) { QuotaProbe.query(shell, cwd) })
                }
            }
            main.post { answered(result) }
        }
    }

    /** 换了账号：上一个账号的数不再算数。 */
    fun forget() {
        snapshot = null
        error = null
        lastAsked = 0
        prefs.edit().remove(key).apply()
    }

    /** 核心说的在用中转（空串、null 是在用账号）。换到中转时清掉存着的额度；账号页换好以后照 accounts.list 的结果也调一次（核心随后也会推）。 在主线程上调用。 */
    fun noteRelay(name: String?) {
        val next = name?.ifBlank { null }
        if (next == relay) return
        relay = next
        if (next != null) {
            snapshot = null
            error = null
            refreshing = false
            main.removeCallbacks(giveUp)
            prefs.edit().remove(key).putString(relayKey, next).apply()
        } else prefs.edit().remove(relayKey).apply()
    }

    companion object {
        private const val KEY = "snapshot"
        private const val RELAY_KEY = "relay"

        /** 打开页面时，数比这旧才去问。 */
        const val STALE_MS = 2 * 60_000L

        /** 点刷新的最短间隔：Claude Code 取额度的接口会限流。 */
        const val MANUAL_MS = 15_000L
    }
}

/**
 * 单起一个 Claude Code，只问它一次 get_usage（skip_behaviors，不扫本机的会话记录）就收掉：不发消息、不存会话、不起 MCP 服务器。 额度是 Claude
 * Code 用它自己的登录去取的，令牌不经过这里。
 */
object QuotaProbe {
    val ARGS =
        listOf(
            "-p",
            "--input-format",
            "stream-json",
            "--output-format",
            "stream-json",
            "--verbose",
            "--no-session-persistence",
            "--strict-mcp-config",
        )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 读完并丢掉 [stream]（标准错误不读的话，写满管道会把进程卡住）。 */
    private fun discard(stream: InputStream) {
        runCatching {
            stream.use { s ->
                val buf = ByteArray(4096)
                do {
                    val n = s.read(buf)
                } while (n >= 0)
            }
        }
    }

    /** 先 initialize，再要额度（get_usage）。 */
    private fun usageRequests(): List<JsonObject> =
        listOf(
            buildJsonObject {
                put("type", "control_request")
                put("request_id", "init")
                putJsonObject("request") { put("subtype", "initialize") }
            },
            buildJsonObject {
                put("type", "control_request")
                put("request_id", "usage")
                putJsonObject("request") {
                    put("subtype", "get_usage")
                    put("skip_behaviors", true)
                }
            },
        )

    /** 等 [lines] 里出现 get_usage 的应答，最多 [timeoutMs] 毫秒；超时、进程先退出了都抛异常。 */
    private fun awaitUsage(
        process: Process,
        lines: LinkedBlockingQueue<String>,
        timeoutMs: Long,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw IllegalStateException(Strings.coreQuotaClaudeNoAnswer())
            val line = lines.poll(left.coerceAtMost(1_000), TimeUnit.MILLISECONDS)
            if (line == null) {
                if (!process.isAlive && lines.isEmpty())
                    throw IllegalStateException(
                        Strings.coreQuotaClaudeExited(process.exitValue().toString()),
                    )
                continue
            }
            usageAnswer(line)?.let {
                return it
            }
        }
    }

    /** 输出的一行要是 get_usage 的应答，取出其中的额度（`response.response`；答的是错误时抛异常），别的行是 null。 */
    private fun usageAnswer(line: String): JsonObject? {
        val o =
            runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        if (o.str("type") != "control_response") return null
        val response = o["response"] as? JsonObject ?: return null
        if (response.str("request_id") != "usage") return null
        if (response.str("subtype") != "success") {
            throw IllegalStateException(response.str("error") ?: Strings.coreQuotaClaudeNoUsage())
        }
        return response["response"] as? JsonObject
            ?: throw IllegalStateException(Strings.coreQuotaClaudeNoUsage())
    }

    /** 返回 get_usage 的应答（`response.response`）；Claude Code 答的是错误时抛异常。 */
    fun query(shell: LinuxShell, cwd: String, timeoutMs: Long = 45_000): JsonObject {
        val command =
            "cd " +
                LinuxEnv.quote(cwd) +
                " 2>/dev/null || cd; exec claude " +
                ARGS.joinToString(" ")
        val process = shell.start(command)
        val lines = LinkedBlockingQueue<String>()
        thread(isDaemon = true) {
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
        }
        thread(isDaemon = true) { discard(process.errorStream) }
        try {
            val out = process.outputStream
            for (request in usageRequests()) out.write((request.toString() + "\n").toByteArray())
            out.flush()
            return awaitUsage(process, lines, timeoutMs)
        } finally {
            // 关掉标准输入它就自己退出（会顺手删掉 ~/.claude/sessions 里的登记）；不退再杀
            runCatching { process.outputStream.close() }
            if (!runCatching { process.exitsWithin(5_000) }.getOrDefault(false)) shell.kill(process)
        }
    }
}
