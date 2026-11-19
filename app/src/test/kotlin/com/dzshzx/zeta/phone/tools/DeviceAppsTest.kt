package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DeviceAppsTest {
    // 真机 `dumpsys package com.miui.calculator` 的 Packages: 一节（删了与解析无关的行），外加 ##pids
    private val calculator = """
        Packages:
          Package [com.miui.calculator] (3bb578a):
            appId=10282
            codePath=/data/app/~~x==/com.miui.calculator-y==
            versionCode=317000039 minSdk=29 targetSdk=35
            versionName=17.3.9
            flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP KILL_AFTER_RESTORE ]
            timeStamp=2026-10-30 12:42:47
            lastUpdateTime=2026-10-30 12:42:48
            installerPackageName=com.xiaomi.market
            requested permissions:
              android.permission.INTERNET
              android.permission.ACCESS_COARSE_LOCATION
              android.permission.VIBRATE
            install permissions:
              android.permission.INTERNET: granted=true
            User 0: ceDataInode=10079 deDataInode=6914 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false quarantined=false
              installReason=0
              dataDir=/data/user/0/com.miui.calculator
              firstInstallTime=1970-01-01 08:11:27
              runtime permissions:
                android.permission.ACCESS_COARSE_LOCATION: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED ]
                android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
        ##pids
        12345
    """.trimIndent()

    @Test
    fun parsesAppInfo() {
        val info = DeviceApps.parseInfo("com.miui.calculator", calculator, "计算器")!!
        assertTrue(info, info.contains("name: 计算器"))
        assertTrue(info, info.contains("version: 17.3.9 (code 317000039), targetSdk 35, minSdk 29"))
        assertTrue(info, info.contains("uid: 10282"))
        assertTrue(info, info.contains("system app: no"))
        assertTrue(info, info.contains("installer: com.xiaomi.market"))
        assertFalse(info, info.contains("first installed"))
        assertTrue(info, info.contains("state: default (enabled)"))
        assertTrue(info, info.contains("running: yes (pid 12345)"))
        assertTrue(info, info.contains("data dir: /data/user/0/com.miui.calculator"))
        assertTrue(info, info.contains("requested permissions: 3"))
        assertTrue(info, info.contains("runtime permissions granted: POST_NOTIFICATIONS"))
        assertTrue(info, info.contains("runtime permissions not granted: ACCESS_COARSE_LOCATION"))
        assertNull(DeviceApps.parseInfo("com.example.missing", "Packages:\n##pids\n", null))
    }

    @Test
    fun frozenState() {
        val line = "    User 0: ceDataInode=1 installed=true hidden=false suspended=false stopped=true notLaunched=false enabled=3 instant=false"
        assertEquals("3" to true, DeviceApps.userState(line))
        assertEquals(null to null, DeviceApps.userState(""))
    }

    private fun event(time: String, type: String, pkg: String, instance: String) =
        "    time=\"2026-11-19 $time\" type=$type package=$pkg class=$pkg.Main instanceId=$instance taskRootPackage=$pkg taskRootClass=$pkg.Main flags=0x0 "

    private val events = listOf(
        event("09:00:00", "ACTIVITY_RESUMED", "com.a", "1"),
        event("09:10:00", "ACTIVITY_PAUSED", "com.a", "1"),
        event("09:10:00", "ACTIVITY_RESUMED", "com.miui.home", "2"),
        event("09:10:05", "ACTIVITY_PAUSED", "com.miui.home", "2"),
        event("09:10:05", "ACTIVITY_RESUMED", "com.b", "3"),
        event("09:10:30", "ACTIVITY_RESUMED", "com.b", "4"),
        event("09:10:31", "ACTIVITY_STOPPED", "com.b", "3"),
        event("09:20:30", "ACTIVITY_PAUSED", "com.b", "4"),
        event("09:20:30", "ACTIVITY_RESUMED", "com.a", "5"),
    ).joinToString("\n")

    @Test
    fun usageFromEvents() {
        val parsed = DeviceApps.parseEvents(events)
        assertEquals(9, parsed.size)
        val now = LocalDateTime.of(2026, 11, 19, 9, 30)
        val usage = DeviceApps.usage(parsed, LocalDateTime.of(2026, 11, 19, 8, 0), now).associateBy { it.packageName }
        // com.a：09:00–09:10 加上 09:20:30 到现在
        assertEquals((10 * 60 + 9 * 60 + 30) * 1000L, usage.getValue("com.a").foregroundMs)
        assertEquals(2, usage.getValue("com.a").opens)
        // com.b：两个实例重叠也照各自算（10:05–10:31 与 10:30–20:30）
        assertEquals((26 + 600) * 1000L, usage.getValue("com.b").foregroundMs)
        assertEquals(1, usage.getValue("com.b").opens)
        // 从 09:15 起算：com.a 只剩 09:20:30 以后的
        val late = DeviceApps.usage(parsed, LocalDateTime.of(2026, 11, 19, 9, 15), now).associateBy { it.packageName }
        assertEquals((9 * 60 + 30) * 1000L, late.getValue("com.a").foregroundMs)
        assertEquals((5 * 60 + 30) * 1000L, late.getValue("com.b").foregroundMs)
    }

    @Test
    fun recentOpensNewestFirst() {
        val recent = DeviceApps.recent(DeviceApps.parseEvents(events), LocalDateTime.of(2026, 11, 19, 8, 0))
        assertEquals(listOf("com.a", "com.b", "com.miui.home", "com.a"), recent.map { it.second })
        assertEquals(LocalDateTime.of(2026, 11, 19, 9, 20, 30), recent.first().first)
    }

    @Test
    fun recentSkipsHomeAndLockScreenWithoutBreakingRuns() {
        val more = events + "\n" + listOf(
            event("09:30:00", "ACTIVITY_RESUMED", "com.android.systemui", "6"),
            event("09:30:09", "ACTIVITY_RESUMED", "com.a", "5"),
        ).joinToString("\n")
        val skip = setOf("com.miui.home", "com.android.systemui")
        val recent = DeviceApps.recent(DeviceApps.parseEvents(more), LocalDateTime.of(2026, 11, 19, 8, 0), skip)
        assertEquals(listOf("com.a", "com.b", "com.a"), recent.map { it.second })
    }
}
