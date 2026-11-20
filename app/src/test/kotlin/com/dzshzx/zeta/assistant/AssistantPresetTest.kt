package com.dzshzx.zeta.assistant

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.launchArgument
import com.dzshzx.zeta.shots.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 数字助理单独定的模型和思考强度只用在它开的对话上，不改新对话的设置。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AssistantPresetTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun presetAppliesToThisConversationOnly() {
        val hub = Fixtures.readyHub(app)
        val conversation = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        conversation.preset(model = "sonnet", effort = "low")
        assertEquals("sonnet", conversation.model)
        assertEquals("low", conversation.effort)
        val other = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        assertNull(other.model)
        assertNull(other.effort)
    }

    /** 没单独定：和新对话一样不指定，由 Claude Code 照它的设置定。 */
    @Test
    fun unsetFollowsClaudeCode() {
        val hub = Fixtures.readyHub(app)
        assertNull(hub.settings.assistantModel)
        assertNull(hub.settings.assistantEffort)
        val conversation = Conversation(hub, "/workspace/claude", sessionId = null, title = "", historyFile = null)
        conversation.preset(model = hub.settings.assistantModel, effort = hub.settings.assistantEffort)
        assertNull(conversation.launchArgument("--model"))
        assertNull(conversation.launchArgument("--effort"))
    }
}
