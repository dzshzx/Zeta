package com.dzshzx.zeta.phone.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具定义照真机上三种写法的样子编的，内容都是编造的。 */
class AppToolDefsTest {
    /** 字符串说明，带 class、method、index（时钟、录音机、便签这一类）。 */
    private val plain = """
        [
          {"name":"demo_query","description":"查询所有示例。当用户问起时调用。\n","method":"query","class":"com.example.clock.tool.DemoToolService","paramCount":0,"params":[]},
          {"name":"demo_add","description":"新增一个示例，返回 id","method":"add","class":"com.example.clock.tool.DemoToolService","paramCount":2,
           "params":[{"name":"hour","type":"integer","description":"小时(0-23)","required":true,"index":0},
                     {"name":"label","type":"string","description":"备注","required":false,"index":1,"maxLength":20}]}
        ]
    """.trimIndent()

    /** 按语言分的说明（电话、桌面、钱包这一类），参数的说明也是。 */
    private val localized = """
        [{"name":"demo_settings","descriptions":{"zh":"示例设置工具：查询和修改。","en":"Demo settings."},"method":"settings","class":"com.example.home.DemoService","paramCount":2,
          "params":[{"name":"action","type":"string","descriptions":{"zh":"get 或 set"},"required":true,"index":0},
                    {"name":"files","type":"array","items":{"type":"string"},"descriptions":{"en":"paths"},"required":false,"index":1}]}]
    """.trimIndent()

    /** 没有 class、method（相册、天气这一类），参数类型有 number。 */
    private val bare = """
        [{"name":"demo_search","description":"在示例里搜索的唯一入口","params":[{"name":"query","type":"string","description":"关键词","required":true},{"name":"score","type":"number","description":"最低分"}]},
         {"description":"缺名字的跳过"}]
    """.trimIndent()

    private fun app(pkg: String, label: String, json: String, vararg services: String) =
        AppToolDefs.App(pkg, label, services.toList().ifEmpty { listOf("$pkg/$pkg.Service") }, AppToolDefs.parse(json))

    @Test
    fun parsesThePlainFormat() {
        val tools = AppToolDefs.parse(plain)
        assertEquals(listOf("demo_query", "demo_add"), tools.map { it.name })
        assertEquals("查询所有示例。当用户问起时调用。", tools[0].description)
        assertEquals("com.example.clock.tool.DemoToolService", tools[0].serviceClass)
        val (hour, label) = tools[1].params
        assertEquals(AppToolDefs.Param("hour", "integer", "小时(0-23)", required = true, itemType = null, maxLength = null), hour)
        assertFalse(label.required)
        assertEquals(20, label.maxLength)
    }

    @Test
    fun parsesLocalizedDescriptionsPreferringChinese() {
        val tool = AppToolDefs.parse(localized).single()
        assertEquals("示例设置工具：查询和修改。", tool.description)
        assertEquals("get 或 set", tool.params[0].description)
        // 没有中文时取英文
        assertEquals("paths", tool.params[1].description)
        assertEquals("string", tool.params[1].itemType)
    }

    @Test
    fun parsesToolsWithoutClassAndSkipsNamelessOnes() {
        val tools = AppToolDefs.parse(bare)
        assertEquals(listOf("demo_search"), tools.map { it.name })
        assertNull(tools.single().serviceClass)
        assertEquals("number", tools.single().params[1].type)
    }

    @Test
    fun brokenFilesGiveNoTools() {
        assertTrue(AppToolDefs.parse("").isEmpty())
        assertTrue(AppToolDefs.parse("{\"name\":\"x\"}").isEmpty())
    }

    @Test
    fun picksTheServiceByClassElseTheFirst() {
        val clock = app("com.example.clock", "示例时钟", plain, "com.example.clock/com.example.clock.Other", "com.example.clock/com.example.clock.tool.DemoToolService")
        assertEquals("com.example.clock/com.example.clock.tool.DemoToolService", clock.serviceFor(clock.tools[0]))
        val gallery = app("com.example.gallery", "示例相册", bare)
        assertEquals("com.example.gallery/com.example.gallery.Service", gallery.serviceFor(gallery.tools[0]))
    }

