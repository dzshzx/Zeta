package com.dzshzx.zeta.core.claude

import com.dzshzx.zeta.core.LinuxShell
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.core.LinuxEnv
import com.dzshzx.zeta.core.exitsWithin
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 设置页“上下文与扩展”里的操作没成功；[message] 是给人看的中文说明。 */
class ClaudeConfigException(message: String) : Exception(message)

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
    data class Installed(val name: String, val replaced: Boolean, val enabled: Boolean) : SkillImport

    /** 已经有同名的，要用户确认替换。 */
    data class Exists(val name: String, val description: String) : SkillImport
}

/**
 * 读写 Linux 环境里 Claude Code 自己的配置：~/.claude 下的记忆、Skill、Subagent、settings.json，以及经 `claude mcp` 管理的 MCP 服务器。
 * 解析、校验、路径、前言、ZIP 检查都在应用这边做（见同目录的几个文件，有单元测试）；读写文件是在 Debian 里以普通用户身份跑
 * [ClaudeScripts] 里的小段壳脚本，文件的所有者自然是那个用户。内容、Token 都经标准输入传，不放命令行。
 * 全是耗时的阻塞调用，别在主线程上用。
 */
class ClaudeConfigClient(private val shellProvider: () -> LinuxShell?) {
    companion object {
        const val MAX_TEXT = 1024 * 1024

        /** 主机 [backend]（默认是侧栏正看着的那台）上的 Claude Code 配置。 */
        fun of(hub: ConversationHub, backend: String) = ClaudeConfigClient { hub.shell(backend) }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 上次读记忆时 settings.json 里的 autoMemoryDirectory（校验可写路径用）。 */
    @Volatile private var customMemoryDir: String? = null

    /** 上次读到的 MCP 配置（带原值，只在内存里）。 */
    @Volatile private var mcpEntries: List<McpEntry> = emptyList()

    private fun shell(): LinuxShell = shellProvider() ?: throw ClaudeConfigException("还没有可用的 Linux 环境")

    // ---------- 跑脚本 ----------

    private class Output(val stdout: String, val stderr: String, val code: Int)

    private fun exec(script: String, args: List<String>, input: ((OutputStream) -> Unit)? = null, timeoutMs: Long = 60_000): Output {
        val shell = shell()
        val command = "exec bash -c " + LinuxEnv.quote(script) + " zeta" + args.joinToString("") { " " + LinuxEnv.quote(it) }
        val process = runCatching { shell.start(command) }.getOrElse { throw ClaudeConfigException("进不了 Linux 环境：${it.message}") }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(
            Thread { runCatching { process.inputStream.copyTo(stdout) } },
            Thread { runCatching { process.errorStream.copyTo(stderr) } },
        ).onEach {
            it.isDaemon = true
            it.start()
        }
        // 数据另起线程写：数据大（Skill 的 tar）时不会因为对方先写满输出而互相等着；对方不读就退出时写不进去，不算错
        var writeError: Throwable? = null
        val writer = Thread {
            try {
                process.outputStream.use { out -> input?.invoke(out) }
            } catch (error: IOException) {
                // 对面已经不读了
            } catch (error: Throwable) {
                writeError = error
                runCatching { process.outputStream.close() }
            }
        }.apply {
            isDaemon = true
            start()
        }
        if (!process.exitsWithin(timeoutMs)) {
            shell.kill(process)
            throw ClaudeConfigException("等了太久没有结果")
        }
        writer.join(2_000)
        readers.forEach { it.join(2_000) }
        writeError?.let { throw it as? ClaudeConfigException ?: ClaudeConfigException(it.message ?: "没有成功") }
        return Output(stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8), process.exitValue())
    }

    /** 跑一个带记录输出的脚本；没打出开头的记号说明根本没进到环境里。 */
    private fun records(script: String, args: List<String>, input: ((OutputStream) -> Unit)? = null, timeoutMs: Long = 60_000): List<ShellRecord> {
        val output = exec(script, args, input, timeoutMs)
        if ("${ClaudeScripts.MARKER}\n" !in output.stdout) throw ClaudeConfigException(failure(output))
        return ClaudeScripts.records(output.stdout).also {
            if (output.code != 0) throw ClaudeConfigException(failure(output))
        }
    }

    /** 跑一个 [ClaudeScripts.script] 写的脚本，返回它的记录（账号页这类别处的脚本也走这里）。 */
    internal fun runScript(script: String, args: List<String>, timeoutMs: Long = 60_000): List<ShellRecord> = records(script, args, null, timeoutMs)

