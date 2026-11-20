package com.dzshzx.zeta.phone

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/** 屏幕工具这一步做了什么：聊天（没有现场）和胶囊（有现场）用同一个函数，胶囊能说得更具体。 */
class PhoneToolTitlesTest {
    private fun title(name: String, scene: CallScene? = null, build: JsonObjectBuilder.() -> Unit = {}) =
        PhoneToolCatalog.title(name, buildJsonObject(build), scene)

    private val onCalculator = CallScene(onBackground = true, backgroundApp = "计算器") { index ->
        when (index) {
            3 -> SceneControl("搜索", password = false)
            4 -> SceneControl(null, password = true)
            else -> null
        }
    }

    @Test
    fun chatSaysWhatTheInputSays() {
        assertEquals("看屏幕（带截图）", title("screen") { put("screenshot", true) })
        assertEquals("点按「7」", title("tap") { put("match", "7") })
        assertEquals("点按控件 12", title("tap") { put("node", 12) })
        assertEquals("点按 id=search", title("tap") { put("id", "search") })
        assertEquals("长按 (300, 640)", title("long_press") { put("x", 300); put("y", 640) })
        assertEquals("点按屏幕", title("tap"))
        assertEquals("向下滚动", title("scroll") { put("direction", "down") })
        assertEquals("向上滚动「设置」", title("scroll") { put("direction", "up"); put("match", "设置") })
        assertEquals("向上滑动", title("swipe") { put("x1", 300); put("y1", 900); put("x2", 300); put("y2", 300) })
        assertEquals("向右滑动", title("swipe") { put("x1", 100); put("y1", 900); put("x2", 600); put("y2", 880) })
        assertEquals("输入「1+1」", title("type_text") { put("text", "1+1") })
        assertEquals("清空输入框", title("type_text") { put("text", "") })
        assertEquals("等「结果」出现", title("wait") { put("match", "结果") })
        assertEquals("等 id=progress 消失", title("wait") { put("id", "progress"); put("gone", true) })
        assertEquals("等微信打开", title("wait") { put("app", "微信") })
        assertEquals("等 com.tencent.mm 打开", title("wait") { put("app", "com.tencent.mm") })
        assertEquals("等待 1.5 秒", title("wait") { put("duration_ms", 1500) })
        assertEquals("打开计算器", title("open_app") { put("app", "计算器") })
        assertEquals("打开 com.tencent.mm", title("open_app") { put("app", "com.tencent.mm") })
        assertEquals("打开链接 example.com/a", title("open_app") { put("uri", "https://example.com/a") })
        assertEquals("打开设置页 WIFI_SETTINGS", title("open_app") { put("action", "android.settings.WIFI_SETTINGS") })
        assertEquals("把应用移到主屏", title("show_to_user"))
        assertEquals("查找应用 计算器", title("find_apps") { put("query", "计算器") })
        assertEquals("Android：dumpsys battery", title("shell") { put("command", "\ndumpsys battery\nhead") })
    }

    /** 分叉过的按键说法统一成一套：说做了什么，不说按的是哪个键（「主屏」在 Zeta 里指主显示屏）。 */
    @Test
    fun keysSayWhatHappens() {
        val keys = listOf("back", "home", "recents", "enter", "delete", "paste", "wake", "notifications", "quick_settings", "volume_up")
        assertEquals(
            listOf("按返回键", "回到桌面", "打开最近任务", "按回车", "按退格", "粘贴", "点亮屏幕", "下拉通知栏", "下拉控制中心", "按 volume_up"),
            keys.map { key -> title("press_key") { put("key", key) } },
        )
    }

    @Test
    fun capsuleNamesTheControlAndTheApp() {
        assertEquals("点按「搜索」", title("tap", onCalculator) { put("node", 3) })
        // 编号对不上字的控件：说在后台操作哪个应用
        assertEquals("在后台操作计算器", title("tap", onCalculator) { put("node", 9) })
        assertEquals("在后台操作计算器", title("long_press", onCalculator) { put("x", 1); put("y", 2) })
        assertEquals("输入密码", title("type_text", onCalculator) { put("node", 4); put("text", "secret") })
        assertEquals("把计算器移到主屏", title("show_to_user", onCalculator))
        // 主屏上没有“在后台操作”这一说
        val main = CallScene(onBackground = false, backgroundApp = "计算器") { null }
        assertEquals("点按控件 9", title("tap", main) { put("node", 9) })
        assertEquals("把计算器移到主屏", title("show_to_user", main))
    }
}
