package com.dzshzx.zeta.core.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** [Licenses] 的 SPDX 表达式和分段，[ZetaInfo] 的源码地址：都是纯函数。 */
class LicensesTest {
    @Test
    fun expressionsExpandIntoAlternatives() {
        assertEquals(listOf(listOf("MIT")), Licenses.alternatives("MIT"))
        assertEquals(listOf(listOf("MIT"), listOf("Apache-2.0")), Licenses.alternatives("MIT OR Apache-2.0"))
        assertEquals(listOf(listOf("BSD-3-Clause", "ISC")), Licenses.alternatives("BSD-3-Clause AND ISC"))
        // AND 比 OR 先结合，括号改变结合
        assertEquals(listOf(listOf("A"), listOf("B", "C")), Licenses.alternatives("A OR B AND C"))
        assertEquals(
            listOf(listOf("MIT", "Unicode-3.0"), listOf("Apache-2.0", "Unicode-3.0")),
            Licenses.alternatives("(MIT OR Apache-2.0) AND Unicode-3.0"),
        )
        assertEquals(
            listOf(listOf("Apache-2.0 WITH LLVM-exception")),
            Licenses.alternatives("Apache-2.0 WITH LLVM-exception"),
        )
        assertEquals(listOf("MIT", "Apache-2.0", "Unicode-3.0"), Licenses.ids("(MIT OR Apache-2.0) AND Unicode-3.0"))
    }

    @Test
    fun malformedExpressionsAreRejected() {
        for (bad in listOf("", "(MIT", "MIT)", "MIT AND", "OR MIT", "GPL-2.0-only WITH", "MIT Apache-2.0")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Licenses.alternatives(bad) }
        }
    }

    @Test
    fun paragraphsReflowWrappedLines() {
        val text = "  TITLE\n  Version 1\n\nFirst line\n  continues here.\r\n\r\n\n  Second.\n"
        assertEquals(listOf("TITLE Version 1", "First line continues here.", "Second."), Licenses.paragraphs(text))
    }

    @Test
    fun assetPathsFollowSpdxIds() {
        assertEquals("licenses/MIT.txt", Licenses.asset("MIT"))
        assertEquals("licenses/Apache-2.0-with-LLVM-exception.txt", Licenses.asset("Apache-2.0 WITH LLVM-exception"))
    }

    /** 正式版指向 tag，测试版指向版本名里的提交号（版本名的写法见 app/build.gradle.kts）。 */
    @Test
    fun sourceLinkFollowsVersionName() {
        assertEquals("v0.7.1", ZetaInfo.sourceRef("0.7.1"))
        assertEquals("https://github.com/dzshzx/Zeta/tree/v0.7.1", ZetaInfo.sourceUrl("0.7.1"))
        assertEquals("abc1234", ZetaInfo.sourceRef("0.7.2-dev.3+gabc1234"))
        assertEquals("https://github.com/dzshzx/Zeta/tree/abc1234", ZetaInfo.sourceUrl("0.7.2-dev.3+gabc1234"))
        // 不在 git 仓里构建时提交号是 unknown
        assertNull(ZetaInfo.sourceRef("0.7.0-dev.1+gunknown"))
        assertEquals("https://github.com/dzshzx/Zeta", ZetaInfo.sourceUrl("0.7.0-dev.1+gunknown"))
    }
}
