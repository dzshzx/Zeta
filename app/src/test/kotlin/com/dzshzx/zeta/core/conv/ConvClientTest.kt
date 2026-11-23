package com.dzshzx.zeta.core.conv

import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ChatItem
import com.dzshzx.zeta.core.CodeRewind
import com.dzshzx.zeta.core.ContextLevel
import com.dzshzx.zeta.core.ElicitField
import com.dzshzx.zeta.core.ElicitationRequest
import com.dzshzx.zeta.core.PermissionReply
import com.dzshzx.zeta.core.PermissionRequest
import com.dzshzx.zeta.core.ServeError
import com.dzshzx.zeta.core.ServeRequests
import com.dzshzx.zeta.core.SessionStatus
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskOutput
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.TodoEntry
import com.dzshzx.zeta.core.TodoList
import com.dzshzx.zeta.core.TodoStatus
import com.dzshzx.zeta.i18n.UiLanguage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ConvClient]、[CoreConversation] 对着假的连接：录好的协议消息（docs/core.md「对话」）喂进去，看合并出的状态和条目、发出的请求。 */
class ConvClientTest {
    /** 一次请求：方法和参数。 */
    private data class Call(val method: String, val params: JsonObject)

    /** 假的连接：记下请求，按方法给定好的应答；[onCall] 在应答之前跑，模拟核心在应答前推来的消息。 */
    private class FakeLink : ServeRequests {
        val calls = LinkedBlockingQueue<Call>()
        val results = HashMap<String, String>()
        val errors = HashMap<String, String>()
        @Volatile var onCall: (Call) -> Unit = {}

        override fun call(method: String, params: JsonObject): JsonElement? {
            val call = Call(method, params)
            calls.put(call)
            onCall(call)
            errors[method]?.let { throw ServeError(it) }
            return results[method]?.let(Json::parseToJsonElement)
        }

        fun took(): List<Call> = ArrayList<Call>().also { calls.drainTo(it) }

        fun next(): Call = checkNotNull(calls.poll(5, TimeUnit.SECONDS)) { "5 秒内没有请求" }
    }

    private class Recorder : ConvListener {
        val changes = CopyOnWriteArrayList<CoreConversation>()
        val turns = CopyOnWriteArrayList<ConvTurn>()
        val phone = CopyOnWriteArrayList<JsonObject>()
        val subagents = LinkedBlockingQueue<ConvSubagent>()
        val outputs = LinkedBlockingQueue<Pair<String, TaskOutput>>()
        val activity = CopyOnWriteArrayList<Map<String, SessionActivity>>()

        override fun changed(conv: CoreConversation) {
            changes += conv
        }

        override fun turn(conv: CoreConversation, turn: ConvTurn) {
            turns += turn
        }

        override fun phone(conv: CoreConversation, message: JsonObject) {
            phone += message
        }

        override fun subagent(conv: CoreConversation, subagent: ConvSubagent) {
            subagents.put(subagent)
        }

        override fun taskOutput(conv: CoreConversation, task: String, output: TaskOutput) {
            outputs.put(task to output)
        }

        override fun activity(sessions: Map<String, SessionActivity>) {
            activity += sessions
        }
    }

    private val link = FakeLink()
    private val events = Recorder()
    private val client = ConvClient(link, events)

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun push(text: String) = assertTrue(client.handle(obj(text)))

    private fun ids(items: List<ChatItem>): String = items.joinToString(",") { it.id }

    /** 打开一个对话：核心在应答之前推来整份状态（会话 s1）和两条条目。 */
    private fun opened(spec: ConvSpec = SPEC): CoreConversation {
        link.onCall = { call ->
            if (call.method == "conv.open") {
                val name = call.params.str("conv")
                push(
                    """{"type":"conv.state","conv":"$name","full":true,"session":"s1","title":"修 bug","cwd":"/h/p",""" +
                        """"busy":false,"mode":{"user":"default","process":"default"},"queued":[]}""",
                )
                push(
                    """{"type":"conv.items","conv":"$name","full":true,"at":0,"remove":0,""" +
                        """"items":[{"kind":"user","id":"a","text":"你好"},{"kind":"answer","id":"b","text":"在"}]}""",
                )
            }
        }
        val conv = client.open(spec)
        link.onCall = {}
        link.took()
        events.changes.clear()
        return conv
    }

    private fun JsonObject.str(key: String) =
        (this[key] as kotlinx.serialization.json.JsonPrimitive).content

    @Test
    fun openSendsTheSpecAndKeepsPushesThatCameBeforeTheReply() {
        val conv = opened(SPEC.copy(session = null, file = null))
        assertEquals("k1", conv.name)
        assertEquals("s1", conv.view.state.session)
        assertEquals("修 bug", conv.view.state.title)
        assertEquals("a,b", ids(conv.view.items))
        assertTrue(conv.view.connected)
        assertEquals(listOf(conv), client.conversations)

        val second = ConvClient(link, events)
        link.onCall = {}
        second.open(ConvSpec(cwd = "/h/p", options = ConvOptions(autoTitle = true, desk = true)))
        assertEquals(
            listOf(
                Call(
                    "conv.open",
                    obj("""{"conv":"k1","cwd":"/h/p","options":{"auto_title":true,"desk":true}}"""),
                ),
            ),
            link.took(),
        )
    }

