package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 新对话里选的模型、思考强度和权限模式不只交给核心，这边也记着，每次 conv.open 都带上（choices）：选的那次请求没送到、
 * 核心里那份没了再打开、还没开始的新对话被重建时，第一条消息照样按选的跑，不按默认的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class NewChatChoicesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun conversation(hub: ConversationHub) =
        Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)

    private fun FakeCore.Call.choice(key: String): String? =
        (params["choices"] as? JsonObject)?.get(key)?.jsonPrimitive?.content

    /** 刚打开 Zeta 时选模型，第一次连主机没连上（选的那次失败了），紧接着发消息：发消息时打开对话带上选的模型。 */
    @Test
    fun choiceMadeWhileTheFirstOpenFailsReachesTheCore() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        var opens = 0
        core.respond = { call ->
            if (call.method == "conv.open" && opens++ == 0) throw ServeError("连不上")
            core.defaultReply(call)
        }
        val chat = conversation(hub)
        chat.chooseModel("sonnet")
        core.next("conv.open")
        chat.send("你好")
        val open = core.next("conv.open")
        assertEquals("sonnet", open.choice("model"))
        assertEquals("你好", core.next("conv.send").str("text"))
    }

    /** 断线期间核心收掉了这个还没发过消息的对话：重连时按 key 打不开，新开的那次带上选过的。 */
    @Test
    fun reopenAfterTheCoreDroppedItCarriesTheChoices() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = conversation(hub)
        chat.chooseModel("sonnet")
        core.next("conv.model")
        // 打开以后才选的：打开时的参数里没有，重开时也要带上
        chat.chooseEffort("low")
        chat.choosePermissionMode("plan")
        core.next("conv.mode")
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.respond = { call ->
            if (call.method == "conv.open" && call.str("key") != null)
                throw ServeError("不在了", "conversation_gone")
            core.defaultReply(call)
        }
        core.drop()
        awaitCore { chat.offline }
        core.reconnect()
        assertNotNull(core.next("conv.open").str("key"))
        val fresh = core.next("conv.open")
        assertNull(fresh.str("key"))
        assertEquals("sonnet", fresh.choice("model"))
        assertEquals("low", fresh.choice("effort"))
        assertEquals("plan", fresh.choice("mode"))
    }

    /** 核心交班的那一下：选模型的请求被旧核心答「对话已关闭」，接着连接断开；重连后新核心里没有它，新开的那次带上选的模型。 */
    @Test
    fun choiceRejectedDuringHandoverReachesTheNewCore() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = conversation(hub)
        chat.prepare()
        core.next("conv.prepare")
        awaitMain { chat.view.state.key.isNotEmpty() }
        core.respond = { call ->
            when {
                call.method == "conv.model" -> throw ServeError("对话已关闭")
                call.method == "conv.open" && call.str("key") != null ->
                    throw ServeError("不在了", "conversation_gone")
                else -> core.defaultReply(call)
            }
        }
        chat.chooseModel("sonnet")
        core.next("conv.model")
        core.drop()
        awaitCore { chat.offline }
        core.reconnect()
        assertNotNull(core.next("conv.open").str("key"))
        assertEquals("sonnet", core.next("conv.open").choice("model"))
    }

    /** 选回默认也要记着：模型写 default，思考强度写空串，不然核心按更早送到的那次留着。 */
    @Test
    fun choosingTheDefaultIsRemembered() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val chat = conversation(hub)
        chat.chooseModel("sonnet")
        chat.chooseEffort("low")
        chat.chooseModel(null)
        chat.chooseEffort(null)
        core.next("conv.effort")
        core.next("conv.effort")
        chat.leave()
        core.next("conv.close")
        chat.send("你好")
        val open = core.next("conv.open")
        assertEquals("default", open.choice("model"))
        assertEquals("", open.choice("effort"))
    }

    /** 环境就绪、换了默认工作目录时，还没开始的新对话按新目录重建：用户已经选的模型跟着过去。 */
    @Test
    fun renewedBlankKeepsTheChoices() {
        val hub = Fixtures.readyHub(app)
        val core = FakeCore().install(hub)
        val before = hub.current
        before.chooseModel("sonnet")
        core.next("conv.model")
        hub.renewBlank()
        assertNotSame(before, hub.current)
        hub.current.send("你好")
        assertEquals("sonnet", core.next("conv.open").choice("model"))
    }
}
