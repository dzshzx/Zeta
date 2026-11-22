package com.dzshzx.zeta.shots

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.NoticeItem
import com.dzshzx.zeta.core.ResultCardPolicy
import com.dzshzx.zeta.core.TurnResult
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.alerts
import com.dzshzx.zeta.core.phone
import com.dzshzx.zeta.phone.ZetaAccessibilityService
import com.dzshzx.zeta.shots.Fixtures.force
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.app.ZetaTheme
import com.dzshzx.zeta.ui.overlay.ResultCard
import io.github.suqi8.coui.kmp.theme.COUITheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows

/**
 * 一轮结束后屏幕底部的结果卡片：两张图（回答长到要滚动、出错），以及卡片自己怎么收起（没碰多久、碰了重新计时、下滑、按钮）， hub 那边一轮结束时弹不弹、完成通知响不响，新的一轮开始、回到
 * Zeta、锁屏时收起。窗口本身（叠在别的应用上、返回键、焦点）要上真机看。
 */
class ResultCardShots : ShotTest() {
    @Test
    fun answer() {
        Fixtures.readyHub(app)
        shoot("result-card") { OnScreen("整理相册截图", LongAnswer, failed = false) }
    }

    @Test
    fun error() {
        Fixtures.readyHub(app)
        shoot("result-card-error") {
            OnScreen(
                "打开计算器",
                "API Error: 529 {\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}",
                failed = true,
            )
        }
    }

    @Test
    fun dismissesAfterIdle() {
        var dismissed = 0
        val text = "计算器打开了，结果是 42。"
        present(text) { dismissed++ }
        val idle = ResultCardPolicy.idleDismissMs(text)
        advance(idle - 500)
        assertEquals("还没到时间", 0, dismissed)
        advance(1_000)
        assertEquals("没碰就自己收起", 1, dismissed)
    }

    @Test
    fun touchRestartsTheIdleTimer() {
        var dismissed = 0
        val text = "计算器打开了，结果是 42。"
        present(text) { dismissed++ }
        val idle = ResultCardPolicy.idleDismissMs(text)
        advance(idle - 1_000)
        compose.onNodeWithText("已完成").performTouchInput {
            down(center)
            up()
        }
        advance(1_500)
        assertEquals("松手后重新计时", 0, dismissed)
        advance(idle)
        assertEquals(1, dismissed)
    }

