package com.dzshzx.zeta.assistant

/**
 * 唤起助理时前台的一个应用：系统给的 AssistStructure、AssistContent 里读出来的东西，
 * 或者 ASSIST intent 里带的包名（那时没有界面文字）。
 */
data class ScreenApp(
    val packageName: String?,
    val label: String?,
    /** 应用自己报的网页地址（浏览器、带网页的应用）。 */
    val webUri: String? = null,
    /** 是不是有焦点的那个（分屏时有几个）。 */
    val focused: Boolean = true,
    /** 屏幕上的文字，按界面顺序，已去重、限长。 */
    val lines: List<String> = emptyList(),
    /** 文字太多，后面的没收。 */
    val truncated: Boolean = false,
)

/** 界面树上的一个节点。真机上是 AssistStructure.ViewNode（见 [AssistReader]），抽出来好写单元测试。 */
internal interface ScreenNode {
    /** 不看它和它下面的：不可见、密码框、应用声明了不给助理看。 */
    val skipped: Boolean
    val text: String?
    val description: String?
    val childCount: Int
    fun child(index: Int): ScreenNode
}

/**
 * 把唤起助理时的屏幕整理成一段给 Claude 的精简文字：应用名、包名、网址、主要可见文字。
 * 纯函数，单元测试覆盖。截图另作图片附件，这里只说有没有。
 */
object ScreenDigest {
    /** 一个应用最多收多少行、多少字。 */
    const val MAX_LINES = 150
    const val MAX_CHARS = 3_000

    /** 一行最长多少字（长段落截断）。 */
    const val MAX_LINE = 300

    /** 最多走多少个节点、多深：有的应用界面树很大，读完要好几百毫秒。 */
    private const val MAX_NODES = 1_500
    private const val MAX_DEPTH = 48

    /** 节点没有文字时，描述（图标按钮的读屏说明）超过这么长才收：短的多是「返回」「更多」这类按钮名。 */
    private const val MIN_DESCRIPTION = 4

    /** 消息里屏幕内容那一段的开头和结尾。 */
    const val OPEN_TAG = "<screen_context>"
    const val CLOSE_TAG = "</screen_context>"

    /** 从界面树里按顺序收文字：先文字，没有文字才用够长的描述；空白折成一个空格，重复的只留第一次。 */
    internal fun collect(roots: List<ScreenNode>): Pair<List<String>, Boolean> {
        val seen = LinkedHashSet<String>()
        var chars = 0
        var visited = 0
        var truncated = false
        val stack = ArrayDeque<Pair<ScreenNode, Int>>()
        for (root in roots.asReversed()) stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            if (visited >= MAX_NODES) {
                truncated = true
                break
            }
            val (node, depth) = stack.removeLast()
            visited++
            if (node.skipped) continue
            val line = clean(node.text) ?: clean(node.description)?.takeIf { it.length >= MIN_DESCRIPTION }
            if (line != null && line !in seen) {
                if (seen.size >= MAX_LINES || chars + line.length > MAX_CHARS) {
                    truncated = true
                    break
                }
                seen += line
                chars += line.length
            }
            if (depth >= MAX_DEPTH) {
                if (node.childCount > 0) truncated = true
                continue
            }
            for (i in node.childCount - 1 downTo 0) stack.addLast(node.child(i) to depth + 1)
        }
        return seen.toList() to truncated
    }

    private fun clean(value: String?): String? {
        val text = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (text.length > MAX_LINE) text.take(MAX_LINE) + "…" else text
    }

    /**
     * 屏幕内容那一段。[apps] 为空且没有截图时为 null（什么都没带）。有焦点的应用排前面。
     */
    fun contextBlock(apps: List<ScreenApp>, screenshot: Boolean): String? {
        if (apps.isEmpty() && !screenshot) return null
        return buildString {
            append(OPEN_TAG).append('\n')
            append(if (screenshot) "唤起 Zeta 时的屏幕，截图见附图。" else "唤起 Zeta 时在前台的应用，没有截图。").append('\n')
            for (app in apps.sortedByDescending { it.focused }) {
                append("应用：").append(appName(app)).append('\n')
                app.webUri?.takeIf { it.isNotBlank() }?.let { append("网址：").append(it.take(MAX_LINE)).append('\n') }
                if (app.lines.isNotEmpty()) {
                    append("屏幕上的文字：\n")
                    app.lines.forEach { append(it).append('\n') }
                    if (app.truncated) append("（文字太多，后面的没有列出）\n")
                }
            }
            append(CLOSE_TAG)
        }
    }

    /** 「设置（com.android.settings）」；没有名字时只有包名，什么都没有时「不知道是哪个应用」。 */
    fun appName(app: ScreenApp): String {
        val label = app.label?.trim()?.takeIf { it.isNotEmpty() }
        val pkg = app.packageName?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            label != null && pkg != null && label != pkg -> "$label（$pkg）"
            else -> label ?: pkg ?: "不知道是哪个应用"
        }
    }

    /** 发给 Claude 的消息：用户的问题，后面空一行接屏幕内容（有的话）。 */
    fun message(prompt: String, block: String?): String {
        val question = prompt.trim()
        return if (block == null) question else "$question\n\n$block"
    }
}

/** 浮窗上给的几个建议问题。没带屏幕内容时不给（问“这页”没有意义）。 */
object AssistantSuggestions {
    private val forScreen = listOf("这页讲了什么", "总结要点", "翻译成中文", "接下来该怎么做")

    fun forContext(hasScreen: Boolean): List<String> = if (hasScreen) forScreen else emptyList()
}
