package com.dzshzx.zeta.phone.browser

import com.dzshzx.zeta.core.bool
import com.dzshzx.zeta.core.clipChars
import com.dzshzx.zeta.core.int
import com.dzshzx.zeta.core.safeCut
import com.dzshzx.zeta.core.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 浏览器工具里不碰 Android 的部分：参数解析、网址规则、把页面脚本交回的 JSON 整理成给 Claude 看的精简文字。
 * 都是纯函数，单元测试在 BrowserPageTest。
 */

/** 页面里一个可交互的元素（页面脚本提取，坐标是 CSS 像素、相对视口）。[id] 是交给 Claude 的编号，同一个页面里不变。 */
internal data class PageElement(
    val id: Int,
    val tag: String,
    val name: String = "",
    val role: String? = null,
    val type: String? = null,
    val value: String? = null,
    val placeholder: String? = null,
    val href: String? = null,
    val checked: Boolean? = null,
    val expanded: Boolean? = null,
    val disabled: Boolean = false,
    val editable: Boolean = false,
    val options: Int? = null,
    val top: Int = 0,
    val bottom: Int = 0,
)

/** 页面脚本交回的一份页面概况。 */
internal data class PageSnapshot(
    val url: String,
    val title: String,
    val scrollY: Int,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageHeight: Int,
    val elements: List<PageElement>,
    /** 视口里看得见的文字，块与块之间换行。 */
    val text: String,
    /** 页面脚本那一侧就截断了（字太多或超时）。 */
    val textCut: Boolean,
    /** 页面上可交互元素的总数（扫描到的，不一定都列出）。 */
    val total: Int,
    /** 下一个可用的编号：跨页面递增，旧页面的编号不会落到新页面的元素上。 */
    val next: Int,
)

/** 交给 Claude 的上限。 */
internal object BrowserLimits {
    /** 页面概况里列出的元素：视口里的最多这么多。 */
    const val VIEWPORT_ELEMENTS = 60

    /** 视口下方（再滚一下就到）的元素最多列这么多，好让 Claude 知道往下有什么。 */
    const val BELOW_ELEMENTS = 12

    /** 页面概况里的可见文字。 */
    const val VIEW_TEXT = 2_500

    /** read 一次最多给的字数。 */
    const val READ_TEXT = 8_000

    /** 元素名字、值的长度。 */
    const val NAME = 80
    const val HREF = 120

    /** 截图的长边（像素）。 */
    const val SHOT_EDGE = 1280

    /** 等待的上限。 */
    const val WAIT_MS = 15_000
}

internal object BrowserPage {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * evaluateJavascript 交回的原始值：页面脚本 `return JSON.stringify(...)`，所以外面又包了一层 JSON 字符串。
     * 解不开、脚本没有返回时返回 null。
     */
    fun decode(raw: String?): JsonObject? {
        if (raw == null || raw == "null" || raw.isBlank()) return null
        val outer = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return null
        val inner = when (outer) {
            is JsonPrimitive -> if (outer.isString) runCatching { json.parseToJsonElement(outer.content) }.getOrNull() else null
            else -> outer
        }
        return inner as? JsonObject
    }

    fun parseSnapshot(value: JsonObject): PageSnapshot = PageSnapshot(
        url = value.str("url").orEmpty(),
        title = value.str("title").orEmpty(),
        scrollY = value.num("scrollY"),
        viewportWidth = value.num("vw"),
        viewportHeight = value.num("vh"),
        pageHeight = value.num("height"),
        elements = (value["elements"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::element) },
        text = value.str("text").orEmpty(),
        textCut = value.bool("textCut") == true,
        total = value.num("total"),
        next = value.num("next"),
    )

    /** 页面脚本描述的一个元素；没有编号的返回 null。 */
    fun element(e: JsonObject): PageElement? {
        val id = e.num("id").takeIf { it > 0 } ?: return null
        return PageElement(
            id = id,
            tag = e.str("tag").orEmpty(),
            name = e.str("name").orEmpty(),
            role = e.str("role")?.takeIf { it.isNotBlank() },
            type = e.str("type")?.takeIf { it.isNotBlank() },
            value = e.str("value"),
            placeholder = e.str("placeholder")?.takeIf { it.isNotBlank() },
            href = e.str("href")?.takeIf { it.isNotBlank() },
            checked = (e["checked"] as? JsonPrimitive)?.booleanOrNull,
            expanded = (e["expanded"] as? JsonPrimitive)?.booleanOrNull,
            disabled = e.bool("disabled") == true,
            editable = e.bool("editable") == true,
            options = (e["options"] as? JsonPrimitive)?.intOrNull,
            top = e.num("top"),
            bottom = e.num("bottom"),
        )
    }