    private fun failure(output: Output): String =
        output.stderr.lines().lastOrNull { it.isNotBlank() }?.trim()?.take(300)
            ?: when (output.code) {
                3 -> "建不了目录或删不掉"
                4 -> "解包失败"
                5 -> "写入失败，可能是存储空间不足"
                6 -> "换不上新文件"
                else -> "没有成功（退出码 ${output.code}）"
            }

    private class Loaded(val text: String, val sha: String, val exists: Boolean)

    /** 读整个文本文件；超过 [limit] 报错。不存在时 exists 为 false、sha 为 "-"。 */
    private fun load(path: String, limit: Int = MAX_TEXT): Loaded {
        val file = records(ClaudeScripts.READ_FILE, listOf(path, (limit + 1).toString())).firstOrNull { it.tag == "F" }?.file()
            ?: return Loaded("", "-", false)
        if (file.size > limit) throw ClaudeConfigException("文件超过 ${limit shr 20} MiB，请在终端里编辑")
        return Loaded(file.text(), sha256(file.head), true)
    }

    /** 写文件；[expected] 见 [ClaudeScripts.WRITE_FILE]。现有内容对不上时返回 null。 */
    private fun store(path: String, content: ByteArray, expected: String, removeAfter: String = ""): String? {
        val result = records(ClaudeScripts.WRITE_FILE, listOf(path, expected, removeAfter), input = { it.write(content) })
        if (result.any { it.tag == "CONFLICT" }) return null
        return result.firstOrNull { it.tag == "OK" }?.get(1) ?: throw ClaudeConfigException("没有写成")
    }

    /** 读 settings.json；不存在时是空对象，不是有效的 JSON 时报错。 */
    internal fun readSettings(): JsonObject = parseSettings(load(ClaudePaths.settings(shell().home)))

    private fun parseSettings(loaded: Loaded): JsonObject = try {
        ClaudeSettings.parse(loaded.text.takeIf { loaded.exists })
    } catch (error: ClaudeSettings.Invalid) {
        throw ClaudeConfigException(error.message!!)
    }

    /**
     * 改 settings.json：读出来、改要改的键、按读到时的校验值写回去；中途被别人改了就在新内容上重做一遍。
     * 返回写回去的（没变化时是读到的）内容。
     */
    internal fun updateSettings(transform: (JsonObject) -> JsonObject): JsonObject {
        val path = ClaudePaths.settings(shell().home)
        repeat(3) {
            val loaded = load(path)
            val current = parseSettings(loaded)
            val updated = transform(current)
            if (updated == current) return current
            if (store(path, ClaudeSettings.render(updated).toByteArray(), loaded.sha) != null) return updated
        }
        throw ClaudeConfigException("settings.json 一直在被改动，稍后再试")
    }

    // ---------- 记忆 ----------

    fun memory(cwd: String, project: String?): MemoryOverview {
        val home = shell().home
        val scan = records(ClaudeScripts.MEMORY_SCAN, listOf(cwd))
        val settings = ClaudeSettings.memory(ClaudeSettings.parseLenient(scan.firstOrNull { it.tag == "S" }?.file()?.text()))
        val instructions = scan.firstOrNull { it.tag == "I" }?.file()
        val custom = ClaudePaths.customMemoryDir(settings.customDir, home)
        customMemoryDir = custom
        val projects: List<MemoryProject>
        val selected: String?
        val dir: String?
        if (custom != null) {
            // 设了 autoMemoryDirectory 时所有项目共用这一个目录，不用再选项目
            projects = emptyList()
            selected = null
            dir = custom
        } else {
            val real = scan.firstOrNull { it.tag == "R" }?.get(1)?.takeIf { it.startsWith("/") }
            val base = real?.let { ClaudePaths.memoryBase(it, gitProbe(scan)) }
            val defaultKey = base?.let { ClaudePaths.projectKey(it) }
            val dirs = scan.filter { it.tag == "P" }.map {
                ProjectDir(it[1], it[2].toIntOrNull() ?: -1, it.long(3), ClaudeMemory.sampleCwd(it[4]))
            }
            projects = ClaudeMemory.projects(dirs, defaultKey, base)
            selected = project?.takeIf { key -> projects.any { it.key == key } } ?: projects.firstOrNull()?.key
            dir = selected?.let { ClaudePaths.memoryDir(home, it) }
        }
        var index: ConfigFile? = null
        var files = emptyList<MemoryEntry>()
        if (dir != null) {
            val listing = records(ClaudeScripts.MEMORY_FILES, listOf(dir))
            val memoryMd = listing.firstOrNull { it.tag == "X" }?.file()
            index = ConfigFile("$dir/MEMORY.md", memoryMd != null, memoryMd?.size ?: 0)
            files = listing.filter { it.tag == "M" }.mapNotNull { it.file() }
                .filter { ClaudePaths.isMemoryFileName(ClaudePaths.baseName(it.path)) }
                .map { ClaudeMemory.entry(it.path, it.size, it.text()) }
                .sortedBy { it.fileName }
        }
        return MemoryOverview(
            autoMemory = settings.autoMemory,
            forcedOff = settings.forcedOff,
            customDir = custom,
            instructions = ConfigFile(ClaudePaths.instructions(home), instructions != null, instructions?.size ?: 0),
            projects = projects,
            selected = selected,
            dir = dir,
            index = index,
            files = files,
        )
    }

