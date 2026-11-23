package com.dzshzx.zeta.core.claude

/**
 * Claude Code 给 MCP 工具起的名字：`mcp__服务器__工具`，服务器名里字母、数字、_、- 以外的字符换成 _。定时任务给手机工具 phone 写规则时用；
 * 工具权限页上的名字由核心给（helper/claudecfg 的 McpServerName）。
 */
object McpNames {
    fun server(name: String): String {
        var normalized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        if (name.startsWith("claude.ai "))
            normalized = normalized.replace(Regex("_+"), "_").trim('_')
        return "mcp__$normalized"
    }

    fun tool(server: String, tool: String): String = server(server) + "__" + tool
}
