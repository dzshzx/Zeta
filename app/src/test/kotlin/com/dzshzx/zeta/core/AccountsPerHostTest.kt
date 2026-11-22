package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 账号和额度各台设备各有一份：各台登录的可能是不同的账号，一台的额度、换账号不影响另一台。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AccountsPerHostTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote = Fixtures.workstation.id

    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.settings.remoteHosts = listOf(Fixtures.workstation) }

    /** 那台核心推来的额度（quota.snapshot 里的 snapshot）。 */
    private fun snapshot(subscription: String) =
        Json.parseToJsonElement(
            """{"windows":[{"kind":"session","group":"session","used":0.42,"severity":"normal","label":"5 小时"}],
            "subscription":"$subscription","updatedAt":"2026-11-18T07:00:00Z"}""",
        ) as JsonObject

    @Test
    fun eachHostKeepsItsOwnQuota() {
        val hub = hub()
        assertSame(hub.quota(remote), hub.quota(remote))
        assertNotSame(hub.quota(Backends.LOCAL), hub.quota(remote))
        assertNotSame(hub.accounts(Backends.LOCAL), hub.accounts(remote))
        hub.quota(Backends.LOCAL).forget()
        hub.quota(remote).forget()
        hub.quota(Backends.LOCAL).received(snapshot("max"))
        hub.quota(remote).received(snapshot("team"))
        assertEquals("max", hub.quota(Backends.LOCAL).snapshot?.subscription)
        assertEquals("team", hub.quota(remote).snapshot?.subscription)
        // 存下来的也分开：重开应用后各读各的
        val reopened = hub()
        assertEquals("max", reopened.quota(Backends.LOCAL).snapshot?.subscription)
        assertEquals("team", reopened.quota(remote).snapshot?.subscription)
        // 一台换了账号，只清它的
        reopened.quota(remote).forget()
        assertNull(reopened.quota(remote).snapshot)
        assertEquals("max", hub().quota(Backends.LOCAL).snapshot?.subscription)
        // 核心说在用的账号还没有数：存着的也清掉
        reopened.quota(Backends.LOCAL).received(null)
        assertNull(hub().quota(Backends.LOCAL).snapshot)
    }
}
