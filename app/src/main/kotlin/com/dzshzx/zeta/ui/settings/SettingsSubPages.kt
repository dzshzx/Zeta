package com.dzshzx.zeta.ui.settings

/**
 * 设置页往下的一层页面。Claude 开头的几页和 ToolPermissions 是“上下文与扩展”里管 Claude Code 自身配置的。
 * 名字也是通知深链的协议（[SettingsLink]），改名时在那里留旧名字。
 */
internal enum class SettingsSubPage { Appearance, PermissionHealth, Phone, ClaudeMemory, ClaudeSkills, ClaudeMcp, ClaudeAgents, Schedules, ToolPermissions, Accounts, NotifyTriggers, Diagnostics, Storage, Assistant, Backup }
