package com.dzshzx.zeta.ui.components.miuix

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.addSquircleRect

/**
 * 四个角一样大的超椭圆（HyperOS 的平滑圆角）形状：Miuix 只给了修饰符（squircleSurface、squircleClip）和路径
 * （Path.addSquircleRect），要拿 Shape 的地方（clip、background、border、阴影）用这个。
 */
@Immutable
class SquircleShape(private val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = with(density) { radius.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2)
        return Outline.Generic(Path().apply { addSquircleRect(size.width, size.height, r) })
    }

    override fun equals(other: Any?): Boolean = other is SquircleShape && other.radius == radius

    override fun hashCode(): Int = radius.hashCode()
}

/** 圆角的几档（照 HyperOS 常用的 4dp 一档），胶囊、卡片、面板按大小选一档，不在页面里各写数字。 */
object ZetaCorner {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
    val XXXXL = 32.dp
}
