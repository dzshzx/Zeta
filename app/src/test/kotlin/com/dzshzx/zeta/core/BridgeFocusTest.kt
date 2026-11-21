package com.dzshzx.zeta.core

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.phone.OverlayFocus
import com.dzshzx.zeta.phone.OverlayPhase
import com.dzshzx.zeta.shots.Fixtures
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 外部客户端在顶部胶囊上（[BridgeFocus]）：有调用在处理时显示，最后一个结束后停留 [BridgeFocus.LINGER_MS] 再收起，停留期间又有调用就重新计时；
 * 暂停着一直显示；停止随即收起；和对话一起在用手机时照胶囊的规则挑。真的悬浮窗口在设备上验（tools/phone-tests/bridge-capsule.sh）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class BridgeFocusTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() = Fixtures.install(null)

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun ConversationHub.client(key: String = "workstation"): BridgeFocus {
        openBridged(key) { false }
        return bridgeFoci.single { it.key == key }
    }

    @Test
    fun showsWhileCallingThenLingers() {
        val hub = Fixtures.readyHub(app)
        val client = hub.client()
        assertNull("连上还没调用：不显示", hub.alerts.overlayFocus)
        hub.onBridgeCall("workstation", started = true)
        val focus = hub.alerts.overlayFocus
        assertSame(client, focus)
        focus!!
        assertEquals("电脑在操作", focus.title)
        assertEquals("电脑在操作", focus.thought)
        assertEquals(OverlayPhase.RUNNING, focus.phase)
        assertFalse("外部客户端不能补充、不能在 Zeta 里打开", focus.inZeta)
        hub.onBridgeCall("workstation", started = false)
        assertSame("调用之间的空档里还在", client, hub.alerts.overlayFocus)
        assertEquals("等待下一步", focus.step)
        idle(BridgeFocus.LINGER_MS - 1_000)
        assertSame(client, hub.alerts.overlayFocus)
        idle(2_000)
        assertNull("停留满了收起", hub.alerts.overlayFocus)
    }

    @Test
    fun newCallRestartsTheLinger() {
        val hub = Fixtures.readyHub(app)
        val client = hub.client()
        hub.onBridgeCall("workstation", true)
        hub.onBridgeCall("workstation", false)
        idle(20_000)
        hub.onBridgeCall("workstation", true)
        hub.onBridgeCall("workstation", false)
        idle(20_000)
        assertSame("从最后一个调用结束算", client, hub.alerts.overlayFocus)
        idle(11_000)
        assertNull(hub.alerts.overlayFocus)
    }

    @Test
    fun twoCallsAtOnceLingerAfterTheLast() {
        val hub = Fixtures.readyHub(app)
        val client = hub.client()
        hub.onBridgeCall("workstation", true)
        hub.onBridgeCall("workstation", true)
        hub.onBridgeCall("workstation", false)
        idle(BridgeFocus.LINGER_MS + 1_000)
        assertSame("还有一个在处理", client, hub.alerts.overlayFocus)
        hub.onBridgeCall("workstation", false)
        idle(BridgeFocus.LINGER_MS + 1_000)
        assertNull(hub.alerts.overlayFocus)
    }

    @Test
    fun pausedStaysUntilResumedOrStopped() {
        val hub = Fixtures.readyHub(app)
        val client = hub.client()
        hub.onBridgeCall("workstation", true)
        client.pause()
        hub.onBridgeCall("workstation", false)
        idle(BridgeFocus.LINGER_MS + 1_000)
        assertSame("暂停着：下一个调用会卡在闸门前，要一直看得到", client, hub.alerts.overlayFocus)
        assertEquals(OverlayPhase.PAUSED, client.phase)
        assertEquals("已暂停", client.step)
        client.resume()
        assertNull("继续了、又没有调用：收起", hub.alerts.overlayFocus)

        hub.onBridgeCall("workstation", true)
        hub.onBridgeCall("workstation", false)
        client.pause()
        client.stop()
        assertNull("停止：暂停解除、不再停留", hub.alerts.overlayFocus)
        assertFalse(client.phase.held)
    }

    /** 对话（暂停着，算在用手机）和外部客户端同时在：取最近用手机的；外部客户端收起后回到对话。 */
    @Test
    fun sharesTheCapsuleWithConversations() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        val client = hub.client()
        hub.current.phone.pauseByUser()
        assertSame(hub.current.overlay, hub.alerts.overlayFocus)
        hub.onBridgeCall("workstation", true)
        assertSame("外部客户端刚调用过，比对话近", client, hub.alerts.overlayFocus)
        hub.onBridgeCall("workstation", false)
        idle(BridgeFocus.LINGER_MS + 1_000)
        assertSame(hub.current.overlay, hub.alerts.overlayFocus)
        hub.current.phone.resumeByUser()
    }

    /** 几个客户端各是各的。 */
    @Test
    fun eachClientHasItsOwnFocus() {
        val hub = Fixtures.readyHub(app)
        val a = hub.client("a")
        val b = hub.client("b")
        hub.client("a")
        assertEquals("同一个标识重连不另建", 2, hub.bridgeFoci.size)
        hub.onBridgeCall("a", true)
        assertSame(a, hub.alerts.overlayFocus)
        Thread.sleep(2)
        hub.onBridgeCall("b", true)
        assertSame("最近调用的那个", b, hub.alerts.overlayFocus)
        hub.onBridgeCall("unknown", true)
        assertSame("没连过的标识不算", b, hub.alerts.overlayFocus)
    }

    /** 挑法：接管着的优先，其余取最近用手机的。 */
    @Test
    fun choosePrefersControllingThenRecent() {
        val hub = Fixtures.readyHub(app)
        val older = hub.current.overlay
        val newer = hub.client()
        fun candidate(focus: OverlayFocus, controlling: Boolean, at: Long) =
            TurnAlerts.FocusCandidate(focus, controlling, at)
        assertNull(TurnAlerts.choose(emptyList()))
        assertSame(
            newer,
            TurnAlerts.choose(listOf(candidate(older, false, 1), candidate(newer, false, 2))),
        )
        assertSame(
            older,
            TurnAlerts.choose(listOf(candidate(older, true, 1), candidate(newer, false, 2))),
        )
    }
}
