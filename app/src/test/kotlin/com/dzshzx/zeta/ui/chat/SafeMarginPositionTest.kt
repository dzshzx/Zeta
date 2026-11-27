package com.dzshzx.zeta.ui.chat

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** 底栏按钮的长按说明：横向收进离窗口左右边各一个安全距离的范围，竖向照原样。 */
class SafeMarginPositionTest {
    private fun at(x: Int) =
        SafeMarginPosition(
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ) = IntOffset(x, 500)
                },
                margin = 30,
            )
            .calculatePosition(
                IntRect(0, 0, 0, 0),
                IntSize(1000, 2000),
                LayoutDirection.Ltr,
                IntSize(200, 80),
            )

    @Test
    fun keepsMarginOnBothSides() {
        assertEquals(IntOffset(30, 500), at(0))
        assertEquals(IntOffset(770, 500), at(900))
        assertEquals(IntOffset(400, 500), at(400))
    }
}
