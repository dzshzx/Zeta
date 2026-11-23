package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Zeta 不在前台时，当前对话闲着就在 [ConversationHub.RETIRE_DELAY_MS] 后放开（进程多久结束由主机的闲置超时定）；
 * 看着时不放，到点时又回来看着了的不放，还在回答的等这一轮结束再算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IdleRetireTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val limit = ConversationHub.RETIRE_DELAY_MS

    private fun elapse(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private lateinit var core: FakeCore
    private lateinit var conv: String

    /** 当前对话在核心里打开着、接着进程。 */
    private fun liveCurrent(hub: ConversationHub) {
        core = FakeCore().install(hub)
        hub.current.prepare()
        conv = core.next("conv.open").conv
        core.state(conv, """"process":true""")
        awaitCore { hub.current.isProcessAlive }
    }

    /** 核心推来忙闲。 */
    private fun busy(hub: ConversationHub, busy: Boolean) {
        core.state(conv, """"busy":$busy""")
        awaitCore { hub.current.busy == busy }
    }

    @Test
    fun idleCurrentIsReleasedSoonAfterZetaLeavesTheScreen() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        liveCurrent(hub)
        elapse(limit * 2)
        assertTrue("看着时不放", hub.current.isProcessAlive)

        hub.foreground = false
        elapse(limit - 1_000)
        hub.foreground = true
        elapse(2_000)
        assertTrue("到点时又回来看着了，不放", hub.current.isProcessAlive)

        hub.foreground = false
        elapse(limit - 1_000)
        assertTrue("切出去一会儿还拿着", hub.current.isProcessAlive)
        elapse(1_001)
        assertFalse("切到后台、闲着，几秒后就放开", hub.current.isProcessAlive)
    }

    @Test
    fun answeringCurrentWaitsForTheTurnToEnd() {
        val hub = Fixtures.readyHub(app)
        liveCurrent(hub)
        busy(hub, true)
        hub.foreground = false
        elapse(limit * 2)
        assertTrue("还在回答的不放", hub.current.isProcessAlive)

        busy(hub, false)
        hub.onTurnFinished(hub.current)
        elapse(limit - 1_000)
        assertTrue("从这一轮结束时算起", hub.current.isProcessAlive)
        elapse(1_001)
        assertFalse(hub.current.isProcessAlive)
    }
}