    private fun JsonObject.num(key: String): Int = (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.roundToInt() } ?: 0

    /** 元素是什么（中文），给列表和动作结果用。 */
    fun kind(e: PageElement): String {
        val type = e.type?.lowercase()
        return when (e.role?.lowercase()) {
            "button" -> "按钮"
            "link" -> "链接"
            "checkbox", "menuitemcheckbox" -> "复选框"
            "radio", "menuitemradio" -> "单选项"
            "switch" -> "开关"
            "tab" -> "标签页"
            "menuitem" -> "菜单项"
            "option" -> "选项"
            "combobox" -> "组合框"
            "textbox", "searchbox" -> "输入框"
            "slider" -> "滑块"
            else -> when (e.tag.lowercase()) {
                "a" -> "链接"
                "button" -> "按钮"
                "select" -> "下拉框"
                "textarea" -> "多行输入框"
                "summary" -> "折叠标题"
                "input" -> when (type) {
                    "checkbox" -> "复选框"
                    "radio" -> "单选项"
                    "submit", "button", "reset", "image" -> "按钮"
                    "range" -> "滑块"
                    "file" -> "文件选择"
                    "password" -> "密码框"
                    null, "", "text" -> "输入框"
                    else -> "输入框($type)"
                }
                else -> if (e.editable) "可编辑区" else "可点元素"
            }
        }
    }

    /** 一个元素的一行：`[12] 链接 "更多" → https://…`。 */
    fun line(e: PageElement): String = buildString {
        append('[').append(e.id).append("] ").append(kind(e))
        if (e.name.isNotBlank()) append(" \"").append(clip(e.name, BrowserLimits.NAME)).append('"')
        e.placeholder?.takeIf { it != e.name }?.let { append(" 提示=\"").append(clip(it, BrowserLimits.NAME)).append('"') }
        if (e.tag.equals("select", true)) {
            append(" 当前=\"").append(clip(e.value.orEmpty(), BrowserLimits.NAME)).append('"')
            e.options?.let { append("（").append(it).append(" 个选项）") }
        } else if (e.checked == null) {
            e.value?.takeIf { it.isNotEmpty() }?.let { append(" 值=\"").append(clip(it, BrowserLimits.NAME)).append('"') }
        }
        e.href?.takeIf { !it.startsWith("javascript:", true) }?.let { append(" → ").append(clip(it, BrowserLimits.HREF)) }
        val flags = buildList {
            e.checked?.let { add(if (it) "已选" else "未选") }
            e.expanded?.let { add(if (it) "已展开" else "已收起") }
            if (e.disabled) add("不可用")
        }
        if (flags.isNotEmpty()) append(' ').append(flags.joinToString("·"))
    }

    /**
     * 动作之后交给 Claude 的页面概况：地址和位置、视口里（和紧挨着下方）的可交互元素编号列表、视口里的文字。都有条数和长度上限。
     */
    fun render(page: PageSnapshot, loading: Boolean = false): String = buildString {
        append('「').append(clip(page.title.ifBlank { "无标题" }, 100)).append("」 ").append(page.url).append('\n')
        val vh = max(page.viewportHeight, 1)
        val height = max(page.pageHeight, vh)
        val top = page.scrollY.coerceIn(0, height)
        val bottom = min(top + vh, height)
        append("视口 ").append(page.viewportWidth).append('×').append(vh).append("（CSS 像素）")
        if (height <= vh + 2) {
            append("，整页都在视口里")
        } else {
            append("；页面高 ").append(height).append("，看到的是 ").append(top).append('–').append(bottom)
            val below = height - bottom
            append(if (below > 2) "，下面还有 $below" else "，已到底")
        }
        if (loading) append("；页面还在加载")
        append('\n')

        val inView = page.elements.filter { it.bottom > 0 && it.top < vh }.sortedWith(compareBy({ it.top }, { it.id }))
        val below = page.elements.filter { it.top >= vh }.sortedBy { it.top }
        val above = page.elements.count { it.bottom <= 0 }
        val shown = inView.take(BrowserLimits.VIEWPORT_ELEMENTS)
        val shownBelow = below.take(BrowserLimits.BELOW_ELEMENTS)
        if (shown.isEmpty() && shownBelow.isEmpty()) {
            append("\n视口里没有可交互元素")
        } else {
            append("\n可交互元素（click / type 用方括号里的编号）：\n")
            shown.forEach { append(line(it)).append('\n') }
            if (inView.size > shown.size) append("…视口里还有 ").append(inView.size - shown.size).append(" 个没列出\n")
            if (shownBelow.isNotEmpty()) {
                append("视口下方：\n")
                shownBelow.forEach { append(line(it)).append('\n') }
            }
            val hidden = (below.size - shownBelow.size) + above + max(0, page.total - page.elements.size)
            if (hidden > 0) append("（视口外还有约 ").append(hidden).append(" 个，滚动后会列出）\n")
        }
        val text = normalizeText(page.text)
        if (text.isNotEmpty()) {
            append("\n视口里的文字：\n")
            if (text.length > BrowserLimits.VIEW_TEXT) {
                append(text.take(BrowserLimits.VIEW_TEXT)).append("…\n（只给了前 ").append(BrowserLimits.VIEW_TEXT).append(" 字；read 读全文）")
            } else {
                append(text)
                if (page.textCut) append("\n…（文字太多，没取完；read 读全文）")
            }
        } else {
            append("\n视口里没有文字")
        }
    }.trimEnd()

