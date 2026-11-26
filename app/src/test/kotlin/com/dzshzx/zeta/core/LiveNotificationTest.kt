package com.dzshzx.zeta.core

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.dzshzx.zeta.phone.PhoneTools
import com.dzshzx.zeta.shots.Fixtures
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.toolTitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 通知（Robolectric 上用真的 Notification.Builder）：回答中的对话那条通知显示当前步骤、带超级岛的焦点参数，不申请 Android 16 的实时通知 （带了的话
 * HyperOS 通知栏里的卡片时有时无，见 docs/assistant.md「超级岛」）；前台服务那条只为保活，不上岛。胶囊那句当前步骤分出的类别
 * （大岛左边的词据此取）。岛和卡片的样子要上真机看。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LiveNotificationTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun grantNotifications() =
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun service() = Robolectric.buildService(AgentService::class.java).create().get()

    private fun live(progress: TodoProgress? = null) =
        LiveStatus("s1", "修 bug", "运行 ls", StepKind.TOOL, startedAt = 1_000L, progress = progress)

    /** [Notifier.progress] 发出的那条（对话 s1 的）。 */
    private fun progress(live: LiveStatus): Notification {
        Notifier.progress(app, live)
        return shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(live.key, Notifier.ID_ALERT)!!
    }

    @Test
    fun progressCarriesStepWithoutPromotion() {
        val n = progress(live())
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(n.shortCriticalText)
        assertEquals("执行中", focus(n)["aodTitle"]!!.jsonPrimitive.content)
        // 和完成、等你的提醒同一个渠道、同一条；一轮里更新很多次，不响不弹
        assertEquals(Notifier.CHANNEL_ALERT, n.channelId)
        assertEquals("silent", n.group)
        assertEquals(
            AppVariant.current.notificationTitle("修 bug"),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals("运行 ls", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1_000L, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("进行中没有按钮", 0, n.actions?.size ?: 0)
        assertNull(focus(n)["textButton"])
        assertTrue(n.extras.getBundle(FocusParam.PICS)!!.containsKey(FocusParam.PIC_APP))
        // 点开回到这个对话
        assertEquals(
            "s1",
            shadowOf(n.contentIntent)
                .savedIntent
                .getStringExtra("com.dzshzx.zeta.extra.CONVERSATION"),
        )
    }

    /** 前台服务那条只为保活：最低重要度的渠道，不带焦点参数，写“Claude 正在工作”，带停止、截屏。 */
    @Test
    fun serviceNoticeOnlyKeepsAlive() {
        val n =
            service()
                .notification(
                    StatusNotice(active = true, busy = true, running = listOf("修 bug")),
                )
        assertEquals(Notifier.CHANNEL_SERVICE, n.channelId)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull("不上超级岛", n.extras.getString(FocusParam.EXTRA))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(
            AppVariant.current.notificationTitle("Claude 正在工作"),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals("修 bug", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("停止", "截屏"), n.actions.map { it.title.toString() })
        val idle =
            service().notification(StatusNotice(active = true, busy = false, running = emptyList()))
        assertEquals(
            AppVariant.current.notificationTitle("Claude 会话保持中"),
            idle.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals(
            NotificationManager.IMPORTANCE_MIN,
            app.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(Notifier.CHANNEL_SERVICE)
                .importance,
        )
    }

    /** 大岛 [side] 那边的字。 */
    private fun big(p: JsonObject, side: String) =
        p["param_island"]!!
            .jsonObject["bigIslandArea"]!!
            .jsonObject[side]!!
            .jsonObject["textInfo"]!!
            .jsonObject["title"]!!
            .jsonPrimitive
            .content

    /** 通知里超级岛焦点参数的 param_v2。 */
    private fun focus(n: Notification): JsonObject =
        Json.parseToJsonElement(n.extras.getString(FocusParam.EXTRA)!!)
            .jsonObject["param_v2"]!!
            .jsonObject

    /** 不用了的渠道还在就删掉（含旧的常驻通知渠道“status”），不在时不去删（删渠道会顺带清一遍这个应用的通知）。 */
    @Test
    fun retiredChannelsGo() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("island_waiting", "x", NotificationManager.IMPORTANCE_HIGH),
        )
        manager.createNotificationChannel(
            NotificationChannel("status", "x", NotificationManager.IMPORTANCE_LOW),
        )
        Notifier.ensureChannels(app)
        assertNull(manager.getNotificationChannel("island_waiting"))
        assertNull(manager.getNotificationChannel("waiting"))
        assertNull(manager.getNotificationChannel("status"))
        assertEquals(
            listOf("alert", "service", "update"),
            manager.notificationChannels.map { it.id }.sorted(),
        )
    }

    @Test
    fun todoListBecomesProgressBar() {
        val n = progress(live(progress = TodoProgress(2, 5)))
        assertEquals(
            Notification.ProgressStyle::class.java.name,
            n.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(2, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(5, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        // 超级岛上同一份进度（百分比）；没在等用户，岛不自己展开
        val focus = focus(n)
        assertEquals(40, focus["progressInfo"]!!.jsonObject["progress"]!!.jsonPrimitive.int)
        assertEquals(false, focus["enableFloat"]?.jsonPrimitive?.boolean)
    }

    /** 对话的提醒上超级岛：不静音的弹出来、停几秒收起，大岛左右两边照给的放、右边太长就截短；静音的不弹。 */
    @Test
    fun alertsGoOnIsland() {
        fun param(content: IslandContent) =
            Json.parseToJsonElement(FocusParam.build(content)).jsonObject["param_v2"]!!.jsonObject

        val done = param(FocusParam.alert("修 bug", "已完成", "已完成", "修 bug", emptyList(), false))
        assertEquals(true, done["islandFirstFloat"]?.jsonPrimitive?.boolean)
        assertEquals(
            FocusParam.ALERT_ISLAND_SECONDS,
            done["param_island"]!!.jsonObject["islandTimeout"]!!.jsonPrimitive.int,
        )
        assertEquals("已完成", big(done, "imageTextInfoLeft"))
        assertEquals("修 bug", big(done, "imageTextInfoRight"))

        val quiet = param(FocusParam.alert("修 bug", "已完成", "已完成", "修 bug", emptyList(), true))
        assertEquals(false, quiet["enableFloat"]?.jsonPrimitive?.boolean)

        val long = param(FocusParam.alert("t", "已完成", "已完成", "把登录页的按钮\n改成蓝色的", emptyList(), false))
        assertEquals("把登录页的按钮 改成蓝…", big(long, "imageTextInfoRight"))
    }

    @Test
    fun stepKinds() {
        val hub = Fixtures.readyHub(app)
        val conversation = hub.current
        val focus = conversation.overlay

        hub.showChat("t", listOf(AnswerItem("a", "你好", streaming = true)))
        assertEquals(Step("正在回答", StepKind.ANSWERING), focus.stepInfo)
        assertEquals("正在回答", focus.step)

        val bash =
            ToolStep(
                "t1",
                "Bash",
                buildJsonObject { put("command", "ls -la") },
                ToolStatus.Running,
                null,
            )
        hub.showChat("t", listOf(WorkItem("w", listOf(bash))))
        assertEquals(Step(toolTitle("Bash", bash.input), StepKind.TOOL), focus.stepInfo)

        val tap =
            ToolStep(
                "t2",
                "mcp__${PhoneTools.SERVER}__tap",
                buildJsonObject { put("id", 3) },
                ToolStatus.Running,
                null,
            )
        hub.showChat("t", listOf(WorkItem("w", listOf(tap))))
        assertEquals(StepKind.PHONE, focus.stepInfo.kind)

        hub.showChat("t", listOf(WorkItem("w", listOf(bash.copy(status = ToolStatus.Success)))))
        assertEquals(Step("正在思考", StepKind.THINKING), focus.stepInfo)

        conversation.core { it.copy(status = "正在压缩上下文") }
        assertEquals(Step("正在压缩上下文", StepKind.STATUS), focus.stepInfo)

        conversation.core {
            // 请求号别和别的测试撞上：哪些请求响过铃记在全进程共用的地方
            it.copy(prompts = listOf(PermissionRequest("step-r1", "Bash", JsonObject(emptyMap()))))
        }
        assertEquals(Step("需要你确认", StepKind.CONFIRMING), focus.stepInfo)
        // 提问要用户回答：核心标好的（asks_input）
        conversation.core {
            it.copy(
                prompts =
                    listOf(
                        PermissionRequest(
                            "step-r2",
                            "AskUserQuestion",
                            JsonObject(emptyMap()),
                            asksInput = true,
                        ),
                    ),
            )
        }
        assertEquals(Step("需要你回答", StepKind.ASKING), focus.stepInfo)
    }
}
