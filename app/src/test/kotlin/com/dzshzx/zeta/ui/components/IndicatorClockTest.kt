package com.dzshzx.zeta.ui.components

import com.dzshzx.zeta.sourcesMatching
import org.junit.Assert.assertEquals
import org.junit.Test

class IndicatorClockTest {
    @Test
    fun turnGoesRoundOncePerPeriod() {
        assertEquals(0f, turn(0, 1000), 0f)
        assertEquals(90f, turn(250, 1000), 0.001f)
        assertEquals(0f, turn(1000, 1000), 0f)
        assertEquals(180f, turn(2500, 1000), 0.001f)
    }

    @Test
    fun pingPongGoesThereAndBack() {
        assertEquals(0.3f, pingPong(0, 600, 0.3f, 1f), 0.001f)
        assertEquals(1f, pingPong(600, 600, 0.3f, 1f), 0.001f)
        assertEquals(0.3f, pingPong(1200, 600, 0.3f, 1f), 0.001f)
        // 往回走和往前走对称
        assertEquals(pingPong(200, 600, 0.3f, 1f), pingPong(1000, 600, 0.3f, 1f), 0.001f)
    }

    @Test
    fun pingPongDelayShiftsStart() {
        // 错开的点在起点之前也有值，不是负数取模出来的越界值
        assertEquals(
            pingPong(1050, 600, 0.3f, 1f),
            pingPong(0, 600, 0.3f, 1f, delayMs = 150),
            0.001f,
        )
        assertEquals(0.3f, pingPong(150, 600, 0.3f, 1f, delayMs = 150), 0.001f)
    }

    /**
     * 一直循环的提示动画走 [rememberIndicatorClock]：`rememberInfiniteTransition` 和 `infiniteRepeatable`
     * 每一帧都要新画面， 手机上回答进行中会一直按刷新率整窗重画。
     */
    @Test
    fun noPerFrameInfiniteAnimations() {
        val pattern = Regex("""\b(rememberInfiniteTransition|infiniteRepeatable)\(""")
        assertEquals(
            "一直循环的提示动画改用 ui/components 的 rememberIndicatorClock",
            emptyList<String>(),
            sourcesMatching("src/main/kotlin", pattern) +
                sourcesMatching("../shared/src", pattern, except = setOf("IndicatorClock.kt")),
        )
    }
}
