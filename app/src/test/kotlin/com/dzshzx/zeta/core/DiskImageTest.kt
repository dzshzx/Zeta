package com.dzshzx.zeta.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiskImageTest {
    private val script = File("src/main/assets/zeta_image_android.sh").readText()
    private val enter = File("src/main/assets/zeta_enter.sh").readText()

    @Test
    fun parsesAMountedImageWithAFinishedCheck() {
        val s =
            DiskImageStatus.parse(
                """
                image=1
                size=42949672960
                alloc=11811160064
                mounted=1
                used=10951770112
                avail=31024328704
                old=1
                free=139586437120
                op=check
                phase=ok
                msg=clean
                progress=
                time=1795700000
                """
                    .trimIndent(),
            )
        assertTrue(s.image)
        assertTrue(s.mounted)
        assertTrue(s.oldDirectory)
        assertFalse(s.convertible)
        assertEquals(40L * DiskImageStatus.GB, s.sizeBytes)
        assertEquals(11_811_160_064L, s.allocatedBytes)
        assertEquals(10_951_770_112L, s.usedBytes)
        assertEquals(139_586_437_120L, s.freeBytes)
        val op = s.op!!
        assertEquals("check", op.name)
        assertEquals("clean", op.message)
        assertNull(op.progress)
        assertFalse(op.running)
        assertFalse(s.busy)
        assertEquals(1_795_700_000L, op.time)
    }

    @Test
    fun parsesACopyInProgressAndAnInterruptedOne() {
        val copying =
            DiskImageStatus.parse(
                "image=0\nconvertible=1\nmounted=0\nop=convert\nphase=running\nmsg=copy\nprogress=2048 8192\n",
            )
        assertFalse(copying.image)
        assertTrue(copying.convertible)
        assertNull(copying.usedBytes)
        assertEquals(2048L to 8192L, copying.op?.progress)
        assertTrue(copying.busy)

        // 状态说在做、却没人拿着锁：不算在做，界面上能再做一次
        val stopped =
            DiskImageStatus.parse(
                "image=1\nmounted=1\nop=resize\nphase=running\nmsg=resize\ninterrupted=1\n",
            )
        assertTrue(stopped.op!!.interrupted)
        assertFalse(stopped.busy)
    }

    @Test
    fun noOpWhenTheStateFileIsMissing() {
        assertNull(DiskImageStatus.parse("image=0\nmounted=0\nfree=1\n").op)
    }

    @Test
    fun defaultSizeIsHalfTheFreeSpaceBetween8And40Gb() {
        assertEquals(40, DiskImageStatus.defaultSizeGb(130L * DiskImageStatus.GB))
        assertEquals(10, DiskImageStatus.defaultSizeGb(20L * DiskImageStatus.GB))
        assertEquals(8, DiskImageStatus.defaultSizeGb(6L * DiskImageStatus.GB))
        assertEquals(8, DiskImageStatus.defaultSizeGb(null))
    }

    /** 脚本里写死的路径和 Kotlin 这边的是同一处：进环境前挂的、开机挂的、Zeta 写到磁盘上的都对得上。 */
    @Test
    fun scriptPathsMatchLinuxEnv() {
        assertTrue(script.contains("BASE=${LinuxEnv.OWN_BASE}\n"))
        assertTrue(script.contains("R=\$BASE/rootfs\n"))
        assertEquals(LinuxEnv.OWN_ROOTFS, "${LinuxEnv.OWN_BASE}/rootfs")
        assertTrue(script.contains("IMG=\$BASE/rootfs.img\n"))
        assertEquals(LinuxEnv.OWN_IMAGE, "${LinuxEnv.OWN_BASE}/rootfs.img")
        assertTrue(script.contains("SELF=${LinuxEnv.IMAGE_SCRIPT}\n"))
        assertTrue(enter.contains("[ -f ${LinuxEnv.OWN_IMAGE} ]"))
        assertTrue(enter.contains("' ${LinuxEnv.OWN_ROOTFS} ' /proc/1/mounts"))
        assertTrue(enter.contains("sh ${LinuxEnv.IMAGE_SCRIPT} \"\$BB\" mount"))
    }

    /** 镜像要在看环境在不在之前挂上：刚开机时 rootfs 还是个空目录。 */
    @Test
    fun imageIsMountedBeforeTheEnvironmentIsChecked() {
        val config =
            LinuxEnvConfig(LinuxEnv.OWN_ROOTFS, "zeta", 5000, 5000, "/home/zeta", "/home/zeta")
        val entry = LinuxEnv.enterScript(config, "true")
        assertTrue(entry.indexOf(LinuxEnv.MOUNT_IMAGE) in 0 until entry.indexOf("nsenter"))
        val android = LinuxEnv.androidScript("true")
        assertTrue(android.indexOf(LinuxEnv.MOUNT_IMAGE) in 0 until android.indexOf("nsenter"))
        assertTrue(enter.indexOf("image.sh") in 0 until enter.indexOf("[ -x \"\$R/bin/bash\" ]"))
    }

    @Test
    fun scriptsParse() {
        for (name in listOf("zeta_image_android.sh", "zeta_enter.sh")) {
            val check =
                ProcessBuilder("sh", "-n", "src/main/assets/$name")
                    .redirectErrorStream(true)
                    .start()
            val output = check.inputStream.bufferedReader().readText()
            assertEquals("$name: $output", 0, check.waitFor())
        }
    }

    @Test
    fun aCopyShowsItsStepFractionSpeedAndTimeLeft() {
        val op =
            DiskImageStatus.parse(
                    "op=convert\nphase=running\nmsg=copy\nprogress=1048576 4194304\n" +
                        "time=1795700100\nstarted=1795700000\nstep=1795700080\n",
                )
                .op!!
        assertEquals(listOf("stop", "measure", "copy", "finish"), op.steps)
        assertEquals(2, op.stepIndex)
        assertEquals(1_795_700_000L, op.started)
        assertEquals(0.25f, op.fraction!!, 0.0001f)
        // 1 GB 用了 20 秒
        assertEquals(DiskImageStatus.GB / 20, op.bytesPerSecond)
        assertEquals(60L, op.secondsLeft)
    }

    @Test
    fun noSpeedYetAtTheStartOfACopyAndNoFractionOutsideIt() {
        val fresh =
            DiskImageStatus.parse(
                    "op=convert\nphase=running\nmsg=copy\nprogress=0 4194304\ntime=100\nstarted=90\nstep=99\n",
                )
                .op!!
        assertNull(fresh.bytesPerSecond)
        assertNull(fresh.secondsLeft)
        assertEquals(0f, fresh.fraction!!, 0f)
        // 已用空间带着文件系统的开销，可能超过统计出的大小
        val over =
            DiskImageStatus.parse("op=convert\nphase=running\nmsg=copy\nprogress=5000 4000\n").op!!
        assertEquals(1f, over.fraction!!, 0f)
        val measuring =
            DiskImageStatus.parse("op=convert\nphase=running\nmsg=measure\nprogress=\n").op!!
        assertNull(measuring.fraction)
        assertEquals(1, measuring.stepIndex)
        // 刚开始（start）算在第一步；旧版脚本没写开始时刻
        val starting = DiskImageStatus.parse("op=resize\nphase=running\nmsg=start\n").op!!
        assertEquals(listOf("stop", "check", "resize"), starting.steps)
        assertEquals(0, starting.stepIndex)
        assertEquals(0L, starting.started)
    }

    @Test
    fun theScriptWritesEveryStepTheAppLists() {
        for (step in listOf("stop", "measure", "copy", "finish", "check", "resize", "delete")) {
            assertTrue(step, Regex("state running $step\\b").containsMatchIn(script))
        }
        assertTrue(script.contains("echo \"started=\$STARTED\""))
        assertTrue(script.contains("echo \"step=\$STEP_AT\""))
    }
}
