package com.dzshzx.zeta.shots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.app.ZetaTheme
import java.util.Calendar
import org.junit.Test

/**
 * 侧栏列表停在最顶上时，后到的新会话插在上面也露出来：「全部设备」下各台主机的列表先后到，先到的那台只有较早的会话时， 列表的第一项是「更早」，另一台今天的会话随后插到它上面。
 * 用户往下翻过时不动，照旧停在原来看的那一项。不出图，合入门禁照跑。
 */
class SessionPaneTopTest : ShotTest() {
    private val todayStart =
        Calendar.getInstance()
            .apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            .timeInMillis

    private fun session(id: String, title: String, updated: Long, backend: String) =
        SessionSummary(
            id,
            "/home/u/.claude/projects/-w/$id.jsonl",
            "/w",
            title,
            "",
            updated,
            backend = backend,
        )

    /** 手机本机先到：只有几天前的会话，多到占满一屏多。 */
    private val earlier =
        (1..30).map { session("e$it", "较早的对话 $it", todayStart - it * 86_400_000L, "local") }

    /** 远程设备后到：今天的会话。 */
    private val today =
        (1..3).map { session("t$it", "今天的对话 $it", todayStart + (10 - it) * 60_000L, "r1") }

    private fun show(): (List<SessionSummary>) -> Unit {
        var sessions by mutableStateOf(earlier)
        Fixtures.readyHub(app)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) { PaneShot(sessions) }
        }
        settle()
        return { sessions = it }
    }

    @Test
    fun newSessionsAboveShowWhenAtTop() {
        val update = show()
        compose.onNodeWithText("更早").assertIsDisplayed()
        update((today + earlier).sortedByDescending { it.updated })
        settle()
        compose.onNodeWithText("今天").assertIsDisplayed()
        compose.onNodeWithText("今天的对话 1").assertIsDisplayed()
    }

    @Test
    fun scrolledDownStaysWhereItWas() {
        val update = show()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
        compose.mainClock.autoAdvance = false
        settle()
        compose.onNodeWithText("较早的对话 15").assertIsDisplayed()
        update((today + earlier).sortedByDescending { it.updated })
        settle()
        compose.onNodeWithText("较早的对话 15").assertIsDisplayed()
        compose.onNodeWithText("今天").assertIsNotDisplayed()
    }
}
