package com.dzshzx.zeta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString

/**
 * 解析后的 Markdown 文档：后台线程从 GFM AST 构建，Compose 只负责绘制。
 *
 * 所有块都以源码起始偏移 [MarkdownBlock.offset] 作为身份：流式输出只在尾部追加， 已完成块的偏移与内容都不再变化，构建器会复用同一实例，组合期据此直接跳过。
 */
@Immutable
class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
    /** 文档内所有参与逐字显现的块，用于让显现协调器丢弃已消失的旧块。 */
    val revealKeys: Set<RevealBlockKey>,
    /** 链接与行内代码颜色在构建时写入；主题变化后旧文档不能直接复用。 */
    val inlineStyle: MarkdownInlineStyle,
)

@Immutable
sealed interface MarkdownBlock {
    val offset: Int
}

/** 承载一段富文本的叶子块；[revealKey] 即其源码偏移。 */
sealed interface MarkdownTextBlock : MarkdownBlock {
    val text: AnnotatedString
    val inlineMath: List<InlineMathSpec>
        get() = emptyList()

    val revealKey: RevealBlockKey
        get() = RevealBlockKey(offset)
}

@Immutable
data class InlineMathSpec(
    val id: String,
    val latex: String,
    val rawSource: String,
    val layout: MathLayout,
    /** 在 [MarkdownTextBlock.text] 中的字符区间，逐字显现将其视为单个不可分割原子步。 */
    val rangeStart: Int,
    val rangeEnd: Int,
    /** 紧邻左侧汉字且无空格时的补偿微距（单位 em）。 */
    val leadingSpaceEm: Float = 0f,
    /** 紧邻右侧汉字且无空格时的补偿微距（单位 em）。 */
    val trailingSpaceEm: Float = 0f,
)

data class MarkdownParagraph(
    override val offset: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

data class MarkdownHeading(
    override val offset: Int,
    val level: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

data class MarkdownCode(
    override val offset: Int,
    val language: String?,
    override val text: AnnotatedString,
) : MarkdownTextBlock

/**
 * 独立块级数学公式（`$$...$$` 或 `\[...\]`）。
 *
 * [layout] 由后台线程经 [MathEngine] 预排版；解析失败或本平台没有公式引擎时为 `null`， 界面退回按原文显示 [rawSource]，确保公式内容不丢失且始终可复制。
 */
@Immutable
data class MarkdownMath(
    override val offset: Int,
    val latex: String,
    val rawSource: String,
    val layout: MathLayout?,
) : MarkdownBlock {
    val revealKey: RevealBlockKey
        get() = RevealBlockKey(offset)
}

data class MarkdownQuote(
    override val offset: Int,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

data class MarkdownAlert(
    override val offset: Int,
    val kind: MarkdownAlertKind,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

enum class MarkdownAlertKind {
    Note,
    Tip,
    Important,
    Warning,
    Caution,
}

data class MarkdownList(
    override val offset: Int,
    val ordered: Boolean,
    /** 松散列表的项之间保留段落级留白，紧凑列表只保留行距。 */
    val loose: Boolean,
    val items: List<MarkdownListItem>,
) : MarkdownBlock

data class MarkdownListItem(
    val offset: Int,
    /** 有序列表的序号；无序列表为 null。 */
    val number: Int?,
    /** 任务列表的勾选状态；普通列表项为 null。 */
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>,
    /** marker 与首个文本块同时出现，避免流式时先冒出孤立的圆点。 */
    val markerRevealKey: RevealBlockKey?,
)

data class MarkdownTable(
    override val offset: Int,
    val alignments: List<MarkdownColumnAlign>,
    val header: List<MarkdownTableCell>,
    val rows: List<List<MarkdownTableCell>>,
) : MarkdownBlock

data class MarkdownTableCell(
    override val offset: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

enum class MarkdownColumnAlign {
    Start,
    Center,
    End,
}

data class MarkdownRule(override val offset: Int) : MarkdownBlock
