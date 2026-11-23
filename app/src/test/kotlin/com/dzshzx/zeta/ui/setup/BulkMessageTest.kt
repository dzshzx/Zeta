package com.dzshzx.zeta.ui.setup

import com.dzshzx.zeta.core.claude.BulkChange
import com.dzshzx.zeta.core.claude.PermissionBehavior
import com.dzshzx.zeta.core.claude.PermissionRule
import org.junit.Assert.assertEquals
import org.junit.Test

/** 工具权限页「全部开启 / 全部关闭」确认框里的说明：会加、会删的规则（核心算的）按表列出来。 */
class BulkMessageTest {
    @Test
    fun bulkMessageListsEveryRule() {
        val change =
            BulkChange(
                add =
                    listOf(
                        PermissionRule(PermissionBehavior.Deny, "Bash"),
                        PermissionRule(PermissionBehavior.Deny, "Read"),
                    ),
                remove =
                    listOf(
                        PermissionRule(PermissionBehavior.Ask, "Agent"),
                        PermissionRule(PermissionBehavior.Allow, "Bash(git push:*)"),
                    ),
            )
        assertEquals(
            "加到拒绝（2 条）：Bash、Read\n从允许里删（1 条）：Bash(git push:*)\n从询问里删（1 条）：Agent\n" +
                "\n关掉以后 Claude 连读写文件、跑命令都做不了，只能聊天和用手机工具、MCP 工具。",
            bulkMessage(change, warnNoTools = true),
        )
        assertEquals(
            "加到允许（1 条）：mcp__phone",
            bulkMessage(
                BulkChange(
                    listOf(PermissionRule(PermissionBehavior.Allow, "mcp__phone")),
                    emptyList(),
                ),
                warnNoTools = false,
            ),
        )
    }
}
