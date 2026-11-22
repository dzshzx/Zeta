package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.notify.NotifyRule
import com.dzshzx.zeta.core.notify.NotifyRuleStore
import com.dzshzx.zeta.core.schedule.ScheduleRule
import com.dzshzx.zeta.core.schedule.ScheduleStore
import com.dzshzx.zeta.core.schedule.ScheduledTask
import com.dzshzx.zeta.shots.Fixtures
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 自动压缩、压缩时机、自动标题、定时任务、通知触发各台设备各是各的；删掉一台，它名下的一起删。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class HostClaudeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val remote = Fixtures.workstation.id

    private fun hub(): ConversationHub =
        Fixtures.readyHub(app).also { it.updateRemoteHosts(listOf(Fixtures.workstation)) }

    private fun task(id: String, backend: String) =
        ScheduledTask(
            id = id,
            name = id,
            prompt = "hi",
            rule = ScheduleRule.Daily(LocalTime.of(7, 5)),
            backend = backend,
        )

    private fun rule(id: String, backend: String) =
        NotifyRule(
            id = id,
            name = id,
            packageName = "com.example",
            appLabel = "例子",
            prompt = "hi",
            backend = backend,
        )

    /** 远程设备没设过的照手机的；设过以后和手机互不影响。 */
    @Test
    fun remoteFollowsThePhoneUntilSet() {
        val settings = hub().settings
        val phone = settings.claude(Backends.LOCAL)
        val station = settings.claude(remote)
        phone.autoCompact = false
        phone.autoCompactPercent = 70
        phone.autoTitle = false
        assertFalse(station.autoCompact)
        assertEquals(70, station.autoCompactPercent)
        assertFalse(station.autoTitle)

        station.autoCompact = true
        station.autoCompactPercent = null
        station.autoTitle = true
        assertTrue(station.autoCompact)
        assertNull("明确设成默认点，不再照手机的 70%", station.autoCompactPercent)
        assertTrue(station.autoTitle)
        assertFalse(settings.autoCompact)
        assertEquals(70, settings.autoCompactPercent)
        assertFalse(settings.autoTitle)

        phone.autoCompact = true
        station.autoCompact = false
        assertTrue(settings.autoCompact)
        assertFalse(settings.claude(remote).autoCompact)
    }

    @Test
    fun removingAHostDropsWhatBelongsToIt() {
        val hub = hub()
        hub.settings.claude(remote).autoTitle = false
        ScheduleStore.update(app) { listOf(task("phone", Backends.LOCAL), task("station", remote)) }
        NotifyRuleStore.update(app) {
            listOf(rule("phone", Backends.LOCAL), rule("station", remote))
        }
        hub.updateRemoteHosts(emptyList())
        assertEquals(emptyMap<String, String>(), hub.settings.remoteClaude)
        assertEquals(listOf("phone"), ScheduleStore.load(app).map { it.id })
        assertEquals(listOf("phone"), NotifyRuleStore.load(app).map { it.id })
    }
}
