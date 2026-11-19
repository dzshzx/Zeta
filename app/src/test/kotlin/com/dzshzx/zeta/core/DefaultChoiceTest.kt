package com.dzshzx.zeta.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.showChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置页改新对话的模型、思考强度：存成默认值；当前对话还没开始时跟着换，已经开始的不动。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class DefaultChoiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun notStartedConversationFollows() {
        val hub = Fixtures.readyHub(app)
        hub.chooseDefaultModel("opus")
        hub.chooseDefaultEffort("high")
        assertEquals("opus", hub.settings.model)
        assertEquals("high", hub.settings.effort)
        assertEquals("opus", hub.current.model)
        assertEquals("high", hub.current.effort)
    }

    @Test
    fun startedConversationKeepsItsOwn() {
        val hub = Fixtures.readyHub(app)
        hub.settings.model = null
        hub.settings.effort = null
        hub.startNew()
        hub.showChat("在聊", listOf(UserItem("u1", "你好")))
        hub.chooseDefaultModel("sonnet")
        hub.chooseDefaultEffort("low")
        assertEquals("sonnet", hub.settings.model)
        assertEquals("low", hub.settings.effort)
        assertNull(hub.current.model)
        assertNull(hub.current.effort)
    }
}
