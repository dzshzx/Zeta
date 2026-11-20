package com.dzshzx.zeta.phone

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipLine
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.str
import com.dzshzx.zeta.phone.tools.PhoneTool
import com.dzshzx.zeta.phone.tools.enumProp
import com.dzshzx.zeta.phone.tools.prop
import com.dzshzx.zeta.phone.tools.schema
import com.dzshzx.zeta.phone.tools.tool
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 屏幕这一套手机工具的说明，纯数据：交给 Claude 的定义（[tools]，带工具权限页上的中文名字）和这一步做了什么的一句中文（[title]）。
 * 执行在 [PhoneTools]：记控件编号、切屏、和用户在后台屏幕上的手势互斥，所以不走工具组。
 */
internal object ScreenToolDefs {
    private fun JsonObjectBuilder.after() =
        prop("screenshot", "boolean", "Also return a screenshot taken after the action. Default false.")

    /** 按文字、资源 id 在屏上现找控件（不用先看屏、记编号）。 */
    private fun JsonObjectBuilder.target(insteadOfNode: Boolean = true) {
        val instead = if (insteadOfNode) " instead of node" else ""
        prop("match", "string", "Find the control by its text or content description$instead: exact match first, else the start, else contained; not case-sensitive.")
        prop("id", "string", "Find the control by resource id (e.g. com.android.settings:id/search or just search)$instead.")
    }

    /**
     * [tools] 里的工具这一步做了什么（不是这一套的返回 null）。有 [scene]（胶囊）时按编号说出控件上的字，
     * 说不出字、在后台屏幕上时说在操作哪个应用；没有（聊天里）时照参数说。
     */
    fun title(name: String, a: JsonObject, scene: CallScene?): String? {
        val node = a.int("node")
        val control = node?.let { scene?.control(it) }
        // 说得出字的目标：编号对应的控件上的字，或者按文字找时要找的那段
        val text = control?.label ?: a.str("match")?.takeIf { it.isNotBlank() }?.clipLine(12)
        // 说不出字的目标：资源 id、编号、坐标
        val other = a.str("id")?.takeIf { it.isNotBlank() }?.let { "id=$it" } ?: node?.let { "控件 $it" }
            ?: a.int("x")?.let { "($it, ${a.int("y") ?: 0})" }
        val app = scene?.takeIf { it.onAgentDisplay }?.agentDisplayApp
        fun aimed(verb: String) = text?.let { "$verb「$it」" } ?: app?.let { "在后台操作$it" } ?: other?.let { verb + gap(it) + it }
        return when (name) {
            "screen" -> if (a.bool("screenshot") == true) "看屏幕（带截图）" else "看屏幕"
            "tap" -> aimed("点按") ?: "点按屏幕"
            "long_press" -> aimed("长按") ?: "长按屏幕"
            "scroll" -> {
                val verb = (ScrollDirection.parse(a.str("direction"))?.word ?: "") + "滚动"
                text?.let { "$verb「$it」" } ?: other?.let { verb + gap(it) + it } ?: verb
            }
            "swipe" -> {
                val dx = (a.int("x2") ?: 0) - (a.int("x1") ?: 0)
                val dy = (a.int("y2") ?: 0) - (a.int("y1") ?: 0)
                when {
                    dx == 0 && dy == 0 -> "滑动"
                    abs(dy) >= abs(dx) -> if (dy < 0) "向上滑动" else "向下滑动"
                    else -> if (dx < 0) "向左滑动" else "向右滑动"
                }
            }
            "type_text" -> {
                val value = a.str("text").orEmpty()
                when {
                    value.isEmpty() -> "清空输入框"
                    // 密码框不把内容显示出来
                    control?.password == true -> "输入密码"
                    else -> "输入「" + value.clipLine(16) + "」"
                }
            }
            "press_key" -> when (val key = a.str("key")?.lowercase()?.takeIf { it.isNotBlank() }) {
                "back" -> "按返回键"
                "home" -> "回到桌面"
                "recents" -> "打开最近任务"
                "enter" -> "按回车"
                "delete" -> "按退格"
                "paste" -> "粘贴"
                "wake" -> "点亮屏幕"
                "notifications" -> "下拉通知栏"
                "quick_settings" -> "下拉控制中心"
                null -> "按键"
                else -> "按 $key"
            }
            "wait" -> when (val request = WaitRequest.parse(a).value) {
                is WaitRequest.Control -> "等" + spaced(request.selector.describe()) + if (request.gone) "消失" else "出现"
                is WaitRequest.App -> "等" + spaced(request.app) + "打开"
                is WaitRequest.Time -> "等待 " + "%.1f".format(Locale.ROOT, request.ms / 1000.0).removeSuffix(".0") + " 秒"
                null -> "等待"
            }
            "open_app" -> {
                val request = OpenRequest.parse(a).value
                val action = request?.action
                when {
                    request?.uri != null -> "打开链接 " + request.uri.removePrefix("https://").removePrefix("http://").clipLine(24)
                    action != null && action.startsWith("android.settings.") -> "打开设置页 " + action.substringAfterLast('.')
                    action != null -> "打开 " + action.substringAfterLast('.')
                    request?.app != null -> "打开" + gap(request.app) + request.app.clipLine(16)
                    else -> "打开应用"
                }
            }
            "show_to_user" -> "把" + (scene?.agentDisplayApp ?: "应用") + "移到主屏"
            else -> null
        }
    }