    private fun gitProbe(records: List<ShellRecord>): GitProbe {
        val g = records.firstOrNull { it.tag == "G" } ?: return GitProbe(null)
        if (g[2] != "f") return GitProbe(g[1])
        return GitProbe(
            root = g[1],
            dotGitFile = true,
            gitFile = g[3],
            gitDir = g[4],
            commonDir = g[5].takeIf { it != "-" },
            backLink = g[6].takeIf { it != "-" },
            backLinkReal = g[7],
            commonHasDotGit = g[8] == "1",
        )
    }

    private fun checkEditable(path: String) {
        ClaudePaths.editableProblem(path, shell().home, customMemoryDir)?.let { throw ClaudeConfigException(it) }
    }

    fun read(path: String): TextDocument {
        checkEditable(path)
        val loaded = load(path)
        return TextDocument(loaded.text, loaded.sha, loaded.exists)
    }

    /** 返回新内容的校验值；文件在编辑期间被别人改过时返回 null（[expectedSha] 为 null 表示不检查，直接覆盖）。 */
    fun write(path: String, content: String, expectedSha: String?): String? {
        checkEditable(path)
        val bytes = content.toByteArray()
        if (bytes.size > MAX_TEXT) throw ClaudeConfigException("内容超过 1 MiB")
        return store(path, bytes, expectedSha?.ifEmpty { "-" } ?: "*")
    }

    /** 删一条记忆，连同 MEMORY.md 里指向它的那一行。 */
    fun deleteMemory(path: String) {
        checkEditable(path)
        val name = ClaudePaths.baseName(path)
        if (name == "MEMORY.md" || path == ClaudePaths.instructions(shell().home)) throw ClaudeConfigException("不能删除这个文件")
        records(ClaudeScripts.REMOVE_FILE, listOf(path))
        val index = ClaudePaths.parent(path) + "/MEMORY.md"
        repeat(3) {
            val loaded = load(index)
            if (!loaded.exists) return
            val updated = ClaudeMemory.indexWithout(loaded.text, name) ?: return
            if (store(index, updated.toByteArray(), loaded.sha) != null) return
        }
    }

    fun setAutoMemory(on: Boolean) {
        updateSettings { ClaudeSettings.withAutoMemory(it, on) }
    }

    // ---------- Skill ----------

    fun skills(): SkillList {
        val result = records(ClaudeScripts.SKILLS, emptyList())
        val settings = ClaudeSettings.parseLenient(result.firstOrNull { it.tag == "S" }?.file()?.text())
        val skills = mutableListOf<SkillInfo>()
        result.forEachIndexed { i, record ->
            if (record.tag != "K") return@forEachIndexed
            val name = record[1]
            val skillMd = result.getOrNull(i + 1)?.takeIf { it.tag == "D" }?.file()
            val meta = skillMd?.let { Frontmatter.parse(Frontmatter.split(it.text()).front) }.orEmpty()
            val title = meta["name"]?.text().orEmpty()
            skills += SkillInfo(
                name = name,
                title = title,
                description = meta["description"]?.text().orEmpty(),
                enabled = ClaudeSettings.skillOverride(settings, listOf(name, title).filter { it.isNotEmpty() }) != "off",
                valid = skillMd != null,
            )
        }
        // skills/synced 由 Claude Code 从账号同步，只列出来
        val synced = LinkedHashMap<String, SyncedSkill>()
        result.filter { it.tag == "Y" }.mapNotNull { it.file() }.forEach { manifest ->
            val items = (McpRpc.parse(manifest.text()) as? JsonObject)?.get("skills") as? JsonArray ?: return@forEach
            items.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                if (name !in synced) {
                    val description = (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    synced[name] = SyncedSkill(name, description, ClaudeSettings.skillOverride(settings, listOf(name)) != "off")
                }
            }
        }
        return SkillList(skills.sortedBy { it.name }, synced.values.sortedBy { it.name })
    }

