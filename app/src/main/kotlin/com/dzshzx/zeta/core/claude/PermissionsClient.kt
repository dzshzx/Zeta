package com.dzshzx.zeta.core.claude

import kotlinx.serialization.json.JsonObject

/*
 * 工具权限页的读写：都落在 ~/.claude/settings.json 的 permissions 上（用户级设置，所有项目生效）。
 * 每次改都是“读最新内容 → 只改这几条 → 按读到时的校验值写回”，别人在中间改过就在新内容上重做；
 * 返回写完以后的规则，界面直接拿来显示。
 */

private fun rulesOf(settings: JsonObject): PermissionRules = try {
    ClaudePermissions.rules(settings)
} catch (error: ClaudePermissions.InvalidPermissions) {
    throw ClaudeConfigException(error.message!!)
}

private fun ClaudeConfigClient.editPermissions(transform: (JsonObject) -> JsonObject): PermissionRules = rulesOf(
    updateSettings { settings ->
        try {
            transform(settings)
        } catch (error: ClaudePermissions.InvalidPermissions) {
            throw ClaudeConfigException(error.message!!)
        }
    },
)

fun ClaudeConfigClient.permissions(): PermissionRules = rulesOf(readSettings())

/** 整个工具（内置工具、mcp__phone__xxx，或整个 MCP 服务器 mcp__xxx）设成允许、询问、拒绝，null 是去掉。 */
fun ClaudeConfigClient.setToolPermission(tool: String, behavior: PermissionBehavior?): PermissionRules =
    editPermissions { ClaudePermissions.withTool(it, tool, behavior) }

fun ClaudeConfigClient.addPermissionRule(behavior: PermissionBehavior, rule: String): PermissionRules {
    ClaudePermissions.ruleProblem(rule)?.let { throw ClaudeConfigException(it) }
    return editPermissions { ClaudePermissions.withRule(it, behavior, rule) }
}

fun ClaudeConfigClient.removePermissionRule(behavior: PermissionBehavior, rule: String): PermissionRules =
    editPermissions { ClaudePermissions.withoutRule(it, behavior, rule) }

/** 原来那条已经被别人删了或改了时报错，界面重新读。 */
fun ClaudeConfigClient.replacePermissionRule(
    behavior: PermissionBehavior,
    rule: String,
    newBehavior: PermissionBehavior,
    newRule: String,
): PermissionRules {
    ClaudePermissions.ruleProblem(newRule)?.let { throw ClaudeConfigException(it) }
    return editPermissions {
        ClaudePermissions.replaceRule(it, behavior, rule, newBehavior, newRule)
            ?: throw ClaudeConfigException("这条规则已经被改过了（可能是 Claude 或终端里改的），已重新读取")
    }
}

/** 一键开启或关闭一组工具，见 [ClaudePermissions.bulk]；按文件里最新的内容算要加、要删的。 */
fun ClaudeConfigClient.setGroupPermission(group: PermissionGroup, enable: Boolean): PermissionRules =
    editPermissions { ClaudePermissions.withBulk(it, group, enable) }
