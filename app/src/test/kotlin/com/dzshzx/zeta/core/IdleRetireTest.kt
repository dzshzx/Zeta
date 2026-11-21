package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
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

    @Test
    fun idleCurrentRetiresAfterZetaLeavesTheScreen() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = true
        hub.current.attachLiveProcess(hub)
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
        hub.current.attachLiveProcess(hub)
        hub.current.force("busy", true)
        hub.foreground = false
        elapse(limit * 2)
        assertTrue("还在回答的不收", hub.current.isProcessAlive)

        hub.current.force("busy", false)
        hub.onTurnFinished(hub.current)
        elapse(limit - 60_000)
        assertTrue("从这一轮结束时算起", hub.current.isProcessAlive)
        elapse(60_001)
        assertFalse(hub.current.isProcessAlive)
    }
}
