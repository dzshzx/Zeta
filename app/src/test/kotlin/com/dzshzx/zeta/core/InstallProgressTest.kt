package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 安装脚本的进度行「@zeta progress <已完成> <总数>」怎么折成进度条（[progressFraction]）。 */
class InstallProgressTest {
    @Test
    fun dividesDoneByTotal() {
        assertEquals(0.25f, progressFraction("@zeta progress 1 4")!!, 0f)
        assertEquals(0.5f, progressFraction("@zeta progress 1.5 3")!!, 0f)
    }

    @Test
    fun clampsToZeroAndOne() {
        assertEquals(1f, progressFraction("@zeta progress 9 4")!!, 0f)
        assertEquals(0f, progressFraction("@zeta progress -1 4")!!, 0f)
    }

    @Test
    fun ignoresMalformedLines() {
        assertNull(progressFraction("@zeta progress"))
        assertNull(progressFraction("@zeta progress 1"))
        assertNull(progressFraction("@zeta progress x 4"))
        assertNull(progressFraction("@zeta progress 1 0"))
        assertNull(progressFraction("@zeta progress 1 -2"))
    }
}
