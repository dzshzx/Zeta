package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.ui.pane.combinedOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 侧栏列全部设备的对话（设备胶囊里的「全部设备」）：各台的列表合在一起、各读各的；选回一台就只列那台。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AllHostsListTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote = Fixtures.workstation.id

    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.settings.remoteHosts = listOf(Fixtures.workstation) }

    private fun session(id: String, backend: String, updated: Long) =
        SessionSummary(id, "/p/$id.jsonl", "/w", id, "", updated, backend = backend)

    @Test
    fun listsOfAllHostsAreMergedByTime() {
        val hub = hub()
        assertEquals(listOf(Backends.LOCAL), hub.listedBackends)
        hub.settings.paneAll = true
        assertTrue(hub.allHosts)
        assertEquals(listOf(Backends.LOCAL, remote), hub.listedBackends)

        val list = hub.sessionList
        list.listedOn(
            Backends.LOCAL,
            listOf(session("p2", Backends.LOCAL, 40), session("p1", Backends.LOCAL, 10)),
        )
        list.listedOn(remote, listOf(session("w2", remote, 30), session("w1", remote, 20)))
        assertEquals(listOf("p2", "w2", "w1", "p1"), list.sessions.map { it.id })
        // 重读其中一台：只换它的，另一台的留着
        list.listedOn(remote, listOf(session("w3", remote, 50)))
        assertEquals(listOf("w3", "p2", "p1"), list.sessions.map { it.id })
    }

    /** 一台推来的变化（zeta-helper serve）：只换、只删那台的项，别的主机上同 id 的不动，照时间排。 */
    @Test
    fun pushedChangesTouchOnlyTheirHost() {
        val hub = hub()
        hub.settings.paneAll = true
        val list = hub.sessionList
        list.received(
            Backends.LOCAL,
            true,
            listOf(session("p2", Backends.LOCAL, 40), session("p1", Backends.LOCAL, 10)),
            emptyList(),
        )
        list.received(
            remote,
            true,
            listOf(session("w1", remote, 20), session("p1", remote, 5)),
            emptyList(),
        )
        assertEquals(listOf("p2", "w1", "p1", "p1"), list.sessions.map { it.id })
        list.received(
            Backends.LOCAL,
            false,
            listOf(session("p1", Backends.LOCAL, 60)),
            listOf("p2"),
        )
        assertEquals(listOf("p1", "w1", "p1"), list.sessions.map { it.id })
        assertEquals(listOf(Backends.LOCAL, remote, remote), list.sessions.map { it.backend })
    }

    @Test
    fun choosingOneHostLeavesAllHosts() {
        val hub = hub()
        hub.settings.paneAll = true
        hub.sessionList.listedOn(remote, listOf(session("w1", remote, 20)))
        // 新对话空白页上的胶囊只换新对话开在哪台，列表照旧列全部
        hub.selectBackend(remote)
        assertTrue(hub.allHosts)
        assertEquals(remote, hub.backend)
        assertEquals(listOf("w1"), hub.sessionList.sessions.map { it.id })
        // 侧栏的胶囊选一台：只列那台，别的主机的那些不留
        hub.listOnly(Backends.LOCAL)
        assertFalse(hub.allHosts)
        assertEquals(listOf(Backends.LOCAL), hub.listedBackends)
        assertTrue(hub.sessionList.sessions.isEmpty())
        // 选的就是侧栏正看着的那台：它已经读到的那些留着
        hub.settings.paneAll = true
        hub.sessionList.listedOn(Backends.LOCAL, listOf(session("p1", Backends.LOCAL, 10)))
        hub.sessionList.listedOn(remote, listOf(session("w1", remote, 20)))
        hub.listOnly(Backends.LOCAL)
        assertEquals(listOf("p1"), hub.sessionList.sessions.map { it.id })
    }

    /** 没有远程设备了，存着的「全部设备」不算数。 */
    @Test
    fun allHostsNeedsARemoteHost() {
        val hub = Fixtures.readyHub(app)
        hub.settings.paneAll = true
        assertFalse(hub.allHosts)
    }

    @Test
    fun searchOutcomesCombine() {
        assertNull(combinedOutcome(listOf(null, null)))
        assertEquals(true, combinedOutcome(listOf(true, true)))
        assertEquals(false, combinedOutcome(listOf(true, false)))
        assertEquals(false, combinedOutcome(listOf(true, null)))
    }
}
