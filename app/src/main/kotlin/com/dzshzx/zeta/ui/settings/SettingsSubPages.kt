package com.dzshzx.zeta.ui.settings

/**
 * 设置页往下的一层页面。Claude 开头的几页和 ToolPermissions 是“上下文与扩展”里管 Claude Code 自身配置的。
 * [link] 是通知深链里写的页名（[SettingsLink]），各页不同、定了就不改：已经发出、还挂在通知栏里的通知带的是它。
 * 枚举名随便改，不影响深链。
 */
internal enum class SettingsSubPage(val link: String) {
    Appearance("Appearance"),
    PermissionHealth("Permissions"),
    Phone("Phone"),
    ClaudeMemory("ClaudeMemory"),
    ClaudeSkills("ClaudeSkills"),
    ClaudeMcp("ClaudeMcp"),
    ClaudeAgents("ClaudeAgents"),
    Schedules("Schedules"),
    ToolPermissions("ToolPermissions"),
    Accounts("Accounts"),
    NotifyTriggers("NotifyTriggers"),
    Diagnostics("Diagnostics"),
    Storage("Storage"),
    Assistant("Assistant"),
    Backup("Backup"),
}
