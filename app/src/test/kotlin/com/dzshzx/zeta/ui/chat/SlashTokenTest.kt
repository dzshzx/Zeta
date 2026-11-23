package com.dzshzx.zeta.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用例同 windows/internal/chat 的 TestSlashAt、TestSlashComplete；| 是光标。 */
class SlashTokenTest {
    private fun at(typed: String): String {
        val caret = typed.indexOf('|')
        val token = SlashToken.at(typed.removeRange(caret, caret + 1), caret)
        return token?.let { "${it.prefix} ${it.start}-${it.end}" } ?: "-"
    }

    @Test
    fun findsTheWordBeforeTheCaret() {
        val cases =
            mapOf(
                "/|" to " 0-1",
                "/Com|" to "com 0-4",
                "/compact x|" to "-",
                "x/|" to "-",
                "|" to "-",
                "/a\nb|" to "-",
                "hello /co|" to "co 6-9",
                "你好 /co|" to "co 3-6",
                "你好，/co|" to "-",
                "hello/co|" to "-",
                "hello /co| world" to "co 6-9",
                "hello /c|o world" to "c 6-9",
                "a\n/c|" to "c 2-4",
                "hello /copy x|" to "-",
                "hello |" to "-",
                "/compact| foo" to "compact 0-8",
            )
        for ((typed, want) in cases) assertEquals(typed, want, at(typed))
        assertEquals(null, SlashToken.at("/x", 3))
    }

    @Test
    fun completeReplacesTheWord() {
        val cases =
            listOf(
                Triple("/co|", "compact", "/compact |"),
                Triple("hello /co|", "copy", "hello /copy |"),
                Triple("hello /co| world", "copy", "hello /copy |world"),
                Triple("hello /c|o world", "copy", "hello /copy |world"),
                Triple("你好 /c|x", "clear", "你好 /clear |"),
                Triple("/co|\n下一行", "copy", "/copy\n|下一行"),
            )
        for ((typed, name, want) in cases) {
            val caret = typed.indexOf('|')
            val text = typed.removeRange(caret, caret + 1)
            val (out, at) = SlashToken.at(text, caret)!!.complete(text, name)
            assertEquals(typed, want, out.substring(0, at) + "|" + out.substring(at))
        }
    }
}
