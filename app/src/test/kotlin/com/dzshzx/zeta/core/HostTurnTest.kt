package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「这一轮在不在进行、有没有后台任务在跑」写在三处：聊天页的 [Conversation]（逐条收事件）、接回后台进程时的 zeta-helper hosts（scan_host
 * 读整份输出日志）、zeta_host.sh 的看门狗（turn_running、tasks_running，闲着十分钟就结束进程）。
 * 同一份输出日志每读完一行，三处的结论都要一样，也要和样本标的一样。口径写在 zeta_host.sh 的 turn_running 上面。
 *
 * 样本照 Claude Code 2.1.329 的真实输出删繁（临时 HOME 上的真 claude 连假的 Messages API 抓的），键序照真的： result 行的 type
 * 不在行首；子代理内部的行带 parent_tool_use_id，后台子代理的在这一轮的 result 之后才写来，也没有 stream_event。 Claude Code 那一侧的行为由
 * ClaudeCodeContractTest、ClaudeTaskContractTest 钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HostTurnTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var home: File
    private val processes = mutableListOf<Process>()
    private val host = File("src/main/assets/zeta_host.sh").absoluteFile

    @Before
    fun setUp() {
        assumeTrue("要 Linux 的 /proc 和 bash", File("/proc/self/cmdline").exists())
        home = Files.createTempDirectory("zeta-host-turn").toFile().canonicalFile
    }

    @After
    fun tearDown() {
        if (!::home.isInitialized) return
        processes.forEach { it.destroyForcibly() }
        // 看门狗那个测试真起的托管进程（脱离出去了，要经 stop 结束）；比对的那个测试只有目录，挂的是上面那个假进程
        File(home, ".cache/zeta/hosts")
            .list()
            .orEmpty()
            .filter { it.startsWith("k-") }
            .forEach { key -> runCatching { execute("bash", host.path, "stop", key) } }
        home.deleteRecursively()
    }

    /** 某一刻的样子：这一轮在进行（turn）、有后台任务在跑（tasks）。 */
    private data class State(val turn: Boolean, val tasks: Boolean) {
        override fun toString() = (if (turn) "T" else "-") + (if (tasks) "B" else "-")
    }

    private fun state(mark: String) = State(turn = mark[0] == 'T', tasks = mark[1] == 'B')

    /** 一份输出日志：每一行和读完它之后应有的样子（T 是这一轮在进行，B 是有后台任务在跑，- 是没有）。 */
    private class Sample(val key: String, val what: String, val steps: List<Pair<String, String>>)

    /** 造输出行，字段删繁、键序照真的。[s] 是会话号，每份样本各用各的。 */
    private class Out(private val s: String) {
        private fun q(v: String?) = if (v == null) "null" else "\"$v\""

        private fun agent(parent: String?) = if (parent == null) "" else ",\"agent_id\":\"a1\""

        fun queued(u: String) =
            """{"type":"command_lifecycle","command_uuid":"$u","state":"queued","uuid":"q-$u","session_id":"$s"}"""

        fun started(u: String) =
            """{"type":"command_lifecycle","command_uuid":"$u","state":"started","uuid":"s-$u","session_id":"$s"}"""

        fun completed(u: String) =
            """{"type":"command_lifecycle","command_uuid":"$u","state":"completed","uuid":"c-$u","session_id":"$s"}"""

        fun cancelled(u: String) =
            """{"type":"command_lifecycle","command_uuid":"$u","state":"cancelled","uuid":"x-$u","session_id":"$s"}"""

        val systemInit =
            """{"type":"system","subtype":"init","cwd":"/workspace/claude","session_id":"$s","tools":["Agent","Bash"],"mcp_servers":[],"model":"claude-fake","permissionMode":"default","uuid":"i-$s"}"""
        val status =
            """{"type":"system","subtype":"status","status":"requesting","session_id":"$s","uuid":"st-$s"}"""
        val streamStart =
            """{"type":"stream_event","event":{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-fake","content":[],"stop_reason":null}},"session_id":"$s","parent_tool_use_id":null,"uuid":"se1"}"""
        val streamStop =
            """{"type":"stream_event","event":{"type":"message_stop"},"session_id":"$s","parent_tool_use_id":null,"uuid":"se2"}"""

        fun say(text: String, parent: String? = null) =
            """{"type":"assistant","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-fake","content":[{"type":"text","text":"$text"}],"stop_reason":null},"parent_tool_use_id":${q(parent)}${agent(parent)},"session_id":"$s","uuid":"a-$text"}"""

        fun call(id: String, name: String, input: String, parent: String? = null) =
            """{"type":"assistant","message":{"id":"msg_2","type":"message","role":"assistant","model":"claude-fake","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}],"stop_reason":null},"parent_tool_use_id":${q(parent)}${agent(parent)},"session_id":"$s","uuid":"a-$id","timestamp":"2026-11-20T05:56:40.000Z"}"""

        fun toolResult(
            id: String,
            content: String,
            parent: String? = null,
            error: Boolean = false,
        ) =
            """{"type":"user","message":{"role":"user","content":[{"tool_use_id":"$id","type":"tool_result","content":"$content","is_error":$error}]},"parent_tool_use_id":${q(parent)}${agent(parent)},"session_id":"$s","uuid":"u-$id","timestamp":"2026-11-20T05:56:41.000Z"}"""

        fun userText(text: String, parent: String? = null) =
            """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"$text"}]},"parent_tool_use_id":${q(parent)}${agent(parent)},"session_id":"$s","uuid":"ut-$s"}"""

        fun result(subtype: String = "success") =
            """{"duration_api_ms":43,"stop_reason":"end_turn","session_id":"$s","total_cost_usd":0.00028,"is_error":${subtype != "success"},"num_turns":1,"subtype":"$subtype","result":"done","type":"result","duration_ms":50,"uuid":"r-$s"}"""

        fun tasks(vararg list: Pair<String, String>) =
            """{"type":"system","subtype":"background_tasks_changed","tasks":[""" +
                list.joinToString(",") { (id, type) ->
                    """{"task_id":"$id","task_type":"$type","description":"look around"}"""
                } +
                """],"uuid":"bt-$s","session_id":"$s"}"""

        fun taskStarted(id: String, toolUse: String, type: String, background: Boolean) =
            """{"type":"system","subtype":"task_started","task_id":"$id","tool_use_id":"$toolUse","description":"look around","is_backgrounded":$background,"task_type":"$type","uuid":"ts-$id","session_id":"$s"}"""

        fun taskProgress(id: String) =
            """{"type":"system","subtype":"task_progress","task_id":"$id","description":"Running sub step","usage":{"tool_uses":1},"last_tool_name":"Bash","uuid":"tp-$id","session_id":"$s"}"""

        fun taskUpdated(id: String, status: String) =
            """{"type":"system","subtype":"task_updated","task_id":"$id","patch":{"status":"$status","end_time":1795154208722},"uuid":"tu-$id","session_id":"$s"}"""

        fun taskNotification(id: String, toolUse: String, status: String) =
            """{"type":"system","subtype":"task_notification","task_id":"$id","tool_use_id":"$toolUse","status":"$status","summary":"REPORT: all good","uuid":"tn-$id","session_id":"$s"}"""

        fun controlResponse(id: String, body: String) =
            """{"type":"control_response","response":{"subtype":"success","request_id":"$id","response":$body}}"""
    }

    private val samples: List<Sample> =
        listOf(
            Out("s-plain").run {
                Sample(
                    "plain",
                    "普通一轮",
                    listOf(
                        "--" to queued("u1"),
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to status,
                        "T-" to streamStart,
                        "T-" to say("done"),
                        "T-" to streamStop,
                        "--" to result(),
                        "--" to completed("u1"),
                    ),
                )
            },
            Out("s-steer").run {
                Sample(
                    "steer",
                    "插话在工具边界并进这一轮，两条消息只出一个 result",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to call("toolu_0", "Bash", """{"command":"make"}"""),
                        "T-" to queued("u2"),
                        "T-" to taskStarted("b1", "toolu_0", "local_bash", background = false),
                        "T-" to taskNotification("b1", "toolu_0", "completed"),
                        "T-" to toolResult("toolu_0", "ok"),
                        "T-" to started("u2"),
                        "T-" to say("done"),
                        "T-" to completed("u2"),
                        "--" to result(),
                        "--" to completed("u1"),
                    ),
                )
            },
            Out("s-late").run {
                Sample(
                    "late",
                    "插话没赶上这一轮：result 之后 Claude Code 取走它，另起一轮",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to queued("u2"),
                        "T-" to say("done"),
                        "--" to result(),
                        "--" to completed("u1"),
                        "T-" to started("u2"),
                        "T-" to systemInit,
                        "T-" to say("again"),
                        "--" to result(),
                        "--" to completed("u2"),
                    ),
                )
            },
            Out("s-interrupt").run {
                Sample(
                    "interrupt",
                    "被打断：result 是 error_during_execution，之后还有 cancelled",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to call("toolu_0", "Bash", """{"command":"make"}"""),
                        "T-" to controlResponse("z1", """{"still_queued":[]}"""),
                        "T-" to toolResult("toolu_0", "rejected by the user", error = true),
                        "T-" to userText("[Request interrupted by user for tool use]"),
                        "--" to result("error_during_execution"),
                        "--" to cancelled("u1"),
                    ),
                )
            },
            Out("s-fg-agent").run {
                Sample(
                    "fg-agent",
                    "前台子代理：内部的行都在这一轮里",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to
                            call(
                                "toolu_0",
                                "Agent",
                                """{"prompt":"look around","run_in_background":false}""",
                            ),
                        "T-" to taskStarted("a1", "toolu_0", "local_agent", background = false),
                        "T-" to userText("look around", parent = "toolu_0"),
                        "T-" to taskProgress("a1"),
                        "T-" to
                            call(
                                "toolu_s0",
                                "Bash",
                                """{"command":"echo from-sub"}""",
                                parent = "toolu_0",
                            ),
                        "T-" to toolResult("toolu_s0", "from-sub", parent = "toolu_0"),
                        "T-" to taskUpdated("a1", "completed"),
                        "T-" to taskNotification("a1", "toolu_0", "completed"),
                        "T-" to toolResult("toolu_0", "REPORT: all good"),
                        "T-" to say("done"),
                        "--" to result(),
                        "--" to completed("u1"),
                    ),
                )
            },
            Out("s-bg-agent").run {
                Sample(
                    "bg-agent",
                    "后台子代理：内部的行在这一轮的 result 之后写来，做完后 Claude Code 自己起一轮（只有 init）",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to
                            call(
                                "toolu_0",
                                "Agent",
                                """{"prompt":"look around","run_in_background":true}""",
                            ),
                        "TB" to tasks("a1" to "local_agent"),
                        "TB" to taskStarted("a1", "toolu_0", "local_agent", background = true),
                        "TB" to toolResult("toolu_0", "Async agent launched successfully."),
                        "TB" to say("started it"),
                        "-B" to result(),
                        "-B" to completed("u1"),
                        "-B" to say("checking first", parent = "toolu_0"),
                        "-B" to
                            call(
                                "toolu_s0",
                                "Bash",
                                """{"command":"echo from-sub"}""",
                                parent = "toolu_0",
                            ),
                        "-B" to taskProgress("a1"),
                        "-B" to toolResult("toolu_s0", "from-sub", parent = "toolu_0"),
                        "-B" to say("REPORT: all good", parent = "toolu_0"),
                        "-B" to taskUpdated("a1", "completed"),
                        "-B" to taskNotification("a1", "toolu_0", "completed"),
                        "--" to tasks(),
                        "T-" to systemInit,
                        "T-" to status,
                        "T-" to say("the report says all good"),
                        "--" to result(),
                    ),
                )
            },
            Out("s-bg-stop").run {
                Sample(
                    "bg-stop",
                    "后台命令被停掉：不另起一轮",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to
                            call(
                                "toolu_0",
                                "Bash",
                                """{"command":"make test","run_in_background":true}""",
                            ),
                        "TB" to tasks("b1" to "local_bash"),
                        "TB" to taskStarted("b1", "toolu_0", "local_bash", background = true),
                        "TB" to toolResult("toolu_0", "Command running in background"),
                        "TB" to say("running"),
                        "-B" to result(),
                        "-B" to completed("u1"),
                        "-B" to controlResponse("c1", "{}"),
                        "-B" to taskUpdated("b1", "killed"),
                        "-B" to taskNotification("b1", "toolu_0", "stopped"),
                        "--" to tasks(),
                    ),
                )
            },
            Out("s-bg-done").run {
                Sample(
                    "bg-done",
                    "后台命令做完：Claude Code 自己起一轮汇报",
                    listOf(
                        "T-" to started("u1"),
                        "T-" to systemInit,
                        "T-" to
                            call(
                                "toolu_0",
                                "Bash",
                                """{"command":"make test","run_in_background":true}""",
                            ),
                        "TB" to tasks("b1" to "local_bash"),
                        "TB" to taskStarted("b1", "toolu_0", "local_bash", background = true),
                        "TB" to toolResult("toolu_0", "Command running in background"),
                        "-B" to result(),
                        "-B" to completed("u1"),
                        "-B" to taskUpdated("b1", "completed"),
                        "-B" to taskNotification("b1", "toolu_0", "completed"),
                        "--" to tasks(),
                        "T-" to systemInit,
                        "T-" to say("tests passed"),
                        "--" to result(),
                    ),
                )
            },
        )

    /** 以临时 HOME 跑一条命令（[HostTesting.run]），返回标准输出。 */
    private fun execute(vararg command: String, env: Map<String, String> = emptyMap()): String =
        HostTesting.run(home, command.toList(), env)

    @Test
    fun conversationHelperAndWatchdogAgreeAfterEveryLine() {
        // 每份样本的每个前缀（含一行都没有）各放一个托管目录，挂同一个“活着的 Claude Code”：助手脚本认 cmdline 里有 claude 的
        val alive =
            ProcessBuilder("bash", "-c", "exec -a claude-fake sleep 300").start().also {
                processes += it
            }
        val hosts = File(home, ".cache/zeta/hosts")
        val expected = LinkedHashMap<String, State>()
        for (sample in samples) {
            for (n in 0..sample.steps.size) {
                val key = "${sample.key}-$n"
                val dir = File(hosts, key).apply { mkdirs() }
                File(dir, "pid").writeText("${alive.pid()}\n")
                File(dir, "out.jsonl")
                    .writeText(sample.steps.take(n).joinToString("") { it.second + "\n" })
                expected[key] =
                    if (n == 0) State(turn = false, tasks = false)
                    else state(sample.steps[n - 1].first)
            }
        }

        // zeta-helper hosts：一次列出全部目录
        val helperSays =
            execute(TestHelper.path, "hosts")
                .lineSequence()
                .filter { it.startsWith("{") }
                .map { Json.parseToJsonElement(it).jsonObject }
                .associate {
                    it.str("key")!! to
                        State(turn = it.bool("busy") == true, tasks = (it.int("tasks") ?: 0) > 0)
                }
        // 看门狗：zeta_host.sh state，输出 turn=0|1 tasks=0|1
        val watchdogSays =
            execute(
                    "bash",
                    "-c",
                    "h=\$1; shift; for k; do echo \"\$k \$(bash \"\$h\" state \"\$k\")\"; done",
                    "_",
                    host.path,
                    *expected.keys.toTypedArray(),
                )
                .lines()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val (key, turn, tasks) = line.split(' ')
                    key to State(turn = turn == "turn=1", tasks = tasks == "tasks=1")
                }
        // Conversation：一行一行喂，和进程送来的一样走 handle
        val conversationSays = HashMap<String, State>()
        val hub = Fixtures.readyHub(app)
        for (sample in samples) {
            val chat =
                Conversation(
                    hub,
                    "/workspace/claude",
                    sessionId = null,
                    title = "",
                    historyFile = null,
                )
            conversationSays["${sample.key}-0"] = State(chat.busy, chat.backgroundTasks > 0)
            sample.steps.forEachIndexed { i, (_, line) ->
                chat.receive(line)
                conversationSays["${sample.key}-${i + 1}"] =
                    State(chat.busy, chat.backgroundTasks > 0)
            }
        }

        val wrong = expected.mapNotNull { (key, want) ->
            val says = listOf(conversationSays[key], helperSays[key], watchdogSays[key])
            if (says.all { it == want }) return@mapNotNull null
            val sample = samples.first { key.startsWith(it.key + "-") }
            val n = key.substringAfterLast('-').toInt()
            "${sample.what}，第 $n 行（${sample.steps.getOrNull(n - 1)?.second?.take(90) ?: "一行都没有"}）：" +
                "应为 $want，Conversation ${says[0]}，scan_host ${says[1]}，看门狗 ${says[2]}"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** 看门狗真跑起来：判定函数确实带进了脱离出去的那段脚本，闲着的到点结束，在干活的、有后台任务的不动。 */
    @Test
    fun watchdogEndsOnlyIdleProcesses() {
        // 假的 claude：把参数逐行写到标准输出（就是 out.jsonl），然后一直读标准输入
        HostTesting.writeClaudeStandIn(home, prelude = "printf '%s\\n' \"\$@\"")
        // 「不动」不靠多等几秒：sleep 换成记账的替身，看门狗每轮 sleep 1 时在 rounds 里记一行它的托管目录
        // （看门狗那段是起进程的那段的子 shell，命令行和它一样，托管目录是其中一个参数），
        // 等另外两个各自被看过的轮数超过闲置上限再断言
        val rounds = File(home, "rounds")
        File(home, "bin/sleep").apply {
            writeText(
                "#!/bin/bash\n" +
                    "[ \"\$1\" = 1 ] && tr '\\0' '\\n' < /proc/\$PPID/cmdline | grep -m1 -x '/.*/hosts/[^/]*' >> '${rounds.path}'\n" +
                    "PATH=\${PATH#*:} exec sleep \"\$@\"\n",
            )
            setExecutable(true)
        }
        val out = Out("s-dog")
        val env = mapOf("ZETA_IDLE_LIMIT" to "$IDLE_LIMIT")
        fun start(key: String, vararg lines: String) =
            execute("bash", host.path, "start", key, home.path, "t", *lines, env = env)
        // 标准输入一关，接着的那一方就退出了：三个都没有应用接着
        start(
            "k-idle",
            out.started("u1"),
            out.systemInit,
            out.say("done"),
            out.result(),
            out.completed("u1"),
        )
        start(
            "k-busy",
            out.started("u1"),
            out.systemInit,
            out.say("checking first", parent = "toolu_0"),
        )
        start(
            "k-tasks",
            out.started("u1"),
            out.systemInit,
            out.tasks("b1" to "local_bash"),
            out.result(),
            out.completed("u1"),
        )
        fun dir(key: String) = File(home, ".cache/zeta/hosts/$key")
        fun pid(key: String) = File(dir(key), "pid").readText().trim().toInt()
        val busy = pid("k-busy")
        val tasks = pid("k-tasks")

        assertTrue("闲着的进程没有被看门狗结束", waitUntil(60) { File(dir("k-idle"), "exit").exists() })
        // 另外两个各自被看过的轮数超过闲置上限（多一轮：记下的是开始睡，看是在睡醒之后）还活着
        fun seen(key: String) =
            rounds.takeIf { it.exists() }?.readLines().orEmpty().count { it == dir(key).path }
        waitUntil(60) {
            !HostTesting.alive(busy) ||
                !HostTesting.alive(tasks) ||
                listOf("k-busy", "k-tasks").all { seen(it) >= IDLE_LIMIT + 2 }
        }
        assertTrue("这一轮还在进行的被结束了", HostTesting.alive(busy))
        assertTrue("有后台任务在跑的被结束了", HostTesting.alive(tasks))
        assertFalse(File(dir("k-busy"), "exit").exists())
        assertFalse(File(dir("k-tasks"), "exit").exists())
        assertTrue("看门狗没在转", listOf("k-busy", "k-tasks").all { seen(it) >= IDLE_LIMIT + 2 })
    }

    private companion object {
        /** 看门狗测试里的闲置上限（ZETA_IDLE_LIMIT，轮）。 */
        const val IDLE_LIMIT = 2
    }
}
