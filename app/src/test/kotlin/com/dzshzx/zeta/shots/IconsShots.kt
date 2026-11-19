package com.dzshzx.zeta.shots

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dzshzx.zeta.ui.chat.EffortGauges
import com.dzshzx.zeta.ui.components.coui2.ZetaIcons
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.File
import io.github.suqi8.coui.kmp.icon.extended.Folder
import io.github.suqi8.coui.kmp.icon.extended.Search
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.lang.reflect.Modifier as JavaModifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自画图标（[ZetaIcons]）一览：每个都画出来，最后一行是思考强度刻度表的各种样子（0 是不知道档位），
 * 开头几个是 COUI 自带的图标，用来比粗细、大小。新加的图标不用登记，按反射列出来；图形边界由 [drawnIconsStayInsideTheMargin] 核对。
 */
class IconsShots : ShotTest() {
    private fun drawn(): List<Pair<String, ImageVector>> =
        ZetaIcons::class.java.declaredMethods
            .filter { JavaModifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType == ImageVector::class.java && it.name.startsWith("get") }
            .sortedBy { it.name }
            .map { it.name.removePrefix("get") to it.invoke(ZetaIcons) as ImageVector }

    @Test
    fun icons() {
        val reference = listOf("COUI 复制" to COUIIcons.Copy, "COUI 文件" to COUIIcons.File, "COUI 文件夹" to COUIIcons.Folder, "COUI 搜索" to COUIIcons.Search)
        val efforts = EffortGauges.mapIndexed { level, icon -> "强度 $level" to icon }
        val all = reference + drawn() + efforts
        shoot("zeta-icons") {
            Column(
                Modifier.fillMaxWidth().background(COUITheme.colorScheme.background).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                all.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (name, icon) ->
                            Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(imageVector = icon, contentDescription = name, modifier = Modifier.size(24.dp), tint = COUITheme.colorScheme.labelPrimary)
                                Text(
                                    text = name,
                                    modifier = Modifier.padding(top = 3.dp),
                                    style = COUITheme.textStyles.bodyS.copy(fontSize = 9.sp),
                                    color = COUITheme.colorScheme.labelSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /** 规格里「图形落在 2–22 之内」：每条路径（套上所在组的旋转、缩放、平移）按描边中线算边界，不算线宽。 */
    @Test
    fun drawnIconsStayInsideTheMargin() {
        val icons = drawn() + EffortGauges.mapIndexed { level, icon -> "强度 $level" to icon }
        val outside = icons.flatMap { (name, icon) ->
            paths(icon.root, Matrix()).mapNotNull { path ->
                val bounds = RectF().also { path.computeBounds(it, true) }
                val inside = bounds.left >= 2f - SLACK && bounds.top >= 2f - SLACK && bounds.right <= 22f + SLACK && bounds.bottom <= 22f + SLACK
                if (inside) null else "$name：$bounds"
            }
        }
        assertTrue("这些图形超出了 2–22：\n" + outside.joinToString("\n"), outside.isEmpty())
    }

    private fun paths(group: VectorGroup, parent: Matrix): List<Path> {
        val matrix = Matrix(parent).apply {
            preTranslate(group.translationX + group.pivotX, group.translationY + group.pivotY)
            preRotate(group.rotation)
            preScale(group.scaleX, group.scaleY)
            preTranslate(-group.pivotX, -group.pivotY)
        }
        return group.flatMap { node ->
            when (node) {
                is VectorGroup -> paths(node, matrix)
                is VectorPath -> listOf(node.pathData.toPath().asAndroidPath().apply { transform(matrix) })
            }
        }
    }

    private companion object {
        /** 坐标写到小数点后两位，旋转后会有一点误差。 */
        const val SLACK = 0.05f
    }
}
