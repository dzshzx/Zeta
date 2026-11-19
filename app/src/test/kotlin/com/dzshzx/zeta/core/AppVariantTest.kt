package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVariantTest {
    private val release = AppVariant("com.dzshzx.zeta")
    private val dev = AppVariant("com.dzshzx.zeta.dev")
    private val service = "com.dzshzx.zeta.phone.ZetaAccessibilityService"

    @Test
    fun releaseKeepsItsNamesUnchanged() {
        // 正式版升级到这一版后，名字和以前完全一样：已启用的无障碍服务、排好的闹钟、跑着的后台对话都接得上
        assertFalse(release.isDev)
        assertEquals("zeta", release.linuxName)
        assertEquals("/home/dzshzx/.cache/zeta", release.cacheDir("/home/dzshzx"))
        assertEquals("com.dzshzx.zeta/$service", release.component(service))
        assertEquals("com.dzshzx.zeta.background", release.authority("background"))
        assertEquals("com.dzshzx.zeta.action.SCHEDULE_FIRE", release.action("SCHEDULE_FIRE"))
        assertEquals("Claude 正在工作", release.notificationTitle("Claude 正在工作"))
    }

    @Test
    fun devGetsItsOwnNames() {
        assertTrue(dev.isDev)
        assertEquals("zeta-dev", dev.linuxName)
        assertEquals("/home/dzshzx/.cache/zeta-dev", dev.cacheDir("/home/dzshzx/"))
        // 类在 com.dzshzx.zeta 命名空间里不变，只有包名那一半不同
        assertEquals("com.dzshzx.zeta.dev/$service", dev.component(service))
        assertEquals("com.dzshzx.zeta.dev.background", dev.authority("background"))
        assertEquals("com.dzshzx.zeta.dev.action.STOP", dev.action("STOP"))
        assertEquals("测试版 · 需要你确认", dev.notificationTitle("需要你确认"))
    }

    @Test
    fun otherDevSlotsGetTheirOwnNames() {
        // 测试版的第二、三份（-Pzeta.devSlot=2/3）：Linux 目录、组件、action 都和第一份分开
        val dev2 = AppVariant("com.dzshzx.zeta.dev2")
        assertTrue(dev2.isDev)
        assertEquals("zeta-dev2", dev2.linuxName)
        assertEquals("com.dzshzx.zeta.dev2/$service", dev2.component(service))
        assertEquals("com.dzshzx.zeta.dev2.action.STOP", dev2.action("STOP"))
        assertTrue(dev2.linuxName != dev.linuxName && dev2.linuxName != release.linuxName)
    }

    @Test
    fun namesNeverCollideBetweenPackages() {
        assertTrue(release.linuxName != dev.linuxName)
        assertTrue(release.component(service) != dev.component(service))
        assertTrue(release.authority("background") != dev.authority("background"))
        // 无障碍启用列表按 “:” 分隔、整项比较：测试版的组件名里不含正式版那一整项，反过来也一样
        assertFalse(":${dev.component(service)}:".contains(":${release.component(service)}:"))
        assertEquals("zeta-dev-x", AppVariant("com.dzshzx.zeta.dev.x").linuxName)
    }

    @Test
    fun recognisesBothZetaPackages() {
        assertTrue(AppVariant.isZetaPackage("com.dzshzx.zeta"))
        assertTrue(AppVariant.isZetaPackage("com.dzshzx.zeta.dev"))
        assertFalse(AppVariant.isZetaPackage("com.dzshzx.zetax"))
        assertFalse(AppVariant.isZetaPackage("com.android.shell"))
    }

    @Test
    fun unitTestsBuildTheReleasePackage() {
        // testDebugUnitTest 跑的是 debug（正式版）；测试版只多了 applicationIdSuffix
        assertEquals(AppVariant.RELEASE_PACKAGE, AppVariant.current.packageName)
    }

    @Test
    fun linuxCommandsCarryTheDirectoryName() {
        val config = LinuxEnvConfig("/data/local/zeta/debian/rootfs", "dzshzx", 5000, 5000, "/home/dzshzx", "/workspace")
        val script = LinuxEnv.enterScript(config, "true")
        assertTrue(script, script.contains("${AppVariant.LINUX_NAME_ENV}='\\''zeta'\\''"))
    }
}
