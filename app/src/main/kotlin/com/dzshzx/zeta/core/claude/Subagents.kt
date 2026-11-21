package com.dzshzx.zeta.core.claude

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
    /** 读到时文件内容的 sha256，保存前用它判断文件在编辑期间有没有被改过。 */
    val sha: String = "",
)

data class SubagentDraft(
    val name: String,
    val description: String,
    val model: String,
    val tools: List<String>?,
    val prompt: String,
)

/**
 * ~/.claude/agents 下的 Subagent 文件：前言写 name、description、model、tools，正文是系统提示词。
 * 改写时只重写这四个键，界面不管的键（color、permissionMode、skills……）按原文行留着。
 */
object SubagentFiles {
    private val Known = setOf("name", "description", "model", "tools")
    private val NamePattern = Regex("[a-z0-9][a-z0-9-]{0,63}")

    fun isValidName(name: String) = NamePattern.matches(name)

    fun parse(file: String, text: String): Subagent {
        val split = Frontmatter.split(text)
        val meta = Frontmatter.parse(split.front)
        val tools =
            when (val value = meta["tools"]) {
                is FrontValue.Text ->
                    value.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                is FrontValue.Items -> value.values
                else -> null
            }
        return Subagent(
            file = file,
            name = meta["name"]?.text().orEmpty(),
            // 有的文件把换行写成字面上的 \n
            description = meta["description"]?.text().orEmpty().replace("\\n", "\n"),
            model = meta["model"]?.text().orEmpty(),
            tools = tools,
            prompt = split.body.trim('\n'),
            extraKeys = meta.keys.filter { it !in Known },
        )
    }

    /** 名字和说明的问题；没问题返回 null。 */
    fun draftProblem(draft: SubagentDraft): String? =
        when {
            !isValidName(draft.name) -> "名称只能用小写字母、数字和连字符，并以字母或数字开头"
            draft.description.isBlank() -> "请写上什么时候用它（description），Claude 靠它决定要不要交给这个 Subagent"
            else -> null
        }

    /** 相对路径能不能动：不出 agents 目录、以 .md 结尾。 */
    fun relativeProblem(file: String): String? {
        val parts = file.split('/')
        return when {
            file.isEmpty() || file.startsWith("/") || !file.endsWith(".md") -> "不能改这个文件"
            parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith(".") } -> "不能改这个文件"
            file.any { it < ' ' } -> "不能改这个文件"
            else -> null
        }
    }

    /** 保存到哪：编辑时放在原文件所在的子目录里，文件名跟着名字走；新建放在 agents 目录下。 */
    fun targetFile(original: String?, name: String): String {
        val dir = original?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
        return if (dir != null) "$dir/$name.md" else "$name.md"
    }

    /** 拼出文件内容；[original] 是原文件的内容（新建为 null），从里面留下不认识的键。 */
    fun render(draft: SubagentDraft, original: String?): String {
        val kept = mutableListOf<String>()
        original
            ?.let { Frontmatter.split(it).front }
            ?.let { front ->
                for (block in Frontmatter.blocks(front)) {
                    if (block.key !in Known && block.lines.any { it.isNotBlank() })
                        kept += block.lines
                }
            }
        val lines =
            mutableListOf(
                "---",
                "name: " + draft.name.trim(),
                "description: " + Frontmatter.yamlValue(draft.description.trim()),
            )
        draft.model
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { lines += "model: " + Frontmatter.yamlValue(it) }
        draft.tools?.let { tools ->
            val cleaned = tools.map { it.trim() }.filter { it.isNotEmpty() }
            lines +=
                if (cleaned.isEmpty()) "tools: []"
                else "tools: " + Frontmatter.yamlValue(cleaned.joinToString(", "))
        }
        lines += kept
        lines += "---"
        return lines.joinToString("\n") + "\n\n" + draft.prompt.trim('\n') + "\n"
    }
}
