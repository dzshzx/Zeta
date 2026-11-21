package com.dzshzx.zeta.phone.tools

import com.dzshzx.zeta.core.LinuxEnv
import java.time.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceToolsTest {
    private fun names() = DeviceTools.tools.map { it.name }

    @Test
    fun toolsAreFewAndSmall() {
        assertEquals(
            listOf(
                "find_apps",
                "shell",
                "device_state",
                "media",
                "clock",
                "system_setting",
                "app_control",
                "device_info",
                "logcat",
            ),
            names(),
        )
        // 预算约 3k token；英文 JSON 大约 3 到 4 个字符一个 token，现在约 5.5k 字符
        val chars = DeviceTools.tools.sumOf { it.definition.toString().length }
        assertTrue("tool definitions are $chars chars", chars <= 8_000)
    }

    @Test
    fun titlesAreChinese() {
        fun title(name: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            DeviceTools.title(name, buildJsonObject(build))
        assertEquals("查看手机开关状态", title("device_state") { put("action", "get") })
        assertEquals(
            "查看蓝牙",
            title("device_state") {
                put("action", "get")
                put("target", "bluetooth")
            },
        )
        assertEquals(
            "打开手电筒",
            title("device_state") {
                put("action", "set")
                put("target", "flashlight")
                put("value", "on")
            },
        )
        assertEquals(
            "关闭Wi-Fi",
            title("device_state") {
                put("action", "set")
                put("target", "wifi")
                put("value", false)
            },
        )
        assertEquals(
            "亮度调到 40%",
            title("device_state") {
                put("action", "set")
                put("target", "brightness")
                put("value", 40)
            },
        )
        assertEquals(
            "熄屏时间设为 5 分钟",
            title("device_state") {
                put("action", "set")
                put("target", "screen_timeout")
                put("value", "300")
            },
        )
        assertEquals(
            "响铃模式设为振动",
            title("device_state") {
                put("action", "set")
                put("target", "ringer_mode")
                put("value", "vibrate")
            },
        )
        assertEquals(
            "媒体音量调到 30%",
            title("media") {
                put("action", "set_volume")
                put("stream", "media")
                put("percent", 30)
            },
        )
        assertEquals("下一首", title("media") { put("action", "next") })
        assertEquals(
            "新建闹钟 07:05",
            title("clock") {
                put("action", "add_alarm")
                put("hour", 7)
                put("minute", 5)
            },
        )
        assertEquals(
            "关闭闹钟",
            title("clock") {
                put("action", "update_alarm")
                put("alarm_id", 3)
                put("enabled", false)
            },
        )
        assertEquals(
            "开始倒计时 1 小时 30 分",
            title("clock") {
                put("action", "start_timer")
                put("seconds", 5400)
            },
        )
        assertEquals(
            "冻结 com.miui.calculator",
            title("app_control") {
                put("action", "freeze")
                put("package", "com.miui.calculator")
            },
        )
        assertEquals(
            "安装 app-debug.apk",
            title("app_control") {
                put("action", "install")
                put("path", "/workspace/build/app-debug.apk")
            },
        )
        assertEquals("安装应用", title("app_control") { put("action", "install") })
        assertEquals(
            "卸载 com.x",
            title("app_control") {
                put("action", "uninstall")
                put("package", "com.x")
            },
        )
        assertEquals(
            "清除数据 com.x",
            title("app_control") {
                put("action", "clear_data")
                put("package", "com.x")
            },
        )
        assertEquals("读取已保存的 Wi-Fi 密码", title("device_info") { put("what", "wifi_passwords") })
        assertEquals("读取系统日志 com.x", title("logcat") { put("package", "com.x") })
        assertEquals(
            "修改系统设置 screen_off_timeout",
            title("system_setting") {
                put("action", "put")
                put("namespace", "system")
                put("key", "screen_off_timeout")
            },
        )
    }

    @Test
    fun spokenChinese() {
        assertEquals("30 秒", DeviceTools.spokenZh(30))
        assertEquals("5 分钟", DeviceTools.spokenZh(300))
        assertEquals("1 小时", DeviceTools.spokenZh(3600))
        assertEquals("2 分钟 5 秒", DeviceTools.spokenZh(125))
        assertEquals("0 秒", DeviceTools.spokenZh(0))
        assertEquals("1 小时 30 分", DeviceTools.spokenZh(5400))
        assertEquals("1 小时 1 秒", DeviceTools.spokenZh(3601))
        assertEquals("2 小时 1 分 5 秒", DeviceTools.spokenZh(7265))
    }
}

class DeviceLogcatTest {
    @Test
    fun buildsCommand() {
        assertEquals(
            "logcat -d -v threadtime '*:V' | grep -v '^--------- beginning of' | tail -n 100",
            DeviceLogcat.command(DeviceLogcat.Query()),
        )
        val q =
            DeviceLogcat.Query(
                lines = 50,
                uid = 10282,
                tags = listOf("ActivityManager", "Zeta"),
                level = "W",
                grep = "crash|it's",
                since = LocalDateTime.of(2026, 11, 19, 1, 30, 5),
                buffer = "all",
            )
        assertEquals(
            "logcat -d -v threadtime -b all --uid=10282 -T '11-19 01:30:05.000' -e 'crash|it'\\''s' 'ActivityManager:W' 'Zeta:W' '*:S'" +
                " | grep -v '^--------- beginning of' | tail -n 50",
            DeviceLogcat.command(q),
        )
        assertTrue(DeviceLogcat.command(DeviceLogcat.Query(lines = 9999)).endsWith("tail -n 500"))
    }

    @Test
    fun uidAndTags() {
        assertEquals(
            10282,
            DeviceLogcat.parseUid(
                "package:com.miui.calculator2 uid:10999\npackage:com.miui.calculator uid:10282\n",
                "com.miui.calculator",
            ),
        )
        assertEquals(null, DeviceLogcat.parseUid("", "com.x"))
        // 装在主用户和分身空间里的应用会列出两个 uid，取主用户的
        assertEquals(
            10226,
            DeviceLogcat.parseUid(
                "package:com.android.systemui uid:10226,99910226",
                "com.android.systemui",
            ),
        )
        assertEquals(
            listOf("VRI[NotificationShade]"),
            DeviceLogcat.parseTags("VRI[NotificationShade]"),
        )
        assertEquals(listOf("A", "b.c"), DeviceLogcat.parseTags(" A, b.c ,"))
        assertTrue(runCatching { DeviceLogcat.parseTags("bad tag") }.exceptionOrNull() is ToolError)
    }

    @Test
    fun trimsLongOutput() {
        val long = "x".repeat(5000)
        assertEquals("x".repeat(1000) + "…", DeviceLogcat.trim(long))
        val many = (1..200).joinToString("\n") { "y".repeat(999) }
        val trimmed = DeviceLogcat.trim(many)
        assertTrue(trimmed.startsWith("(older 140 lines omitted to fit)\n"))
        assertTrue(trimmed.length <= DeviceLogcat.MAX_CHARS + 100)
    }
}

class DeviceShellTest {
    @Test
    fun splitsSections() {
        val out = "noise\n##a\n1\n2\n##b\n\n##c\nx\n"
        assertEquals(mapOf("a" to "1\n2", "b" to "", "c" to "x"), sections(out))
        assertEquals("echo '##w'; { cmd wifi status ; } 2>&1; ", section("w", "cmd wifi status"))
    }

    @Test
    fun parsesContentRowsWithCommasInLastColumn() {
        val rows =
            contentRows(
                "Row: 0 _id=1, name=a, b, c\nNo result found.\nRow: 1 _id=2, name=",
                listOf("_id", "name"),
            )
        assertEquals(
            listOf(mapOf("_id" to "1", "name" to "a, b, c"), mapOf("_id" to "2", "name" to "")),
            rows,
        )
    }

    /** shell 原样交回输出和退出码，退出码不是 0 算失败；超时按秒数说。 */
    @Test
    fun shellReportsOutputAndExitCode() {
        var asked = 0L
        fun run(
            exit: Int,
            out: String,
            err: String,
            input: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ): kotlinx.serialization.json.JsonObject =
            DeviceTools.shell(buildJsonObject(input)) { _, timeoutMs ->
                asked = timeoutMs
                com.dzshzx.zeta.core.CommandResult(exit, out, err)
            }
        fun text(result: kotlinx.serialization.json.JsonObject) = result.toString()
        val ok = run(0, "a\n", "") { put("command", "getprop x") }
        assertTrue(text(ok), "a\\n[退出码 0]" in text(ok) && "isError" !in text(ok))
        assertEquals(30_000L, asked)
        val bad =
            run(2, "", "nope") {
                put("command", "false")
                put("timeout_seconds", 5)
            }
        assertTrue(
            text(bad),
            "[stderr]\\nnope\\n[退出码 2]" in text(bad) && "\"isError\":true" in text(bad),
        )
        assertEquals(5_000L, asked)
        assertTrue(
            "[超过 600 秒，已中止]" in
                text(
                    run(-1, "", com.dzshzx.zeta.core.CommandResult.TIMEOUT) {
                        put("command", "sleep 9999")
                        put("timeout_seconds", 9999)
                    },
                ),
        )
        assertTrue("缺少 command" in text(run(0, "", "") { put("command", " ") }))
        // 标准输出末尾没有换行：标准错误、退出码各起一行
        val both = run(1, "a", "e\n") { put("command", "x") }
        assertTrue(text(both), "\"a\\n[stderr]\\ne\\n[退出码 1]\"" in text(both))
        // 只有空白的标准错误不列
        val blank = run(0, "", "  ") { put("command", "x") }
        assertTrue(text(blank), "\"[退出码 0]\"" in text(blank))
    }

    @Test
    fun formats() {
        assertEquals("'it'\\''s'", LinuxEnv.quote("it's"))
        assertEquals("45s", spoken(45_000))
        assertEquals("5m30s", spoken(330_000))
        assertEquals("25m", spoken(1_500_000))
        assertEquals("2h5m", spoken(7_500_000))
        assertEquals("1.5 GB", bytes(1_610_612_736))
        assertEquals("512 B", bytes(512))
        assertTrue(PACKAGE_NAME.matches("com.miui.calculator"))
        assertTrue(!PACKAGE_NAME.matches("com.x; rm -rf /"))
    }
}
