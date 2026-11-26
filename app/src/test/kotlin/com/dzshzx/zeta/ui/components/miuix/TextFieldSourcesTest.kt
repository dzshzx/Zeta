package com.dzshzx.zeta.ui.components.miuix

import com.dzshzx.zeta.sourcesMatching
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 应用代码里的输入框走 Zeta 自己的 [TextField]（光标由 Zeta 管），不直接用 Miuix 的 TextField：它的 String 版本把光标放在开头。 只有包一层的
 * TextFields.kt 用 Miuix 的。
 */
class TextFieldSourcesTest {
    private val direct =
        Regex(
            """^import top\.yukonga\.miuix\.kmp\.basic\.(TextField|\*)( as \w+)?$""",
        )

    @Test
    fun noDirectMiuixTextField() =
        assertEquals(
            "改用 ui/components/miuix 的 TextField",
            emptyList<String>(),
            sourcesMatching("src/main/kotlin", direct, except = setOf("TextFields.kt")),
        )
}
