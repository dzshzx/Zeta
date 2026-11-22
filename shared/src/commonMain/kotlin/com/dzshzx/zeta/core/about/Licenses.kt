package com.dzshzx.zeta.core.about

/**
 * 应用里能看全文的许可证，键是 SPDX 标识。全文在 assets 的 [asset]：GPL-3.0 是构建时从仓根 LICENSE 拷进去的， 别的放在
 * `src/main/assets/licenses/`。MIT、BSD、ISC 的全文不带版权行（各组件的版权行另列在 [Component.copyright]）。
 */
object Licenses {
    val titles: Map<String, String> =
        linkedMapOf(
            "GPL-3.0-only" to "GNU General Public License v3.0",
            "Apache-2.0" to "Apache License 2.0",
            "MIT" to "MIT License",
            "BSD-3-Clause" to "BSD 3-Clause License",
            "ISC" to "ISC License",
            "OFL-1.1" to "SIL Open Font License 1.1",
            "Unicode-3.0" to "Unicode License v3",
        )

    fun asset(id: String): String = "licenses/" + id.replace(" WITH ", "-with-") + ".txt"

    /** 全文按空行分段，段内原来的折行并成空格：手机上按屏宽重新折行，不照 80 列的版式。 */
    fun paragraphs(text: String): List<String> =
        text
            .replace("\r\n", "\n")
            .split(Regex("""\n[ \t]*\n"""))
            .map { paragraph ->
                paragraph.lines().joinToString(" ") { it.trim() }.trim()
            }
            .filter { it.isNotEmpty() }

    /** 表达式里出现的许可证（`WITH` 连着例外算一个），按出现的先后、不重复：组件详情页显示的就是这几份全文。 */
    fun ids(expression: String): List<String> =
        ExpressionParser(expression).also { it.parse() }.seen.distinct()

    /**
     * SPDX 表达式展开成几种可选的组合，每种组合里的许可证都要遵守：`A OR (B AND C)` 是 [[A], [B, C]]。 只认 `AND`、`OR`、括号和
     * `WITH`，够写随包组件的那几种；写错（括号不配对、少了许可证）时抛 [IllegalArgumentException]。
     */
    fun alternatives(expression: String): List<List<String>> = ExpressionParser(expression).parse()
}

/** [Licenses.alternatives] 的递归下降：或 → 与 → 单个许可证或括号，`AND` 比 `OR` 先结合。 */
private class ExpressionParser(private val expression: String) {
    private val tokens = Regex("""\(|\)|[^\s()]+""").findAll(expression).map { it.value }.toList()
    private var at = 0

    /** 解析过程中依次遇到的许可证。 */
    val seen = ArrayList<String>()

    fun parse(): List<List<String>> {
        val result = anyOf()
        require(at == tokens.size) { "多出来的内容：$expression" }
        return result
    }

    private fun peek(): String? = tokens.getOrNull(at)

    private fun next(): String = requireNotNull(tokens.getOrNull(at++)) { "表达式不完整：$expression" }

    private fun anyOf(): List<List<String>> {
        val result = allOf().toMutableList()
        while (peek() == "OR") {
            at++
            result += allOf()
        }
        return result
    }

    private fun allOf(): List<List<String>> {
        var result = single()
        while (peek() == "AND") {
            at++
            val right = single()
            result = result.flatMap { left -> right.map { (left + it).distinct() } }
        }
        return result
    }

    private fun single(): List<List<String>> {
        val token = next()
        if (token == "(") {
            val inner = anyOf()
            require(next() == ")") { "括号不配对：$expression" }
            return inner
        }
        require(token !in OPERATORS) { "这里要一个许可证：$expression" }
        if (peek() != "WITH") return listOf(listOf(token.also { seen += it }))
        at++
        val exception = next()
        require(exception !in OPERATORS) { "WITH 后面要一个例外：$expression" }
        return listOf(listOf("$token WITH $exception".also { seen += it }))
    }

    private companion object {
        val OPERATORS = setOf("(", ")", "AND", "OR", "WITH")
    }
}
