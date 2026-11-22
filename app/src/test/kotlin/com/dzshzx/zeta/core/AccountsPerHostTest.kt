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

    private fun usage(subscription: String) =
        Json.parseToJsonElement(
            """{"response":{"subscription_type":"$subscription","rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":42.0,"resets_at":null}}}}""",
        ) as JsonObject

    @Test
    fun eachHostKeepsItsOwnQuota() {
        val hub = hub()
        assertSame(hub.quota(remote), hub.quota(remote))
        assertNotSame(hub.quota(Backends.LOCAL), hub.quota(remote))
        assertNotSame(hub.accounts(Backends.LOCAL), hub.accounts(remote))
        hub.quota(Backends.LOCAL).forget()
        hub.quota(remote).forget()
        hub.quota(Backends.LOCAL).usageAnswered(usage("max"))
        hub.quota(remote).usageAnswered(usage("team"))
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
    }
}