    /** 收拢空白：每行去掉首尾空白、连续空格并成一个，空行最多留一行。 */
    fun normalizeText(raw: String): String {
        val lines = raw.replace("\r", "").split('\n').map { it.replace(SPACES, " ").trim() }
        val out = StringBuilder()
        var blank = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blank++
                continue
            }
            if (out.isNotEmpty()) out.append(if (blank > 0) "\n\n" else "\n")
            out.append(line)
            blank = 0
        }
        return out.toString()
    }

    /** read：从 [offset] 起给最多 [limit] 字，并说清楚一共多少、下一段从哪里接。 */
    fun readSlice(text: String, offset: Int, limit: Int = BrowserLimits.READ_TEXT): String {
        val all = normalizeText(text)
        if (all.isEmpty()) return "页面上没有文字"
        val start = offset.coerceIn(0, all.length)
        if (start >= all.length) return "已经读完了：全文共 ${all.length} 字，offset $offset 超出了末尾"
        val end = safeCut(all, min(all.length, start + limit), start)
        return buildString {
            append(all, start, end)
            append("\n\n（第 ").append(start).append('–').append(end).append(" 字，全文共 ").append(all.length).append(" 字")
            if (end < all.length) append("；接着读传 offset: ").append(end)
            append('）')
        }
    }

    fun clip(value: String, limit: Int): String = value.replace(SPACES, " ").trim().clipChars(limit, "…")

    private val SPACES = Regex("[\\s\\u00a0]+")
}

/** 网址的规则：只开 http / https。 */
internal object BrowserUrls {
    /** 页面自己跳转、重定向时放行的网址（其余的拦下）。about:blank 是空白页。 */
    fun allowed(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https" || url.equals("about:blank", true)
    }

    /** Claude 给的网址：没写协议的补 https://；不是 http(s) 的拒绝。返回 (网址, 问题)。 */
    fun forAgent(raw: String?): Pair<String?, String?> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null to "open 要给 url"
        val withScheme = if (hasScheme(value)) value else if (value.startsWith("//")) "https:$value" else "https://$value"
        val scheme = withScheme.substringBefore(':').lowercase()
        if (scheme != "http" && scheme != "https") return null to "只能打开 http / https 网页，不能打开 $scheme: 开头的地址"
        val host = runCatching { URI(withScheme).host }.getOrNull()
        if (host.isNullOrBlank()) return null to "网址不对：$value"
        return withScheme to null
    }

    /** 地址栏的输入：像网址就打开，否则当搜索词。不是 http(s) 的网址返回 null。 */
    fun fromAddressBar(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return null
        if (hasScheme(value)) return forAgent(value).first
        val looksLikeHost = value.none { it.isWhitespace() } && (value.contains('.') || value.startsWith("localhost", true)) &&
            runCatching { URI("https://$value").host }.getOrNull()?.let { it.contains('.') || it.equals("localhost", true) } == true
        if (looksLikeHost) return "https://$value"
        return SEARCH + URLEncoder.encode(value, Charsets.UTF_8)
    }

    /** 拦下的地址给 Claude 看的样子：太长的截断。 */
    fun describe(url: String): String = BrowserPage.clip(url, 160)

    /** 显示用的主机名（地址栏没在编辑时显示它）。 */
    fun host(url: String): String = runCatching { URI(url).host }.getOrNull().orEmpty()

    /** 开头是不是协议（`https:`、`javascript:`）；`localhost:8080` 这种主机加端口不算。 */
    private fun hasScheme(value: String): Boolean = SCHEME.containsMatchIn(value) && !HOST_PORT.containsMatchIn(value)

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOST_PORT = Regex("^[a-zA-Z0-9.-]+:\\d+(/|\\?|#|$)")
    private const val SEARCH = "https://www.bing.com/search?q="
}

