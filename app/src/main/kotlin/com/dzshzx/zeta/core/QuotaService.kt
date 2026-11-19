package com.dzshzx.zeta.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.claude.EnvShell
import com.dzshzx.zeta.core.claude.LinuxShell
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 当前账号的订阅额度（应用级，所有对话共用一份）。数都是 Claude Code 给的，Zeta 不碰 OAuth 令牌、不自己调接口：
 * - 对话进程里的 rate_limit_event：每次模型请求的响应头里带着 5 小时和每周窗口，数变了 Claude Code 就发一条；
 * - 控制请求 get_usage：Claude Code 自己去取 claude.ai 的额度（和 /usage 同一份），套餐有哪些窗口就有哪些。
 *   新起对话进程时顺便问（隔 [AUTO_MS] 才问一次），设置页和用量弹窗打开时也问；有开着的对话进程就借它问，
 *   没有就单起一个只答这一个问题的 Claude Code（[QuotaProbe]）。
 * 上次的数存在应用自己的 SharedPreferences 里，下次打开先显示它；换账号时清掉。
 */
class QuotaService(private val hub: ConversationHub) {
    private val prefs = hub.context.getSharedPreferences("quota", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    var snapshot by mutableStateOf(Quota.decode(prefs.getString(KEY, null)))
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** 上一次刷新没取到数的原因；取到了就清掉。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 最近一次发出 get_usage 的时刻。 */
    private var lastAsked = 0L

    /** 单起的问额度的进程和换账号不能同时进行：换账号时要等所有 Claude Code 进程都退出。 */
    internal val probeLock = Any()

    private val giveUp = Runnable {
        if (refreshing) {
            refreshing = false
            error = "等了太久没有结果"
        }
    }

    /** 对话进程的一条事件（主线程上）。只看 rate_limit_event 和 get_usage 的应答。 */
    fun observe(event: JsonObject) {
        val now = System.currentTimeMillis()
        when ((event["type"] as? JsonPrimitive)?.contentOrNull) {
            "rate_limit_event" -> Quota.applyEvent(snapshot, event["rate_limit_info"] as? JsonObject, now)?.let { if (it != snapshot) publish(it) }
            "control_response" -> {
                val body = ((event["response"] as? JsonObject)?.get("response") as? JsonObject) ?: return
                Quota.parseUsage(body, now)?.let(::answered)
            }
        }
    }

    /** get_usage 的应答到了。 */
    private fun answered(parsed: QuotaSnapshot) {
        main.removeCallbacks(giveUp)
        refreshing = false
        val previous = snapshot
        // Claude Code 这次没取到服务器的数（rate_limits 为 null）：已有的窗口留着，不当成新数据
        if (parsed.available && parsed.windows.isEmpty() && previous != null && previous.windows.isNotEmpty()) {
            error = "这次没取到最新的额度，显示的是 ${Quota.ageText(previous.updatedAt, parsed.updatedAt)}的数"
            return
        }
        error = null
        // 被拒的状态只有事件里有：取到新数时沿用还没过期的
        publish(parsed.copy(blocked = previous?.blocked == true, blockedUntil = previous?.blockedUntil).current(parsed.updatedAt))
    }

    private fun publish(next: QuotaSnapshot) {
        snapshot = next
        prefs.edit().putString(KEY, Quota.encode(next)).apply()
    }

    /** 新起的对话进程要不要顺便问一次。在主线程上调用。 */
    internal fun claimAutoQuery(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAsked < AUTO_MS) return false
        lastAsked = now
        return true
    }

