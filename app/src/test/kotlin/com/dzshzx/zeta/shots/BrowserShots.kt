package com.dzshzx.zeta.shots

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dzshzx.zeta.phone.browser.AgentBrowser
import com.dzshzx.zeta.ui.browser.BrowserScreen
import org.junit.After
import org.junit.Test

/**
 * 应用内浏览器页：工具栏、地址栏、接管时浮在底部的提示、“更多”菜单和清除数据的确认框。
 * JVM 上的 WebView 不画网页，页面区域是一块白底；地址、能否后退、接管这些状态直接摆进 [AgentBrowser]。
 */
class BrowserShots : ShotTest() {
    @After
    fun reset() {
        setPage(url = "", canGoBack = false)
        AgentBrowser.handBackByUser()
    }

    @Test
    fun empty() {
        shootBrowser("browser-empty") {}
    }

    @Test
    fun controlling() {
        shootBrowser("browser") { runOnUiThread { showPage() } }
    }

    @Test
    fun menu() {
        shootBrowser("browser-menu") {
            runOnUiThread { showPage() }
            onNodeWithContentDescription("更多").performClick()
        }
    }

    @Test
    fun clearDialog() {
        shootBrowser("browser-clear") {
            runOnUiThread { showPage() }
            onNodeWithContentDescription("更多").performClick()
            mainClock.advanceTimeBy(500)
            waitForIdle()
            onNodeWithText("清除浏览数据").performClick()
        }
    }

    /**
     * 每种配色重新组合时，旧的浏览器页先拆下：WebView 收进屏幕外的窗口（拆下也就交还了，所以接管要在 [prepare] 里再做一次），
     * 新页面再把它挂回来。那个屏幕外的窗口在手机上放在私有虚拟屏上，Robolectric 却把它（2400 像素高的默认底色）叠进截图，先收掉。
     */
    private fun shootBrowser(name: String, prepare: AndroidComposeTestRule<*, *>.() -> Unit) {
        shoot(
            name,
            prepare = {
                runOnUiThread { closeOffscreenWindow() }
                prepare()
            },
        ) { BrowserScreen(onExit = {}) }
    }

    private fun closeOffscreenWindow() {
        val field = AgentBrowser::class.java.getDeclaredField("offscreen").apply { isAccessible = true }
        val window = field.get(AgentBrowser) ?: return
        window.javaClass.getMethod("release").invoke(window)
        field.set(AgentBrowser, null)
    }

    private fun showPage() {
        setPage(url = "https://developer.android.com/develop/ui/compose", canGoBack = true)
        AgentBrowser.takeOverByUser()
    }

    private fun setPage(url: String, canGoBack: Boolean) {
        state<String>("url").value = url
        state<Boolean>("canGoBack").value = canGoBack
    }

    /** AgentBrowser 的界面状态只在它自己里面改（private set），测试直接改背后的 MutableState。 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> state(name: String): MutableState<T> {
        val field = AgentBrowser::class.java.getDeclaredField("$name\$delegate").apply { isAccessible = true }
        return field.get(AgentBrowser) as MutableState<T>
    }
}
