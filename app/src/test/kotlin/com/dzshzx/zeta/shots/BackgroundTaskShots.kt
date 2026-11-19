package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.TaskOutput
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.pane.SessionPane
import com.dzshzx.zeta.ui.tasks.AllConversations
import com.dzshzx.zeta.ui.tasks.BackgroundTasksScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 后台任务：聊天页的任务条、侧栏入口、全部对话的面板、一个任务的详情。 */
class BackgroundTaskShots : ShotTest() {
    private val now = System.currentTimeMillis()

    private fun Conversation.feed(vararg events: String, at: Long) {
        events.forEach { tasks.observe(Json.parseToJsonElement(it).jsonObject as JsonObject, at) }
    }

    /** 当前对话：一个在跑的后台命令、一个在跑的子代理、一个跑完的命令；另有一个在后台跑着监视器的对话。 */
    private fun hubWithTasks(): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.showChat("给设置页加后台任务面板", listOf(UserItem("u1", "跑一遍单元测试，同时让子代理看看面板的排版"), AnswerItem("a1", "测试和审查都放到后台了，跑完我来汇总。", streaming = false)))
        val current = hub.current
        current.feed(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"tools/gradle :app:testDebugUnitTest --tests 'com.dzshzx.zeta.core.*'"}},{"type":"tool_use","id":"t3","name":"Bash","input":{"command":"git fetch -q origin && git status -sb"}}]}}""",
            """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b3","task_type":"local_bash","description":"同步远端"}]}""",
            """{"type":"system","subtype":"task_started","task_id":"b3","tool_use_id":"t3","description":"同步远端","task_type":"local_bash"}""",
            at = now - 9 * 60_000,
        )
        current.feed(
            """{"type":"system","subtype":"task_notification","task_id":"b3","status":"completed","summary":"Background command \"同步远端\" completed (exit code 0)"}""",
            """{"type":"system","subtype":"background_tasks_changed","tasks":[]}""",
            at = now - 9 * 60_000 + 4_000,
        )
        current.feed(
            """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"跑单元测试"}]}""",
            """{"type":"system","subtype":"task_started","task_id":"b1","tool_use_id":"t1","description":"跑单元测试","task_type":"local_bash"}""",
            at = now - 125_000,
        )
        current.feed(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Agent","input":{"prompt":"看看后台任务面板的排版，列出不对齐和文字溢出的地方","subagent_type":"general-purpose"}}]}}""",
            """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"b1","task_type":"local_bash","description":"跑单元测试"},{"task_id":"a2","task_type":"local_agent","subagent_type":"general-purpose","description":"审查面板排版"}]}""",
            """{"type":"system","subtype":"task_started","task_id":"a2","tool_use_id":"t2","description":"审查面板排版","subagent_type":"general-purpose","task_type":"local_agent"}""",
            """{"type":"system","subtype":"task_progress","task_id":"a2","description":"Reading BackgroundTasksScreen.kt","usage":{"tool_uses":4}}""",
            at = now - 48_000,
        )
        current.tasks.force(
            "outputs",
            mapOf(
                "b1" to TaskOutput(
                    "> Task :app:compileDebugKotlin\n> Task :app:testDebugUnitTest\n\nTaskBoardTest > backgroundCommandStartsAndIsStopped PASSED\n" +
                        "TaskBoardTest > subagentProgressAndReport PASSED\nHostTasksTest > runningTasksAreReplayedWithTheirCallAndStartTime PASSED\n",
                    totalBytes = 12_640, truncated = true,
                ),
            ),
        )

        val other = Conversation(hub, "/workspace/claude", "s-other", "盯着构建日志", historyFile = null)
        other.feed(
            """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"m1","name":"Monitor","input":{"description":"构建出错时提醒","command":"tail -f build.log | grep --line-buffered ERROR"}}]}}""",
            """{"type":"system","subtype":"background_tasks_changed","tasks":[{"task_id":"bm1","task_type":"local_bash","description":"构建出错时提醒"}]}""",
            """{"type":"system","subtype":"task_started","task_id":"bm1","tool_use_id":"m1","description":"构建出错时提醒","task_type":"local_bash"}""",
            at = now - 21 * 60_000,
        )
        other.force("backgroundTasks", 1)
        hub.adoptBackground(other)
        return hub
    }

    @Test
    fun panel() {
        val hub = hubWithTasks()
        shoot("tasks-all") { BackgroundTasksScreen(hub = hub, scope = AllConversations, onBack = {}, onOpenConversation = {}) }
    }

    @Test
    fun detail() {
        val hub = hubWithTasks()
        shoot("tasks-detail", prepare = { onNodeWithText("跑单元测试").performClick() }) {
            BackgroundTasksScreen(hub = hub, scope = hub.current.key, onBack = {}, onOpenConversation = {})
        }
    }

    @Test
    fun agentDetail() {
        val hub = hubWithTasks()
        shoot("tasks-agent", prepare = { onNodeWithText("审查面板排版").performClick() }) {
            BackgroundTasksScreen(hub = hub, scope = hub.current.key, onBack = {}, onOpenConversation = {})
        }
    }

    @Test
    fun chatStrip() {
        val hub = hubWithTasks()
        shoot("tasks-chat-strip") { ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {}) }
    }

    @Test
    fun paneEntry() {
        val hub = hubWithTasks()
        shoot("tasks-pane-entry") {
            Box(Modifier.fillMaxSize()) {
                SessionPane(
                    width = 330.dp,
                    sessions = emptyList(),
                    currentSessionId = null,
                    revealed = true,
                    isRunning = { false },
                    onNewChat = {},
                    onOpen = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onArchive = { _, _ -> },
                    onOpenSettings = {},
                    onOpenBackground = {},
                )
            }
        }
    }
}
