package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Conversation] 是核心里那个对话的镜像（docs/core.md「对话」）：用户的操作换成 conv.* 请求，核心推来的状态、条目、一轮起止照到界面上。 连的是假的 serve
 * 连接（[FakeCore]），喂的是照协议写的推送。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ConversationCoreTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() = Fixtures.install(null)

    private fun newChat(hub: ConversationHub, backend: String = Backends.LOCAL) =
        Conversation(
            hub,
            "/workspace/claude",
            sessionId = null,
            title = "",
            historyFile = null,
            backend = backend,
        )

    /** 第一条消息先打开（带手机工具的设置，本机不开桌面窗口），再发；核心给了会话号、消息排着队，照着显示，撤回走 conv.cancel。 */
    @Test
    fun sendOpensThenQueues() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        core.newSession = "s-new"
        val chat = newChat(hub)
        chat.send("你好")
        assertTrue("发出去还没答时就算在回答", chat.busy)
        val open = core.next("conv.open")
        assertNull(open.params["session"])
        val options = open.params["options"]!!.jsonObject
        assertEquals(JsonPrimitive(true), options["phone"])
        assertNotNull("手机工具的 MCP 设置和起进程时一样带上", options["mcp_config"])
        assertNull("本机不开桌面窗口", options["desk"])
        val send = core.next("conv.send")
        assertEquals(open.conv, send.conv)
        assertEquals("你好", send.str("text"))

        core.state(
            send.conv,
            """"busy":true,"queued":[{"uuid":"q1","text":"再看看","images":null,"files":null}]""",
        )
        awaitCore { chat.queued.isNotEmpty() }
        assertEquals("s-new", chat.sessionId)
        assertEquals("再看看", chat.queued.single().text)
        assertTrue(chat.busy)
        chat.cancelQueued("q1", recall = true)
        val cancel = core.next("conv.cancel")
        assertEquals("q1", cancel.str("uuid"))
        assertEquals(JsonPrimitive(true), cancel.params["recall"])
    }

    /** 核心推来的确认弹给用户，回答走 conv.answer；核心收走后不再弹。 */
    @Test
    fun confirmationsRoundTrip() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = newChat(hub)
        chat.prepare()
        val conv = core.next("conv.open").conv
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.state(
            conv,
            """"busy":true,"prompts":[{"kind":"permission","request_id":"r1","tool_name":"Bash",""" +
                """"input":{"command":"ls"},"suggestion_lines":["允许 Bash(ls)"],"notice":"Claude 想运行命令"}]""",
        )
        awaitCore { chat.pending != null }
        val request = chat.pending as PermissionRequest
        assertEquals(listOf("允许 Bash(ls)"), request.suggestionLines)
        assertEquals("Claude 想运行命令", request.notice)
        assertTrue(chat.active)
        chat.reply(request, PermissionReply.AllowAlways)
        val answer = core.next("conv.answer")
        assertEquals("r1", answer.str("request_id"))
        assertEquals("allow_always", answer.params["reply"]!!.jsonObject.str("kind"))
        core.state(conv, """"prompts":[]""")
        awaitCore { chat.pending == null }
    }

    /** 流式回答：先整份，再一次次替换最后一项。 */
    @Test
    fun streamingItemsAreSpliced() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = newChat(hub)
        chat.prepare()
        val conv = core.next("conv.open").conv
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.push(
            """{"type":"conv.items","conv":"$conv","full":true,"items":[""" +
                """{"kind":"user","id":"u1","text":"讲个笑话"},{"kind":"answer","id":"a1","text":"从前","streaming":true}]}""",
        )
        awaitCore { chat.items.size == 2 }
        core.push(
            """{"type":"conv.items","conv":"$conv","at":1,"remove":1,"items":[""" +
                """{"kind":"answer","id":"a1","text":"从前有座山","streaming":false}]}""",
        )
        awaitCore { (chat.items.lastOrNull() as? AnswerItem)?.text == "从前有座山" }
        assertEquals(listOf("u1", "a1"), chat.items.map { it.id })
        assertFalse((chat.items[1] as AnswerItem).streaming)
    }

    /** 一轮的起止：started 时在回答；finished 时闲下来，不在眼前的对话记成有新回答。 */
    @Test
    fun turnStartAndEnd() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = Conversation(hub, "/workspace/claude", "s-bg", "后台的", historyFile = null)
        hub.park(chat)
        val conv = core.next("conv.open").conv
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.push("""{"type":"conv.turn","conv":"$conv","phase":"started"}""")
        awaitMain { chat.busy }
        core.state(conv, """"busy":false""")
        core.push(
            """{"type":"conv.turn","conv":"$conv","phase":"finished","outcome":"done","text":"已完成"}""",
        )
        awaitMain { !chat.busy }
        assertEquals(SessionStatus.Activity.Unread, hub.sessionStatus("s-bg")?.activity)
    }

    /** 断线时显示正在重连；连回来按核心给的 key（和会话号）重新打开。 */
    @Test
    fun reconnectReopensByKey() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = Conversation(hub, "/workspace/claude", "s-old", "旧对话", historyFile = null)
        val first = core.next("conv.open")
        assertEquals("s-old", first.str("session"))
        awaitMain { chat.view.state.key.isNotEmpty() }
        val key = chat.view.state.key

        core.drop("网络不通")
        awaitCore { chat.offline }
        assertTrue(chat.status.orEmpty().contains("正在重连"))

        core.reconnect()
        val again = core.next("conv.open")
        assertEquals(key, again.str("key"))
        assertEquals("s-old", again.str("session"))
        assertEquals(first.conv, again.conv)
        awaitCore { !chat.offline }
    }

    /** 手机工具：核心转来 Claude Code 的 MCP 消息（conv.mcp），手机工具处理完用 conv.mcp_reply 答回去。 */
    @Test
    fun phoneToolsRoundTrip() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = newChat(hub)
        assertNotNull(chat.phoneSession)
        chat.prepare()
        val conv = core.next("conv.open").conv
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.push(
            """{"type":"conv.mcp","conv":"$conv","request_id":"m1",""" +
                """"message":{"jsonrpc":"2.0","id":7,"method":"tools/list"}}""",
        )
        val reply = core.next("conv.mcp_reply")
        assertEquals(conv, reply.conv)
        assertEquals("m1", reply.str("request_id"))
        val response = reply.params["response"] as JsonObject
        assertEquals(JsonPrimitive(7), response["id"])
        assertTrue(response["result"]!!.jsonObject["tools"]!!.jsonArray.isNotEmpty())
    }

    /** 远程主机上的对话同样带手机工具，另开桌面窗口（tmux 里接着用）。 */
    @Test
    fun remoteConversationOpensDeskWindow() {
        val hub = Fixtures.readyHub(app)
        val remote = Fixtures.workstation
        hub.updateRemoteHosts(listOf(remote))
        val core = FakeCore().install(hub)
        val chat = newChat(hub, backend = remote.id)
        chat.prepare()
        val options = core.next("conv.open").params["options"]!!.jsonObject
        assertEquals(JsonPrimitive(true), options["desk"])
        assertEquals(JsonPrimitive(true), options["phone"])
    }
}