    @Test
    fun swipeDownDismisses() {
        var dismissed = 0
        present("计算器打开了。") { dismissed++ }
        compose.onNodeWithText("已完成").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 30.dp.toPx(), durationMillis = 600)
        }
        advance(500)
        assertEquals("拖一点点就松手：弹回去", 0, dismissed)
        compose.onNodeWithText("已完成").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 240.dp.toPx(), durationMillis = 300)
        }
        advance(500)
        assertEquals("拖得够远：关掉", 1, dismissed)
    }

    @Test
    fun buttons() {
        var dismissed = 0
        var opened = 0
        present("计算器打开了。", onOpen = { opened++ }) { dismissed++ }
        compose.onNodeWithText("回到对话").performClick()
        advance(100)
        assertEquals(1, opened)
        assertEquals("回到对话后卡片收起", 1, dismissed)
    }

    @Test
    fun doneDismisses() {
        var dismissed = 0
        present("计算器打开了。") { dismissed++ }
        compose.onNodeWithText("完成").performClick()
        advance(100)
        assertEquals(1, dismissed)
    }

    /** hub 那边：卡片的内容跟着聊天记录；回到 Zeta、锁屏、这个对话又开始一轮、别的对话开始一轮，卡片都不再留。 */
    @Test
    fun hubDropsTheCard() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        hub.showChat(
            "打开计算器",
            listOf(UserItem("u1", "打开计算器"), AnswerItem("a1", "打开了。", streaming = false)),
        )
        val card = TurnResult(hub.current, emptySet())
        assertEquals("打开了。", card.text)
        assertFalse(card.failed)
        assertTrue(hub.alerts.keepsResultCard(card, locked = false))
        assertFalse("锁屏", hub.alerts.keepsResultCard(card, locked = true))

        hub.foreground = true
        assertFalse("回到 Zeta", hub.alerts.keepsResultCard(card, locked = false))
        hub.foreground = false

        hub.current.force("busy", true)
        assertFalse("这个对话又开始一轮", hub.alerts.keepsResultCard(card, locked = false))
        hub.current.force("busy", false)

        // 出错提示比一轮结束晚一步进聊天列表：卡片跟着变成错误
        hub.showChat(
            "打开计算器",
            listOf(
                UserItem("u1", "打开计算器"),
                AnswerItem("a1", "打开了。", streaming = false),
                NoticeItem("n1", "出错了：网络断开", error = true),
            ),
        )
        assertEquals("出错了：网络断开", card.text)
        assertTrue(card.failed)
    }

    /**
     * 一轮结束的真实路径（onTurnEnding → onTurnFinished）：这一轮用过手机、Zeta 不在前台、无障碍服务连着，就弹卡片，
     * 完成通知照发但归进不提醒的组（不响铃、不横幅）；没用手机的一轮不弹，通知照常提醒。
     */
    @Test
    fun turnEndOffersCardAndSilencesTheAlert() {
        val hub = Fixtures.readyHub(app)
        hub.foreground = false
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = Shadows.shadowOf(manager)
        val service =
            ZetaAccessibilityService::class.java.getDeclaredField("instance").apply {
                isAccessible = true
            }
        service.set(null, Robolectric.buildService(ZetaAccessibilityService::class.java).get())
        try {
            val conversation = hub.current
            conversation.phone.force("engagedThisTurn", true)
            hub.onTurnEnding(conversation, "已完成")
            // 真实顺序里，onTurnFinished 之前手机工具的这一轮状态已经清掉了
            conversation.phone.force("engagedThisTurn", false)
            hub.onTurnFinished(conversation)
            val card = hub.alerts.resultCard
            assertTrue("用过手机：弹卡片", card != null && card.conversation === conversation)
            val silent = notifications.allNotifications.single()
            assertEquals("silent", silent.group)
            assertEquals(Notification.GROUP_ALERT_SUMMARY, silent.groupAlertBehavior)

            hub.alerts.dismissResultCard(card!!)
            manager.cancelAll()
            hub.onTurnEnding(conversation, "已完成")
            hub.onTurnFinished(conversation)
            assertEquals("没用手机：不弹", null, hub.alerts.resultCard)
            val loud = notifications.allNotifications.single()
            assertEquals(null, loud.group)
            assertEquals(Notification.GROUP_ALERT_ALL, loud.groupAlertBehavior)
        } finally {
            service.set(null, null)
        }
    }

    /** 卡片贴在屏幕底部（最高半屏：817dp 的一半），后面垫一层页面底色当作别的应用。 */
    @Composable
    private fun OnScreen(title: String, text: String, failed: Boolean) {
        Box(
            Modifier.fillMaxSize().background(COUITheme.colorScheme.backgroundWithCard),
            contentAlignment = Alignment.BottomCenter,
        ) {
            ResultCard(
                title = title,
                text = text,
                failed = failed,
                visible = true,
                onDismiss = {},
                onOpen = {},
                maxHeight = 408.dp,
            )
        }
    }

    private fun present(text: String, onOpen: () -> Unit = {}, onDismiss: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ZetaTheme(darkTheme = false, windowBackdrop = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    ResultCard(
                        title = "打开计算器",
                        text = text,
                        failed = false,
                        visible = true,
                        onDismiss = onDismiss,
                        onOpen = onOpen,
                        maxHeight = 408.dp,
                    )
                }
            }
        }
        advance(100)
    }

    /** 先让触摸之类改的状态落到界面上（快照变更经主线程的消息队列送达），再拨时钟。 */
    private fun advance(ms: Long) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private companion object {
        val LongAnswer =
            """
            11 月的截图已经按日期归档好了，一共 **86 张**：

            - **1 日–10 日**：23 张，放进「截图/11 月上旬」
            - **11 日–20 日**：41 张，放进「截图/11 月中旬」
            - **21 日–30 日**：22 张，放进「截图/11 月下旬」

            另外有 4 张是重复的（同一个页面连着截了两次），我没有删，放在「截图/待确认」里，你看一下再决定要不要删。

            相册里「最近项目」的排序没有变，原来的截图文件夹现在是空的，我也没有删掉它，免得别的应用还在往里面存。

            要是想按应用分类（微信、浏览器、地图……），告诉我一声，我再整理一遍。
            """
                .trimIndent()
    }
}
