package com.dzshzx.zeta.ui.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class DiskImageScreenTest {
    @Test
    fun elapsedTimeReadsLikeAClock() {
        assertEquals("0:00", clockText(-3))
        assertEquals("0:48", clockText(48))
        assertEquals("3:42", clockText(222))
        assertEquals("1:05:09", clockText(3909))
    }
}
