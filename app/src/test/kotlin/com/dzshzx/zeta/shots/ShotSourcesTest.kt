package com.dzshzx.zeta.shots

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 测试里不直接用这几个 Compose 测试函数：
 * - performScrollTo / performScrollToNode（截图测试）：时钟不自动前进时会死循环，用 [ShotTest.scrollIntoView]；
 * - Compose 测试规则的 waitUntil（所有测试）：不执行主线程排队的消息，等后台线程投回来的结果会偶尔等不到，用 core 的 awaitMain。
 */
class ShotSourcesTest {
    /** [dir] 下（含子目录）有一行匹配 [pattern] 的源文件，ShotTest 自己和这个检查除外。 */
    private fun offenders(dir: String, pattern: Regex): List<String> =
        File(dir).walk()
            .filter { f -> f.extension == "kt" && f.name != "ShotTest.kt" && f.name != "ShotSourcesTest.kt" }
            .filter { f -> f.readLines().any { pattern.containsMatchIn(it) } }
            .map { it.name }
            .toList()

    @Test
    fun noRawScrollTo() =
        assertEquals("改用 scrollIntoView()", emptyList<String>(), offenders("src/test/kotlin/com/dzshzx/zeta/shots", Regex("""\.performScrollTo(Node)?\(""")))

    @Test
    fun noComposeWaitUntil() = assertEquals("改用 awaitMain()", emptyList<String>(), offenders("src/test/kotlin", Regex("""compose\.waitUntil\(""")))
}