/** 一次 browser 调用的参数，校验过的。 */
internal data class BrowserRequest(
    val action: String,
    val url: String? = null,
    val element: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val text: String? = null,
    val submit: Boolean = false,
    val direction: String = "down",
    val offset: Int = 0,
    val ms: Int = 0,
) {
    companion object {
        val ACTIONS = listOf("open", "read", "click", "type", "scroll", "back", "forward", "reload", "screenshot", "wait")

        /** 解析并校验；有问题时返回 (null, 说明)。 */
        fun parse(input: JsonObject): Pair<BrowserRequest?, String?> {
            val action = input.str("action")?.trim()?.lowercase().orEmpty()
            if (action !in ACTIONS) return null to "action 要是 ${ACTIONS.joinToString(" / ")} 之一"
            val element = input.int("element")
            val x = input.int("x")
            val y = input.int("y")
            if ((x == null) != (y == null)) return null to "x 和 y 要一起给"
            val text = input.str("text")
            val request = BrowserRequest(
                action = action,
                url = input.str("url"),
                element = element,
                x = x,
                y = y,
                text = text,
                submit = input.bool("submit") == true,
                direction = input.str("direction")?.lowercase() ?: "down",
                offset = (input.int("offset") ?: 0).coerceAtLeast(0),
                ms = input.int("ms") ?: 0,
            )
            val problem = when (action) {
                "open" -> BrowserUrls.forAgent(request.url).second
                "click" -> if (element == null && x == null) "click 要给 element（页面概况里的编号），或者截图上的 x、y" else null
                "type" -> when {
                    text == null -> "type 要给 text"
                    else -> null
                }
                "scroll" -> if (request.direction !in setOf("up", "down")) "direction 只能是 up 或 down" else null
                else -> null
            }
            return if (problem != null) null to problem else request to null
        }

        /** 聊天里和胶囊上显示的一句中文。 */
        fun title(input: JsonObject): String {
            val element = input.int("element")?.let { "[$it]" }
            return when (input.str("action")?.lowercase()) {
                "open" -> "打开网页 " + (input.str("url")?.let { BrowserUrls.host(BrowserUrls.forAgent(it).first ?: it).ifEmpty { BrowserPage.clip(it, 40) } } ?: "")
                "read" -> "读网页全文"
                "click" -> "点按网页里的" + (element ?: input.int("x")?.let { "(${it}, ${input.int("y") ?: 0})" } ?: "元素")
                "type" -> "在网页里输入“" + BrowserPage.clip(input.str("text").orEmpty(), 24) + "”" + if (input.bool("submit") == true) "并提交" else ""
                "scroll" -> if (element != null) "网页滚到 $element" else if (input.str("direction") == "up") "网页向上滚动" else "网页向下滚动"
                "back" -> "网页后退"
                "forward" -> "网页前进"
                "reload" -> "刷新网页"
                "screenshot" -> "网页截图"
                "wait" -> input.str("text")?.let { "等网页出现「" + BrowserPage.clip(it, 20) + "」" }
                    ?: ("等待网页 " + "%.1f".format(((input.int("ms") ?: 1000).coerceIn(100, BrowserLimits.WAIT_MS)) / 1000.0).removeSuffix(".0") + " 秒")
                else -> "浏览器"
            }.trim()
        }
    }
}

/** 截图的大小：WebView 按比例缩到长边不超过 [BrowserLimits.SHOT_EDGE]。click 的 x、y 用这套像素。 */
internal data class ShotSize(val viewWidth: Int, val viewHeight: Int) {
    val scale: Float = min(1f, BrowserLimits.SHOT_EDGE / max(max(viewWidth, viewHeight), 1).toFloat())
    val width: Int get() = max(1, (viewWidth * scale).roundToInt())
    val height: Int get() = max(1, (viewHeight * scale).roundToInt())

    /** 截图上的一点换成视口里的比例（0..1），页面脚本再按视口的 CSS 尺寸换算。点在截图外时返回 null。 */
    fun fraction(x: Int, y: Int): Pair<Double, Double>? {
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        return (x + 0.5) / width to (y + 0.5) / height
    }
}