    @Test
    fun openWithAnExistingSession() {
        link.onCall = {}
        client.open(SPEC)
        assertEquals(
            Call(
                "conv.open",
                obj(
                    """{"conv":"k1","session":"s1","file":"/h/.claude/projects/-h-p/s1.jsonl","cwd":"/h/p",""" +
                        """"options":{"env":{"CLAUDE_AUTOCOMPACT_PCT_OVERRIDE":"80"},"phone":true,"auto_title":true,""" +
                        """"compact_percent":80,"system_prompt":"补充","mcp_config":"{}"}}""",
                ),
            ),
            link.next(),
        )
    }

    @Test
    fun openFailureForgetsTheConversation() {
        link.errors["conv.open"] = "这条连接已经有叫 k1 的对话"
        try {
            client.open(SPEC)
            fail("打开失败没报错")
        } catch (e: ServeError) {
            assertEquals("这条连接已经有叫 k1 的对话", e.message)
        }
        assertTrue(client.conversations.isEmpty())
        // 名字不重用：下一个是 k2
        link.errors.clear()
        assertEquals("k2", client.open(SPEC).name)
    }

    @Test
    fun stateMergesPartialPushesAndFullOnesReplace() {
        val conv = opened()
        push(
            """{"type":"conv.state","conv":"k1","busy":true,"status":"正在重试","model":"opus","later_field":1}""",
        )
        val s = conv.view.state
        assertTrue(s.busy)
        assertEquals("正在重试", s.status)
        assertEquals("opus", s.model)
        assertEquals("修 bug", s.title)
        assertEquals("s1", s.session)
        assertEquals(listOf(conv), events.changes)
        push("""{"type":"conv.state","conv":"k1","model":""}""")
        assertNull(conv.view.state.model)
        push("""{"type":"conv.state","conv":"k1","full":true,"session":"s1"}""")
        assertFalse(conv.view.state.busy)
        assertEquals("", conv.view.state.title)
    }

    @Test
    fun stateDecodesTheHelperTypes() {
        val conv = opened()
        push(STATE)
        val s = conv.view.state
        val permission = s.prompts[0] as PermissionRequest
        assertEquals("r1", permission.requestId)
        assertEquals("AskUserQuestion", permission.toolName)
        assertEquals("用哪个？", permission.questions.single().question)
        assertTrue(permission.questions.single().takesNotes)
        assertEquals("预览", permission.questions.single().options[0].preview)
        assertNull(permission.questions.single().options[1].preview)
        assertEquals("审查面板排版", permission.agentLabel)
        assertEquals(listOf("以后不再问"), permission.suggestionLines)
        assertEquals("Claude 有问题要问你", permission.notice)
        assertTrue(permission.asksInput)
        val form = s.prompts[1] as ElicitationRequest
        assertEquals("github", form.server)
        assertNull(form.url)
        assertEquals(ElicitField.Kind.Multi, form.fields.single().kind)
        assertEquals(listOf("a"), form.fields.single().default)
        assertEquals(1.0, form.fields.single().min)
        assertEquals(2, s.prompts.size) // 认不出的种类跳过

        val task = s.tasks.single()
        assertEquals(TaskKind.Command, task.kind)
        assertEquals(TaskState.Running, task.state)
        assertTrue(task.readable)
        assertNull(task.agentType)
        assertEquals(1000L, task.startedAt)

        assertEquals(1000L, s.usage.contextTokens)
        assertEquals(200_000L, s.usage.contextWindow)
        assertEquals(3L, s.usage.total.total)
        assertEquals(0.5, s.usage.totalCost!!, 0.0)
        assertEquals(ContextLevel.Normal, s.usage.level)
        assertEquals(0.005f, s.usage.fraction!!, 0.0001f)
        assertEquals(true, s.usage.autoCompact)

        assertEquals(
            ConvMessage("q1", "再看看", listOf(ConvImage("image/png", "AAA")), listOf("/h/a.go")),
            s.queued.single(),
        )
        assertEquals(ConvMessage("q0", "撤回的"), s.recalled)
        assertEquals(ConvMode("plan", "default", "plan"), s.mode)
        assertEquals("compact", s.commands.single().name)
        assertEquals("opus", s.models.single().value)
        assertEquals("claude-opus-5-5", s.reportedModel)
        assertEquals("high", s.effort)
        assertEquals(120, s.earlier)
        assertTrue(s.earlierLoading)
        assertTrue(s.canRewind)
        assertTrue(s.steering)
        assertTrue(s.blocked)
        assertTrue(s.process)
        assertTrue(s.rewinding)
        assertEquals(1_700_000_001_000L, s.lastActivity)
        assertTrue(s.modelFromSession)
        assertEquals("h-7", s.hostKey)
        assertEquals(
            TodoList(
                listOf(
                    TodoEntry("1", "跑测试", "正在跑测试", TodoStatus.InProgress),
                    TodoEntry("2", "提交", "提交", TodoStatus.Pending),
                ),
            ),
            s.todos,
        )
    }

