package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.BackgroundTask
import com.dzshzx.zeta.core.Conversation
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.TaskKind
import com.dzshzx.zeta.core.TaskOutput
import com.dzshzx.zeta.core.TaskState
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.core
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import com.dzshzx.zeta.ui.pane.SessionPane
import com.dzshzx.zeta.ui.tasks.AllConversations
import com.dzshzx.zeta.ui.tasks.BackgroundTasksScreen
import org.junit.Test

/** 后台任务：聊天页的任务条、侧栏入口、全部对话的面板、一个任务的详情。 */
class BackgroundTaskShots : ShotTest() {
    private val now = System.currentTimeMillis()

    /** 当前对话：一个在跑的后台命令、一个在跑的子代理、一个跑完的命令；另有一个在后台跑着监视器的对话。 */
    private fun hubWithTasks(): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.showChat(
            "给设置页加后台任务面板",
            listOf(
                UserItem("u1", "跑一遍单元测试，同时让子代理看看面板的排版"),
                AnswerItem("a1", "测试和审查都放到后台了，跑完我来汇总。", streaming = false),
            ),
        )
        val current = hub.current
        current.core {
            it.copy(
                tasks =
                    listOf(
                        BackgroundTask(
                            "b3",
                            "local_bash",
                            TaskKind.Command,
                            "同步远端",
                            "git fetch -q origin && git status -sb",
                            null,
                            "t3",
                            startedAt = now - 9 * 60_000,
                            endedAt = now - 9 * 60_000 + 4_000,
                            state = TaskState.Completed,
                            summary = "Background command \"同步远端\" completed (exit code 0)",
                        ),
                        BackgroundTask(
                            "b1",
                            "local_bash",
                            TaskKind.Command,
                            "跑单元测试",
                            "tools/gradle :app:testDebugUnitTest --tests 'com.dzshzx.zeta.core.*'",
                            null,
                            "t1",
                            startedAt = now - 125_000,
                        ),
                        BackgroundTask(
                            "a2",
                            "local_agent",
                            TaskKind.Agent,
                            "审查面板排版",
                            "看看后台任务面板的排版，列出不对齐和文字溢出的地方",
                            "general-purpose",
                            "t2",
                            startedAt = now - 48_000,
                            progress = "Reading BackgroundTasksScreen.kt",
                            toolUses = 4,
                        ),
                    ),
            )
        }
        current.tasks.force(
            "outputs",
            mapOf(
                "b1" to
                    TaskOutput(
                        "> Task :app:compileDebugKotlin\n> Task :app:testDebugUnitTest\n\nTaskBoardTest > backgroundCommandStartsAndIsStopped PASSED\n" +
                            "TaskBoardTest > subagentProgressAndReport PASSED\nHostTasksTest > runningTasksAreReplayedWithTheirCallAndStartTime PASSED\n",
                        totalBytes = 12_640,
                        truncated = true,
                    ),
            ),
        )

        val other = Conversation(hub, "/workspace/claude", "s-other", "盯着构建日志", historyFile = null)
        other.core {
            it.copy(
                tasks =
                    listOf(
                        BackgroundTask(
                            "bm1",
                            "local_bash",
                            TaskKind.Monitor,
                            "构建出错时提醒",
                            "tail -f build.log | grep --line-buffered ERROR",
                            null,
                            "m1",
                            startedAt = now - 21 * 60_000,
                        ),
                    ),
            )
        }
        hub.park(other)
        return hub
    }

    @Test
    fun panel() {
        val hub = hubWithTasks()
        shoot("tasks-all") {
            BackgroundTasksScreen(
                hub = hub,
                scope = AllConversations,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    @Test
    fun detail() {
        val hub = hubWithTasks()
        shoot("tasks-detail", prepare = { onNodeWithText("跑单元测试").performClick() }) {
            BackgroundTasksScreen(
                hub = hub,
                scope = hub.current.key,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    @Test
    fun agentDetail() {
        val hub = hubWithTasks()
        shoot("tasks-agent", prepare = { onNodeWithText("审查面板排版").performClick() }) {
            BackgroundTasksScreen(
                hub = hub,
                scope = hub.current.key,
                onBack = {},
                onOpenConversation = {},
            )
        }
    }

    @Test
    fun chatStrip() {
        val hub = hubWithTasks()
        shoot("tasks-chat-strip") {
            ChatScreen(
                hub = hub,
                onOpenPane = {},
                onOpenSettings = {},
                onOpenInstall = {},
                onOpenLogin = {},
            )
        }
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
                    statusOf = { null },
                    onNewChat = {},
                    onOpen = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onArchive = { _, _ -> },
                    onOpenSettings = {},
                    onOpenBackgroundTasks = {},
                )
            }
        }
    }
}