    /** 设置页、用量弹窗打开时（[force] 为 false：数够新就不问），或者点了刷新。在主线程上调用。 */
    fun refresh(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (refreshing) return
        val age = snapshot?.let { now - it.updatedAt } ?: Long.MAX_VALUE
        if (!force && age < STALE_MS) return
        if (now - lastAsked < MANUAL_MS) return
        val config = hub.env ?: return
        lastAsked = now
        refreshing = true
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, 60_000)
        // 借开着的对话进程问，应答从 observe 进来
        if (hub.current.queryQuota()) return
        thread(name = "zeta-quota", isDaemon = true) {
            val body = runCatching { synchronized(probeLock) { QuotaProbe.query(EnvShell(config), config.defaultCwd) } }
            main.post {
                val parsed = body.getOrNull()?.let { Quota.parseUsage(it, System.currentTimeMillis()) }
                if (parsed != null) {
                    answered(parsed)
                } else {
                    main.removeCallbacks(giveUp)
                    refreshing = false
                    // 自己抛的（Claude Code 没答、答了错误）带中文原因；别的（进不了环境、管道断了）只说没取到
                    error = "没取到最新的额度" + ((body.exceptionOrNull() as? IllegalStateException)?.message?.let { "：$it" } ?: "") + "。"
                }
            }
        }
    }

    /** 换了账号：上一个账号的数不再算数。 */
    fun forget() {
        snapshot = null
        error = null
        lastAsked = 0
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "snapshot"

        /** 新起对话进程时顺便问的最短间隔。 */
        const val AUTO_MS = 5 * 60_000L

        /** 打开页面时，数比这旧才去问。 */
        const val STALE_MS = 2 * 60_000L

        /** 点刷新的最短间隔：Claude Code 取额度的接口会限流。 */
        const val MANUAL_MS = 15_000L
    }
}

/**
 * 单起一个 Claude Code，只问它一次 get_usage（skip_behaviors，不扫本机的会话记录）就收掉：不发消息、不存会话、不起 MCP 服务器。
 * 额度是 Claude Code 用它自己的登录去取的，令牌不经过这里。
 */
object QuotaProbe {
    val ARGS = listOf(
        "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
        "--no-session-persistence", "--strict-mcp-config",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 返回 get_usage 的应答（`response.response`）；Claude Code 答的是错误时抛异常。 */
    fun query(shell: LinuxShell, cwd: String, timeoutMs: Long = 45_000): JsonObject {
        val command = "cd " + LinuxEnv.quote(cwd) + " 2>/dev/null || cd; exec claude " + ARGS.joinToString(" ")
        val process = shell.start(command)
        val lines = LinkedBlockingQueue<String>()
        thread(isDaemon = true) { runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } } }
        thread(isDaemon = true) { runCatching { process.errorStream.use { s -> val buf = ByteArray(4096); do { val n = s.read(buf) } while (n >= 0) } } }
        try {
            val out = process.outputStream
            for (request in listOf(
                buildJsonObject { put("type", "control_request"); put("request_id", "init"); putJsonObject("request") { put("subtype", "initialize") } },
                buildJsonObject {
                    put("type", "control_request"); put("request_id", "usage")
                    putJsonObject("request") { put("subtype", "get_usage"); put("skip_behaviors", true) }
                },
            )) out.write((request.toString() + "\n").toByteArray())
            out.flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("Claude Code 没有按时回答")
                val line = lines.poll(left.coerceAtMost(1_000), TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (!process.isAlive && lines.isEmpty()) throw IllegalStateException("Claude Code 起不来（退出码 ${process.exitValue()}）")
                    continue
                }
                val o = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                if ((o["type"] as? JsonPrimitive)?.contentOrNull != "control_response") continue
                val response = o["response"] as? JsonObject ?: continue
                if ((response["request_id"] as? JsonPrimitive)?.contentOrNull != "usage") continue
                if ((response["subtype"] as? JsonPrimitive)?.contentOrNull != "success") {
                    throw IllegalStateException((response["error"] as? JsonPrimitive)?.contentOrNull ?: "Claude Code 没有给出额度")
                }
                return response["response"] as? JsonObject ?: throw IllegalStateException("Claude Code 没有给出额度")
            }
        } finally {
            // 关掉标准输入它就自己退出（会顺手删掉 ~/.claude/sessions 里的登记）；不退再杀
            runCatching { process.outputStream.close() }
            if (!runCatching { process.waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)) shell.kill(process)
        }
    }
}
