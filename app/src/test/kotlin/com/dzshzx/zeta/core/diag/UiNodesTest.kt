package com.dzshzx.zeta.core.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class UiNodesTest {
    @Test
    fun oneNodeIsOneLineOfFourColumns() {
        // tools/vd 按制表符切成四列（tools/vdisplay/nodes.go 的 parseNodes）：文字里的换行、制表符不能多出行或列
        val line = UiNodes.line(610, 80, "继续执行\n下一步\t2", "")
        assertEquals("610\t80\t继续执行 下一步 2\t", line)
        assertEquals(4, line.split('\t').size)
        assertEquals("1\t2\t\t点按展开控制", UiNodes.line(1, 2, "", "点按展开控制"))
    }
}
