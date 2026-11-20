package com.dzshzx.zeta.shots

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 截图测试里不直接用这几个 Compose 测试函数，改用 [ShotTest] 里的替代：
 * - performScrollTo / performScrollToNode：时钟不自动前进时会死循环，用 [ShotTest.scrollIntoView]；
 * - waitUntil：不执行主线程排队的消息，等后台线程投回来的结果会偶尔等不到，用 [ShotTest.awaitMain]。
 */
class ShotSourcesTest {
    private fun offenders(pattern: Regex): List<String> =
        File("src/test/kotlin/com/dzshzx/zeta/shots")
            .listFiles { f -> f.extension == "kt" && f.name != "ShotTest.kt" && f.name != "ShotSourcesTest.kt" }!!
            .filter { f -> f.readLines().any { pattern.containsMatchIn(it) } }
            .map { it.name }

    @Test
    fun noRawScrollTo() = assertEquals("改用 scrollIntoView()", emptyList<String>(), offenders(Regex("""\.performScrollTo(Node)?\(""")))

    @Test
    fun noRawWaitUntil() = assertEquals("改用 awaitMain()", emptyList<String>(), offenders(Regex("""\.waitUntil\(""")))
}
