package com.dzshzx.zeta.phone.tools

import android.content.Context
import com.dzshzx.zeta.phone.browser.AgentBrowser
import com.dzshzx.zeta.phone.browser.BrowserRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Zeta 内置浏览器：一个工具，用 action 区分动作（照 Eta 的 browser_use）。实现在 [AgentBrowser]。 */
internal object BrowserTools : PhoneToolGroup {
    const val NAME = "browser"

    override val tools: List<JsonElement> = listOf(
        tool(
            NAME,
            "Zeta's built-in web browser (Android WebView), driven off-screen; the user can watch it in Zeta and take over. " +
                "Use it instead of WebFetch when a page needs JavaScript, a login or interaction. Cookies stay inside Zeta. " +
                "open, click, type, scroll, back, forward, reload and wait return a page view: numbered interactive elements in or just below the viewport, plus the visible text. " +
                "Element numbers stay valid on the same page. read returns the full page text in chunks (offset). " +
                "screenshot returns an image of the viewport; click also takes x/y in its pixels. Only http(s) pages; downloads, popups and file uploads are blocked.",
        ) {
            schema(required = listOf("action")) {
                enumProp("action", BrowserRequest.ACTIONS, "What to do.")
                prop("url", "string", "open: the address (https:// is assumed when missing).")
                prop("element", "integer", "click / type: element number from the latest page view. scroll: scroll this element into view.")
                prop("x", "integer", "click: x in screenshot pixels, with y, when the element is not listed.")
                prop("y", "integer", "click: y in screenshot pixels.")
                prop("text", "string", "type: text that replaces the field's content (for a select: the option to pick). wait: wait until this text appears.")
                prop("submit", "boolean", "type: press Enter afterwards (submits the form).")
                enumProp("direction", listOf("up", "down"), "scroll: page direction, default down.")
                prop("offset", "integer", "read: character offset to continue from.")
                prop("ms", "integer", "wait: milliseconds (default 1000; with text it is the timeout, default 10000; max 15000).")
            }
        },
    )

    override fun call(context: Context, name: String, input: JsonObject): JsonObject = AgentBrowser.execute(context, input)

    override fun title(name: String, input: JsonObject): String = BrowserRequest.title(input)
}
