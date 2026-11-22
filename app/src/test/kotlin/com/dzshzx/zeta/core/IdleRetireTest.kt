package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Zeta 不在前台时，当前对话闲着满 [ConversationHub.IDLE_RETIRE_MS] 就收掉背后的 Claude Code 进程；
 * 看着时不收，回到前台重新计时，还在回答的等这一轮结束再计时。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class IdleRetireTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val limit = ConversationHub.IDLE_RETIRE_MS

    @Before fun reset() = Fixtures.install(null)

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
    fun idleCurrentRetiresAfterZetaLeavesTheScreen() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        liveCurrent(hub)
        elapse(limit * 2)
        assertTrue("看着时不收", hub.current.isProcessAlive)

        hub.foreground = false
        elapse(limit - 60_000)
        hub.foreground = true
        hub.foreground = false
        elapse(limit - 60_000)
        assertTrue("回到前台看过一眼，重新计时", hub.current.isProcessAlive)
        elapse(60_001)
        assertFalse("切到后台闲满十分钟就收掉", hub.current.isProcessAlive)
    }

    @Test
    fun answeringCurrentWaitsForTheTurnToEnd() {
        val hub = Fixtures.readyHub(app)
        liveCurrent(hub)
        busy(hub, true)
        hub.foreground = false
        elapse(limit * 2)
        assertTrue("还在回答的不收", hub.current.isProcessAlive)

        busy(hub, false)
        hub.onTurnFinished(hub.current)
        elapse(limit - 60_000)
        assertTrue("从这一轮结束时算起", hub.current.isProcessAlive)
        elapse(60_001)
        assertFalse(hub.current.isProcessAlive)
    }
}