    /**
     * 核心自己说的话带种类码（docs/i18n.md「核心种类码清单」）：状态行、确认卡的通知、「以后不再询问」各条、后台子代理的兜底叫法、
     * 任务清单的兜底标题、一轮的结局、读后台输出的出错都读出码，按界面语言出字；没有码的照核心附带的文字。
     */
    @Test
    fun stateDecodesTheKindCodesAndSaysThemInTheUiLanguage() {
        val conv = opened()
        push(
            """{"type":"conv.state","conv":"k1",
            "status":"请求出错，正在重试（第 2 次）","status_code":"statusRetrying","status_args":{"attempt":2},
            "prompts":[{"kind":"permission","request_id":"r1","tool_name":"Bash","input":{},
              "suggestion_lines":["这个对话里自动接受改动","原文"],
              "suggestion_msgs":[{"code":"suggestMode","args":{"mode":"acceptEdits"}},{"code":"suggestFromTheFuture"}],
              "agent_id":"a1","agent_label":"后台子代理","agent_label_code":"promptBackgroundSubagent",
              "notice":"Claude 想用 Bash","notice_code":"promptTool","notice_args":{"tool":"Bash"}}],
            "todos":[{"key":"3","content":"任务 #3","active_form":"任务 #3","status":"in_progress","content_code":"todoUntitled"},
              {"key":"4","content":"提交","active_form":"正在提交","status":"pending"}]}""",
        )
        push(
            """{"type":"conv.turn","conv":"k1","phase":"finished","outcome":"done","text":"已完成","code":"turnDone"}""",
        )
        val s = conv.view.state
        val permission = s.prompts.single() as PermissionRequest
        val todos = s.todos!!.entries
        assertEquals("请求出错，正在重试（第 2 次）", s.statusSaid())
        UiLanguage.current = UiLanguage.ENGLISH
        try {
            assertEquals("Request failed, retrying (attempt 2)", s.statusSaid())
            assertEquals("Claude wants to use Bash", permission.noticeSaid())
            assertEquals(
                listOf("Auto-accept edits in this conversation", "原文"),
                permission.suggestionLinesSaid(),
            )
            assertEquals("Background subagent", permission.agentLabelSaid())
            assertEquals("Task #3", todos[0].contentSaid())
            assertEquals("Task #3", todos[0].activeFormSaid())
            assertEquals("提交", todos[1].contentSaid())
            assertEquals("正在提交", todos[1].activeFormSaid())
            assertEquals("Done", events.turns.last().said())
            assertEquals(
                "Couldn't read the output",
                TaskOutput("", 0, false, "没有读到输出", "taskNoOutput").errorSaid(),
            )
        } finally {
            UiLanguage.current = UiLanguage.SOURCE
        }
        // 条数对不上时照文字
        assertEquals(
            listOf("a", "b", "c"),
            permission.copy(suggestionLines = listOf("a", "b", "c")).suggestionLinesSaid(),
        )
    }

    @Test
    fun itemsReplaceFromAt() {
        val conv = opened()
        val steps =
            listOf(
                // 流式回答：换掉最后一项
                """{"at":1,"remove":1,"items":[{"kind":"answer","id":"b2","text":"在呢","streaming":true}]}""" to
                    "a,b2",
                // 接着加一项
                """{"at":2,"remove":0,"items":[{"kind":"work","id":"c","steps":[]}]}""" to "a,b2,c",
                // 往前载入更早的：在开头插入
                """{"at":0,"remove":0,"items":[{"kind":"user","id":"z"}]}""" to "z,a,b2,c",
                // 删掉中间两项
                """{"at":1,"remove":2,"items":[]}""" to "z,c",
                // 整份
                """{"full":true,"items":[{"kind":"user","id":"x"}]}""" to "x",
            )
        for ((change, want) in steps) {
            push("""{"type":"conv.items","conv":"k1",""" + change.drop(1))
            assertEquals(change, want, ids(conv.view.items))
        }
        assertEquals(steps.size, events.changes.size)
        push(
            """{"type":"conv.items","conv":"k1","at":1,"remove":0,"items":[{"kind":"answer","id":"y","text":"流"}]}""",
        )
        assertEquals(AnswerItem("y", "流", false), conv.view.items[1])
    }

