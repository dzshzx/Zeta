package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 在用中转时没有订阅额度：「是不是订阅」只看核心给的 relay（quota.snapshot 推送、quota.usage 的结果，账号页换好后的 accounts.list），
 * 记在各台设备自己的 [QuotaService.relay] 里。是中转时不存、不显示额度，也不向 Claude Code 要（不借对话进程、不单起）；换回账号恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class RelayQuotaTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote = Fixtures.workstation.id

    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.settings.remoteHosts = listOf(Fixtures.workstation) }

    private fun snapshot() =
        Json.parseToJsonElement(
            """{"windows":[{"kind":"session","group":"session","used":0.42,"severity":"normal","label":"5 小时"}],
            "subscription":"max","updatedAt":"2026-11-18T07:00:00Z"}""",
        ) as JsonObject

    @Test
    fun relayFromTheCoreHidesQuotaPerHostAndAccountComesBack() {
        val hub = hub()
        val local = hub.quota(Backends.LOCAL)
        val far = hub.quota(remote)
        local.received(snapshot(), "")
        far.received(snapshot(), "")
        assertTrue(local.subscribed)

        // 核心推来在用中转：额度清掉，存着的也清掉；另一台不受影响
        local.received(null, "gw")
        assertEquals("gw", local.relay)
        assertFalse(local.subscribed)
        assertNull(local.snapshot)
        assertTrue(far.subscribed)
        assertNotNull(far.snapshot)
        // 重开应用：先按存着的显示，仍是中转、没有额度
        val reopened = hub().quota(Backends.LOCAL)
        assertEquals("gw", reopened.relay)
        assertNull(reopened.snapshot)

        // 在用中转时推来的额度也不收（核心不会这么推，这里钉住只看 relay）
        local.received(snapshot(), "gw")
        assertNull(local.snapshot)

        // 换回账号：relay 为空串，额度照常
        local.received(snapshot(), "")
        assertTrue(local.subscribed)
        assertEquals("max", local.snapshot?.subscription)
        assertNull(hub().quota(Backends.LOCAL).relay)
    }

    /**
     * 刷新：订阅账号时会去问（refreshing 立即为真，之后借对话进程或单起 Claude Code）；在用中转时一步也不做。 JVM 上进 Linux 环境的 su 总是失败，
     * 单起的那次随后以出错结束，不影响这里看的那一刻。
     */
    @Test
    fun refreshAsksNothingWhileOnARelay() {
        val hub = hub()
        val quota = hub.quota(Backends.LOCAL)
        try {
            quota.noteRelay("gw")
            quota.refresh(force = true)
            assertFalse("在用中转时不该去问额度", quota.refreshing)
            assertNull(quota.error)

            quota.noteRelay(null)
            quota.refresh(force = true)
            assertTrue("换回账号后该去问额度", quota.refreshing)
        } finally {
            quota.close()
        }
    }
}
