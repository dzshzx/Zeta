package com.dzshzx.zeta.phone

import com.dzshzx.zeta.core.AppVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGuardTest {
    private val serviceClass = "com.dzshzx.zeta.phone.ZetaAccessibilityService"
    private val release = AppVariant(AppVariant.RELEASE_PACKAGE).component(serviceClass)
    private val dev = AppVariant(AppVariant.RELEASE_PACKAGE + ".dev").component(serviceClass)

    /** 一台手机上常见的启用列表：别的应用的服务，有的写成“包名/.类名”。 */
    private val others = "com.example.passwords/com.example.passwords.autofill.AutofillAccessibilityService:" +
        "io.github.mangi.eta/.agent.EtaAccessibilityService:com.example.clicker/.service.ClickService"

    @Test
    fun componentNamesComeFromTheVariant() {
        assertEquals("com.dzshzx.zeta.dev/$serviceClass", dev)
        assertEquals("com.dzshzx.zeta/$serviceClass", release)
    }

    @Test
    fun appendKeepsEveryOtherServiceVerbatim() {
        val added = AccessibilityList.withAdded(others, dev)
        assertEquals("$others:$dev", added)
        assertTrue(AccessibilityList.lost(others, added).isEmpty())
        // 已经在了就原样返回
        assertEquals(added, AccessibilityList.withAdded(added, dev))
        // 正式版的那一项不当成测试版的
        val withRelease = "$others:$release"
        assertFalse(AccessibilityList.contains(withRelease, dev))
        assertEquals("$withRelease:$dev", AccessibilityList.withAdded(withRelease, dev))
    }

    @Test
    fun emptyAndNullLists() {
        assertEquals(dev, AccessibilityList.withAdded(null, dev))
        assertEquals(dev, AccessibilityList.withAdded("null", dev))
        assertEquals(dev, AccessibilityList.withAdded("  ", dev))
        assertEquals("", AccessibilityList.normalize("null"))
        assertTrue(AccessibilityList.entries(null).isEmpty())
        assertEquals("a/b.C:$dev", AccessibilityList.withAdded("a/b.C:", dev))
    }

    @Test
    fun shortFormCountsAsTheSameComponent() {
        // “包名/.类名”的类名相对于包名：正式版的类就在包名下，可以这样简写
        val short = "com.dzshzx.zeta/.phone.ZetaAccessibilityService"
        assertEquals(release, AccessibilityList.canonical(short))
        assertTrue(AccessibilityList.contains("$others:$short", release))
        assertEquals("$others:$short", AccessibilityList.withAdded("$others:$short", release))
        assertEquals(others, AccessibilityList.withRemoved("$others:$short", release))
        // 测试版的类不在它的包名下，简写指的是另一个类，不能当成它
        val devShort = "com.dzshzx.zeta.dev/.phone.ZetaAccessibilityService"
        assertFalse(AccessibilityList.contains(devShort, dev))
        assertEquals("$devShort:$dev", AccessibilityList.withAdded(devShort, dev))
    }

    @Test
    fun removeTakesOnlyThisPackagesEntry() {
        val list = "$others:$release:$dev"
        val removed = AccessibilityList.withRemoved(list, dev)
        assertEquals("$others:$release", removed)
        assertEquals(listOf(dev), AccessibilityList.lost(list, removed))
        assertTrue(AccessibilityList.lost(list, removed, except = dev).isEmpty())
        // 列表里没有它时什么都不丢
        assertEquals(others, AccessibilityList.withRemoved(others, dev))
        assertEquals("", AccessibilityList.withRemoved(dev, dev))
    }

    @Test
    fun lostReportsMissingOthers() {
        val after = "io.github.mangi.eta/.agent.EtaAccessibilityService:$dev"
        assertEquals(
            listOf("com.example.passwords/com.example.passwords.autofill.AutofillAccessibilityService", "com.example.clicker/.service.ClickService"),
            AccessibilityList.lost(others, after),
        )
        // 写法不同但是同一个组件，不算丢
        assertTrue(AccessibilityList.lost("io.github.mangi.eta/.agent.EtaAccessibilityService", "io.github.mangi.eta/io.github.mangi.eta.agent.EtaAccessibilityService").isEmpty())
    }

    @Test
    fun policy() {
        val now = 1_000_000_000L
        fun decide(listed: Boolean = false, armed: Boolean = true, phoneTools: Boolean = true, autoRestore: Boolean = true, recent: List<Long> = emptyList()) =
            GuardPolicy.decide(listed, armed, phoneTools, autoRestore, recent, now)
        assertEquals(GuardAction.Restore, decide())
        assertEquals("开着就不用管", GuardAction.None, decide(listed = true))
        assertEquals("没开过、或在 Zeta 里关掉的不守", GuardAction.None, decide(armed = false))
        assertEquals("手机操作关着不守", GuardAction.None, decide(phoneTools = false))
        assertEquals(GuardAction.Report, decide(autoRestore = false))
        val window = GuardPolicy.WINDOW_MS
        val three = listOf(now - 1_000, now - 2_000, now - 3_000)
        assertEquals("短时间里加回太多次就不拉锯了", GuardAction.GiveUp, decide(recent = three))
        assertEquals("早于窗口的不算", GuardAction.Restore, decide(recent = three.take(2) + (now - window)))
    }
}