    @Test
    fun itemsOutOfRangeReopensForAFullCopy() {
        val conv = opened()
        push("""{"type":"conv.state","conv":"k1","session":"s2"}""")
        link.onCall = { call ->
            when (call.method) {
                // 关掉之前又来了一次替换：在等整份，不套
                "conv.close" ->
                    push(
                        """{"type":"conv.items","conv":"k1","at":0,"remove":0,"items":[{"kind":"user","id":"late"}]}""",
                    )
                "conv.open" ->
                    push(
                        """{"type":"conv.items","conv":"k1","full":true,"items":[{"kind":"user","id":"fresh"}]}""",
                    )
            }
        }
        push("""{"type":"conv.items","conv":"k1","at":1,"remove":5,"items":[]}""")
        assertEquals(Call("conv.close", obj("""{"conv":"k1"}""")), link.next())
        val open = link.next()
        assertEquals("conv.open", open.method)
        // 会话号换过：接最新的，原来的会话记录不再带
        assertEquals("s2", open.params.str("session"))
        assertFalse("file" in open.params)
        waitFor { ids(conv.view.items) == "fresh" && conv.view.connected }
    }

    @Test
    fun turnsAndPhonePushesGoToTheirCallbacks() {
        opened()
        push("""{"type":"conv.turn","conv":"k1","phase":"started"}""")
        push(
            """{"type":"conv.turn","conv":"k1","phase":"finished","outcome":"done","text":"已完成"}""",
        )
        assertEquals(
            listOf(ConvTurn(false), ConvTurn(true, ConvTurn.Outcome.Done, "已完成")),
            events.turns,
        )
        val mcp =
            """{"type":"conv.mcp","conv":"k1","request_id":"m1","message":{"method":"tools/call"}}"""
        push(mcp)
        push("""{"type":"conv.reply","conv":"k1","message_id":"msg1"}""")
        push("""{"type":"conv.phone_cancel","conv":"k1"}""")
        assertEquals(obj(mcp), events.phone[0])
        assertEquals(
            listOf("conv.mcp", "conv.reply", "conv.phone_cancel"),
            events.phone.map { it.str("type") },
        )
    }

    @Test
    fun otherPushesAreLeftToTheCaller() {
        opened()
        assertFalse(client.handle(obj("""{"type":"zeta_sessions","full":true}""")))
        assertFalse(client.handle(obj("""{"type":"zeta_reply","id":3,"ok":true}""")))
        // 不认识的对话名、已关掉的：算对话的推送，什么也不做
        assertTrue(client.handle(obj("""{"type":"conv.state","conv":"k9","busy":true}""")))
        assertTrue(events.changes.isEmpty())
    }

    @Test
    fun disconnectThenReconnectReopensWithTheLatestSession() {
        val conv = opened()
        push("""{"type":"conv.state","conv":"k1","session":"s2"}""") // 比如 /clear 后换了会话号
        client.disconnected()
        assertFalse(conv.view.connected)
        try {
            conv.interrupt()
            fail("断线时的请求没报错")
        } catch (e: ServeError) {
            assertEquals("和主机的连接断了，正在重连", e.message)
        }
        assertTrue(link.took().isEmpty())
        client.connected()
        val open = link.next()
        assertEquals("conv.open", open.method)
        assertEquals("k1", open.params.str("conv"))
        assertEquals("s2", open.params.str("session"))
        assertEquals(obj(SPEC_OPTIONS), open.params["options"])
        waitFor { conv.view.connected }
        conv.interrupt()
        assertEquals(Call("conv.interrupt", obj("""{"conv":"k1"}""")), link.next())
    }

    @Test
    fun reopenFailureStaysDisconnectedUntilTheNextConnection() {
        val conv = opened()
        client.disconnected()
        link.errors["conv.open"] = "核心起不来"
        client.connected()
        link.next()
        waitFor { conv.view.problem == "核心起不来" }
        assertFalse(conv.view.connected)
        link.errors.clear()
        client.disconnected()
        client.connected()
        link.next()
        waitFor { conv.view.connected && conv.view.problem == null }
    }

    /** 第一次打开还在等应答时连接报了连上：不能再开一次（核心会说名字重了）；关掉的重连后也不再打开。 */
    @Test
    fun reconnectSkipsConversationsStillOpeningAndClosedOnes() {
        val closed = opened()
        closed.close()
        link.took()
        link.onCall = { call -> if (call.method == "conv.open") client.connected() }
        client.open(SPEC)
        link.onCall = {}
        Thread.sleep(200)
        assertEquals(listOf("conv.open"), link.took().map { it.method })
    }

    /** 打开的请求发出后断过线、连接又在了：说不准落在哪条连接上，先放开同名的再开一次。 */
    @Test
    fun openAcrossADisconnectIsRedone() {
        var first = true
        link.onCall = { call ->
            if (call.method == "conv.open" && first) {
                first = false
                client.disconnected()
                client.connected()
            }
        }
        val conv = client.open(SPEC)
        assertEquals(listOf("conv.open", "conv.close", "conv.open"), link.took().map { it.method })
        assertTrue(conv.view.connected)
    }