    /** 中文和紧跟着的英文、数字、括号之间空一格（「点按 id=search」），和中文之间不空（「点按控件 3」）。 */
    private fun gap(next: String): String = if (next.firstOrNull()?.let { it.code < 128 } == true) " " else ""

    /** 夹在两段中文之间：两头是英文、数字时各空一格（「等 com.android.settings 打开」「等微信打开」）。 */
    private fun spaced(word: String): String = gap(word) + word + if (word.lastOrNull()?.let { it.code < 128 } == true) " " else ""

    val tools: List<PhoneTool> = buildList {
        add(tool("screen", "读屏：前台应用、可点的控件和截图", "Read the current screen: the foreground app and a numbered list of visible controls (text, kind, centre in screenshot pixels, what can be done with each). Optionally a screenshot.", readOnly = true) {
            schema {
                enumProp("display", listOf("main", "background"), "Switch to this screen first: main (what the user sees) or background (this conversation's hidden screen). Omit to stay on the current one.")
                prop("screenshot", "boolean", "Also return a screenshot. Default false; use it when the list of controls is not enough.")
                prop("max_nodes", "integer", "How many controls to list at most (10-250, default 80).")
            }
        })
        add(tool("tap", "点按", "Tap a control (by its index from the latest observation, or found by text / resource id) or a point on the screen.") {
            schema {
                prop("node", "integer", "Index of the control to tap.")
                target()
                prop("x", "integer", "X in screenshot pixels, when not using node or match/id.")
                prop("y", "integer", "Y in screenshot pixels, when not using node or match/id.")
                after()
            }
        })
        add(tool("long_press", "长按", "Press and hold a control or a point.") {
            schema {
                prop("node", "integer", "Index of the control.")
                target()
                prop("x", "integer", "X in screenshot pixels, when not using node or match/id.")
                prop("y", "integer", "Y in screenshot pixels, when not using node or match/id.")
                prop("duration_ms", "integer", "How long to hold (300-5000, default 700).")
                after()
            }
        })
        add(tool("swipe", "滑动", "Swipe from one point to another, for precise drags (sliders, maps, carousels). To browse a list use scroll.") {
            schema(required = listOf("x1", "y1", "x2", "y2")) {
                prop("x1", "integer", "Start X in screenshot pixels.")
                prop("y1", "integer", "Start Y in screenshot pixels.")
                prop("x2", "integer", "End X in screenshot pixels.")
                prop("y2", "integer", "End Y in screenshot pixels.")
                prop("duration_ms", "integer", "Duration (50-5000, default 300). Slower swipes scroll more precisely.")
                after()
            }
        })
        add(tool("scroll", "滚动列表", "Scroll the main list of the screen, or the list containing the control given by node / match / id, and report whether the content moved and whether the end was reached.") {
            schema(required = listOf("direction")) {
                enumProp("direction", listOf("up", "down", "left", "right"), "Where the new content should come from: down reveals content further down.")
                enumProp("amount", listOf("page", "small"), "page (default) is about one screen; small nudges an item into view.")
                prop("node", "integer", "Index of the list, or of any control inside it.")
                target()
                after()
            }
        })
        add(tool("type_text", "往输入框里输入文字", "Put text into an input field: the one given by node or match/id, or else the field that has focus. Replaces what is there unless append is true; an empty text clears the field. The result says if the field reads back differently.") {
            schema(required = listOf("text")) {
                prop("text", "string", "The text to enter. Empty clears the field.")
                prop("node", "integer", "Index of the input field. Omit to use the focused field.")
                prop("match", "string", "Find the field by its text, hint or content description instead of node.")
                prop("id", "string", "Find the field by resource id instead of node.")
                prop("append", "boolean", "Add to the existing text instead of replacing it. Default false.")
                prop("submit", "boolean", "Press Enter / the keyboard's action button afterwards. Default false.")
                after()
            }
        })
        add(tool("press_key", "按系统键（返回、回到桌面等）", "Press a system key.") {
            schema(required = listOf("key")) {
                putJsonObject("key") {
                    put("type", "string")
                    put("enum", JsonArray(listOf("back", "home", "recents", "enter", "delete", "paste", "wake", "notifications", "quick_settings").map { JsonPrimitive(it) }))
                    put("description", "back, home, recents (app switcher), enter, delete (backspace), paste (the clipboard into the focused field), " +
                        "wake (turn the screen on), notifications (open the shade), quick_settings. " +
                        "home and recents do not exist on the background screen; wake, notifications and quick_settings always act on the main screen.")
                }
                after()
            }
        })
        add(tool(
            "wait",
            "等控件出现或应用到前台，再读屏",
            "Wait, then return the fresh screen: for a fixed time, until a control matching match/id appears (or is gone), or until an app is in front. " +
                "Fails with the current screen if the condition is not met in time.",
            readOnly = true,
        ) {
            schema {
                target(insteadOfNode = false)
                prop("gone", "boolean", "With match/id: wait until no such control is left. Default false.")
                prop("app", "string", "Wait until this app (name or package) is in front on the current screen.")
                prop("timeout_ms", "integer", "Give up after this long when waiting for a control or an app (500-60000, default 10000).")
                prop("duration_ms", "integer", "Without match/id/app: just wait this long (100-30000, default 1000).")
                after()
            }
        })
        add(tool(
            "open_app",
            "打开应用、链接或设置页",
            "Open an app, a link or deep link (uri), or an intent action such as a settings page, by default on the hidden background screen. " +
                "Later tools act on the screen it opened on.",
        ) {
            schema {
                prop("app", "string", "App name as shown on the home screen (e.g. 微信) or package name (e.g. com.tencent.mm). With uri/action: the app that should handle it.")
                prop("uri", "string", "A link or deep link to open (https://..., geo:..., an app's own scheme). Only real URIs, never made-up ones.")
                prop("action", "string", "An intent action, e.g. android.settings.WIFI_SETTINGS, android.settings.APPLICATION_DETAILS_SETTINGS (with uri package:<name>). Default with uri: VIEW.")
                enumProp(
                    "display", listOf("auto", "background", "main"),
                    "auto (default): the background screen, except the home screen, system UI and apps already in front of the user, which stay on the main screen. " +
                        "background: insist on the background screen. main: open it in front of the user.",
                )
                after()
            }
        })
        add(tool("show_to_user", "把后台屏幕上的应用移到主屏给你看", "Move the app on the background screen to the main screen, in front of the user, for steps they must do themselves (payment, password, captcha, a login or permission prompt) or to show them the result. Afterwards tools act on the main screen.") {
            schema {
                prop("reason", "string", "Short reason, e.g. 需要你输入支付密码.")
                after()
            }
        })
    }
}
