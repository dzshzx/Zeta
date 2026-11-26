package com.dzshzx.zeta.core

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 常驻通知（Robolectric 上用真的 Notification.Builder）：提升时带上实时通知要的字段、系统认它可以提升，不提升时照旧；
 * 胶囊那句当前步骤分出的类别（状态栏小胶囊上的词据此取）。系统是否真的接受提升要上真机看 dumpsys。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LiveNotificationTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun service() = Robolectric.buildService(AgentService::class.java).create().get()

    private fun live(progress: TodoProgress? = null) =
        LiveStatus(
            "s1",
            "修 bug",
            "运行 ls",
            StepKind.TOOL,
            startedAt = 1_000L,
            progress = progress,
            others = 1,
        )

    @Test
    fun promotedCarriesLiveFields() {
        val n =
            service()
                .notification(
                    StatusNotice(
                        active = true,
                        busy = true,
                        running = listOf("修 bug", "别的"),
                        live = live(),
                    ),
                )
        assertTrue(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertTrue("系统据此决定能不能提升", n.hasPromotableCharacteristics())
        // 对照：不常驻的通知系统不认，上面这条检查不是摆设
        assertFalse(
            Notification.Builder(app, Notifier.CHANNEL_STATUS)
                .setContentTitle("x")
                .build()
                .hasPromotableCharacteristics(),
        )
        assertEquals("执行中", n.shortCriticalText)
        assertEquals(
            AppVariant.current.notificationTitle("修 bug"),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals("运行 ls", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(
            "另有 1 个对话在回答",
            n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString(),
        )
        assertEquals(1_000L, n.`when`)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue("更新不再弹横幅", n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // 点开回到这个对话
        assertEquals(
            "s1",
            shadowOf(n.contentIntent)
                .savedIntent
                .getStringExtra("com.dzshzx.zeta.extra.CONVERSATION"),
        )
        assertEquals(
            Notification.BigTextStyle::class.java.name,
            n.extras.getString(Notification.EXTRA_TEMPLATE),
        )
    }

    /** 停下来等用户时，实时通知（超级岛展开时也是它）正文是具体的命令，按钮换成允许、拒绝；照样能提升。 */
    @Test
    fun waitingCarriesReplies() {
        val bash =
            PermissionRequest(
                "r1",
                "Bash",
                buildJsonObject {
                    put("command", "touch a")
                    put("description", "建文件")
                },
                emptyList(),
            )
        val candidate =
            LiveCandidate(
                "s1",
                "修 bug",
                AlertNotice.waitingStep(bash),
                controlling = false,
                engaged = false,
                engagedAt = 0L,
                current = false,
                startedAt = 1_000L,
                request = bash,
            )
        val waiting = LiveUpdate.status(candidate, running = 1, todo = null)
        val service = service()
        val n = service.notification(StatusNotice(true, true, listOf("修 bug"), waiting))
        // 这个请求第一次出现：这次更新要提醒（照“运行状态”渠道的设置弹、响）；同一个请求再发就不再提醒
        assertEquals(Notifier.CHANNEL_STATUS, n.channelId)
        assertEquals(0, n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        val again = service.notification(StatusNotice(true, true, listOf("修 bug"), waiting))
        assertTrue(again.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(n.hasPromotableCharacteristics())
        assertEquals("待确认", n.shortCriticalText)
        assertEquals("需要你确认：建文件", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("允许", "拒绝"), n.actions.map { it.title.toString() })
        assertEquals(listOf(true, false), n.actions.map { it.isAuthenticationRequired })
        // 超级岛的焦点参数：等用户时岛自己展开，按钮是同一批回答，键对得上放进 extras 的按钮
        val focus = focus(n)
        assertEquals(true, focus["enableFloat"]?.jsonPrimitive?.boolean)
        assertEquals(true, focus["islandFirstFloat"]?.jsonPrimitive?.boolean)
        // 岛左边是短词，右边是等的是什么（从请求里取，不从“需要你确认：…”那句切）
        assertEquals("待确认", big(focus, "imageTextInfoLeft"))
        assertEquals("建文件", big(focus, "imageTextInfoRight"))
        val buttons = focus["textButton"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("允许", "拒绝"), buttons.map { it["actionTitle"]!!.jsonPrimitive.content })
        val actions = n.extras.getBundle(FocusParam.ACTIONS)!!
        assertEquals(
            listOf("允许", "拒绝"),
            buttons.map {
                actions
                    .getParcelable(
                        it["action"]!!.jsonPrimitive.content,
                        Notification.Action::class.java,
                    )!!
                    .title
                    .toString()
            },
        )
        assertTrue(n.extras.getBundle(FocusParam.PICS)!!.containsKey(FocusParam.PIC_APP))
        // 接管着时胶囊上是“你在接管”，没有回答的按钮
        val controlling =
            LiveUpdate.status(
                candidate.copy(step = Step("你在接管", StepKind.CONTROLLING), controlling = true),
                running = 1,
                todo = null,
            )
        val plain = service.notification(StatusNotice(true, true, listOf("修 bug"), controlling))
        assertEquals("实时通知平时不带停止、截屏", 0, plain.actions?.size ?: 0)
        assertNull(focus(plain)["textButton"])
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

    /** 测试版上试过的两个渠道还在就删掉，不在时不去删（删渠道会顺带清一遍这个应用的通知）。 */
    @Test
    fun retiredChannelsGo() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("island_waiting", "x", NotificationManager.IMPORTANCE_HIGH),
        )
        Notifier.ensureChannels(app)
        assertNull(manager.getNotificationChannel("island_waiting"))
        assertNull(manager.getNotificationChannel("waiting"))
        assertEquals(
            listOf("alert", "status", "update"),
            manager.notificationChannels.map { it.id }.sorted(),
        )
    }

    @Test
    fun todoListBecomesProgressBar() {
        val n =
            service()
                .notification(
                    StatusNotice(true, true, listOf("修 bug"), live(progress = TodoProgress(2, 5))),
                )
        assertEquals(
            Notification.ProgressStyle::class.java.name,
            n.extras.getString(Notification.EXTRA_TEMPLATE),
        )
        assertEquals(2, n.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(5, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertTrue(n.hasPromotableCharacteristics())
        // 超级岛上同一份进度（百分比）；没在等用户，岛不自己展开
        val focus = focus(n)
        assertEquals(40, focus["progressInfo"]!!.jsonObject["progress"]!!.jsonPrimitive.int)
        assertEquals(false, focus["enableFloat"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun notPromotedKeepsOldLook() {
        val n =
            service()
                .notification(
                    StatusNotice(
                        active = true,
                        busy = true,
                        running = listOf("修 bug"),
                        live = null,
                    ),
                )
        assertFalse(n.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(n.shortCriticalText)
        assertNull("不提升时不上超级岛", n.extras.getString(FocusParam.EXTRA))
        assertEquals(
            AppVariant.current.notificationTitle("Claude 正在工作"),
            n.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertEquals("修 bug", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val idle =
            service()
                .notification(
                    StatusNotice(active = true, busy = false, running = emptyList(), live = null),
                )
        assertEquals(
            AppVariant.current.notificationTitle("Claude 会话保持中"),
            idle.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertFalse(idle.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
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
