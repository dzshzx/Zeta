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
            "添加到拒绝（2 条）：Bash、Read\n从允许中移除（1 条）：Bash(git push:*)\n从询问中移除（1 条）：Agent\n" +
                "\n关闭后，Claude 将无法读写文件或执行命令，只能对话及使用手机工具和 MCP 工具。",
            bulkMessage(change, warnNoTools = true),
        )
        assertEquals(
            "添加到允许（1 条）：mcp__phone",
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
