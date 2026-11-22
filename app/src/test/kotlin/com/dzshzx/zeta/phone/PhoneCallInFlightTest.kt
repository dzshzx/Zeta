package com.dzshzx.zeta.phone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.FakeCore
import com.dzshzx.zeta.core.phone
import com.dzshzx.zeta.core.phoneCallRunning
import com.dzshzx.zeta.shots.Fixtures
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 应用内更新只等正在处理的手机工具调用：对话在不在回答不算。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PhoneCallInFlightTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private val call =
        Json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"screen","arguments":{}}}""",
        ) as JsonObject

    @Test
    fun onlyACallInFlightCounts() {
        val hub = Fixtures.readyHub(app)
        FakeCore().install(hub)
        val chat =
            Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        hub.park(chat)
        chat.send("先问一句")
        assertTrue(chat.busy)
        assertFalse("对话在回答，没有手机工具调用", hub.phoneCallRunning())

        // 暂停着：调用停在闸门前，算正在处理
        chat.phone.pauseByUser()
        val worker = thread { chat.phone.handle(call) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!hub.phoneCallRunning() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(hub.phoneCallRunning())

        chat.phone.cancelWaiting()
        worker.join(5_000)
        assertFalse(hub.phoneCallRunning())
    }
}
