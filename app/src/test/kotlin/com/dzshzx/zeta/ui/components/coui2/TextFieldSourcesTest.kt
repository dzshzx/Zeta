package com.dzshzx.zeta.ui.components.coui2

import com.dzshzx.zeta.sourcesMatching
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 应用代码里的输入框和输入对话框走 Zeta 自己的 [TextField]、[TextInputDialog]（光标由 Zeta 管）， 不直接用 COUI 2 的
 * TextField、OverlayInputDialog：它们的 String 版本把光标放在开头。只有包一层的 TextFields.kt 用 COUI 的。
 */
class TextFieldSourcesTest {
    private val direct =
        Regex(
            """^import io\.github\.suqi8\.coui\.kmp\.(basic\.(TextField|\*)|overlay\.(OverlayInputDialog|\*))( as \w+)?$""",
        )

    @Test
    fun noDirectCouiTextField() =
        assertEquals(
            "改用 ui/components/coui2 的 TextField、TextInputDialog",
            emptyList<String>(),
            sourcesMatching("src/main/kotlin", direct, except = setOf("TextFields.kt")),
        )
}
