package com.dzshzx.zeta.phone

import com.dzshzx.zeta.core.LinuxEnvConfig
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** screen_record 的参数、放哪、拼的 root 命令和编码尺寸。真的录要上设备（tools/phone-tests/phone-tool-gaps.sh）。 */
class ScreenRecordingTest {
    private val env =
        LinuxEnvConfig("/data/local/zeta/debian/rootfs/", "zeta", 5000, 5000, "/home/zeta", "/")

    @Test
    fun limitDefaultsAndCaps() {
        assertEquals(ScreenRecording.DEFAULT_SECONDS, ScreenRecording.limit(null))
        assertEquals(1, ScreenRecording.limit(0))
        assertEquals(180, ScreenRecording.limit(999))
        assertEquals(30, ScreenRecording.limit(30))
    }

    /** 和附件放同一处：Linux 用户的 ~/.cache/<ZETA_NAME>/files/<随机>/，Android 一侧是 rootfs 里的同一个文件。 */
    @Test
    fun outputGoesToTheAttachmentFolder() {
        val out =
            ScreenRecording.output(
                env,
                "/home/zeta/.cache/zeta-dev",
                LocalDateTime.of(2026, 11, 21, 9, 5, 7),
                id = "ab12cd34",
            )
        assertEquals(
            "/home/zeta/.cache/zeta-dev/files/ab12cd34/screen-20261121-090507.mp4",
            out.linuxPath,
        )
        assertEquals("/data/local/zeta/debian/rootfs" + out.linuxPath, out.androidPath)
        assertEquals(
            listOf(
                    "/home/zeta/.cache",
                    "/home/zeta/.cache/zeta-dev",
                    "/home/zeta/.cache/zeta-dev/files",
                    "/home/zeta/.cache/zeta-dev/files/ab12cd34",
                )
                .map { "/data/local/zeta/debian/rootfs$it" },
            out.androidDirs,
        )
        assertEquals("5000:5000", out.owner)
        // 只建、只交出还没有的目录，已有的属主不动
        val prepare = ScreenRecording.prepareCommand(out)
        assertTrue(
            prepare,
            prepare.startsWith(
                "{ [ -d '/data/local/zeta/debian/rootfs/home/zeta/.cache' ] || " +
                    "{ mkdir '/data/local/zeta/debian/rootfs/home/zeta/.cache' && chown 5000:5000 '/data/local/zeta/debian/rootfs/home/zeta/.cache'; }; } && ",
            ),
        )
        assertEquals(4, prepare.split(" && { [ -d").size)
    }

    @Test
    fun rootCommands() {
        assertEquals(
            "screenrecord --time-limit 30 --bit-rate 6000000 '/r/a b.mp4' </dev/null >/dev/null 2>'/data/local/tmp/l.log' & echo \$!",
            ScreenRecording.screenrecordCommand("/r/a b.mp4", 30, "/data/local/tmp/l.log"),
        )
        // SIGINT 才让 screenrecord 写完 mp4；只对还是 screenrecord 的进程号发信号
        val stop = ScreenRecording.stopCommand(4321)
        assertTrue(
            stop,
            stop.startsWith(
                "p=4321; is() { [ -d /proc/\$p ] && grep -q screenrecord /proc/\$p/cmdline",
            ),
        )
        assertTrue(
            stop,
            "kill -INT \$p" in stop && stop.indexOf("kill -INT") < stop.indexOf("kill -9"),
        )
        assertEquals(
            "[ -s '/r/x.mp4' ] && chown 5000:5000 '/r/x.mp4' && chmod 644 '/r/x.mp4' && stat -c %s '/r/x.mp4'",
            ScreenRecording.handoverCommand("/r/x.mp4", "5000:5000"),
        )
        assertEquals(
            "/system/bin/cat '/data/user/0/p/cache/x.mp4' > '/r/x.mp4' && [ -s '/r/x.mp4' ] && chown 1:2 '/r/x.mp4' && chmod 644 '/r/x.mp4' && stat -c %s '/r/x.mp4'",
            ScreenRecording.handoverCommand(
                "/r/x.mp4",
                "1:2",
                source = "/data/user/0/p/cache/x.mp4",
            ),
        )
    }

    @Test
    fun encodeSizeKeepsAspectInBlocksOf16() {
        // 手机和模拟器上的后台屏幕
        assertEquals(576 to 1280, ScreenRecording.encodeSize(1220, 2656))
        assertEquals(1280 to 576, ScreenRecording.encodeSize(2656, 1220))
        assertEquals(720 to 1280, ScreenRecording.encodeSize(720, 1280))
        assertEquals(320 to 480, ScreenRecording.encodeSize(330, 490))
        assertEquals(16 to 1280, ScreenRecording.encodeSize(1, 5000))
    }

    @Test
    fun spokenSizes() {
        assertEquals("12.3 秒", ScreenRecording.seconds(12_340))
        assertEquals("1 KB", ScreenRecording.size(10))
        assertEquals("850 KB", ScreenRecording.size(850_000))
        assertEquals("2.5 MB", ScreenRecording.size(2_500_000))
    }
}
