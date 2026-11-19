package com.dzshzx.zeta.core.claude

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** settings.json 里 permissions 的三张表。 */
enum class PermissionBehavior(val key: String, val label: String) {
    Allow("allow", "允许"),
    Ask("ask", "询问"),
    Deny("deny", "拒绝"),
}

/** 一条规则按 Claude Code 的写法拆开：`工具` 或 `工具(参数)`；括号不配对时 [malformed]，Claude Code 也不认。 */
data class ParsedRule(val tool: String, val content: String?, val malformed: Boolean = false) {
    /** `工具`、`工具()`、`工具(*)` 都是整个工具（读写文件那几个工具在允许表里例外，见 [ClaudePermissions.wholeTool]）。 */
    val wholeTool: Boolean get() = !malformed && (content == null || content == "" || content == "*")
}

/** 读出来的规则（只取字符串；别的值留在文件里不动）。 */
data class PermissionRules(val allow: List<String>, val ask: List<String>, val deny: List<String>) {
    fun of(behavior: PermissionBehavior): List<String> = when (behavior) {
        PermissionBehavior.Allow -> allow
        PermissionBehavior.Ask -> ask
        PermissionBehavior.Deny -> deny
    }

    /** 全部规则，拒绝、询问、允许依次排，每张表里保持文件里的顺序。 */
    val all: List<Pair<PermissionBehavior, String>>
        get() = listOf(PermissionBehavior.Deny, PermissionBehavior.Ask, PermissionBehavior.Allow).flatMap { b -> of(b).map { b to it } }

    val isEmpty: Boolean get() = allow.isEmpty() && ask.isEmpty() && deny.isEmpty()

    companion object {
        val Empty = PermissionRules(emptyList(), emptyList(), emptyList())
    }
}

/** 一个工具（或一个 MCP 服务器）在权限页上的状态。 */
data class ToolPermission(
    /** 整个工具的规则定下的结果；null 是没写，按权限模式走。 */
    val behavior: PermissionBehavior?,
    /** [behavior] 来自整个 MCP 服务器的规则（mcp__服务器），不是这个工具自己的。 */
    val inherited: Boolean = false,
    /** 这个工具带参数的规则（比如 Bash(git push:*)）。 */
    val specific: List<Pair<PermissionBehavior, String>> = emptyList(),
)

/**
 * Claude Code（2.1.327 实测）的权限规则：settings.json 的 permissions.allow / ask / deny 三张表。
 * 读改都是纯函数；改的时候只动那几条规则，permissions 里别的键（defaultMode 等）、表里别的规则和不认识的值原样留着。
 */
object ClaudePermissions {
    /**
     * 用临时 HOME 和假的 API 实测出来的语义，页面上照这句话告诉用户：
     * 拒绝压过询问、询问压过允许，和规则写得细不细无关（比如允许 Bash(git push:*) 又对 Bash 设了询问，git push 照样要问）；
     * 完全放行（bypassPermissions）下允许规则没有用处，询问和拒绝照样生效：设了询问的会弹出确认，整个工具被拒绝时 Claude 根本拿不到这个工具。
     */
    const val SEMANTICS = "规则按“拒绝 > 询问 > 允许”取最严的，写得再细也一样。完全放行模式下，询问和拒绝照样生效。"

    /** Claude Code 的工具别名（旧名字），规则里写哪个都一样。 */
    private val Aliases = mapOf(
        "Task" to "Agent",
        "KillShell" to "TaskStop",
        "KillBash" to "TaskStop",
        "ListPeers" to "ListAgents",
        "Brief" to "SendUserMessage",
    )

    fun canonicalTool(name: String): String = Aliases[name] ?: name

