package com.dzshzx.zeta.assistant

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回答面板的档位、松手停在哪、拖到哪里关掉或打开 Zeta。 */
class AssistantSheetTest {
    private val metrics =
        SheetMetrics(
            base = 300f,
            dismiss = 92f,
            handoff = 24f,
            maxLift = 40f,
            tolerance = 6f,
            slack = 12f,
        )

    /** 一块已经铺开的面板：最大高度 1000（可用 1200，输入栏那部分 200），已经升到基础高度。 */
    private fun openSheet(): SheetState =
        SheetState(metrics).apply {
            room = 1200
            panel = 500
            frame = 300
            show()
            settle(this)
        }

    /** 把高度的弹簧放完：每帧 16ms 的假时钟。 */
    private fun settle(sheet: SheetState) {
        var now = 0L
        val clock =
            object : MonotonicFrameClock {
                override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                    now += 16_000_000L
                    return onFrame(now)
                }
            }
        runBlocking(clock) { sheet.follow(sheet.target) }
    }

    @Test
    fun stopsFollowEta() {
        val stops = SheetStops.of(base = 300f, grown = 500f, max = 1000f)
        assertEquals(300f, stops.lowest)
        assertEquals(300f + 700f * 0.58f, stops.medium, 0.01f)
        assertEquals(300f, stops.nearest(100f))
        assertEquals(stops.medium, stops.nearest(650f), 0.01f)
        assertEquals(1000f, stops.nearest(950f))
        // 拖过以后停在比基础高度还低的地方：最低档就是那里
        assertEquals(200f, SheetStops.of(base = 300f, grown = 200f, max = 1000f).lowest)
        // 放不下基础高度（键盘弹出、屏幕很矮）：各档都截在最大高度以内
        val short = SheetStops.of(base = 300f, grown = 300f, max = 250f)
        assertEquals(250f, short.base)
        assertEquals(250f, short.lowest)
    }

    @Test
    fun opensAtBaseHeightAndOnlyGrows() {
        val sheet = openSheet()
        assertEquals(300f, sheet.target)
        assertEquals(300f, sheet.height, 0.5f)
        assertEquals(1000f, sheet.max)
        // 回答很短：保持基础高度
        sheet.viewport = 200
        sheet.content = 80
        assertEquals(300f, sheet.wanted())
        // 回答变长：按需要长高，长到最大为止
        sheet.content = 400
        assertEquals(300f - 200f + 400f + 12f, sheet.wanted())
        sheet.grow(sheet.wanted()!!)
        assertEquals(512f, sheet.target)
        sheet.content = 5000
        sheet.grow(sheet.wanted()!!)
        assertEquals(1000f, sheet.target)
        // 不缩回去
        sheet.grow(400f)
        assertEquals(1000f, sheet.target)
    }

    @Test
    fun draggingStopsAutoExpandAndSettlesToNearestStop() {
        val sheet = openSheet()
        sheet.startDrag()
        assertFalse(sheet.autoExpand)
        sheet.dragBy(-380f, allowHandoff = true)
        assertEquals(680f, sheet.height)
        assertEquals(SheetRelease.Settle(sheet.stops.medium), sheet.finishDrag(true, true))
        // 拖过以后回答再变长也不自动长高
        sheet.viewport = 100
        sheet.content = 5000
        assertNull(sheet.wanted())
    }

    @Test
    fun dismissNeedsPullBelowLowestStop() {
        val sheet = openSheet()
        sheet.startDrag()
        sheet.dragBy(80f, allowHandoff = false)
        assertEquals(300f, sheet.height)
        assertEquals(80f * 0.28f, sheet.rubber, 0.01f)
        assertEquals(SheetRelease.Settle(300f), sheet.finishDrag(true, false))
        sheet.startDrag()
        sheet.dragBy(100f, allowHandoff = false)
        assertEquals(SheetRelease.Dismiss, sheet.finishDrag(true, false))
        // 手势被取消时不关
        sheet.startDrag()
        sheet.dragBy(200f, allowHandoff = false)
        assertEquals(SheetRelease.Settle(300f), sheet.finishDrag(false, false))
    }

    @Test
    fun handoffOnlyFromHandleAtTheTop() {
        val sheet = openSheet()
        // 从把手一路拉过最高档：先长到最高，再拉 24 才够
        sheet.startDrag()
        sheet.dragBy(-710f, allowHandoff = true)
        assertEquals(1000f, sheet.height)
        assertFalse(sheet.handoffReady)
        assertTrue(sheet.handoffProgress > HandoffHintProgress)
        sheet.dragBy(-20f, allowHandoff = true)
        assertTrue(sheet.handoffReady)
        assertEquals(-(30f * 0.36f), sheet.rubber, 0.01f)
        assertEquals(SheetRelease.Handoff, sheet.finishDrag(true, true))
        // 拉得再远，面板往上浮最多 40
        sheet.startDrag()
        sheet.dragBy(-500f, allowHandoff = true)
        assertEquals(-40f, sheet.rubber)
        sheet.finishDrag(false, true)
        // 回答区的拖动拉过最高档不算数
        sheet.startDrag()
        sheet.dragBy(-500f, allowHandoff = false)
        assertEquals(0f, sheet.handoffProgress)
        assertEquals(SheetRelease.Settle(1000f), sheet.finishDrag(true, false))
    }

    @Test
    fun replyAreaDragClaimsOnlyWhenTheSheetCanMove() {
        val sheet = openSheet()
        // 面板没到最高：往上拖归面板；往下拖只有回答已经滚到顶才归面板
        assertTrue(sheet.claims(-10f, listAtTop = false))
        assertTrue(sheet.claims(10f, listAtTop = true))
        assertFalse(sheet.claims(10f, listAtTop = false))
        sheet.startDrag()
        sheet.dragBy(-1000f, allowHandoff = false)
        sheet.finishDrag(true, false)
        // 已经在最高：往上拖交给回答区滚动
        assertFalse(sheet.claims(-10f, listAtTop = true))
    }
}
