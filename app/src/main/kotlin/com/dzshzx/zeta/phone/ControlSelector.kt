package com.dzshzx.zeta.phone

/** 控件上能拿来匹配的信息，从 AccessibilityNodeInfo 抄出来（这样匹配规则不碰 Android，能做单元测试）。 */
internal data class ControlFacts(
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val viewId: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
)

/** 找控件是为了做什么：同样对得上的几个里，优先挑能做这件事的。 */
internal enum class ControlPurpose {
    TAP,
    LONG_PRESS,
    TYPE,
    SCROLL,
    ANY,
}

/** 挑中的控件在候选列表里的位置，和跟它一样好的有几个（含它自己）。 */
internal class ControlPick(val index: Int, val ties: Int)

/**
 * 按文字和资源 id 找控件，不用先看屏、记编号。
 *
 * 文字对控件的文字、描述、提示三样比，不分大小写，空白合并：完全相同的最好，其次是开头相同，再次是包含。 id 认完整的 `包名:id/名字`，也认 `/` 后面的名字。两样都给时都要对上。
 */
internal class ControlSelector private constructor(val match: String?, val id: String?) {
    private val needle = match?.let(::normalize)

    /** 对得上的程度：0 是对不上，越大越好。 */
    fun score(facts: ControlFacts): Int {
        if (id != null) {
            val viewId = facts.viewId ?: return 0
            if (viewId != id && viewId.substringAfterLast('/') != id.substringAfterLast('/'))
                return 0
        }
        val wanted = needle ?: return 1
        return listOfNotNull(facts.text, facts.description, facts.hint).maxOfOrNull {
            textScore(normalize(it), wanted)
        } ?: 0
    }

    private fun textScore(value: String, wanted: String): Int =
        when {
            value.isEmpty() -> 0
            value == wanted -> 3
            value.startsWith(wanted) -> 2
            wanted in value -> 1
            else -> 0
        }

    /** 从按屏幕上先后排好的控件里挑一个。点按、长按、滚动先看对得上的程度，再看能不能做这件事； 输入反过来，先要是输入框（旁边写着同样文字的标签点不进去）。都一样时取靠前的。 */
    fun pick(controls: List<ControlFacts>, purpose: ControlPurpose): ControlPick? {
        var best = -1
        var bestRank = 0L
        var ties = 0
        controls.forEachIndexed { index, facts ->
            val score = score(facts)
            if (score == 0) return@forEachIndexed
            val rank = rank(facts, score, purpose)
            when {
                best < 0 || rank > bestRank -> {
                    best = index
                    bestRank = rank
                    ties = 1
                }
                rank == bestRank -> ties++
            }
        }
        return if (best < 0) null else ControlPick(best, ties)
    }

    /** 挑控件时的排位，越大越好：对得上的程度、能不能做这件事、可不可用，输入时前两样对调。 */
    private fun rank(facts: ControlFacts, score: Int, purpose: ControlPurpose): Long {
        val fit = if (fits(facts, purpose)) 1L else 0L
        val enabled = if (facts.enabled) 1L else 0L
        return if (purpose == ControlPurpose.TYPE) fit * 100 + score * 10 + enabled
        else score * 100 + fit * 10 + enabled
    }

    private fun fits(facts: ControlFacts, purpose: ControlPurpose): Boolean =
        when (purpose) {
            ControlPurpose.TAP -> facts.clickable
            ControlPurpose.LONG_PRESS -> facts.longClickable
            ControlPurpose.TYPE -> facts.editable
            ControlPurpose.SCROLL -> facts.scrollable
            ControlPurpose.ANY -> true
        }

    /** 说给 Claude 和用户听的：「搜索」、id=search_bar、「搜索」(id=search_bar)。 */
    fun describe(): String =
        listOfNotNull(match?.let { "「$it」" }, id?.let { "id=$it" }).joinToString(" ")

    companion object {
        /** 两样都没给（或都是空白）时返回 null。 */
        fun of(match: String?, id: String?): ControlSelector? {
            val m = match?.takeIf { it.isNotBlank() }
            val i = id?.trim()?.takeIf { it.isNotEmpty() }
            return if (m == null && i == null) null else ControlSelector(m, i)
        }

        private val SPACE = Regex("\\s+")

        private fun normalize(value: String): String = value.replace(SPACE, " ").trim().lowercase()
    }
}
