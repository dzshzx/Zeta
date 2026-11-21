package com.dzshzx.zeta.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenToolArgsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun targetPrefersNodeThenSelectorThenPoint() {
        assertEquals(
            ControlTarget.Index(3),
            ControlTarget.parse(json("""{"node":3,"match":"OK","x":1,"y":2}"""), allowPoint = true)
                .value,
        )
        val found =
            ControlTarget.parse(json("""{"match":"OK","x":1,"y":2}"""), allowPoint = true).value
        assertTrue(found is ControlTarget.Find)
        assertEquals(
            ControlTarget.Point(1, 2),
            ControlTarget.parse(json("""{"x":1,"y":2}"""), allowPoint = true).value,
        )
        assertEquals(
            ControlTarget.Unset,
            ControlTarget.parse(json("""{"x":1,"y":2}"""), allowPoint = false).value,
        )
        assertNotNull(ControlTarget.parse(json("""{"x":1}"""), allowPoint = true).problem)
        assertEquals(
            ControlTarget.Unset,
            ControlTarget.parse(json("""{"match":"  "}"""), allowPoint = true).value,
        )
    }

    @Test
    fun waitDefaultsToOneSecond() {
        assertEquals(WaitRequest.Time(1000), WaitRequest.parse(json("{}")).value)
        assertEquals(
            WaitRequest.Time(30_000),
            WaitRequest.parse(json("""{"duration_ms":999999}""")).value,
        )
        assertEquals(WaitRequest.Time(100), WaitRequest.parse(json("""{"duration_ms":5}""")).value)
    }

    @Test
    fun waitForControlOrApp() {
        val control =
            WaitRequest.parse(json("""{"match":"Done","gone":true,"timeout_ms":100}""")).value
                as WaitRequest.Control
        assertTrue(control.gone)
        assertEquals(500L, control.timeoutMs)
        assertEquals("「Done」", control.selector.describe())
        assertEquals(
            WaitRequest.App("com.android.settings", 10_000),
            WaitRequest.parse(json("""{"app":" com.android.settings "}""")).value,
        )
        assertNotNull(WaitRequest.parse(json("""{"match":"x","app":"y"}""")).problem)
        assertNotNull(WaitRequest.parse(json("""{"gone":true}""")).problem)
    }

    @Test
    fun openNeedsSomethingToOpen() {
        assertNotNull(OpenRequest.parse(json("{}")).problem)
        val app = OpenRequest.parse(json("""{"app":"Settings"}""")).value!!
        assertFalse(app.isIntent)
        val link =
            OpenRequest.parse(
                    json("""{"uri":"https://example.com/a?b=c","app":"com.android.chrome"}"""),
                )
                .value!!
        assertTrue(link.isIntent)
        assertEquals(OpenRequest.VIEW, link.intentAction)
        assertEquals("com.android.chrome", link.app)
        val settings =
            OpenRequest.parse(json("""{"action":"android.settings.WIFI_SETTINGS"}""")).value!!
        assertEquals("android.settings.WIFI_SETTINGS", settings.intentAction)
        assertNull(settings.uri)
    }

    @Test
    fun openRejectsMalformedUriAndAction() {
        assertNotNull(OpenRequest.parse(json("""{"uri":"example.com"}""")).problem)
        assertNotNull(OpenRequest.parse(json("""{"uri":"https://a\nb"}""")).problem)
        assertNotNull(OpenRequest.parse(json("""{"action":"WIFI_SETTINGS"}""")).problem)
        assertNotNull(
            OpenRequest.parse(json("""{"action":"android.settings.WIFI; reboot"}""")).problem,
        )
        assertNull(
            OpenRequest.parse(
                    json(
                        """{"uri":"package:com.example","action":"android.settings.APPLICATION_DETAILS_SETTINGS"}""",
                    ),
                )
                .problem,
        )
    }

    @Test
    fun mainOnlyFollowsTheDefaultHome() {
        val self = "com.dzshzx.zeta.dev"
        // 手机：HyperOS 的桌面
        val phone = OpenRequest.mainOnly("com.miui.home", self)
        assertEquals(setOf("android", "com.android.systemui", self, "com.miui.home"), phone)
        // 不是默认桌面的那些照常放到后台屏幕上
        assertFalse("com.android.launcher3" in phone)
        assertFalse("com.android.settings" in phone)
        // 模拟器
        assertTrue(
            "com.google.android.apps.nexuslauncher" in
                OpenRequest.mainOnly("com.google.android.apps.nexuslauncher", self),
        )
        // 问不到默认桌面时只剩固定的几个；没设默认时问到的是系统的选择界面
        assertEquals(
            setOf("android", "com.android.systemui", self),
            OpenRequest.mainOnly(null, self),
        )
        assertEquals(
            setOf("android", "com.android.systemui", self),
            OpenRequest.mainOnly("android", self),
        )
    }

    @Test
    fun parsesRootTasksWithTopActivity() {
        val out =
            """
            RootTask id=1 bounds=[0,0][1220,2656] displayId=0 userId=0
             configuration={1.0 ...}
              taskId=2: com.miui.home/com.miui.home.launcher.Launcher bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{com.miui.home/com.miui.home.launcher.Launcher}

            RootTask id=40 bounds=[0,0][1220,2656] displayId=7 userId=0
              taskId=40: com.android.settings/.Settings bounds=[0,0][1220,2656] userId=0 visible=true topActivity=ComponentInfo{com.android.settings/com.android.settings.SubSettings}
            """
                .trimIndent()
        val tasks = SystemOutput.rootTasks(out)
        assertEquals(2, tasks.size)
        assertEquals(7, tasks[1].displayId)
        assertEquals("com.android.settings", tasks[1].packageName)
        assertTrue(tasks[1].visible)
        assertEquals("com.android.settings/com.android.settings.SubSettings", tasks[1].topActivity)
        assertEquals(
            "com.android.settings/.SubSettings",
            SystemOutput.shortComponent(tasks[1].topActivity!!),
        )
        assertEquals("a.b/c.D", SystemOutput.shortComponent("a.b/c.D"))
    }

    @Test
    fun parsesResolvedActivity() {
        val found =
            "priority=0 preferredOrder=0 match=0x208000 specificIndex=-1 isDefault=true\ncom.microsoft.emmx/com.google.android.apps.chrome.IntentDispatcher\n"
        assertEquals(
            "com.microsoft.emmx/com.google.android.apps.chrome.IntentDispatcher",
            SystemOutput.resolvedComponent(found),
        )
        assertNull(SystemOutput.resolvedComponent("No activity found\n"))
        assertNull(SystemOutput.resolvedComponent(""))
        assertTrue(SystemOutput.isChooser("com.android.intentresolver/.ResolverActivity"))
        assertTrue(SystemOutput.isChooser("android/com.android.internal.app.ResolverActivity"))
        assertFalse(SystemOutput.isChooser("com.android.settings/.Settings\$WifiSettingsActivity"))
    }

    @Test
    fun readBackCheck() {
        assertTrue(textLanded("hello", "hello", null))
        assertFalse(textLanded("hello", "hell", null))
        assertTrue(textLanded("", null, "Search"))
        assertTrue(textLanded("", "Search", "Search"))
        assertFalse(textLanded("", "old text", "Search"))
    }
}
