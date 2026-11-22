package com.dzshzx.zeta.core.claude

/*
 * 设置页的「默认模型」「默认权限模式」「默认思考强度」：读写 ~/.claude/settings.json，和 Claude Code 的 /model、/effort 存为默认一样
 * （用户级设置，所有项目生效）。
 * 改法同 PermissionsClient：读最新内容 → 只改这一个键 → 按读到时的校验值写回；返回写完以后的默认值。
 */

fun ClaudeConfigClient.defaults(): ClaudeDefaults = ClaudeSettings.defaults(readSettings())

fun ClaudeConfigClient.setDefaultModel(value: String?): ClaudeDefaults =
    ClaudeSettings.defaults(updateSettings { ClaudeSettings.withModel(it, value) })

/** 上下文窗口（token 数）；null 是删掉，按模型本身的窗口。 */
fun ClaudeConfigClient.setCompactWindow(tokens: Long?): ClaudeDefaults {
    if (tokens != null && tokens < ClaudeSettings.MIN_COMPACT_WINDOW)
        throw ClaudeConfigException("上下文窗口最小是 ${ClaudeSettings.MIN_COMPACT_WINDOW / 1000}K")
    return ClaudeSettings.defaults(updateSettings { ClaudeSettings.withCompactWindow(it, tokens) })
}

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

/** 默认思考强度：[model] 是完整模型 ID 时只改这个模型的，null 时改没单独设的模型用的那一档；[level] 为 null 是删掉。 */
fun ClaudeConfigClient.setDefaultEffort(model: String?, level: String?): ClaudeDefaults =
    ClaudeSettings.defaults(
        updateSettings {
            try {
                ClaudeSettings.withEffort(it, model, level)
            } catch (error: ClaudeSettings.Invalid) {
                throw ClaudeConfigException("settings.json 里的 modelSettings 不是对象，先在终端里修好它")
            }
        },
    )

private const val BYPASS = "bypassPermissions"
