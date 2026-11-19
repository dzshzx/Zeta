package com.dzshzx.zeta.shots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.DraftImage
import com.dzshzx.zeta.core.DraftRecord
import com.dzshzx.zeta.core.DraftRecords
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.ui.components.ConversationSidePaneScaffold
import com.dzshzx.zeta.ui.pane.SessionPane
import java.util.Calendar
import org.junit.Test

/** 侧栏（会话列表）：分组列表（含草稿标记）、长按菜单、多选、删除确认、重命名、搜索。侧栏放在聊天页的推移容器里，展开着。 */
class SessionPaneShots : ShotTest() {
    private val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun session(id: String, cwd: String, title: String, updated: Long, preview: String = "", external: Boolean = false, archived: Boolean = false) =
        SessionSummary(id, "/home/dzshzx/.claude/projects/-w/$id.jsonl", cwd, title, preview, updated, external = external, archived = archived)

    private val sessions = listOf(
        session("s1", "/workspace/claude/zeta", "侧栏迁到 COUI 2", todayStart + 3 * 60_000),
        session("s2", "/workspace/claude/zeta", "修复登录页的布局", todayStart + 2 * 60_000),
        session("s3", "/workspace/claude", "整理下载目录", todayStart + 60_000, external = true),
        session("s4", "/workspace/claude/fleet-state", "排查 company-vps 磁盘告警", todayStart - 3 * 86_400_000L),
        session("s5", "/workspace/claude", "", todayStart - 5 * 86_400_000L, preview = "把周报里的表格换成钉钉表格，再发到群里"),
        session("s6", "/workspace/claude", "很久以前的对话", todayStart - 9 * 86_400_000L),
        session("s7", "/workspace/claude", "已经归档的对话", todayStart - 20 * 86_400_000L, archived = true),
    )

    /** s4、s6 有没发出的草稿（一份是文字，一份只有图片）；正看着的 s2 也有，但不标。 */
    private fun hub(): ConversationHub {
        Settings(app).composerDrafts = DraftRecords.encode(
            listOf(
                DraftRecord("s4", "先看 df -h 的输出，再决定删哪些日志", 0, 0, emptyList(), emptyList(), at = 3),
                DraftRecord("s6", "", 0, 0, listOf(DraftImage("i1", "image/jpeg", 1), DraftImage("i2", "image/jpeg", 1)), emptyList(), at = 2),
                DraftRecord("s2", "正看着的对话不标", 0, 0, emptyList(), emptyList(), at = 1),
            ),
        )
        return Fixtures.readyHub(app).also { it.choosePinned(setOf("s4")) }
    }

    @Composable
    private fun Pane(hub: ConversationHub) {
        ConversationSidePaneScaffold(
            visible = true,
            backHandlerEnabled = true,
            onOpen = {},
            onDismiss = {},
            pane = { width, revealed, modifier ->
                SessionPane(
                    width = width,
                    sessions = sessions,
                    currentSessionId = "s2",
                    revealed = revealed,
                    isRunning = { it == "s1" },
                    onNewChat = {},
                    onOpen = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onArchive = { _, _ -> },
                    onOpenSettings = {},
                    modifier = modifier,
                )
            },
        ) { Box(Modifier.fillMaxSize()) }
    }

    private fun AndroidComposeTestRule<*, *>.longPress(text: String) {
        onNodeWithText(text).performTouchInput { longClick() }
    }

    @Test
    fun list() {
        val hub = hub()
        shoot("pane") { Pane(hub) }
    }

    @Test
    fun menu() {
        val hub = hub()
        shoot("pane-menu", prepare = { longPress("整理下载目录") }) { Pane(hub) }
    }

    @Test
    fun select() {
        val hub = hub()
        shoot("pane-select", prepare = {
            longPress("整理下载目录")
            onNodeWithText("选择").performClick()
            // 时钟不自己走：等菜单收起的动画放完再点下一行，不然这一下点在菜单的遮罩上
            mainClock.advanceTimeBy(1_000)
            onNodeWithText("排查 company-vps 磁盘告警").performClick()
        }) { Pane(hub) }
    }

    @Test
    fun delete() {
        val hub = hub()
        shoot("pane-delete", prepare = {
            longPress("很久以前的对话")
            onNodeWithText("删除").performClick()
        }) { Pane(hub) }
    }

    @Test
    fun rename() {
        val hub = hub()
        shoot("pane-rename", prepare = {
            longPress("修复登录页的布局")
            onNodeWithText("重命名").performClick()
        }) { Pane(hub) }
    }

    @Test
    fun search() {
        val hub = hub()
        shoot("pane-search", prepare = { onNode(hasSetTextAction()).performTextInput("对话") }) { Pane(hub) }
    }
}
