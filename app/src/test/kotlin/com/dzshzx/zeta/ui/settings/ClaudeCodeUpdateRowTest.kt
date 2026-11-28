package com.dzshzx.zeta.ui.settings

import com.dzshzx.zeta.core.conv.ClaudeUpdate
import org.junit.Assert.assertEquals
import org.junit.Test

class ClaudeCodeUpdateRowTest {
    @Test
    fun updateNote() {
        assertEquals(
            "已从 2.1.330 更新到 2.1.331",
            updateNote(
                ClaudeUpdate(ok = true, before = "2.1.330", version = "2.1.331", output = ""),
            ),
        )
        assertEquals(
            "已是最新",
            updateNote(
                ClaudeUpdate(
                    ok = true,
                    before = "2.1.330",
                    version = "2.1.330",
                    output = "Claude Code is up to date (2.1.330)",
                ),
            ),
        )
        assertEquals(
            "更新失败：npm ERR! permission denied",
            updateNote(
                ClaudeUpdate(
                    ok = false,
                    before = "2.1.330",
                    version = "2.1.330",
                    output = "Checking…\nnpm ERR! permission denied\n",
                ),
            ),
        )
        assertEquals(
            "更新失败：claude update 执行出错",
            updateNote(ClaudeUpdate(ok = false, before = null, version = null, output = "")),
        )
    }
}
