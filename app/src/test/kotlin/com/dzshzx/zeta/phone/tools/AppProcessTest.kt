package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.LinuxEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 起 app_process 辅助进程的脚本：五个调用方各自的样子，逐字钉住。 */
class AppProcessTest {
    private val apk = "/data/app/~~x/com.dzshzx.zeta.dev-y/base.apk"

    // 经 androidScript 跑（$1 是 BusyBox）用它的 tr；经 rootShell 跑没有 $1，用系统的 tr
    private val envBusyBox =
        """for kv in $("$1" tr '\000' ' ' < /proc/$(pidof system_server)/environ); do case "${'$'}kv" in BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*|ANDROID_*=*) export "${'$'}kv";; esac; done; """
    private val envSystem =
        """for kv in $(tr '\000' ' ' < /proc/$(pidof system_server)/environ); do case "${'$'}kv" in BOOTCLASSPATH=*|DEX2OATBOOTCLASSPATH=*|SYSTEMSERVERCLASSPATH=*|ANDROID_*=*) export "${'$'}kv";; esac; done; """

    @Test
    fun backgroundScreen() = assertEquals(
        LinuxEnv.androidScript(
            envBusyBox + """CLASSPATH="$2" exec app_process /system/bin com.dzshzx.zeta.phone.VdHelper "$3" "$4" "$5" "$6" "$7"""",
            apk, "com.dzshzx.zeta.dev.vd", "7", "1220", "2656", "520",
        ),
        appProcess(apk, "com.dzshzx.zeta.phone.VdHelper", listOf("com.dzshzx.zeta.dev.vd", "7", "1220", "2656", "520")),
    )

    // 剪贴板也不把标准错误并进标准输出（不加 2>&1）：辅助进程的结果和失败原因都打在标准输出上，
    // 调用方把两路拼在一起找最后一行结果，并不并结果一样
    @Test
    fun clipboard() = assertEquals(
        LinuxEnv.androidScript(envBusyBox + """CLASSPATH="$2" exec app_process /system/bin com.dzshzx.zeta.phone.tools.ClipboardHelper "$3"""", apk, "get"),
        appProcess(apk, ClipboardHelper::class.java.name, listOf("get")),
    )

    @Test
    fun provider() {
        val request = """{"user":0,"ops":[{"op":"query","uri":"content://x/it's"}]}"""
        assertEquals(
            LinuxEnv.androidScript(envBusyBox + """CLASSPATH="$2" exec app_process /system/bin com.dzshzx.zeta.phone.tools.ProviderHelper "$3"""", apk, request),
            appProcess(apk, ProviderHelper::class.java.name, listOf(request)),
        )
        // 以 system 身份起
        assertEquals(
            LinuxEnv.androidScript(
                envBusyBox + """CLASSPATH="$2" exec "$1" nsenter -t 1 -m -S 1000 -G 1000 -- app_process /system/bin com.dzshzx.zeta.phone.tools.ProviderHelper "$3"""",
                apk, request,
            ),
            appProcess(apk, ProviderHelper::class.java.name, listOf(request), uid = 1000),
        )
    }

    @Test
    fun hotspot() {
        for (word in listOf("on", "off")) {
            assertEquals(
                envSystem + "CLASSPATH='$apk' exec app_process /system/bin com.dzshzx.zeta.phone.tools.HotspotHelper $word",
                appProcessCommand(apk, HotspotHelper::class.java.name, word),
            )
        }
    }

    @Test
    fun systemEnv() = assertEquals(envSystem, SYSTEM_ENV)

    @Test
    fun atMostSevenArguments() {
        assertThrows(IllegalArgumentException::class.java) { appProcess(apk, "x.Main", List(8) { "$it" }) }
    }
}
