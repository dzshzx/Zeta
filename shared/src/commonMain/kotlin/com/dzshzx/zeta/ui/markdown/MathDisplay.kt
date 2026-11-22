package com.dzshzx.zeta.ui.markdown

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Text
import java.util.ServiceLoader
import kotlin.math.min

/** 排好版的一个公式。共用代码只存着它，交回排出它的那个 [MathEngine] 去画（手机上包着 RaTeX 的 DisplayList）。 */
interface MathLayout

/**
 * 公式的排版和绘制。手机上是 RaTeX（app 的 ui/markdown/RaTeXMath.kt）；桌面没有排版引擎（[PlainMath]），公式都按 LaTeX 原文显示。 每个平台在
 * META-INF/services 里登记一个。
 */
interface MathEngine {
    /** 排版；排不了（不认识的写法、引擎不可用）是 null，界面按原文显示。在后台线程上调用。 */
    fun layout(latex: String, displayMode: Boolean): MathLayout?

    /** 画块级公式 [block]，[layout] 是它排好的版。 */
    @Composable
    fun DisplayMath(
        block: MarkdownMath,
        layout: MathLayout,
        style: MarkdownStyle,
        inQuote: Boolean,
        reveal: SmoothTextRevealCoordinator?,
        modifier: Modifier,
    )

    /** 段落 [text] 里各行内公式 [specs] 的占位内容，键是占位的 id。 */
    @Composable
    fun inlineContent(
        text: AnnotatedString,
        specs: List<InlineMathSpec>,
        textStyle: TextStyle,
        fallbackColor: Color,
        maxInlineWidthDp: Dp?,
    ): Map<String, InlineTextContent>

    companion object {
        val current: MathEngine by lazy {
            ServiceLoader.load(MathEngine::class.java, MathEngine::class.java.classLoader)
                .firstOrNull() ?: error("没有登记公式引擎：META-INF/services/${MathEngine::class.java.name}")
        }
    }
}

/** 没有排版引擎（桌面）：[layout] 都是 null，块级公式按原文显示，行内公式留在文字里。 */
class PlainMath : MathEngine {
    override fun layout(latex: String, displayMode: Boolean): MathLayout? = null

    @Composable
    override fun DisplayMath(
        block: MarkdownMath,
        layout: MathLayout,
        style: MarkdownStyle,
        inQuote: Boolean,
        reveal: SmoothTextRevealCoordinator?,
        modifier: Modifier,
    ) = error("PlainMath 不排版，不会有排好的公式")

    @Composable
    override fun inlineContent(
        text: AnnotatedString,
        specs: List<InlineMathSpec>,
        textStyle: TextStyle,
        fallbackColor: Color,
        maxInlineWidthDp: Dp?,
    ): Map<String, InlineTextContent> = emptyMap()
}

/** 块级公式：排好版的交给 [MathEngine] 画；没排成的保留完整 LaTeX 原文，不丢字符，支持横向滚动与划词复制。 */
@Composable
fun MarkdownDisplayMathBlock(
    block: MarkdownMath,
    style: MarkdownStyle,
    inQuote: Boolean,
    reveal: SmoothTextRevealCoordinator?,
    modifier: Modifier = Modifier,
) {
    val layout = block.displayList
    if (layout != null) {
        MathEngine.current.DisplayMath(block, layout, style, inQuote, reveal, modifier)
        return
    }
    val fallbackParagraph =
        remember(block.offset, block.rawSource) {
            MarkdownParagraph(offset = block.offset, text = AnnotatedString(block.rawSource))
        }
    val scrollState = rememberScrollState()
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .mathHorizontalOverflowFade(scrollState)
                .horizontalScroll(scrollState)
                .padding(vertical = 2.dp),
    ) {
        MarkdownMathFallbackText(
            block = fallbackParagraph,
            textStyle = style.code.copy(color = style.secondaryColor),
            reveal = reveal,
        )
    }
}

@Composable
private fun MarkdownMathFallbackText(
    block: MarkdownParagraph,
    textStyle: TextStyle,
    reveal: SmoothTextRevealCoordinator?,
) {
    if (reveal == null || block.text.isEmpty()) {
        Text(text = block.text, style = textStyle, softWrap = false)
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    val animatedStyle = remember(textStyle) { textStyle.copy(textMotion = TextMotion.Animated) }
    Text(
        text = block.text,
        style = animatedStyle,
        softWrap = false,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layout -> revealState.onTextLayout(block.text.text, layout) },
    )
}

/** 仅在公式实际超出视口可横向滚动时启用离屏图层与边缘渐隐提示，常态短公式保持零离屏开销。 滚动偏移只在绘制期读取，拖动中不触发组合重组。 */
fun Modifier.mathHorizontalOverflowFade(scrollState: ScrollState): Modifier =
    this.graphicsLayer {
            compositingStrategy =
                if (scrollState.maxValue > 0) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
        }
        .drawWithContent {
            drawContent()
            val maxScroll = scrollState.maxValue
            if (maxScroll <= 0 || size.width <= 0f) return@drawWithContent
            val maxFadePx = min(16.dp.toPx(), size.width * 0.12f)
            val leftFadePx = min(scrollState.value.toFloat(), maxFadePx)
            if (leftFadePx > 0.5f) {
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = leftFadePx,
                        ),
                    size = Size(leftFadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            val rightFadePx = min((maxScroll - scrollState.value).toFloat(), maxFadePx)
            if (rightFadePx > 0.5f) {
                val startX = size.width - rightFadePx
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = startX,
                            endX = size.width,
                        ),
                    topLeft = Offset(startX, 0f),
                    size = Size(rightFadePx, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
