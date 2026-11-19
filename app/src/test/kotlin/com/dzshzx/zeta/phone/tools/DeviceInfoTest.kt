package com.dzshzx.zeta.phone.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoTest {
    // 真机 dumpsys battery（节选）
    private val battery = """
        Current Battery Service state:
          AC powered: true
          USB powered: false
          Wireless powered: false
          Dock powered: false
          status: 2
          health: 2
          present: true
          level: 85
          scale: 100
          voltage: 4426
          temperature: 262
          technology: Li-poly
    """.trimIndent()

    @Test
    fun batteryLine() {
        assertEquals("85%, charging (plugged: AC), 26.2°C, 4.43 V, current 3 mA, health good", DeviceInfo.battery(battery, 3000))
        assertNull(DeviceInfo.battery("", null))
    }

    @Test
    fun thermalLine() {
        val text = """
            IsStatusOverride: false
            Thermal Status: 0
            	Temperature{mValue=4.428, mType=6, mName=vbat, mStatus=0}
            	Temperature{mValue=26.4, mType=2, mName=battery, mStatus=0}
            	Temperature{mValue=26.76148, mType=3, mName=skin, mStatus=0}
            	Temperature{mValue=30.2, mType=0, mName=CPU4, mStatus=0}
            	Temperature{mValue=30.6, mType=0, mName=CPU1, mStatus=0}
            	Temperature{mValue=29.4, mType=1, mName=GPU2, mStatus=0}
        """.trimIndent()
        assertEquals("battery 26.4°C, skin 26.8°C, CPU max 30.6°C, GPU max 29.4°C, no throttling", DeviceInfo.thermal(text))
    }

    @Test
    fun memoryAndStorage() {
        val meminfo = "MemTotal:       15447572 kB\nMemAvailable:    4863600 kB\nSwapTotal:      16777212 kB\nSwapFree:        8963720 kB"
        assertEquals("4.6 GB available of 14.7 GB, swap 7.5 GB used of 16.0 GB", DeviceInfo.memory(meminfo))
        assertEquals("115.6 GB free of 477.2 GB", DeviceInfo.storage("Data-Free: 121194420K / 500367340K total = 24% free"))
    }

    @Test
    fun overviewPutsItTogether() {
        val text = DeviceInfo.overview(
            mapOf(
                "device" to "Xiaomi 17\n25113PN0EC\n17\nOS4.0\nOS4.0.1.0",
                "battery" to battery,
                "uptime" to "827261.56 5028246.64",
                "screen" to "  mWakefulness=Dozing\n    isKeyguardShowing=true",
                "wifi" to "Wifi is disabled",
                "cellular" to "运营商甲,运营商乙\nNR_SA,NR_SA",
            ),
        )
        assertTrue(text, text.startsWith("device: Xiaomi 17, Android 17, HyperOS 4.0 (OS4.0.1.0)"))
        assertTrue(text, text.contains("\nbattery: 85%, charging"))
        assertTrue(text, text.contains("\nuptime: 229h47m"))
        assertTrue(text, text.contains("\nscreen: off (Dozing), locked"))
        assertTrue(text, text.contains("\nwifi: off"))
        assertTrue(text, text.contains("\ncellular: 运营商甲 / 运营商乙 (NR_SA)"))
    }

    @Test
    fun locationPicksNewestFix() {
        // 真机 dumpsys location 的格式，坐标是编的
        val text = """
            true
            827261.56 5028246.64
                    last location=Location[network 30.123456,120.654321 hAcc=30.0 et=+9d13h36m10s315ms {Bundle[{source=2}]}]
                    last location=Location[fused 30.1,120.6 hAcc=33.0 et=+8d21h46m30s786ms {Bundle[{}]}]
                    last location=Location[gps 30.123,120.654 hAcc=3.7900925 et=+9d6h17m3s306ms alt=38.5 vAcc=2.5 {Bundle[{satellites=28}]}]
        """.trimIndent()
        val result = DeviceInfo.location(text)
        assertTrue(result, result.startsWith("latitude 30.123456, longitude 120.654321, accuracy ±30 m, from network, 11m ago"))
        assertEquals((9L * 86_400 + 13 * 3600 + 36 * 60 + 10) * 1000 + 315, DeviceInfo.elapsed("+9d13h36m10s315ms"))
        assertNull(DeviceInfo.elapsed("9d"))
        assertTrue(DeviceInfo.location("false\n1.0 1.0\n").startsWith("location is off"))
    }

    @Test
    fun savedWifi() {
        // WifiConfigStore.xml 的结构，名字和密码都是编的
        val xml = """
            <NetworkList>
            <Network>
            <WifiConfiguration>
            <string name="ConfigKey">&quot;Cafe &amp; Bar&quot;WPA_PSK</string>
            <string name="SSID">&quot;Cafe &amp; Bar&quot;</string>
            <string name="PreSharedKey">&quot;p&lt;ss word&quot;</string>
            <null name="WEPKeys" />
            <int name="SecurityType" value="2" />
            </WifiConfiguration>
            </Network>
            <Network>
            <WifiConfiguration>
            <string name="SSID">&quot;Open Net&quot;</string>
            <null name="PreSharedKey" />
            <int name="SecurityType" value="0" />
            </WifiConfiguration>
            </Network>
            <Network>
            <WifiConfiguration>
            <string name="SSID">e4b8ade69687</string>
            <string name="PreSharedKey">&quot;12345678&quot;</string>
            <int name="SecurityType" value="4" />
            </WifiConfiguration>
            </Network>
            <Network>
            <WifiConfiguration>
            <string name="SSID">&quot;家&#128512;&quot;</string>
            <string name="PreSharedKey">&quot;&#x1F431;cat&#39;s&quot;</string>
            <int name="SecurityType" value="2" />
            </WifiConfiguration>
            </Network>
            </NetworkList>
        """.trimIndent()
        val nets = DeviceInfo.parseWifiStore(xml)
        assertEquals(listOf("Cafe & Bar", "Open Net", "中文", "家\uD83D\uDE00"), nets.map { it.ssid })
        // 字符引用按码位还原：表情不会变成半个代理对
        assertEquals("\uD83D\uDC31cat's", nets[3].password)
        assertEquals("p<ss word", nets[0].password)
        assertEquals("WPA/WPA2", nets[0].security)
        assertNull(nets[1].password)
        assertEquals("open", nets[1].security)
        assertEquals("WPA3", nets[2].security)
    }

    @Test
    fun heavyApps() {
        val ps = "   RSS   PID NAME\n678448  5199 system_server\n523096  3733 com.tencent.mm\n"
        assertEquals("system_server (pid 5199): 663 MB\ncom.tencent.mm (pid 3733): 511 MB", DeviceInfo.topMemory(ps))
        val disk = """
            Package Names: ["com.a","com.b","com.c"]
            App Sizes: [1048576,0,10]
            App Data Sizes: [0,3145728,0]
            Cache Sizes: [0,1048576,0]
        """.trimIndent()
        assertEquals(
            "com.b: 4 MB (app 0 B, data 3 MB, cache 1 MB)\ncom.a: 1 MB (app 1 MB, data 0 B, cache 0 B)",
            DeviceInfo.topStorage(disk, 2),
        )
    }
}
