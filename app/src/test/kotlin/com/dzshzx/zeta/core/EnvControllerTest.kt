package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.force
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EnvController]：检查环境、换环境、默认工作目录、维护卡片。单元测试的 PATH 里的 `su` 总是失败（app/src/test/no-root）， 所以检查的结果是没有
 * root；要真 root 的安装、SSH、修复在设备上验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class EnvControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun checkSetupWithoutRoot() {
        val environment = Fixtures.readyHub(app).environment
        environment.checkSetup()
        assertEquals(SetupState.Checking, environment.setup)
        awaitMain { environment.setup != SetupState.Checking }
        assertEquals(SetupState.NoRoot, environment.setup)
    }

    /** 换环境：丢掉存下来的环境和查过的状态，原来的对话、会话列表都不要了，再重新检查。 */
    @Test
    fun redetectDropsTheOldEnvironment() {
        val hub = Fixtures.readyHub(app)
        val environment = hub.environment
        val before = hub.current
        environment.ssh =
            SshStatus(
                installed = true,
                running = true,
                autostart = false,
                port = "22",
                listen = "0.0.0.0",
                keys = 0,
                passwordLogin = false,
            )
        hub.sessionList.sessions =
            listOf(SessionSummary("s1", "/f", "/workspace/claude", "旧会话", "", 0))
        environment.redetect("/data/local/other/rootfs")
        assertEquals("/data/local/other/rootfs", environment.rootfsHint)
        assertNull(hub.settings.env)
        assertNull(environment.account)
        assertNull(environment.ssh)
        assertEquals(emptyList<SessionSummary>(), hub.sessionList.sessions)
        assertNotSame(before, hub.current)
        assertEquals("/workspace", hub.current.cwd)
        awaitMain { environment.setup != SetupState.Checking }
        assertEquals(SetupState.NoRoot, environment.setup)
    }

    /** 改默认工作目录：存进设置，还没开始的新对话跟着换过去；已经开始的不动。 */
    @Test
    fun defaultCwdRenewsTheBlankConversation() {
        val hub = Fixtures.readyHub(app)
        hub.environment.setDefaultCwd("/workspace/other")
        assertEquals("/workspace/other", hub.settings.env?.defaultCwd)
        assertEquals("/workspace/other", hub.current.cwd)
        hub.current.force("items", listOf(UserItem("u1", "你好")))
        val started = hub.current
        hub.environment.setDefaultCwd("/workspace/claude")
        assertSame(started, hub.current)
    }

    /** 维护卡片：做完的可以关掉，还在做的关不掉。 */
    @Test
    fun onlyFinishedMaintenanceIsDismissed() {
        val environment = Fixtures.readyHub(app).environment
        environment.maintenance = EnvController.Maintenance("修复环境", running = true, detail = "")
        assertTrue(environment.working)
        environment.dismissMaintenance()
        assertEquals("修复环境", environment.maintenance?.title)
        environment.maintenance = EnvController.Maintenance("修复环境", running = false, detail = "好了")
        environment.dismissMaintenance()
        assertNull(environment.maintenance)
        assertTrue(environment.canSwitchEnvironment)
    }
}
