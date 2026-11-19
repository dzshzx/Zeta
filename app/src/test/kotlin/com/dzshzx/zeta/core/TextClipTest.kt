package com.dzshzx.zeta.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TextClipTest {
    private val smile = "😀"

    @Test
    fun clipsWithoutSplittingAnEmoji() {
        assertEquals("abc", "abc".clipChars(3, "…"))
        assertEquals("ab…", "abcd".clipChars(2, "…"))
        // 第 3 个字符是表情的前半：退一格，不留半个表情
        assertEquals("ab…", "ab$smile".clipChars(3, "…"))
        assertEquals("ab$smile…", "ab${smile}c".clipChars(4, "…"))
        assertEquals("ab", "ab${smile}c".clipChars(3))
        assertEquals("…", "abc".clipChars(0, "…"))
        assertEquals("便签标题十二个字整整十二…", "便签标题十二个字整整十二个字".clipChars(12, "…"))
    }

    @Test
    fun safeCutStepsBackOnlyInsideAPair() {
        val text = "a${smile}b"
        assertEquals(1, safeCut(text, 2))
        assertEquals(3, safeCut(text, 3))
        assertEquals(1, safeCut(text, 1))
        assertEquals(0, safeCut("${smile}x", 1))
        // 最多退到这一段的开头 from
        assertEquals(1, safeCut(text, 2, from = 1))
        assertEquals(1, safeCut("${smile}x", 1, from = 1))
        // 切在末尾不用退
        assertEquals(text.length, safeCut(text, text.length))
    }
}
