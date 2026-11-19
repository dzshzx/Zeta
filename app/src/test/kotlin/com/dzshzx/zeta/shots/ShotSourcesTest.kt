package com.dzshzx.zeta.shots

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 截图测试里不直接用 performScrollTo / performScrollToNode：时钟不自动前进时它们会死循环，用 [ShotTest.scrollIntoView]。 */
class ShotSourcesTest {
    @Test
    fun noRawScrollTo() {
        val dir = File("src/test/kotlin/com/dzshzx/zeta/shots")
        val offenders = dir.listFiles { f -> f.extension == "kt" && f.name != "ShotTest.kt" && f.name != "ShotSourcesTest.kt" }!!
            .filter { f -> f.readLines().any { Regex("""\.performScrollTo(Node)?\(""").containsMatchIn(it) } }
            .map { it.name }
        assertEquals("改用 scrollIntoView()", emptyList<String>(), offenders)
    }
}
