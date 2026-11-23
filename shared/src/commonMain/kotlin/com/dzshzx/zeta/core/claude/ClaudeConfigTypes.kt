package com.dzshzx.zeta.core.claude

/*
 * 「Claude Code 配置」各页显示的东西：核心 config.* 方法的结果（docs/core.md「Claude Code 配置」，Go 里是 helper/claudecfg），
 * 由 [ClaudeConfigCore] 从应答里读出来。这里只有数据，怎么算的都在核心里。
 */

/**
 * settings.json 里新对话的默认值。Claude Code 的 /model、/effort 存为默认时写的就是这几项（2.1.329）； 「只用于这个会话」的不写文件，Zeta
 * 对话里选的也一样，只管那个对话。
 */
data class ClaudeDefaults(
    /** model；null 是没设，用 Claude Code 内置的默认模型。 */
    val model: String?,
    /** permissions.defaultMode；null 是没设（逐项确认）。完全放行 Claude Code 不肯存成默认。 */
    val permissionMode: String?,
    /** 按模型存的思考强度（modelSettings.<模型>.effortLevel），按文件里的顺序；/effort 存为默认时写这里。 */
    val efforts: Map<String, String>,
    /** 顶层的 effortLevel：模型没有单独存时用它。 */
    val effort: String?,
    /**
     * autoCompactWindow：上下文窗口（token 数），自动压缩按它算，界面上的上下文占用也以它为满；null 是没设，按模型本身的窗口。比模型的窗口大时 Claude
     * Code 按模型的算。终端里 /autocompact 按模型存的（modelSettings.<模型>.autoCompactWindow）不在这里。
     */
    val compactWindow: Long? = null,
)

// ---------- 记忆 ----------

data class ConfigFile(val path: String, val exists: Boolean, val size: Long)

data class MemoryProject(val key: String, val label: String, val count: Int, val isDefault: Boolean)

data class MemoryEntry(
    val file: String,
    val fileName: String,
    val name: String,
    val description: String,
    val type: String,
    val size: Long,
)

/** 记忆页要显示的全部内容：开关、用户指令文件、选中项目的自动记忆目录。 */
data class MemoryOverview(
    val autoMemory: Boolean,
    /** settings.json 的 env 里设了 CLAUDE_CODE_DISABLE_AUTO_MEMORY，开关不起作用。 */
    val forcedOff: Boolean,
    /** 设了 autoMemoryDirectory 时所有项目共用这个目录。 */
    val customDir: String?,
    val instructions: ConfigFile,
    val projects: List<MemoryProject>,
    val selected: String?,
    val dir: String?,
    val index: ConfigFile?,
    val files: List<MemoryEntry>,
)

/** 读到的一个文本文件；[sha] 是读到时内容的 sha256，写回时核心用它判断中间有没有被别人改过。 */
data class TextDocument(val content: String, val sha: String, val exists: Boolean)

// ---------- Skills ----------

data class SkillInfo(
    /** 文件夹名，也是 Skill 的名字（/名字、skillOverrides 的键）。 */
    val name: String,
    /** SKILL.md 里写的 name。 */
    val title: String,
    val description: String,
    val enabled: Boolean,
    /** 有 SKILL.md。 */
    val valid: Boolean,
)

data class SyncedSkill(val name: String, val description: String, val enabled: Boolean)

data class SkillList(val skills: List<SkillInfo>, val synced: List<SyncedSkill>)

sealed interface SkillImport {
    data class Installed(val name: String, val replaced: Boolean, val enabled: Boolean) :
        SkillImport

    /** 已经有同名的，要用户确认替换。 */
    data class Exists(val name: String, val description: String) : SkillImport
}

// ---------- Subagent ----------

data class Subagent(
    /** 相对 ~/.claude/agents 的路径。 */
    val file: String,
    val name: String,
    val description: String,
    val model: String,
    /** null 表示没写 tools，可以用全部工具。 */
    val tools: List<String>?,
    val prompt: String,
    val extraKeys: List<String>,
    /** 读到时文件内容的 sha256，保存时核心用它判断文件在编辑期间有没有被改过。 */
    val sha: String = "",
)

data class SubagentDraft(
    val name: String,
    val description: String,
    val model: String,
    val tools: List<String>?,
    val prompt: String,
)

// ---------- MCP ----------

