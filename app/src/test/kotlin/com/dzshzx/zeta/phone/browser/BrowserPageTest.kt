package com.dzshzx.zeta.phone.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun decodeUnwrapsTheStringifiedResult() {
        // evaluateJavascript 交回的是 JSON 编码过的字符串
        val raw = JsonPrimitive("""{"ok":true,"n":3}""").toString()
        assertEquals(3, BrowserPage.decode(raw)!!["n"].toString().toInt())
        assertNull(BrowserPage.decode("null"))
        assertNull(BrowserPage.decode(null))
        assertNull(BrowserPage.decode("\"not json\""))
        assertEquals(
            "x",
            (BrowserPage.decode("""{"error":"x"}""")!!["error"] as JsonPrimitive).content,
        )
    }

    private val sample =
        json(
            """
            {"url":"https://example.com/a","title":"Example Domain","scrollY":0,"vw":412,"vh":800,"height":2000,"total":4,"next":9,
             "text":"Example Domain\n  This domain is   for use in examples.\n\n\n\nMore information...","textCut":false,
             "elements":[
               {"id":3,"tag":"a","name":"More information...","href":"https://www.iana.org/domains/example","top":300,"bottom":320},
               {"id":4,"tag":"input","type":"search","name":"","placeholder":"Search","value":"abc","top":100,"bottom":140},
               {"id":5,"tag":"button","name":"Go","disabled":true,"top":100,"bottom":140},
               {"id":6,"tag":"input","type":"checkbox","name":"Remember me","checked":true,"top":900,"bottom":920},
               {"tag":"a","name":"no id"}
             ]}
            """
                .trimIndent(),
        )

    @Test
    fun parseSnapshotKeepsElementsWithIds() {
        val page = BrowserPage.parseSnapshot(sample)
        assertEquals("https://example.com/a", page.url)
        assertEquals(4, page.elements.size)
        assertEquals(9, page.next)
        val box = page.elements.first { it.id == 4 }
        assertEquals("search", box.type)
        assertEquals("Search", box.placeholder)
        assertEquals(true, page.elements.first { it.id == 6 }.checked)
    }

    @Test
    fun elementLinesSayWhatEachElementIs() {
        val page = BrowserPage.parseSnapshot(sample)
        val byId = page.elements.associateBy { it.id }
        assertEquals(
            "[3] 链接 \"More information...\" → https://www.iana.org/domains/example",
            BrowserPage.line(byId.getValue(3)),
        )
        assertEquals("[4] 输入框(search) 提示=\"Search\" 值=\"abc\"", BrowserPage.line(byId.getValue(4)))
        assertEquals("[5] 按钮 \"Go\" 不可用", BrowserPage.line(byId.getValue(5)))
        assertEquals("[6] 复选框 \"Remember me\" 已选", BrowserPage.line(byId.getValue(6)))
        val select =
            PageElement(id = 7, tag = "select", name = "Language", value = "English", options = 12)
        assertEquals("[7] 下拉框 \"Language\" 当前=\"English\"（12 个选项）", BrowserPage.line(select))
        val role =
            PageElement(id = 8, tag = "div", name = "Menu", role = "button", expanded = false)
        assertEquals("[8] 按钮 \"Menu\" 已收起", BrowserPage.line(role))
        val js = PageElement(id = 9, tag = "a", name = "x", href = "javascript:void(0)")
        assertEquals("[9] 链接 \"x\"", BrowserPage.line(js))
    }

    @Test
    fun renderListsViewportElementsInOrderThenBelow() {
        val text = BrowserPage.render(BrowserPage.parseSnapshot(sample))
        val lines = text.lines()
        assertEquals("「Example Domain」 https://example.com/a", lines[0])
        assertEquals("视口 412×800（CSS 像素）；页面高 2000，看到的是 0–800，下面还有 1200", lines[1])
        // 视口里的按位置排：4、5（top 100）在 3（top 300）前面；6 在视口下方
        val i4 = lines.indexOfFirst { it.startsWith("[4]") }
        val i5 = lines.indexOfFirst { it.startsWith("[5]") }
        val i3 = lines.indexOfFirst { it.startsWith("[3]") }
        val below = lines.indexOf("视口下方：")
        val i6 = lines.indexOfFirst { it.startsWith("[6]") }
        assertTrue(i4 in 0 until i5 && i5 < i3 && i3 < below && below < i6)
        // 文字收拢空白、空行最多一行
        assertTrue(
            text.endsWith(
                "视口里的文字：\nExample Domain\nThis domain is for use in examples.\n\nMore information...",
            ),
        )
    }

    @Test
    fun renderCapsElementsAndText() {
        val many =
            (1..100).map {
                PageElement(id = it, tag = "a", name = "link $it", top = it, bottom = it + 5)
            } +
                (101..150).map {
                    PageElement(
                        id = it,
                        tag = "a",
                        name = "below $it",
                        top = 900 + it,
                        bottom = 920 + it,
                    )
                }
        val page =
            PageSnapshot(
                url = "https://x.test/",
                title = "",
                scrollY = 0,
                viewportWidth = 400,
                viewportHeight = 800,
                pageHeight = 5000,
                elements = many,
                text = "字".repeat(BrowserLimits.VIEW_TEXT + 100),
                textCut = true,
                total = 150,
                next = 151,
            )
        val text = BrowserPage.render(page, loading = true)
        assertTrue(text.contains("页面还在加载"))
        assertEquals(
            BrowserLimits.VIEWPORT_ELEMENTS + BrowserLimits.BELOW_ELEMENTS,
            text.lines().count { it.startsWith("[") },
        )
        assertTrue(text.contains("…视口里还有 40 个没列出"))
        assertTrue(text.contains("视口外还有约 38 个"))
        assertTrue(text.contains("只给了前 ${BrowserLimits.VIEW_TEXT} 字"))
        assertTrue(text.length < BrowserLimits.VIEW_TEXT + 6_000)
    }

    @Test
    fun renderScrollPosition() {
        fun at(y: Int, height: Int = 2400) =
            BrowserPage.render(
                    PageSnapshot(
                        "https://x.test/",
                        "t",
                        y,
                        400,
                        800,
                        height,
                        emptyList(),
                        "",
                        false,
                        0,
                        1,
                    ),
                )
                .lines()[1]
        assertEquals("视口 400×800（CSS 像素）；页面高 2400，看到的是 640–1440，下面还有 960", at(640))
        assertEquals("视口 400×800（CSS 像素）；页面高 2400，看到的是 1600–2400，已到底", at(1600))
        assertEquals("视口 400×800（CSS 像素），整页都在视口里", at(0, height = 500))
        val short =
            BrowserPage.render(
                PageSnapshot(
                    "https://x.test/",
                    "t",
                    0,
                    400,
                    800,
                    500,
                    emptyList(),
                    "",
                    false,
                    0,
                    1,
                ),
            )
        assertTrue(short.contains("视口里没有可交互元素") && short.contains("视口里没有文字"))
    }

    @Test
    fun readSliceTellsWhereToContinue() {
        val text = "a".repeat(100) + "\n\n\n" + "b".repeat(50)
        val first = BrowserPage.readSlice(text, 0, limit = 60)
        assertTrue(first.startsWith("a".repeat(60)))
        assertTrue(first.contains("（第 0–60 字，全文共 152 字；接着读传 offset: 60）"))
        val last = BrowserPage.readSlice(text, 120, limit = 60)
        assertTrue(last.endsWith("（第 120–152 字，全文共 152 字）"))
        assertTrue(BrowserPage.readSlice(text, 999).startsWith("已经读完了"))
        assertEquals("页面上没有文字", BrowserPage.readSlice("  \n ", 0))
    }

    @Test
    fun clipDoesNotSplitSurrogatePairs() {
        val emoji = "😀".repeat(5)
        val clipped = BrowserPage.clip(emoji, 3)
        assertEquals("😀…", clipped)
        assertEquals("a b", BrowserPage.clip("  a \n\t b ", 10))
    }

    @Test
    fun onlyHttpAndHttpsAreAllowed() {
        assertTrue(BrowserUrls.allowed("https://example.com"))
        assertTrue(BrowserUrls.allowed("HTTP://example.com"))
        assertTrue(BrowserUrls.allowed("about:blank"))
        for (bad in
            listOf(
                "file:///sdcard/a.txt",
                "content://media/1",
                "javascript:alert(1)",
                "intent://x#Intent;end",
                "data:text/html,hi",
                "about:config",
            )) {
            assertFalse(bad, BrowserUrls.allowed(bad))
        }
    }

    @Test
    fun agentUrlsGetHttpsAndRejectOtherSchemes() {
        assertEquals("https://example.com" to null, BrowserUrls.forAgent(" example.com "))
        assertEquals("http://a.test/x", BrowserUrls.forAgent("http://a.test/x").first)
        assertEquals("https://cdn.test/x", BrowserUrls.forAgent("//cdn.test/x").first)
        for (bad in
            listOf("file:///etc/passwd", "content://x/y", "javascript:alert(1)", "intent://x")) {
            val (url, problem) = BrowserUrls.forAgent(bad)
            assertNull(bad, url)
            assertNotNull(bad, problem)
        }
        assertNotNull(BrowserUrls.forAgent("").second)
        assertNotNull(BrowserUrls.forAgent("https://").second)
    }

    @Test
    fun addressBarOpensHostsAndSearchesWords() {
        assertEquals("https://example.com", BrowserUrls.fromAddressBar("example.com"))
        assertEquals(
            "https://example.com/a?b=1",
            BrowserUrls.fromAddressBar("https://example.com/a?b=1"),
        )
        assertEquals("https://localhost:8080", BrowserUrls.fromAddressBar("localhost:8080"))
        assertEquals(
            "https://www.bing.com/search?q=hello+world",
            BrowserUrls.fromAddressBar("hello world"),
        )
        assertEquals("https://www.bing.com/search?q=kotlin", BrowserUrls.fromAddressBar("kotlin"))
        assertNull(BrowserUrls.fromAddressBar("file:///sdcard/x"))
        assertNull(BrowserUrls.fromAddressBar("javascript:alert(1)"))
        assertNull(BrowserUrls.fromAddressBar("   "))
        assertEquals("example.com", BrowserUrls.host("https://example.com/path"))
    }

    @Test
    fun requestValidation() {
        fun parse(text: String) = BrowserRequest.parse(json(text))
        assertNotNull(parse("""{}""").second)
        assertNotNull(parse("""{"action":"fly"}""").second)
        assertEquals(
            "https://example.com",
            BrowserUrls.forAgent(parse("""{"action":"OPEN","url":"example.com"}""").first!!.url)
                .first,
        )
        assertNotNull(parse("""{"action":"open","url":"file:///x"}""").second)
        assertNotNull(parse("""{"action":"click"}""").second)
        assertNotNull(parse("""{"action":"click","x":3}""").second)
        assertEquals(12, parse("""{"action":"click","element":12}""").first!!.element)
        assertEquals(
            3 to 4,
            parse("""{"action":"click","x":3,"y":4}""").first!!.let { it.x to it.y },
        )
        assertNotNull(parse("""{"action":"type","element":2}""").second)
        assertEquals("", parse("""{"action":"type","element":2,"text":""}""").first!!.text)
        assertNotNull(parse("""{"action":"scroll","direction":"left"}""").second)
        assertEquals("down", parse("""{"action":"scroll"}""").first!!.direction)
        assertEquals(0, parse("""{"action":"read","offset":-5}""").first!!.offset)
    }

    @Test
    fun titlesAreShortChinese() {
        fun title(text: String) = BrowserRequest.title(json(text))
        assertEquals(
            "打开网页 example.com",
            title("""{"action":"open","url":"https://example.com/path"}"""),
        )
        assertEquals("点按网页中的[12]", title("""{"action":"click","element":12}"""))
        assertEquals("点按网页中的(3, 4)", title("""{"action":"click","x":3,"y":4}"""))
        assertEquals(
            "在网页中输入「kotlin」并提交",
            title("""{"action":"type","element":2,"text":"kotlin","submit":true}"""),
        )
        assertEquals("网页向上滚动", title("""{"action":"scroll","direction":"up"}"""))
        assertEquals("等待网页出现「Done」", title("""{"action":"wait","text":"Done"}"""))
        assertEquals("等待网页 2.5 秒", title("""{"action":"wait","ms":2500}"""))
        assertEquals("网页截图", title("""{"action":"screenshot"}"""))
    }

    @Test
    fun shotSizeScalesTheLongEdgeAndMapsPoints() {
        val size = ShotSize(1220, 2400)
        assertEquals(BrowserLimits.SHOT_EDGE, size.height)
        assertEquals(651, size.width)
        val (fx, fy) = size.fraction(0, 0)!!
        assertTrue(fx > 0 && fx < 0.01 && fy > 0 && fy < 0.01)
        assertNull(size.fraction(651, 10))
        assertNull(size.fraction(-1, 10))
        val small = ShotSize(400, 600)
        assertEquals(1f, small.scale)
        assertEquals(400 to 600, small.width to small.height)
        assertEquals(0.50125, small.fraction(200, 300)!!.first, 1e-9)
    }
}