    private fun checkSkillName(name: String) {
        if (name.isEmpty() || '/' in name || name.startsWith(".") || name == "synced" || name.any { it < ' ' }) {
            throw ClaudeConfigException("名字不对")
        }
    }

    fun importSkill(fileName: String, zip: ByteArray, replace: Boolean): SkillImport {
        val archive: SkillArchive
        val skill: SkillPackage
        try {
            archive = SkillArchive.open(zip)
            skill = archive.skill(fileName)
        } catch (error: SkillArchiveException) {
            throw ClaudeConfigException(error.message!!)
        }
        val result = records(ClaudeScripts.SKILL_INSTALL, listOf(skill.name, if (replace) "1" else "0"), input = { archive.writeTar(skill, it) }, timeoutMs = 120_000)
        if (result.any { it.tag == "EXISTS" }) {
            val old = result.firstOrNull { it.tag == "D" }?.file()?.let { Frontmatter.parse(Frontmatter.split(it.text()).front) }
            return SkillImport.Exists(skill.name, old?.get("description")?.text().orEmpty())
        }
        val ok = result.firstOrNull { it.tag == "OK" } ?: throw ClaudeConfigException("没有装上")
        val settings = ClaudeSettings.parseLenient(result.firstOrNull { it.tag == "S" }?.file()?.text())
        return SkillImport.Installed(skill.name, ok[1] == "1", ClaudeSettings.skillOverride(settings, listOf(skill.name)) != "off")
    }

    /** 停用写进 settings.json 的 skillOverrides（"off"），和 Claude Code 里 /skills 关掉的效果一样；启用就把它去掉。 */
    fun setSkillEnabled(skill: SkillInfo, enabled: Boolean) {
        checkSkillName(skill.name)
        updateSettings { ClaudeSettings.withSkillEnabled(it, skill.name, listOf(skill.title).filter { t -> t.isNotEmpty() }, enabled) }
    }

    fun deleteSkill(name: String) {
        checkSkillName(name)
        val result = records(ClaudeScripts.SKILL_DELETE, listOf(name))
        if (result.any { it.tag == "MISSING" }) throw ClaudeConfigException("找不到这个 Skill，可能已经被删了")
        updateSettings { ClaudeSettings.withoutSkill(it, name) }
    }

    // ---------- MCP ----------

    private fun scanMcp(cwd: String): List<McpEntry> {
        val scan = records(ClaudeScripts.MCP_SCAN, listOf(cwd))
        val claudeJson = scan.firstOrNull { it.tag == "J" }?.file()
        if (claudeJson != null && !claudeJson.complete) throw ClaudeConfigException("~/.claude.json 太大，读不了")
        val real = scan.firstOrNull { it.tag == "R" }?.get(1)?.takeIf { it.startsWith("/") }
        val probe = gitProbe(scan)
        // 本地配置按项目路径存：cwd 本身，或者它所在的 git 仓库的根
        val keys = listOfNotNull(cwd, real, probe.root, ClaudePaths.canonicalRoot(probe))
        val entries = McpConfig.entries(claudeJson?.text(), keys, scan.filter { it.tag == "M" }.mapNotNull { it.file()?.text() })
        mcpEntries = entries
        return entries
    }

    fun mcpServers(cwd: String): List<McpServer> = McpConfig.describe(scanMcp(cwd))

    private fun entry(server: McpServer, cwd: String): McpEntry =
        (mcpEntries.takeIf { it.isNotEmpty() } ?: scanMcp(cwd)).firstOrNull { it.name == server.name && it.scope == server.scope }
            ?: throw ClaudeConfigException("找不到这个服务器，可能已经被删了")

