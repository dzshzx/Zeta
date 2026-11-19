package com.dzshzx.zeta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.ui.components.EtaMonospace
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 正文与思考共用同一套渲染器，只在字号、墨色浓度与留白节奏上区分层级。 */
internal enum class MarkdownTone { Answer, Thinking }

internal val EditorialLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * 一次渲染所需的全部样式。按主题与语气整体记忆，块级组件只读取不再推导，
 * 避免每个段落各自拼一遍 TextStyle。
 */
@Immutable
internal class MarkdownStyle(
    val tone: MarkdownTone,
    val isDark: Boolean,
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val code: TextStyle,
    val table: TextStyle,
    val textColor: Color,
    val strongColor: Color,
    val quoteTextColor: Color,
    val secondaryColor: Color,
    val markerColor: Color,
    val subtleBorderColor: Color,
    val ruleColor: Color,
    val quoteBackground: Color,
    val quoteBarColor: Color,
    val codeBackground: Color,
    val codeHeaderBackground: Color,
    val tableHeaderBackground: Color,
    val inline: MarkdownInlineStyle,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** 引用块内的排版样式：保持清晰可读的次主墨色，而不是退化成发虚的说明灰。 */
    fun quoted(): MarkdownStyle = MarkdownStyle(
        tone = tone,
        isDark = isDark,
        body = body.copy(color = quoteTextColor),
        headings = headings.map { it.copy(color = strongColor) },
        code = code,
        table = table.copy(color = quoteTextColor),
        textColor = quoteTextColor,
        strongColor = strongColor,
        quoteTextColor = quoteTextColor,
        secondaryColor = secondaryColor,
        markerColor = markerColor,
        subtleBorderColor = subtleBorderColor,
        ruleColor = ruleColor,
        quoteBackground = quoteBackground,
        quoteBarColor = quoteBarColor,
        codeBackground = codeBackground,
        codeHeaderBackground = codeHeaderBackground,
        tableHeaderBackground = tableHeaderBackground,
        inline = inline,
    )
}

@Composable
internal fun rememberMarkdownStyle(tone: MarkdownTone): MarkdownStyle {
    val colors = COUITheme.colorScheme
    val textStyles = COUITheme.textStyles
    // COUITheme 换主题时就地改同一个配色对象的字段，所以按会变的那几个值记忆，不按对象记忆
    return remember(tone, colors.isDark, colors.primary, colors.labelPrimary, textStyles.bodyXL, textStyles.bodyM) {
        val answer = tone == MarkdownTone.Answer
        val isDark = colors.isDark
        // 墨色按层级取 COUI 的文字配色：正文 labelPrimary，重点再实一档；思考整体降一级。
        val strongColor = if (answer) colors.labelPrimary.copy(alpha = 1f) else colors.labelPrimaryVariant
        val textColor = if (answer) colors.labelPrimary else colors.labelSecondary
        val quoteTextColor = if (answer) colors.labelPrimaryVariant else colors.labelSecondary
        val secondaryColor = if (answer) colors.labelSecondary else colors.labelSecondaryVariant
        val markerColor = colors.labelSecondaryVariant
        val body = (if (answer) textStyles.bodyXL else textStyles.bodyM).copy(
            fontSize = if (answer) 16.sp else 13.5.sp,
            lineHeight = if (answer) ANSWER_LINE_HEIGHT_SP.sp else THINKING_LINE_HEIGHT_SP.sp,
            letterSpacing = if (answer) 0.1.sp else 0.sp,
            color = textColor,
            lineHeightStyle = EditorialLineHeightStyle,
        )
        // 中文无衬线体下 SemiBold 与常规字重接近；标题与重点统一使用 Bold + 实墨色拉开层级。
        val headings = if (answer) {
            listOf(
                body.copy(
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.2).sp,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.1).sp,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 16.5.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
                body.copy(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
            )
        } else {
            listOf(
                body.copy(
                    fontSize = 14.sp,
                    lineHeight = THINKING_LINE_HEIGHT_SP.sp,
                    fontWeight = FontWeight.Bold,
                    color = strongColor,
                ),
            )
        }
        val inlineCodeColor = if (answer) colors.labelPrimary else strongColor
        MarkdownStyle(
            tone = tone,
            isDark = isDark,
            body = body,
            headings = headings,
            code = TextStyle(
                fontFamily = EtaMonospace,
                fontSize = if (answer) 13.sp else 12.sp,
                lineHeight = if (answer) 20.sp else 18.sp,
                color = inlineCodeColor,
                lineHeightStyle = EditorialLineHeightStyle,
            ),
            table = body.copy(
                fontSize = if (answer) 14.sp else 13.sp,
                lineHeight = if (answer) 21.sp else 19.sp,
            ),
            textColor = textColor,
            strongColor = strongColor,
            quoteTextColor = quoteTextColor,
            secondaryColor = secondaryColor,
            markerColor = markerColor,
            // 底色、描边都是半透明的 COUI 容器色，铺在聊天页的白底、黑底或卡片底上都有层次
            subtleBorderColor = colors.container8,
            ruleColor = colors.divider,
            quoteBackground = colors.fillThin,
            quoteBarColor = colors.container16,
            codeBackground = colors.container4,
            codeHeaderBackground = colors.fillThin,
            tableHeaderBackground = colors.fillThin,
            inline = MarkdownInlineStyle(
                linkColor = colors.primary,
                codeBackground = colors.container8,
                strongColor = strongColor,
                codeColor = inlineCodeColor,
            ),
        )
    }
}

