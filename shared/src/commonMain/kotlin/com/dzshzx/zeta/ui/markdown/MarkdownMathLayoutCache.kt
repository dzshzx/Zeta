package com.dzshzx.zeta.ui.markdown

/** 排版一个公式；排不了是 null。默认交给本平台的 [MathEngine]，测试可以换成假的。 */
fun interface MathLayoutEngine {
    fun layout(latex: String, displayMode: Boolean): MathLayout?
}

val markdownMathLayoutCache = MarkdownMathLayoutCache()

/**
 * 进程内有界公式布局缓存。
 *
 * 流式输出同一长段落时，前半句已闭合的行内公式会随尾部文字追加反复经过行内构建； 同时缓存成功与失败结果，使同一公式只调用一次原生解析，并返回同一 [MathLayout] 实例供组合期跳过。
 */
class MarkdownMathLayoutCache(
    private val engine: MathLayoutEngine = MathLayoutEngine(MathEngine.current::layout),
    private val maxEntries: Int = 256,
    private val maxCacheableChars: Int = 4_096,
) {
    init {
        require(maxEntries > 0 && maxCacheableChars > 0)
    }

    private data class Key(val latex: String, val displayMode: Boolean)

    private sealed interface Entry {
        data class Hit(val layout: MathLayout) : Entry

        data object Miss : Entry
    }

    private val lock = Any()
    private val map =
        object : LinkedHashMap<Key, Entry>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean =
                size > maxEntries
        }

    fun layout(latex: String, displayMode: Boolean): MathLayout? {
        val trimmed = latex.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > maxCacheableChars) {
            return engine.layout(trimmed, displayMode)
        }
        val key = Key(trimmed, displayMode)
        synchronized(lock) {
            when (val cached = map[key]) {
                is Entry.Hit -> return cached.layout
                Entry.Miss -> return null
                null -> Unit
            }
        }
        val computed = engine.layout(trimmed, displayMode)
        val entry = if (computed != null) Entry.Hit(computed) else Entry.Miss
        synchronized(lock) {
            map[key] = entry
        }
        return computed
    }
}
