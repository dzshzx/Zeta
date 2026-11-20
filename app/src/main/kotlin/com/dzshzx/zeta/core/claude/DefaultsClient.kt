package com.dzshzx.zeta.core.claude

/*
 * 设置页的「默认模型」「默认权限模式」：读写 ~/.claude/settings.json，和 Claude Code 的 /model 存为默认一样（用户级设置，所有项目生效）。
 * 改法同 PermissionsClient：读最新内容 → 只改这一个键 → 按读到时的校验值写回；返回写完以后的默认值。
 */

fun ClaudeConfigClient.defaults(): ClaudeDefaults = ClaudeSettings.defaults(readSettings())

fun ClaudeConfigClient.setDefaultModel(value: String?): ClaudeDefaults =
    ClaudeSettings.defaults(updateSettings { ClaudeSettings.withModel(it, value) })

/** 完全放行不能设成默认：Claude Code 自己也不肯存（只用于当前会话）。 */
fun ClaudeConfigClient.setDefaultPermissionMode(mode: String): ClaudeDefaults {
    if (mode == BYPASS) throw ClaudeConfigException("完全放行不能设成默认，只能在对话里临时切换")
    return ClaudeSettings.defaults(
        updateSettings {
            try {
                ClaudeSettings.withDefaultMode(it, mode)
            } catch (error: ClaudeSettings.Invalid) {
                throw ClaudeConfigException("settings.json 里的 permissions 不是一个对象，先在终端里修好它")
            }
        },
    )
}

private const val BYPASS = "bypassPermissions"