    @Test
    fun requestsWriteTheirParameters() {
        val conv = opened()
        val cases: List<Pair<() -> Any?, Call>> =
            listOf(
                {
                    conv.send("看图", listOf(ConvImage("image/png", "AAA")), listOf("/h/a.go"))
                } to
                    call(
                        "conv.send",
                        """"text":"看图","images":[{"media_type":"image/png","data":"AAA"}],"files":["/h/a.go"]""",
                    ),
                { conv.send("好") } to call("conv.send", """"text":"好""""),
                {
                    conv.send("日报", mode = "acceptEdits", rules = obj("""{"allow":["Bash"]}"""))
                } to
                    call(
                        "conv.send",
                        """"text":"日报","mode":"acceptEdits","rules":{"allow":["Bash"]}""",
                    ),
                { conv.interrupt() } to call("conv.interrupt", ""),
                { conv.steer() } to call("conv.steer", ""),
                { conv.cancel("q1", true) } to call("conv.cancel", """"uuid":"q1","recall":true"""),
                {
                    conv.answer("r1", PermissionReply.Allow)
                } to call("conv.answer", """"request_id":"r1","reply":{"kind":"allow"}"""),
                {
                    conv.answer("r1", PermissionReply.AllowAlways)
                } to call("conv.answer", """"request_id":"r1","reply":{"kind":"allow_always"}"""),
                {
                    conv.answer("r1", PermissionReply.Deny("换个做法"))
                } to
                    call(
                        "conv.answer",
                        """"request_id":"r1","reply":{"kind":"deny","feedback":"换个做法"}""",
                    ),
                {
                    conv.answer("r1", PermissionReply.Deny(null))
                } to call("conv.answer", """"request_id":"r1","reply":{"kind":"deny"}"""),
                {
                    conv.answer("r2", PermissionReply.ApprovePlan("acceptEdits"))
                } to
                    call(
                        "conv.answer",
                        """"request_id":"r2","reply":{"kind":"approve_plan","mode":"acceptEdits"}""",
                    ),
                {
                    conv.answer(
                        "r3",
                        PermissionReply.Answer(mapOf("用哪个？" to "A"), mapOf("用哪个？" to "快")),
                    )
                } to
                    call(
                        "conv.answer",
                        """"request_id":"r3","reply":{"kind":"answer","answers":{"用哪个？":"A"},"notes":{"用哪个？":"快"}}""",
                    ),
                {
                    conv.answer("r3", PermissionReply.Clarify(mapOf("用哪个？" to "A")))
                } to
                    call(
                        "conv.answer",
                        """"request_id":"r3","reply":{"kind":"clarify","answers":{"用哪个？":"A"}}""",
                    ),
                {
                    conv.elicit("e1", "accept", mapOf("tags" to listOf("a", "b")))
                } to
                    call(
                        "conv.elicit",
                        """"request_id":"e1","action":"accept","values":{"tags":["a","b"]}""",
                    ),
                {
                    conv.elicit("e1", "decline")
                } to call("conv.elicit", """"request_id":"e1","action":"decline""""),
                { conv.setModel("opus") } to call("conv.model", """"value":"opus""""),
                { conv.setEffort(null) } to call("conv.effort", """"value":null"""),
                { conv.setMode(null) } to call("conv.mode", """"value":null"""),
                {
                    conv.setOptions(ConvOptions(autoTitle = true))
                } to call("conv.options", """"options":{"auto_title":true}"""),
                {
                    conv.preset("opus", null, "按日报的格式写")
                } to call("conv.preset", """"model":"opus","hint":"按日报的格式写""""),
                { conv.compact() } to call("conv.compact", ""),
                { conv.prepare() } to call("conv.prepare", ""),
                { conv.stopTask("t1") } to call("conv.stop_task", """"task":"t1""""),
                { conv.end() } to call("conv.end", ""),
                {
                    conv.mcpReply("m1", obj("""{"result":{}}"""))
                } to call("conv.mcp_reply", """"request_id":"m1","response":{"result":{}}"""),
                { conv.loadEarlier() } to call("conv.earlier", """"rest":false"""),
                { conv.loadEarlier(rest = true) } to call("conv.earlier", """"rest":true"""),
                {
                    conv.rewrite("u3", "改一下", files = listOf("/h/b.md"))
                } to call("conv.rewrite", """"target":"u3","text":"改一下","files":["/h/b.md"]"""),
                {
                    conv.resend("u3")
                } to call("conv.rewrite", """"target":"u3","resend":true"""),
                { conv.drop("u3") } to call("conv.drop", """"target":"u3""""),
                {
                    conv.restoreCode("u3", drop = true)
                } to call("conv.restore_code", """"target":"u3","drop":true"""),
                {
                    conv.restoreCode("u3", resend = true)
                } to call("conv.restore_code", """"target":"u3","drop":false,"resend":true"""),
                {
                    conv.watchSubagent("tu1", true)
                } to call("conv.subagent", """"tool_use_id":"tu1","watch":true"""),
                {
                    conv.watchSubagent("tu1", false)
                } to call("conv.subagent", """"tool_use_id":"tu1","watch":false"""),
                {
                    conv.watchTaskOutput("t1", true)
                } to call("conv.task_output", """"task":"t1","watch":true"""),
                {
                    conv.watchTaskOutput("t1", false)
                } to call("conv.task_output", """"task":"t1","watch":false"""),
                { client.watchActivity(true) } to Call("conv.activity", obj("""{"watch":true}""")),
            )
        for ((run, want) in cases) {
            run()
            assertEquals(listOf(want), link.took())
        }
    }

    private fun call(method: String, params: String): Call =
        Call(method, obj("""{"conv":"k1"""" + (if (params.isEmpty()) "" else ",$params") + "}"))

    @Test
    fun requestResultsAndErrors() {
        val conv = opened()
        assertNull(conv.takeRecalled())
        link.results["conv.take_recalled"] =
            """{"uuid":"q1","text":"再看看","images":null,"files":null}"""
        assertEquals(ConvMessage("q1", "再看看"), conv.takeRecalled())

        link.results["conv.elicit"] = """{"errors":{"port":"要填整数","host":"必填"}}"""
        assertEquals(
            mapOf("port" to "要填整数", "host" to "必填"),
            conv.elicit("e1", "accept", mapOf("port" to listOf("x"))),
        )
        link.results.remove("conv.elicit")
        assertEquals(
            emptyMap<String, String>(),
            conv.elicit("e1", "accept", mapOf("port" to listOf("80"))),
        )

        assertNull(conv.codeChanges("u1"))
        link.results["conv.code_changes"] = """{"files":["/h/a.go"],"insertions":3,"deletions":1}"""
        assertEquals(CodeRewind.Changes(listOf("/h/a.go"), 3, 1), conv.codeChanges("u1"))

        link.results["conv.quota"] = """{"five_hour":{"utilization":12}}"""
        assertEquals(obj("""{"five_hour":{"utilization":12}}"""), conv.quota())

        // 主机负载借这条连接问 system.cpu；主机读不了 /proc/stat 时 percent 为 null
        link.results["system.cpu"] = """{"percent":37,"cores":8}"""
        assertEquals(HostCpu(37, 8), client.hostCpu())
        link.results["system.cpu"] = """{"percent":null,"cores":8}"""
        assertEquals(HostCpu(null, 8), client.hostCpu())

        // 闲置超时：读、改都借这条连接；改的时候带上 minutes
        link.results["system.idle"] = """{"minutes":30}"""
        assertEquals(30, client.idleMinutes())
        link.results["system.set_idle"] = """{"minutes":0}"""
        assertEquals(0, client.setIdleMinutes(0))
        assertEquals(
            JsonPrimitive(0),
            link.took().last { it.method == "system.set_idle" }.params["minutes"],
        )
        link.results.remove("system.idle")
        try {
            client.idleMinutes()
            fail("核心没答值却当成了一个数")
        } catch (_: ServeError) {}

        link.errors["conv.compact"] = "没有打开这个对话"
        try {
            conv.compact()
            fail("核心拒绝了却没报错")
        } catch (e: ServeError) {
            assertEquals("没有打开这个对话", e.message)
        }
    }

    @Test
    fun closeForgetsAndSends() {
        val conv = opened()
        conv.close()
        assertEquals(listOf(Call("conv.close", obj("""{"conv":"k1"}"""))), link.took())
        assertTrue(client.conversations.isEmpty())
        push("""{"type":"conv.state","conv":"k1","busy":true}""")
        assertFalse(conv.view.state.busy)
        try {
            conv.send("x")
            fail("关掉后还能发")
        } catch (_: ServeError) {}
        // 断着线时关掉不发请求
        val other = opened()
        client.disconnected()
        link.took()
        other.close()
        assertTrue(link.took().isEmpty())
    }

    @Test
    fun forkRegistersTheNewConversationBeforeTheReply() {
        val conv = opened()
        link.onCall = { call ->
            if (call.method == "conv.fork")
                push(
                    """{"type":"conv.state","conv":"${call.params.str("as")}","full":true,"title":"分支 · 修 bug","cwd":"/h/p"}""",
                )
        }
        val child = conv.fork("b")
        assertEquals(
            listOf(Call("conv.fork", obj("""{"conv":"k1","as":"k2","last":"b"}"""))),
            link.took(),
        )
        assertEquals("分支 · 修 bug", child.view.state.title)
        assertTrue(child.view.connected)
        assertEquals(listOf(conv, child), client.conversations)
        conv.fork("b", ConvOptions(phone = true))
        assertEquals(
            listOf(
                Call(
                    "conv.fork",
                    obj("""{"conv":"k1","as":"k3","last":"b","options":{"phone":true}}"""),
                ),
            ),
            link.took(),
        )
        // 新分支重连后照样重新打开，沿用原对话的设置
        client.disconnected()
        client.connected()
        val reopened = generateSequence { link.calls.poll(2, TimeUnit.SECONDS) }.take(3).toList()
        val forChild = reopened.single { it.params.str("conv") == "k2" }
        assertEquals(obj(SPEC_OPTIONS), forChild.params["options"])
        assertEquals("/h/p", forChild.params.str("cwd"))

        waitFor { client.conversations.all { it.view.connected } }
        link.errors["conv.fork"] = "这一轮剪不准"
        try {
            conv.fork("b")
            fail("分支失败没报错")
        } catch (_: ServeError) {}
        assertEquals(3, client.conversations.size)
    }

    @Test
    fun subagentPushesMergeAndOutOfRangeWatchesAgain() {
        val conv = opened()
        // 还没看着的不收
        push(subagent(full = true, items = """[{"kind":"user","id":"s0"}]"""))
        assertNull(events.subagents.poll())
        conv.watchSubagent("tu1", true)
        link.took()
        // 整份之前的替换不套
        push(subagent(at = 0, remove = 0, items = """[{"kind":"user","id":"early"}]"""))
        push(subagent(full = true, items = """[{"kind":"user","id":"s0"}]"""))
        val first = events.subagents.poll(1, TimeUnit.SECONDS)!!
        assertEquals("s0", ids(first.items))
        assertEquals("a7", first.meta!!.agentId)
        assertEquals(true, first.meta!!.background)
        // 状态只看核心给的 state，不看 outcome、running 这些
        assertEquals(SubagentState.Running, first.state)
        assertEquals(4, first.toolUses)
        assertEquals("看看测试", first.prompt)
        push(subagent(at = 1, remove = 0, items = """[{"kind":"answer","id":"s1","text":"好"}]"""))
        assertEquals("s0,s1", ids(events.subagents.poll(1, TimeUnit.SECONDS)!!.items))
        // 只有条目以外的字段变了：空的替换
        push(subagent(at = 0, remove = 0, items = "[]", state = "read_failed", failure = "权限不够"))
        val failed = events.subagents.poll(1, TimeUnit.SECONDS)!!
        assertEquals("s0,s1", ids(failed.items))
        assertEquals(SubagentState.ReadFailed, failed.state)
        assertEquals("权限不够", failed.failure)
        // 认不出的 state 照协议「都不是时」当未做完
        push(subagent(at = 0, remove = 0, items = "[]", state = "paused"))
        assertEquals(SubagentState.Unfinished, events.subagents.poll(1, TimeUnit.SECONDS)!!.state)
        push(subagent(at = 5, remove = 1, items = "[]"))
        assertEquals(
            Call("conv.subagent", obj("""{"conv":"k1","tool_use_id":"tu1","watch":true}""")),
            link.next(),
        )
        assertNull(events.subagents.poll(200, TimeUnit.MILLISECONDS))
        // 重新打开以后再看一次
        client.disconnected()
        link.took()
        client.connected()
        assertEquals("conv.open", link.next().method)
        assertEquals(
            Call("conv.subagent", obj("""{"conv":"k1","tool_use_id":"tu1","watch":true}""")),
            link.next(),
        )
    }

    @Test
    fun taskOutputPushesReachTheListenerWhileWatchedAndWatchAgainAfterReopening() {
        val conv = opened()
        val pushed =
            """{"type":"conv.task_output","conv":"k1","task":"t1","text":"ok\n","total_bytes":9000,"truncated":true}"""
        // 还没看着的不收
        push(pushed)
        assertNull(events.outputs.poll())
        conv.watchTaskOutput("t1", true)
        link.took()
        push(pushed)
        assertEquals(
            "t1" to TaskOutput("ok\n", 9000, true),
            events.outputs.poll(1, TimeUnit.SECONDS),
        )
        push(
            """{"type":"conv.task_output","conv":"k1","task":"t1","text":"","total_bytes":0,"truncated":false,"error":"没有进程"}""",
        )
        assertEquals("没有进程", events.outputs.poll(1, TimeUnit.SECONDS)!!.second.error)
        // 重新打开以后再看一次：放开对话时核心停了推送
        client.disconnected()
        link.took()
        client.connected()
        assertEquals("conv.open", link.next().method)
        assertEquals(
            Call("conv.task_output", obj("""{"conv":"k1","task":"t1","watch":true}""")),
            link.next(),
        )
        // 停了以后不收
        conv.watchTaskOutput("t1", false)
        push(pushed)
        assertNull(events.outputs.poll(200, TimeUnit.MILLISECONDS))
    }

    private fun subagent(
        full: Boolean = false,
        at: Int = 0,
        remove: Int = 0,
        items: String,
        state: String = "running",
        failure: String? = null,
    ): String =
        """{"type":"conv.subagent","conv":"k1","tool_use_id":"tu1",""" +
            (if (full) """"full":true,""" else "") +
            (if (failure != null) """"failure":"$failure",""" else "") +
            """"at":$at,"remove":$remove,"items":$items,"meta":{"agent_id":"a7","agent_type":"general-purpose",""" +
            """"finished":true,"stopped":false,"background":true},"outcome":"completed","prompt":"看看测试","tool_uses":4,""" +
            """"running":true,"loaded":true,"missing":false,"file":"/h/a.jsonl","skipped":0,"state":"$state"}"""

    @Test
    fun activityIsDecodedAndResubscribedAfterReconnecting() {
        client.watchActivity(true)
        link.took()
        push(
            """{"type":"conv.activity","sessions":{"s1":{"activity":"waiting","tasks":2,"turn_end":1700000000000},""" +
                """"s2":{},"s3":{"activity":"answering"}}}""",
        )
        assertEquals(
            mapOf(
                "s1" to SessionActivity(SessionStatus.Activity.Waiting, 2, 1_700_000_000_000),
                "s2" to SessionActivity(null),
                "s3" to SessionActivity(SessionStatus.Activity.Answering),
            ),
            events.activity.single(),
        )
        client.disconnected()
        client.connected()
        assertEquals(Call("conv.activity", obj("""{"watch":true}""")), link.next())
    }

    private fun waitFor(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!done()) {
            check(System.currentTimeMillis() < deadline) { "5 秒内没等到" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SPEC_OPTIONS =
            """{"env":{"CLAUDE_AUTOCOMPACT_PCT_OVERRIDE":"80"},"system_prompt":"补充","mcp_config":"{}",""" +
                """"phone":true,"auto_title":true,"compact_percent":80}"""

        val SPEC =
            ConvSpec(
                session = "s1",
                file = "/h/.claude/projects/-h-p/s1.jsonl",
                cwd = "/h/p",
                options =
                    ConvOptions(
                        env = mapOf("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE" to "80"),
                        systemPrompt = "补充",
                        mcpConfig = "{}",
                        phone = true,
                        autoTitle = true,
                        compactPercent = 80,
                    ),
            )

        /** 一份各字段都有的状态，写法照 helper/conv/state.go 的 stateFields 和各 Go 类型。 */
        val STATE =
            """{"type":"conv.state","conv":"k1",
            "prompts":[
              {"kind":"permission","request_id":"r1","tool_name":"AskUserQuestion","input":{"questions":[]},
               "questions":[{"question":"用哪个？","header":"选","options":[{"label":"A","description":"甲","preview":"预览"},
                 {"label":"B","description":"乙"}],"takes_notes":true}],
               "suggestions":[{"type":"addRules"}],"suggestion_lines":["以后不再问"],"agent_id":"a1","agent_label":"审查面板排版","tool_use_id":"tu9",
               "notice":"Claude 有问题要问你","asks_input":true},
              {"kind":"elicitation","request_id":"e1","server":"github","message":"填一下",
               "fields":[{"key":"tags","title":"标签","kind":"multi","choices":[{"value":"a","label":"甲"}],"default":["a"],"min":1}],
               "notice":"MCP 服务器 github 需要你填写信息","asks_input":true},
              {"kind":"later","request_id":"x"}],
            "tasks":[{"id":"t1","type":"local_bash","kind":"command","description":"跑测试","command":"go test","started_at":1000,
              "state":"running","running":true,"readable":true}],
            "usage":{"context_tokens":1000,"context_window":200000,"model":"claude-opus-5-5",
              "turns":[{"tokens":{"input":1,"output":2,"cache_read":0,"cache_write":0,"total":3},"cost_usd":0.5,"duration_ms":900}],
              "auto_compact":true,"compact_at":167000,"total":{"input":1,"output":2,"cache_read":0,"cache_write":0,"total":3},
              "total_cost":0.5,"fraction":0.005,"level":"normal"},
            "queued":[{"uuid":"q1","text":"再看看","images":[{"media_type":"image/png","data":"AAA"}],"files":["/h/a.go"]}],
            "recalled":{"uuid":"q0","text":"撤回的","images":null,"files":null},
            "mode":{"user":"plan","process":"default","shown":"plan"},
            "commands":[{"name":"compact","description":"压缩","argumentHint":""}],
            "models":[{"value":"opus","displayName":"Opus","description":"最强","resolvedModel":"claude-opus-5-5"}],
            "reported_model":"claude-opus-5-5","effort":"high","earlier":120,"earlier_loading":true,"can_rewind":true,
            "steering":true,"blocked":true,"process":true,
            "rewinding":true,"last_activity":1700000001000,"model_from_session":true,"host_key":"h-7",
            "todos":[{"key":"1","content":"跑测试","active_form":"正在跑测试","status":"in_progress"},
              {"key":"2","content":"提交","active_form":"提交","status":"pending"}]}"""
    }
}