    /** 跑 claude 的 MCP 子命令；[secrets] 里的值不会出现在报错里。 */
    private fun claude(cwd: String, args: List<String>, stdin: ByteArray? = null, secrets: Collection<String> = emptyList(), timeoutMs: Long = 60_000): String {
        val output = exec(ClaudeScripts.CLAUDE, listOf(cwd) + args, input = stdin?.let { bytes -> { it.write(bytes) } }, timeoutMs = timeoutMs)
        val (text, code) = ClaudeScripts.claudeOutput(output.stdout)
        if (code == null) throw ClaudeConfigException(failure(output))
        if (code != 0) {
            val line = text.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "claude 返回了 $code"
            throw ClaudeConfigException(mask(line, secrets).take(300))
        }
        return text
    }

    private fun mask(text: String, secrets: Collection<String>): String =
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.fold(text) { acc, secret -> acc.replace(secret, "…") }

    /** 逐个连接做健康检查（claude mcp list），可能要十几秒；它会把配置里的 stdio 服务器都起一遍（和每次开对话时一样）。 */
    fun mcpStatus(cwd: String): McpStatus {
        val entries = scanMcp(cwd)
        val text = claude(cwd, listOf("mcp", "list"), timeoutMs = 120_000)
        return McpConfig.parseStatus(text, entries)
    }

    private fun addJson(cwd: String, scope: String, name: String, config: JsonObject) {
        claude(cwd, listOf("mcp", "add-json", "-s", scope, name, "-"), stdin = config.toString().toByteArray(), secrets = secretsOf(config))
    }

