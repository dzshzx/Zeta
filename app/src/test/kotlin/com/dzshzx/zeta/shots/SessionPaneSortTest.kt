package com.dzshzx.zeta.shots

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.SessionSort
import com.dzshzx.zeta.core.SessionSummary
import com.dzshzx.zeta.ui.app.ZetaTheme
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/** 侧栏按设置里选的时间分组、排先后（「最新回答」「发起时间」），记录文件的修改时刻不参与；第一组标题右边的排序按钮点开菜单换排法。 不出图，合入门禁照跑。 */
class SessionPaneSortTest : ShotTest() {
    private val todayStart =
        Calendar.getInstance()
            .apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            .timeInMillis

    private val day = 86_400_000L
    private val minute = 60_000L

    private fun session(title: String, created: Long, replied: Long, updated: Long) =
        SessionSummary(
            title,
            "/home/u/.claude/projects/-w/$title.jsonl",
            "/w",
            title,
            "",
            updated,
            created = created,
            replied = replied,
        )

    /** 昨天发起、今天才回答；今天发起、回答得比它早；三天前发起和回答、但工具结果今天刚写过（updated 最新）。 */
    private val sessions =
        listOf(
            session(
                "昨天发起的",
                created = todayStart - day,
                replied = todayStart + 30 * minute,
                updated = todayStart + 31 * minute,
            ),
            session(
                "今天发起的",
                created = todayStart + 5 * minute,
                replied = todayStart + 10 * minute,
                updated = todayStart + 10 * minute,
            ),
            session(
                "三天前的",
                created = todayStart - 3 * day,
                replied = todayStart - 3 * day,
                updated = todayStart + 40 * minute,
            ),
        )

    private fun show(sort: SessionSort): ConversationHub {
        val hub = Fixtures.readyHub(app)
        hub.settings.sessionSort = sort
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) { PaneShot(sessions) }
        }
        settle()
        return hub
    }

    private fun top(text: String): Dp =
        compose.onNodeWithText(text).assertIsDisplayed().getUnclippedBoundsInRoot().top

    /** 自上而下的顺序（标题和会话混在一起比）。 */
    private fun assertOrder(vararg texts: String) {
        val tops = texts.map(::top)
        assertEquals("自上而下应是 ${texts.toList()}，位置 $tops", tops.sorted(), tops)
    }

    @Test
    fun latestReplyGroupsByReplyTime() {
        show(SessionSort.Replied)
        assertOrder("今天", "昨天发起的", "今天发起的", "更早", "三天前的")
    }

    @Test
    fun dateCreatedGroupsByCreationTime() {
        show(SessionSort.Created)
        assertOrder("今天", "今天发起的", "更早", "昨天发起的", "三天前的")
    }

    @Test
    fun sortButtonSwitchesTheOrder() {
        val hub = show(SessionSort.Replied)
        compose.tapNode(compose.onNodeWithContentDescription("排序"))
        compose.tap("发起时间")
        settle()
        assertEquals(SessionSort.Created, hub.settings.sessionSort)
        assertOrder("今天", "今天发起的", "更早", "昨天发起的", "三天前的")
    }
}
