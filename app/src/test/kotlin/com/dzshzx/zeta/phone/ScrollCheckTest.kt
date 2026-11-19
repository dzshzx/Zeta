package com.dzshzx.zeta.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollCheckTest {
    private val forward = 0x1000
    private val backward = 0x2000
    private val scrollUp = android.R.id.accessibilityActionScrollUp
    private val scrollDown = android.R.id.accessibilityActionScrollDown
    private val scrollLeft = android.R.id.accessibilityActionScrollLeft
    private val scrollRight = android.R.id.accessibilityActionScrollRight
    private val pageDown = android.R.id.accessibilityActionPageDown

    @Test
    fun parsesDirectionAndAmount() {
        assertEquals(ScrollDirection.DOWN, ScrollDirection.parse(" Down "))
        assertNull(ScrollDirection.parse("sideways"))
        assertNull(ScrollDirection.parse(null))
        assertEquals(ScrollAmount.PAGE, ScrollAmount.parse(null))
        assertEquals(ScrollAmount.SMALL, ScrollAmount.parse("SMALL"))
        assertNull(ScrollAmount.parse("half"))
    }

    @Test
    fun picksExactThenPageThenLegacyAction() {
        assertEquals(scrollDown, ScrollPlan.action(setOf(scrollDown, pageDown, forward), ScrollDirection.DOWN))
        assertEquals(pageDown, ScrollPlan.action(setOf(pageDown, forward), ScrollDirection.DOWN))
        assertEquals(forward, ScrollPlan.action(setOf(forward, backward), ScrollDirection.DOWN))
        assertEquals(backward, ScrollPlan.action(setOf(forward, backward), ScrollDirection.UP))
        assertNull(ScrollPlan.action(emptySet(), ScrollDirection.DOWN))
    }

    @Test
    fun legacyActionsFollowTheListAxis() {
        // 横着的列表（看 CollectionInfo 得知）：前进是向右，竖着滚不用前进后退
        assertEquals(forward, ScrollPlan.action(setOf(forward), ScrollDirection.RIGHT, horizontal = true))
        assertNull(ScrollPlan.action(setOf(forward), ScrollDirection.DOWN, horizontal = true))
        assertNull(ScrollPlan.action(setOf(forward), ScrollDirection.RIGHT))
        // 报了横向动作的控件，前进后退不当作竖着
        assertNull(ScrollPlan.action(setOf(forward, scrollRight), ScrollDirection.DOWN))
    }

    @Test
    fun otherAxisOnlyIsRefused() {
        assertTrue(ScrollPlan.onlyOtherAxis(setOf(scrollLeft, scrollRight), ScrollDirection.DOWN))
        assertFalse(ScrollPlan.onlyOtherAxis(setOf(scrollLeft, scrollDown), ScrollDirection.DOWN))
        assertFalse(ScrollPlan.onlyOtherAxis(setOf(forward), ScrollDirection.LEFT))
        assertNull(ScrollPlan.action(setOf(scrollLeft, scrollRight), ScrollDirection.DOWN))
    }

    @Test
    fun boundaryFromReportedActions() {
        // 到底的列表只报往回滚的动作
        assertEquals(false, ScrollPlan.canScroll(setOf(backward), ScrollDirection.DOWN))
        assertEquals(false, ScrollPlan.canScroll(setOf(scrollUp), ScrollDirection.DOWN))
        assertEquals(true, ScrollPlan.canScroll(setOf(forward, backward), ScrollDirection.DOWN))
        assertEquals(true, ScrollPlan.canScroll(setOf(scrollUp), ScrollDirection.UP))
        // 不报滚动动作的控件说不准
        assertNull(ScrollPlan.canScroll(emptySet(), ScrollDirection.DOWN))
        assertNull(ScrollPlan.canScroll(setOf(scrollLeft), ScrollDirection.DOWN))
    }

    @Test
    fun supportRankLooksAtTheWholeAxis() {
        assertEquals(4, ScrollPlan.supportRank(setOf(scrollUp), ScrollDirection.DOWN))
        assertEquals(3, ScrollPlan.supportRank(setOf(pageDown), ScrollDirection.UP))
        assertEquals(2, ScrollPlan.supportRank(setOf(backward), ScrollDirection.DOWN))
        assertEquals(0, ScrollPlan.supportRank(setOf(scrollLeft), ScrollDirection.DOWN))
        assertEquals(0, ScrollPlan.supportRank(setOf(forward), ScrollDirection.DOWN, horizontal = true))
    }

    @Test
    fun bestIsLargestThenBestSupportedThenDeepest() {
        assertEquals(1, ScrollPlan.best(listOf(ScrollCandidate(100, 4, 1), ScrollCandidate(200, 2, 1))))
        assertEquals(0, ScrollPlan.best(listOf(ScrollCandidate(200, 4, 1), ScrollCandidate(200, 2, 5))))
        assertEquals(1, ScrollPlan.best(listOf(ScrollCandidate(200, 2, 1), ScrollCandidate(200, 2, 5))))
        assertNull(ScrollPlan.best(emptyList()))
    }

    @Test
    fun strokeMovesFingerAgainstContent() {
        val area = Box(0, 100, 1000, 1101)
        // 要看下面的内容：手指从下往上
        assertEquals(Stroke(499, 900, 499, 300), ScrollPlan.stroke(area, ScrollDirection.DOWN, ScrollAmount.PAGE))
        assertEquals(Stroke(499, 300, 499, 900), ScrollPlan.stroke(area, ScrollDirection.UP, ScrollAmount.PAGE))
        val small = ScrollPlan.stroke(area, ScrollDirection.DOWN, ScrollAmount.SMALL)!!
        assertTrue(small.y1 - small.y2 in 200..260)
        val wide = Box(0, 0, 1001, 400)
        assertEquals(Stroke(200, 199, 800, 199), ScrollPlan.stroke(wide, ScrollDirection.LEFT, ScrollAmount.PAGE))
        assertNull(ScrollPlan.stroke(Box(0, 0, 1000, 30), ScrollDirection.DOWN, ScrollAmount.PAGE))
    }

    @Test
    fun boxHelpers() {
        assertEquals(Box(50, 50, 100, 100), Box(0, 0, 100, 100).intersect(Box(50, 50, 200, 200)))
        assertTrue(Box(0, 0, 10, 10).intersect(Box(20, 20, 30, 30)).isEmpty)
        assertEquals(Box(10, 15, 90, 85), Box(0, 0, 100, 100).inset(0.1f, 0.15f))
    }

    private fun anchors(vararg items: Pair<String, Int>) = items.map { (key, y) -> Anchor(key, 100, y) }

    @Test
    fun consistentMotionIsMeasured() {
        val before = anchors("title" to 50, "a" to 300, "b" to 500, "c" to 700)
        // 标题吸顶不动，列表项往上走了 200：往下滚了 200
        val after = anchors("title" to 50, "a" to 100, "b" to 300, "c" to 500, "d" to 700)
        assertEquals(ScrollResult.Moved(200), ScrollCheck.compare(before, after, ScrollDirection.DOWN))
        assertEquals(ScrollResult.Backward(200), ScrollCheck.compare(before, after, ScrollDirection.UP))
    }

    @Test
    fun replacedContentCountsAsChanged() {
        val before = anchors("title" to 50, "a" to 300, "b" to 500)
        val after = anchors("title" to 50, "x" to 300, "y" to 500)
        assertEquals(ScrollResult.Changed, ScrollCheck.compare(before, after, ScrollDirection.DOWN))
    }

    @Test
    fun identicalContentIsStill() {
        val before = anchors("a" to 300, "b" to 500)
        assertEquals(ScrollResult.Still, ScrollCheck.compare(before, before, ScrollDirection.DOWN))
        // 一像素的抖动不算动
        assertEquals(ScrollResult.Still, ScrollCheck.compare(before, anchors("a" to 301, "b" to 500), ScrollDirection.DOWN))
        assertEquals(ScrollResult.Unknown, ScrollCheck.compare(emptyList(), emptyList(), ScrollDirection.DOWN))
    }

    @Test
    fun duplicateKeysAndOutliersAreIgnored() {
        val before = anchors("icon" to 100, "icon" to 200, "a" to 300, "b" to 500, "c" to 700, "ad" to 900)
        // 一个控件自己乱动（轮播），不影响多数的判断
        val after = anchors("icon" to 400, "icon" to 600, "a" to 150, "b" to 350, "c" to 550, "ad" to 1200)
        assertEquals(ScrollResult.Moved(150), ScrollCheck.compare(before, after, ScrollDirection.DOWN))
    }

    @Test
    fun horizontalUsesX() {
        val before = listOf(Anchor("a", 100, 10), Anchor("b", 400, 10))
        val after = listOf(Anchor("a", 600, 10), Anchor("b", 900, 10))
        // 内容往右走：露出左边的内容
        assertEquals(ScrollResult.Moved(500), ScrollCheck.compare(before, after, ScrollDirection.LEFT))
    }

    @Test
    fun summaryTellsMovedAndEnd() {
        val half = { v: Int -> v / 2 }
        assertEquals("向下滚了约 100 像素（截图坐标），已经到底了", scrollSummary(ScrollResult.Moved(200), true, ScrollDirection.DOWN, half))
        assertEquals("向上滚动了，换了一屏内容", scrollSummary(ScrollResult.Changed, null, ScrollDirection.UP, half))
        assertEquals("没有滚动：已经到顶了", scrollSummary(ScrollResult.Still, true, ScrollDirection.UP, half))
        assertTrue(scrollSummary(ScrollResult.Still, null, ScrollDirection.DOWN, half).startsWith("没有滚动：这里可能不能向下滚"))
    }
}
