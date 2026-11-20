package com.dzshzx.zeta.shots

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.core.AnswerItem
import com.dzshzx.zeta.core.ToolStatus
import com.dzshzx.zeta.core.ToolStep
import com.dzshzx.zeta.core.UserItem
import com.dzshzx.zeta.core.WorkItem
import com.dzshzx.zeta.shots.Fixtures.showChat
import com.dzshzx.zeta.ui.chat.ChatScreen
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/** 聊天里手机工具的步骤：每个工具一行标题，说法和顶部胶囊、工具权限页取自同一处。 */
class PhoneToolShots : ShotTest() {
    @Test
    fun steps() {
        val hub = Fixtures.readyHub(app)
        hub.showChat("在后台开计算器", turn)
        shoot("chat-phone-tools", prepare = { onNodeWithText("已完成 ${steps.size} 个步骤").performClick() }) {
            ChatScreen(hub = hub, onOpenPane = {}, onOpenSettings = {}, onOpenInstall = {}, onOpenLogin = {})
        }
    }

    private fun step(id: String, tool: String, input: JsonObjectBuilder.() -> Unit) =
        ToolStep(id, "mcp__phone__$tool", buildJsonObject(input), ToolStatus.Success, "好了")

    private val steps = listOf(
        step("p1", "find_apps") { put("query", "计算器") },
        step("p2", "open_app") { put("app", "计算器") },
        step("p3", "screen") { put("screenshot", true) },
        step("p4", "tap") { put("match", "7") },
        step("p5", "tap") { put("node", 12) },
        step("p6", "long_press") { put("x", 300); put("y", 640) },
        step("p7", "scroll") { put("direction", "down") },
        step("p8", "swipe") { put("x1", 300); put("y1", 900); put("x2", 300); put("y2", 300) },
        step("p9", "type_text") { put("text", "1+1") },
        step("p10", "press_key") { put("key", "home") },
        step("p11", "wait") { put("match", "结果") },
        step("p12", "show_to_user") { put("reason", "给你看结果") },
        step("p13", "shell") { put("command", "dumpsys battery | head -3") },
        step("p14", "device_state") { put("action", "set"); put("target", "wifi"); put("value", "on") },
    )

    private val turn = listOf(
        UserItem("u1", "在后台开计算器算一下 1+1，再把 Wi-Fi 打开"),
        WorkItem("w1", steps),
        AnswerItem("a1", "算好了，等于 2；Wi-Fi 已经打开。", streaming = false),
    )
}