    @Test
    fun findsAppsByPackageOrLabel() {
        val apps = listOf(app("com.example.clock", "示例时钟", plain), app("com.example.gallery", "示例相册", bare))
        assertEquals("com.example.clock", AppToolDefs.find(apps, " COM.example.clock ")?.packageName)
        assertEquals("com.example.gallery", AppToolDefs.find(apps, "示例相册")?.packageName)
        assertNull(AppToolDefs.find(apps, "相册"))
        // 给了应用就按它找；没给就看哪些应用有这个工具
        assertEquals(listOf("com.example.clock"), AppToolDefs.owners(apps, "示例时钟", "demo_search").map { it.packageName })
        assertTrue(AppToolDefs.owners(apps, "相册", "demo_search").isEmpty())
        assertEquals(listOf("com.example.gallery"), AppToolDefs.owners(apps, null, "demo_search").map { it.packageName })
    }

    @Test
    fun supersededToolsAreNotOffered() {
        val calendar = app(
            "com.android.calendar", "日历",
            """[{"name":"create_event","description":"创建日程"},{"name":"query_legal_holidays","description":"查询法定节假日"}]""",
        )
        val offered = AppToolDefs.offered(calendar)!!
        assertEquals(listOf("query_legal_holidays"), offered.tools.map { it.name })
        assertTrue(AppToolDefs.supersededBy("com.android.calendar", "create_event")!!.startsWith("use edit_calendar and search_personal instead"))
        // 不知道是哪个应用时按工具名找
        assertTrue(AppToolDefs.supersededBy(null, "create_event")!!.contains("edit_calendar"))
        assertNull(AppToolDefs.supersededBy("com.android.calendar", "query_legal_holidays"))
        assertNull(AppToolDefs.supersededBy("com.example.other", "create_event"))
        val details = AppToolDefs.details(offered)
        assertFalse(details.contains("## create_event"))
        assertTrue(details.contains("Some of its tools are not offered here"))
        // 全被取代的应用不给 Claude；没有取代项的原样
        assertNull(AppToolDefs.offered(app("com.android.deskclock", "时钟", """[{"name":"deskclock_query_alarms"}]""")))
        val gallery = app("com.example.gallery", "示例相册", bare)
        assertEquals(gallery, AppToolDefs.offered(gallery))
    }

    /** 说明里叫 Claude 改用的都是现有的手机工具。 */
    @Test
    fun supersedingToolsExist() {
        val names = PhoneToolGroups.all.flatMap { group -> group.tools.map { it.name } }.toSet()
        for (entry in AppToolDefs.SUPERSEDED) for (use in entry.use) assertTrue("${entry.packageName} → $use", use in names)
    }

    @Test
    fun overviewAndDetailsShowNamesTypesAndRequiredParams() {
        val apps = listOf(app("com.example.clock", "示例时钟", plain), app("com.example.gallery", "示例相册", bare))
        val overview = AppToolDefs.overview(apps)
        assertTrue(overview.contains("- com.example.clock (示例时钟), 2 tools: demo_query, demo_add"))
        val details = AppToolDefs.details(apps[0])
        assertTrue(details.contains("## demo_query\n查询所有示例。当用户问起时调用。\nParameters: none"))
        assertTrue(details.contains("- hour (integer, required): 小时(0-23)"))
        assertTrue(details.contains("- label (string, at most 20 chars): 备注"))
        val home = app("com.example.home", "示例桌面", localized)
        assertTrue(AppToolDefs.details(home).contains("- files (array of string): paths"))
        assertEquals("No system app exposes tools to the system agent on this phone.", AppToolDefs.overview(emptyList()))
    }

    @Test
    fun reportsMissingRequiredParams() {
        val add = AppToolDefs.parse(plain)[1]
        assertEquals(listOf("hour"), AppToolDefs.missing(add, buildJsonObject { put("label", "x") }))
        assertEquals(listOf("hour"), AppToolDefs.missing(add, buildJsonObject { put("hour", JsonNull) }))
        assertTrue(AppToolDefs.missing(add, buildJsonObject { put("hour", 7) }).isEmpty())
    }

    @Test
    fun titleTakesTheFirstClauseOfTheDescription() {
        val tools = AppToolDefs.parse(plain)
        assertEquals("示例时钟 · 查询所有示例", AppToolDefs.title("示例时钟", tools[0], "demo_query"))
        assertEquals("示例时钟 · 新增一个示例", AppToolDefs.title("示例时钟", tools[1], "demo_add"))
        assertEquals("系统应用 · demo_x", AppToolDefs.title("系统应用", null, "demo_x"))
        val long = AppToolDefs.parse("""[{"name":"t","description":"这是一段特别特别特别特别特别长的说明没有标点"}]""").single()
        assertEquals("示例 · 这是一段特别特别特别特别特别长的…", AppToolDefs.title("示例", long, "t"))
    }
}
