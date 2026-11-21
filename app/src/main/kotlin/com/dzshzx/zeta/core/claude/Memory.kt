package com.dzshzx.zeta.core.claude

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

data class TextDocument(val content: String, val sha: String, val exists: Boolean)

/** [ClaudeScripts.MEMORY_SCAN] 里一个项目目录的 P 记录。 */
data class ProjectDir(
    val key: String,
    val memoryCount: Int,
    val memoryMtime: Long,
    val sampleCwd: String?,
) {
    val hasMemory: Boolean
        get() = memoryCount >= 0
}

object ClaudeMemory {
    /** 记忆文件的前言：name、description、type（新版写在 metadata.type 下面）。 */
    fun entry(path: String, size: Long, head: String): MemoryEntry {
        val meta = Frontmatter.parse(Frontmatter.split(head).front)
        val nestedType = (meta["metadata"] as? FrontValue.Fields)?.values?.get("type")
        return MemoryEntry(
            file = path,
            fileName = ClaudePaths.baseName(path),
            name = meta["name"]?.text().orEmpty(),
            description = meta["description"]?.text().orEmpty(),
            type = meta["type"]?.text()?.ifEmpty { null } ?: nestedType.orEmpty(),
            size = size,
        )
    }

    /** 去掉 MEMORY.md 里指向 [fileName] 的那几行；没有要去掉的返回 null。 */
    fun indexWithout(index: String, fileName: String): String? {
        val lines = index.split("\n")
        val kept = lines.filter { "]($fileName)" !in it && "](./$fileName)" !in it }
        return if (kept.size == lines.size) null else kept.joinToString("\n")
    }

    /** 会话记录里抠出来的 `"cwd":"…"`（JSON 转义过）。 */
    fun sampleCwd(raw: String): String? {
        if (raw.isEmpty()) return null
        val value = McpRpc.parse("{$raw}") as? kotlinx.serialization.json.JsonObject ?: return null
        return (value["cwd"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf {
            it.startsWith("/")
        }
    }

    /** 项目列表：默认工作目录对应的项目排第一（即使还没有记忆），其余有 memory 目录、里面有文件的按修改时间新的在前。 名字从会话记录里的 cwd 还原，对不上的显示目录名。 */
    fun projects(
        dirs: List<ProjectDir>,
        defaultKey: String?,
        defaultLabel: String?,
    ): List<MemoryProject> {
        val samples = dirs.mapNotNull { it.sampleCwd }
        val others =
            dirs
                .filter { it.key != defaultKey && it.memoryCount > 0 }
                .sortedByDescending { it.memoryMtime }
                .map {
                    MemoryProject(
                        it.key,
                        ClaudePaths.labelFor(it.key, listOfNotNull(it.sampleCwd) + samples)
                            ?: it.key,
                        it.memoryCount,
                        false,
                    )
                }
        val default = defaultKey?.let { key ->
            MemoryProject(
                key,
                defaultLabel ?: key,
                dirs.firstOrNull { it.key == key }?.memoryCount?.coerceAtLeast(0) ?: 0,
                true,
            )
        }
        return listOfNotNull(default) + others
    }
}
