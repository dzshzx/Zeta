package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandResultTest {
    @Test
    fun timeoutIsMarkedByExitCodeAndStderr() {
        assertTrue(CommandResult(-1, "partial", CommandResult.TIMEOUT).timedOut)
        assertFalse(CommandResult(1, "", CommandResult.TIMEOUT).timedOut)
        assertFalse(CommandResult(-1, "", "killed").timedOut)
        assertFalse(CommandResult(0, "timeout", "").timedOut)
        assertTrue(BytesResult(-1, ByteArray(0), CommandResult.TIMEOUT).timedOut)
        assertFalse(BytesResult(0, "timeout".toByteArray(), "").timedOut)
    }

    @Test
    fun bytesComeBackWithExitCodeAndStderr() {
        // 单元测试的 PATH 里的 su 总是失败（app/src/test/no-root/su）：退出码、stderr 照样收回来，不算超时
        val result = LinuxEnv.runRootBytes("id -u", 10_000)
        assertEquals(1, result.exitCode)
        assertEquals(0, result.stdout.size)
        assertTrue(result.stderr, "no root" in result.stderr)
        assertFalse(result.timedOut)
    }
}