/**
 * 相邻块之间的留白。源码中的空行只用于切分块，不直接占据高度；可见间距按语义分配：
 * 标题前更宽以开启新章节，标题与引导段后收紧使其贴近所属内容，分割线上下保持均等呼吸感。
 */
internal fun markdownBlockGap(previous: MarkdownBlock?, current: MarkdownBlock, tone: MarkdownTone): TextUnit {
    if (previous == null) return 0.sp
    val gap = when {
        previous is MarkdownRule || current is MarkdownRule -> 16f
        current is MarkdownHeading && previous is MarkdownHeading -> 8f
        current is MarkdownHeading -> when {
            current.level == 1 -> 22f
            current.level == 2 -> 20f
            else -> 16f
        }
        previous is MarkdownHeading && current is MarkdownMath -> 10f
        previous is MarkdownHeading -> 6f
        previous is MarkdownMath && current is MarkdownMath -> 12f
        previous is MarkdownMath || current is MarkdownMath -> 14f
        previous is MarkdownParagraph && current is MarkdownList -> 8f
        previous is MarkdownParagraph && current is MarkdownParagraph -> 11f
        else -> 13f
    }
    val scale = if (tone == MarkdownTone.Answer) 1f else THINKING_GAP_SCALE
    return (gap * scale).sp
}

/** 紧凑列表项内部：纯文本与子列表保持紧凑，嵌套引用、代码或表格时留出结构间距。 */
internal fun markdownCompactGap(
    previous: MarkdownBlock?,
    current: MarkdownBlock,
    tone: MarkdownTone,
): TextUnit {
    if (previous == null) return 0.sp
    val structured = previous.isStructuredContainer() || current.isStructuredContainer()
    return when {
        structured -> if (tone == MarkdownTone.Answer) 8.sp else 6.sp
        else -> if (tone == MarkdownTone.Answer) 5.sp else 3.5.sp
    }
}

internal fun MarkdownBlock.isStructuredContainer(): Boolean = when (this) {
    is MarkdownQuote,
    is MarkdownCode,
    is MarkdownMath,
    is MarkdownAlert,
    is MarkdownTable,
    is MarkdownRule,
    -> true
    else -> false
}

internal const val ANSWER_LINE_HEIGHT_SP = 25
internal const val THINKING_LINE_HEIGHT_SP = 21
private const val THINKING_GAP_SCALE = 0.75f