    /** 和 Claude Code 一样拆：第一个没转义的 `(` 和最后一个没转义的 `)`，`)` 要在末尾，工具名里不能再有括号。 */
    fun parse(rule: String): ParsedRule {
        if ('(' !in rule && ')' !in rule) return ParsedRule(canonicalTool(rule), null)
        val open = firstUnescaped(rule, '(')
        val close = lastUnescaped(rule, ')')
        if (open < 0 && close < 0) return ParsedRule(canonicalTool(rule), null)
        val name = if (open >= 0) rule.substring(0, open) else rule
        if (open < 0 || close <= open || close != rule.length - 1 || name.any { it == '(' || it == ')' } || name.isEmpty()) {
            return ParsedRule(rule, null, malformed = true)
        }
        val content = rule.substring(open + 1, close).replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")
        return ParsedRule(canonicalTool(name), content)
    }

    private fun escapedAt(text: String, index: Int): Boolean {
        var slashes = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            slashes++
            i--
        }
        return slashes % 2 == 1
    }

    private fun firstUnescaped(text: String, c: Char): Int = text.indices.firstOrNull { text[it] == c && !escapedAt(text, it) } ?: -1

    private fun lastUnescaped(text: String, c: Char): Int = text.indices.lastOrNull { text[it] == c && !escapedAt(text, it) } ?: -1

    /** 用户输入的一条规则有什么问题；没问题返回 null。 */
    fun ruleProblem(rule: String): String? = when {
        rule.isBlank() -> "规则不能是空的"
        rule != rule.trim() -> "规则前后不能有空格"
        rule.any { it == '\n' || it == '\r' || it == '\t' } -> "规则里不能有换行"
        parse(rule).malformed -> "括号不配对，写成 工具名 或 工具名(参数)"
        else -> null
    }

    // ---------- 读 ----------

    class InvalidPermissions : Exception("settings.json 里的 permissions 不是一个对象，先在终端里修好它")

    private fun permissionsOf(settings: JsonObject): JsonObject? = when (val p = settings["permissions"]) {
        null -> null
        is JsonObject -> p
        else -> throw InvalidPermissions()
    }

    private fun strings(list: JsonElement?): List<String> =
        (list as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    fun rules(settings: JsonObject): PermissionRules {
        val p = permissionsOf(settings) ?: return PermissionRules.Empty
        return PermissionRules(strings(p["allow"]), strings(p["ask"]), strings(p["deny"]))
    }

    /** 拒绝 > 询问 > 允许。 */
    private fun strongest(behaviors: Collection<PermissionBehavior>): PermissionBehavior? = when {
        PermissionBehavior.Deny in behaviors -> PermissionBehavior.Deny
        PermissionBehavior.Ask in behaviors -> PermissionBehavior.Ask
        PermissionBehavior.Allow in behaviors -> PermissionBehavior.Allow
        else -> null
    }

    /**
     * 允许表里这几个读写文件的工具写成 `工具(*)` 或 `工具()` 时，Claude Code 只在工作目录里放行，不算整个工具；
     * 拒绝、询问表里照样是整个工具。
     */
    private val WorkingDirStar = setOf("Read", "Edit", "Write", "NotebookEdit", "Glob", "Grep", "LSP", "SendUserFile")

    /** 这条规则在 [behavior] 表里是不是管整个工具。 */
    fun wholeTool(behavior: PermissionBehavior, parsed: ParsedRule): Boolean =
        parsed.wholeTool && !(behavior == PermissionBehavior.Allow && parsed.content != null && parsed.tool in WorkingDirStar)

    private fun matchesWhole(behavior: PermissionBehavior, rule: String, names: Set<String>): Boolean =
        parse(rule).let { wholeTool(behavior, it) && it.tool in names }

    /** 一个内置工具（或手机工具 mcp__phone__xxx、一个 MCP 服务器 mcp__xxx）整体的规则和它带参数的规则。 */
    fun tool(rules: PermissionRules, tool: String): ToolPermission {
        val names = setOf(canonicalTool(tool))
        val whole = rules.all.filter { (b, rule) -> matchesWhole(b, rule, names) }.map { it.first }
        val specific = rules.all.filter { (b, rule) -> parse(rule).let { !it.malformed && !wholeTool(b, it) && it.tool in names } }
        return ToolPermission(strongest(whole), specific = specific)
    }

    /** MCP 服务器整体：`mcp__服务器` 和 `mcp__服务器__*` 是一回事。 */
    fun server(rules: PermissionRules, server: String): ToolPermission {
        val names = serverNames(server)
        return ToolPermission(strongest(rules.all.filter { (b, rule) -> matchesWhole(b, rule, names) }.map { it.first }))
    }

    /** MCP 的一个工具：它自己的规则和整个服务器的规则一起算，取最严的。 */
    fun mcpTool(rules: PermissionRules, server: String, tool: String): ToolPermission {
        val own = tool(rules, McpNames.tool(server, tool))
        val whole = server(rules, server).behavior
        val combined = strongest(listOfNotNull(own.behavior, whole))
        return own.copy(behavior = combined, inherited = combined != null && combined != own.behavior)
    }

    private fun serverNames(server: String): Set<String> = McpNames.server(server).let { setOf(it, "${it}__*") }

    // ---------- 改 ----------

    /**
     * 改 permissions 里的三张表：[edit] 拿到每张表（没有就是空的），返回新表；表没变就不动它。
     * 原来没有的表只在加了规则时才建；原来有的表删空了留着 []，和 Claude Code 自己写的一样。
     */
    private fun edit(settings: JsonObject, edit: (PermissionBehavior, List<JsonElement>) -> List<JsonElement>): JsonObject {
        val current = permissionsOf(settings)
        val map = LinkedHashMap(current ?: JsonObject(emptyMap()))
        var changed = false
        for (behavior in PermissionBehavior.entries) {
            val before = map[behavior.key]
            if (before != null && before !is JsonArray) throw InvalidPermissions()
            val list: List<JsonElement> = if (before is JsonArray) before.toList() else emptyList()
            val after = edit(behavior, list)
            if (after == list) continue
            if (before == null && after.isEmpty()) continue
            map[behavior.key] = JsonArray(after)
            changed = true
        }
        if (!changed) return settings
        return ClaudeSettings.with(settings, "permissions", JsonObject(map))
    }

    private fun JsonElement.rule(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * 把整个工具设成 [behavior]（null 是去掉，回到按权限模式走）：先从三张表里去掉整个工具的写法（含别名、`工具()`、`工具(*)`），
     * 再在对应的表末尾加一条。带参数的规则不动。MCP 服务器传 `mcp__服务器`，`mcp__服务器__*` 也一并换掉。
     */
    fun withTool(settings: JsonObject, tool: String, behavior: PermissionBehavior?): JsonObject {
        val names = if (tool.startsWith("mcp__") && tool.indexOf("__", 5) < 0) setOf(tool, "${tool}__*") else setOf(canonicalTool(tool))
        val name = canonicalTool(tool)
        return edit(settings) { b, list ->
            val kept = list.filterNot { e -> e.rule()?.let { matchesWhole(b, it, names) } == true }
            if (b == behavior) kept + JsonPrimitive(name) else kept
        }
    }

    /** 加一条规则到 [behavior] 那张表；同一条在别的表里的先去掉（一条规则只放一处）。已经在了就不动。 */
    fun withRule(settings: JsonObject, behavior: PermissionBehavior, rule: String): JsonObject {
        if (rules(settings).of(behavior).contains(rule)) {
            return edit(settings) { b, list -> if (b == behavior) list else list.filterNot { it.rule() == rule } }
        }
        return edit(settings) { b, list ->
            val kept = list.filterNot { it.rule() == rule }
            if (b == behavior) kept + JsonPrimitive(rule) else kept
        }
    }

    fun withoutRule(settings: JsonObject, behavior: PermissionBehavior, rule: String): JsonObject =
        edit(settings) { b, list -> if (b == behavior) list.filterNot { it.rule() == rule } else list }

    /**
     * 把 [behavior] 表里的 [rule] 换成 [newBehavior] 表里的 [newRule]；同一张表时留在原位。
     * 原来那条已经不在了（别人改过文件）返回 null，让界面重新读。
     */
    fun replaceRule(settings: JsonObject, behavior: PermissionBehavior, rule: String, newBehavior: PermissionBehavior, newRule: String): JsonObject? {
        if (rule !in rules(settings).of(behavior)) return null
        if (behavior == newBehavior) {
            return edit(settings) { b, list ->
                if (b != behavior) {
                    list.filterNot { it.rule() == newRule }
                } else {
                    var replaced = false
                    list.mapNotNull { e ->
                        when (e.rule()) {
                            rule -> if (replaced) null else JsonPrimitive(newRule).also { replaced = true }
                            newRule -> null
                            else -> e
                        }
                    }
                }
            }
        }
        return withRule(withoutRule(settings, behavior, rule), newBehavior, newRule)
    }

    // ---------- 一键开启、关闭 ----------

    private fun inGroup(rule: String, group: PermissionGroup): Boolean {
        val parsed = parse(rule)
        if (parsed.malformed) return false
        return when (group) {
            is PermissionGroup.Tools -> parsed.tool in group.names.map(::canonicalTool)
            is PermissionGroup.Server -> McpNames.server(group.server).let { parsed.tool == it || parsed.tool.startsWith(it + "__") }
        }
    }

    /**
     * 整组开启（[enable]，设为允许）或关闭（设为拒绝）要加、要删的规则。
     * 加：内置工具没有通配写法，每个工具一条；MCP 服务器（手机工具是 phone）一条整个服务器的规则。已经有了的不加。
     * 删：组里每个工具上和目标相反的规则，带参数的也算（开启删拒绝和询问，关闭删允许和询问），不然它们按“拒绝 > 询问 > 允许”会盖住整组的设置。
     * 认不出的写法不动。返回空的说明已经全是那个状态。
     */
    fun bulk(rules: PermissionRules, group: PermissionGroup, enable: Boolean): BulkChange {
        val target = if (enable) PermissionBehavior.Allow else PermissionBehavior.Deny
        val opposite = if (enable) setOf(PermissionBehavior.Deny, PermissionBehavior.Ask) else setOf(PermissionBehavior.Allow, PermissionBehavior.Ask)
        val remove = rules.all.filter { (b, rule) -> b in opposite && inGroup(rule, group) }.distinct()
        val present = rules.of(target)
        val add = when (group) {
            is PermissionGroup.Tools -> group.names.map(::canonicalTool).distinct()
                .filterNot { name -> present.any { matchesWhole(target, it, setOf(name)) } }
            is PermissionGroup.Server -> listOf(McpNames.server(group.server))
                .filterNot { present.any { rule -> matchesWhole(target, rule, serverNames(group.server)) } }
        }
        return BulkChange(add.map { target to it }, remove)
    }

    /** 按 [bulk] 改：删掉要删的（同一条在表里出现几次都删），要加的放在表末尾。 */
    fun withBulk(settings: JsonObject, group: PermissionGroup, enable: Boolean): JsonObject {
        val change = bulk(rules(settings), group, enable)
        if (change.isEmpty) return settings
        return edit(settings) { b, list ->
            val drop = change.remove.filter { it.first == b }.map { it.second }.toSet()
            list.filterNot { it.rule() in drop } + change.add.filter { it.first == b }.map { JsonPrimitive(it.second) }
        }
    }
}

/** 一键开启、关闭的一组工具。 */
sealed interface PermissionGroup {
    /** 内置工具：逐个写规则。 */
    data class Tools(val names: List<String>) : PermissionGroup

    /** 一个 MCP 服务器（手机工具是 phone）：写整个服务器的规则 mcp__服务器。 */
    data class Server(val server: String) : PermissionGroup
}

/** 一键开启、关闭要加、要删的规则（哪张表、哪条）。 */
data class BulkChange(val add: List<Pair<PermissionBehavior, String>>, val remove: List<Pair<PermissionBehavior, String>>) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

/** Claude Code 给 MCP 工具起的名字：`mcp__服务器__工具`，服务器名里字母、数字、_、- 以外的字符换成 _。 */
object McpNames {
    fun server(name: String): String {
        var normalized = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        if (name.startsWith("claude.ai ")) normalized = normalized.replace(Regex("_+"), "_").trim('_')
        return "mcp__$normalized"
    }

    fun tool(server: String, tool: String): String = server(server) + "__" + tool
}