/** 配置里的一个 MCP 服务器。密钥只说有没有：网址里的查询参数、像是密钥的参数，核心已经抹掉了。 */
data class McpServer(
    val name: String,
    /** user、local、project。 */
    val scope: String,
    /** http、sse、stdio、ws…… */
    val type: String,
    val url: String,
    val command: String,
    val args: List<String>,
    val envKeys: List<String>,
    /** Authorization 之外的请求头。 */
    val headerKeys: List<String>,
    val bearer: Boolean,
    val authHeader: Boolean,
    val oauth: Boolean,
    /** 同名的服务器在优先级更高的范围里也有，这一个实际不会用到。 */
    val shadowedBy: String? = null,
)

/** `claude mcp list` 报的一个服务器的状态：connected、partial、failed、auth、pending、rejected、disabled…… */
data class McpState(val state: String, val issue: String)

data class McpConnector(val name: String, val state: McpState)

data class McpStatus(val statuses: Map<String, McpState>, val connectors: List<McpConnector>) {
    /** 范围 [scope] 里的服务器 [name] 的状态；`claude mcp list` 没报它时为 null。 */
    fun of(scope: String, name: String): McpState? = statuses["$scope/$name"]
}

data class McpTool(val name: String, val description: String)

// ---------- 工具权限 ----------

/** settings.json 里 permissions 的三张表。 */
enum class PermissionBehavior(val key: String, val label: String) {
    Allow("allow", "允许"),
    Ask("ask", "询问"),
    Deny("deny", "拒绝");

    companion object {
        fun of(key: String?): PermissionBehavior? = entries.firstOrNull { it.key == key }
    }
}

/** 一条规则：在哪张表里、写的什么（`工具` 或 `工具(参数)`）。 */
data class PermissionRule(val behavior: PermissionBehavior, val rule: String)

/** 一个工具（或一个 MCP 服务器）的状态，核心按规则算好。 */
data class ToolState(
    /** 整个工具的规则定下的结果；null 是没写，按权限模式走。 */
    val behavior: PermissionBehavior?,
    /** [behavior] 来自整个 MCP 服务器的规则（mcp__服务器），不是这个工具自己的。 */
    val inherited: Boolean = false,
    /** 这个工具带参数的规则（比如 Bash(git push:*)）。 */
    val specific: List<PermissionRule> = emptyList(),
)

/** 权限页上的一个工具一行。 */
data class ToolView(
    val name: String,
    /** 一句中文说明；MCP 工具没有。 */
    val label: String,
    /** 改它时交给核心的规则名（内置工具就是名字，MCP 工具是 mcp__服务器__工具）。 */
    val rule: String,
    /** 它自己那条规则，下拉里显示的是它。 */
    val own: PermissionBehavior?,
    /** 收在“更多内置工具”里的。 */
    val more: Boolean,
    val state: ToolState,
)

/** 一组工具一键开启或关闭会加、会删的规则；空的说明已经全是那个状态。 */
data class BulkChange(val add: List<PermissionRule>, val remove: List<PermissionRule>) {
    val isEmpty: Boolean
        get() = add.isEmpty() && remove.isEmpty()
}

data class BulkPair(val enable: BulkChange, val disable: BulkChange)

/** 一个 MCP 服务器整体的设置。 */
data class ServerView(
    val name: String,
    /** 整个服务器的规则名 mcp__服务器。 */
    val rule: String,
    /** 配置里的范围；不在配置里的（手机工具 phone）为空。 */
    val scope: String,
    val oauth: Boolean,
    val state: ToolState,
    val bulk: BulkPair,
)

/** 一键开关的一组：内置工具（[tools]）或一个 MCP 服务器（[server]）。 */
data class PermissionGroup(val tools: List<String> = emptyList(), val server: String = "")

/** 权限页要怎么算：MCP 服务器按 [cwd] 列；给了 [server] 时另算它整体的设置（[PermissionsView.focus]）和它的 [tools]。 */
data class PermissionsQuery(
    val cwd: String,
    val server: String = "",
    val tools: List<String> = emptyList(),
)

/** 工具权限页，核心按规则算好。 */
data class PermissionsView(
    /** 全部规则，按拒绝、询问、允许排；[malformed] 里的是括号不配对、Claude Code 认不出的。 */
    val rules: List<PermissionRule>,
    val malformed: Set<PermissionRule>,
    /** 内置工具，常用的在前。 */
    val builtin: List<ToolView>,
    val builtinBulk: BulkPair,
    /** 配置里的 MCP 服务器（同名的只列一次）。 */
    val servers: List<ServerView>,
    /** 查询里给的服务器的每个工具。 */
    val serverTools: List<ToolView>,
    /** 查询里给的服务器整体的设置。 */
    val focus: ServerView?,
) {
    /** 一键开关内置工具时的那一组。 */
    val builtinGroup: PermissionGroup
        get() = PermissionGroup(tools = builtin.map { it.name })
}
