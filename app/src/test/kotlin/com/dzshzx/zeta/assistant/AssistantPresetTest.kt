package com.dzshzx.zeta.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 数字助理单独定的模型和思考等级只用在它开的对话上，不改新对话的设置。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AssistantPresetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun presetAppliesToThisConversationOnly() {
        val hub = Fixtures.readyHub(app)
        hub.settings.model = "opus"
        hub.settings.effort = "high"
        val conversation = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        conversation.preset(model = "sonnet", permissionMode = null, effort = "low")
        assertEquals("sonnet", conversation.model)
        assertEquals("low", conversation.effort)
        assertEquals("opus", hub.settings.model)
        assertEquals("high", hub.settings.effort)
    }

    @Test
    fun unsetFollowsNewConversationSettings() {
        val hub = Fixtures.readyHub(app)
        hub.settings.model = "opus"
        hub.settings.effort = "high"
        assertNull(hub.settings.assistantModel)
        assertNull(hub.settings.assistantEffort)
        val conversation = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        conversation.preset(model = hub.settings.assistantModel, permissionMode = null, effort = hub.settings.assistantEffort)
        assertEquals("opus", conversation.model)
        assertEquals("high", conversation.effort)
    }
}