    private fun secretsOf(config: JsonObject): List<String> = buildList {
        (config["headers"] as? JsonObject)?.values?.forEach { v -> (v as? JsonPrimitive)?.contentOrNull?.let { add(it); add(it.removePrefix("Bearer ")) } }
        (config["env"] as? JsonObject)?.values?.forEach { v -> (v as? JsonPrimitive)?.contentOrNull?.let(::add) }
        (config["args"] as? JsonArray)?.forEach { v -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.length >= 12 }?.let(::add) }
    }

    private fun addServer(name: String, config: JsonObject) {
        if (!McpConfig.isValidName(name)) throw ClaudeConfigException("名称只能用字母、数字、下划线和连字符")
        val home = shell().home
        if (scanMcp(home).any { it.name == name && it.scope == "user" }) throw ClaudeConfigException("已经有一个叫 $name 的服务器了")
        addJson(home, "user", name, config)
    }

    /** 新服务器放进用户配置（所有项目可用）。[token] 只经标准输入交给 claude mcp add-json。 */
    fun addHttpServer(name: String, type: String, url: String, token: String) = addServer(name, McpConfig.httpConfig(type, url, token))

    fun addStdioServer(name: String, command: String, args: List<String>, env: Map<String, String>) =
        addServer(name, McpConfig.stdioConfig(command, args, env))

    fun removeMcp(server: McpServer, cwd: String) {
        if (server.scope !in McpConfig.Scopes) throw ClaudeConfigException("范围不对")
        claude(cwd, listOf("mcp", "remove", "-s", server.scope, server.name))
    }

    /** 换掉 Bearer Token；空串表示去掉身份认证。经 claude mcp 删掉重加，重加失败时把原样放回去。 */
    fun setMcpToken(server: McpServer, cwd: String, token: String) {
        if (server.scope !in setOf("user", "local")) throw ClaudeConfigException("只能修改用户配置或本地配置里的服务器")
        val original = entry(server, cwd)
        if (original.type == "stdio") throw ClaudeConfigException("命令行启动的服务器没有 Token")
        val updated = McpConfig.withToken(original.config, token.trim())
        claude(cwd, listOf("mcp", "remove", "-s", server.scope, server.name))
        try {
            addJson(cwd, server.scope, server.name, updated)
        } catch (error: ClaudeConfigException) {
            runCatching { addJson(cwd, server.scope, server.name, original.config) }
            throw error
        }
        mcpEntries = emptyList()
    }

    /** 直接按 MCP 协议连上去要工具清单（initialize → tools/list）。HTTP、SSE 从应用这边连；stdio 在 Linux 环境里起进程。 */
    fun mcpTools(server: McpServer, cwd: String): List<McpTool> {
        val entry = entry(server, cwd)
        if (entry.config["oauth"] is JsonObject) throw ClaudeConfigException("这个服务器用 OAuth 登录，Zeta 取不到它的工具")
        val vars = McpConfig.referencedVars(entry.config)
        val values = if (vars.isEmpty()) emptyMap() else envValues(vars)
        val config = McpConfig.expandAll(entry.config, values)
        val secrets = secretsOf(config) + values.values
        try {
            return when (entry.type) {
                "http", "sse" -> {
                    val url = (config["url"] as? JsonPrimitive)?.contentOrNull ?: throw ClaudeConfigException("配置里没有地址")
                    val headers = (config["headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
                    if (entry.type == "sse") McpHttp.toolsSse(url, headers) else McpHttp.tools(url, headers)
                }
                "stdio" -> stdioTools(config, cwd, secrets)
                else -> throw ClaudeConfigException("这种类型（${entry.type}）的服务器看不了工具")
            }
        } catch (error: McpException) {
            throw ClaudeConfigException(mask(error.message ?: "没有成功", secrets))
        }
    }

    private fun envValues(names: Set<String>): Map<String, String> =
        records(ClaudeScripts.ENV_VALUES, names.toList()).filter { it.tag == "V" && it[2] == "1" }.associate { it[1] to it[3] }

    private fun stdioTools(config: JsonObject, cwd: String, secrets: List<String>): List<McpTool> {
        val shell = shell()
        val command = (config["command"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ClaudeConfigException("配置里没有命令")
        val args = (config["args"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty()
        val env = (config["env"] as? JsonObject)?.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.takeIf { Regex("[A-Za-z_][A-Za-z0-9_]*").matches(k) }?.let { k to it }
        }?.toMap().orEmpty()
        val process = runCatching { shell.start("exec bash -c " + LinuxEnv.quote(ClaudeScripts.MCP_STDIO) + " zeta " + LinuxEnv.quote(cwd)) }
            .getOrElse { throw ClaudeConfigException("进不了 Linux 环境：${it.message}") }
        val errors = McpStdio.drain(process.errorStream)
        try {
            try {
                process.outputStream.write(McpStdio.preamble(command, args, env))
                process.outputStream.flush()
            } catch (error: IOException) {
                throw McpException("启动不了")
            }
            // npx 之类第一次运行要先下载，给足时间
            return McpStdio(process, 60_000).tools()
        } catch (error: McpException) {
            val detail = errors().lines().lastOrNull { it.isNotBlank() }?.trim()?.let { mask(it, secrets).take(200) }
            throw McpException(error.message + (detail?.let { "：$it" } ?: ""))
        } finally {
            runCatching { process.outputStream.close() }
            if (!runCatching { process.exitsWithin(2_000) }.getOrDefault(false)) shell.kill(process)
        }
    }

    // ---------- Subagent ----------

    fun agents(): List<Subagent> {
        val dir = ClaudePaths.agentsDir(shell().home) + "/"
        return records(ClaudeScripts.AGENTS, emptyList()).filter { it.tag == "A" }.mapNotNull { record ->
            val file = record.file() ?: return@mapNotNull null
            if (!file.path.startsWith(dir) || file.size > MAX_TEXT) return@mapNotNull null
            SubagentFiles.parse(file.path.removePrefix(dir), file.text()).copy(sha = sha256(file.head))
        }
    }

    /** 返回保存后的文件（相对 ~/.claude/agents）。改了名字时旧文件会删掉。 */
    fun saveAgent(original: Subagent?, draft: SubagentDraft): String {
        SubagentFiles.draftProblem(draft)?.let { throw ClaudeConfigException(it) }
        original?.let { SubagentFiles.relativeProblem(it.file)?.let { p -> throw ClaudeConfigException(p) } }
        val dir = ClaudePaths.agentsDir(shell().home)
        val target = SubagentFiles.targetFile(original?.file, draft.name.trim())
        var originalText: String? = null
        if (original != null) {
            val loaded = load("$dir/${original.file}")
            if (loaded.exists && original.sha.isNotEmpty() && loaded.sha != original.sha) {
                throw ClaudeConfigException("这个 Subagent 在你编辑时被改过了（可能是 Claude 改的）。返回列表重新打开再改。")
            }
            originalText = loaded.text.takeIf { loaded.exists }
        }
        val content = SubagentFiles.render(draft, originalText).toByteArray()
        val renamed = original != null && target != original.file
        val expected = if (original == null || renamed) "-" else "*"
        store("$dir/$target", content, expected, removeAfter = if (renamed) "$dir/${original.file}" else "")
            ?: throw ClaudeConfigException("已经有一个叫 ${draft.name.trim()} 的 Subagent 了")
        return target
    }

    fun deleteAgent(file: String) {
        SubagentFiles.relativeProblem(file)?.let { throw ClaudeConfigException(it) }
        records(ClaudeScripts.REMOVE_FILE, listOf(ClaudePaths.agentsDir(shell().home) + "/" + file))
    }
}
