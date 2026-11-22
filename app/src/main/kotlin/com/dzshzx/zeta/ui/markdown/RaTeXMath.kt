package com.dzshzx.zeta.ui.markdown

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import io.ratex.RaTeXEngine
import io.ratex.RaTeXException
import io.ratex.RaTeXFontLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RaTeX 原生排版桥接。
 *
 * 传入全透明色作为默认颜色哨兵（`a == 0f`），使解析产出的 [DisplayList] 只与公式文本和 `displayMode`
 * 相关，可跨深浅色主题、正文/标题/引用/思考语气直接缓存复用；绘制前再将 哨兵颜色绑定为当前上下文墨色，同时保留公式内显式 `\color` 指定的颜色。
 */
internal object RaTeXMathLayoutEngine {
    private const val SENTINEL_TRANSPARENT_ARGB = 0
    private val nativeUnavailable = AtomicBoolean(false)

    fun layout(latex: String, displayMode: Boolean): DisplayList? {
        val normalized = latex.trim()
        if (normalized.isEmpty() || '\u0000' in normalized || nativeUnavailable.get()) return null
        return try {
            RaTeXEngine.parseBlocking(
                latex = normalized,
                displayMode = displayMode,
                color = SENTINEL_TRANSPARENT_ARGB,
            )
        } catch (_: RaTeXException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (error: LinkageError) {
            if (nativeUnavailable.compareAndSet(false, true)) {
                Log.w(
                    "Zeta",
                    "markdown math native engine unavailable: ${error.javaClass.simpleName}",
                )
            }
            null
        }
    }
}

internal fun DisplayList.bindDefaultColor(defaultColor: Color): DisplayList {
    if (items.isEmpty()) return this
    val fallback =
        RaTeXColor(
            r = defaultColor.red,
            g = defaultColor.green,
            b = defaultColor.blue,
            a = defaultColor.alpha,
        )
    val mapped = items.map { item ->
        when (item) {
            is DisplayItem.GlyphPath -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Line -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Rect -> item.copy(color = item.color.resolveWith(fallback))
            is DisplayItem.Path -> item.copy(color = item.color.resolveWith(fallback))
        }
    }
    return copy(items = mapped)
}

private fun RaTeXColor.resolveWith(fallback: RaTeXColor): RaTeXColor =
    when {
        a <= 0f -> fallback
        fallback.a >= 0.999f -> this
        else -> copy(a = (a * fallback.a).coerceIn(0f, 1f))
    }

internal object RaTeXFontRegistry {
    private const val ASSET_FONT_DIR = "fonts"
    private val SUPPLEMENTAL_FONTS =
        listOf(
            "Caligraphic-Bold" to "KaTeX_Caligraphic-Bold.ttf",
        )

    private val loaded = AtomicBoolean(false)
    private val loadLock = Any()
    private val supplementalCache = ConcurrentHashMap<String, Typeface>()
    private val readyState = mutableStateOf(false)

    @Composable
    fun rememberFontsReady(): Boolean {
        val ready by readyState
        if (ready) return true
        val appContext = LocalContext.current.applicationContext
        LaunchedEffect(appContext) {
            withContext(Dispatchers.IO) {
                ensureLoadedBlocking(appContext)
            }
        }
        return ready
    }

    fun ensureLoadedBlocking(context: Context) {
        if (loaded.get()) return
        synchronized(loadLock) {
            if (loaded.get()) return
            val appContext = context.applicationContext ?: context
            RaTeXFontLoader.ensureLoaded(appContext, ASSET_FONT_DIR)
            for ((fontId, fileName) in SUPPLEMENTAL_FONTS) {
                runCatching {
                    Typeface.createFromAsset(appContext.assets, "$ASSET_FONT_DIR/$fileName")
                }
                    .getOrNull()
                    ?.let { typeface ->
                        supplementalCache[fontId] = typeface
                    }
            }
            loaded.set(true)
            readyState.value = true
        }
    }

    fun resolveTypeface(fontId: String): Typeface? =
        RaTeXFontLoader.getTypeface(fontId) ?: supplementalCache[fontId] ?: Typeface.DEFAULT
}

/** RaTeX 排好的一个公式。 */
internal class RaTeXLayout(val list: DisplayList) : MathLayout

/** 行内公式排好的版（手机上都是 RaTeX 排的）。 */
internal val InlineMathSpec.raTeX: DisplayList
    get() = (displayList as RaTeXLayout).list

/** 手机上的公式引擎：RaTeX 排版、画。登记在 app 的 META-INF/services 里。 */
class RaTeXMathEngine : MathEngine {
    override fun layout(latex: String, displayMode: Boolean): MathLayout? =
        RaTeXMathLayoutEngine.layout(latex, displayMode)?.let(::RaTeXLayout)

    @Composable
    override fun DisplayMath(
        block: MarkdownMath,
        layout: MathLayout,
        style: MarkdownStyle,
        inQuote: Boolean,
        reveal: SmoothTextRevealCoordinator?,
        modifier: Modifier,
    ) = RaTeXDisplayMath(block, (layout as RaTeXLayout).list, style, inQuote, reveal, modifier)

    @Composable
    override fun inlineContent(
        text: AnnotatedString,
        specs: List<InlineMathSpec>,
        textStyle: TextStyle,
        fallbackColor: Color,
        maxInlineWidthDp: Dp?,
    ): Map<String, InlineTextContent> =
        rememberInlineMathContentMap(text, specs, textStyle, fallbackColor, maxInlineWidthDp)
}
