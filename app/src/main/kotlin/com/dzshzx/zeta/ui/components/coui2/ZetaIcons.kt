package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * COUI 2 没有的图标，照它 Regular 档的画法自己画，和 COUI 图标放在一起粗细、大小一致。规格只写在这里：
 * - 24×24 的画布，图形落在 2–22 之内（四周留 2，算描边中线，不算线宽）；`IconsShots` 逐个核对。
 * - 描边一律用 [line]（线宽 1.7，圆头圆角），实心部分用 [solid]；圆角按部件大小取，大框 2.5–3.5，小部件可以更小。
 * - 路径用 SVG 的写法。颜色由 Icon 的 tint 决定；淡的部分（刻度表没亮的格）用半透明，tint 以后仍是淡的。
 * 新图标写成 `val 名字 by lazy { icon { … } }`，`IconsShots` 按反射列出来，不用登记。
 */
internal object ZetaIcons {
    /** 向上的箭头：发送、上移。 */
    val ArrowUp: ImageVector by lazy {
        icon {
            line("M12 21.15V2.85M5 9.85l7-7 7 7")
        }
    }

    /** 向下的箭头：下移、回到底部。 */
    val ArrowDown: ImageVector by lazy {
        icon {
            line("M12 2.85v18.3M5 14.15l7 7 7-7")
        }
    }

    /** 向下的折角：展开、下一个。 */
    val ChevronDown: ImageVector by lazy {
        icon {
            line("M3.5 7.75 12 16.25 20.5 7.75")
        }
    }

    /** 向上的折角：收起、上一个。 */
    val ChevronUp: ImageVector by lazy {
        icon {
            line("M3.5 16.25 12 7.75 20.5 16.25")
        }
    }

    /** 实心圆角方块：停止。 */
    val Stop: ImageVector by lazy {
        icon {
            solid("M7.5 4h9A3.5 3.5 0 0 1 20 7.5v9a3.5 3.5 0 0 1-3.5 3.5h-9A3.5 3.5 0 0 1 4 16.5v-9A3.5 3.5 0 0 1 7.5 4Z")
        }
    }

    /** 回形针：附加文件。 */
    val Paperclip: ImageVector by lazy {
        icon {
            line("M20.3 11.4 12.3 19.4a5.3 5.3 0 0 1-7.5-7.5L12.9 3.8a3.55 3.55 0 0 1 5.02 5.02L9.9 16.85a1.8 1.8 0 0 1-2.55-2.55L14.7 7")
        }
    }

    /** 归档。 */
    val Archive: ImageVector by lazy {
        icon {
            line("M4.6 3.35h14.8a1.75 1.75 0 0 1 1.75 1.75v1.6a1.75 1.75 0 0 1-1.75 1.75H4.6A1.75 1.75 0 0 1 2.85 6.7V5.1A1.75 1.75 0 0 1 4.6 3.35ZM4.35 8.45v9.2a3 3 0 0 0 3 3h9.3a3 3 0 0 0 3-3v-9.2M12 11.5v5.5M9.5 14.5 12 17l2.5-2.5")
        }
    }

    /** 取消归档。 */
    val Unarchive: ImageVector by lazy {
        icon {
            line("M4.6 3.35h14.8a1.75 1.75 0 0 1 1.75 1.75v1.6a1.75 1.75 0 0 1-1.75 1.75H4.6A1.75 1.75 0 0 1 2.85 6.7V5.1A1.75 1.75 0 0 1 4.6 3.35ZM4.35 8.45v9.2a3 3 0 0 0 3 3h9.3a3 3 0 0 0 3-3v-9.2M12 17v-5.5M9.5 14 12 11.5l2.5 2.5")
        }
    }

    /** 星光：技能。 */
    val Sparkle: ImageVector by lazy {
        icon {
            line("M10.2 6 Q11.57 12.23 17.8 13.6 Q11.57 14.97 10.2 21.2 Q8.83 14.97 2.6 13.6 Q8.83 12.23 10.2 6Z")
            line("M18.2 2.6 Q18.74 5.06 21.2 5.6 Q18.74 6.14 18.2 8.6 Q17.66 6.14 15.2 5.6 Q17.66 5.06 18.2 2.6Z")
        }
    }

    /** 闪电：立即插话。 */
    val Bolt: ImageVector by lazy {
        icon {
            line("M13.6 2.85 5.2 13.4h6.4l-1.2 7.75 8.4-10.55h-6.4l1.2-7.75Z")
        }
    }

    /** 划掉的图片：图片无法显示。 */
    val ImageOff: ImageVector by lazy {
        icon {
            line("M20.9 17.3V7.35a3.5 3.5 0 0 0-3.5-3.5H7.2M3.6 5.2A3.5 3.5 0 0 0 3.1 7.35v9.3a3.5 3.5 0 0 0 3.5 3.5h11.1M3.2 15.6l4.2-4.2 4 4M3.35 3.35l17.3 17.3")
        }
    }

    /** 扳手：没有专门图标的工具。 */
    val Wrench: ImageVector by lazy {
        icon {
            // 先竖着画（头朝上、开口朝上），再转 45° 让头朝右上
            group(rotate = 45f, pivotX = 12f, pivotY = 12f) {
                line("M9.9 12.69V19.9A2.1 2.1 0 0 0 14.1 19.9V12.69A5.6 5.6 0 0 0 13.7 2.16V5.7A1.7 1.7 0 0 1 10.3 5.7V2.16A5.6 5.6 0 0 0 9.9 12.69Z")
            }
        }
    }

    /** 分支：从这里分到新对话。 */
    val Branch: ImageVector by lazy {
        icon {
            line("M6.6 8.2v7.6M17.4 9.5c0 4.1-3.3 5.9-8.3 7.6")
            line("M6.6 3.05a2.55 2.55 0 1 0 0 5.1 2.55 2.55 0 1 0 0-5.1ZM6.6 15.85a2.55 2.55 0 1 0 0 5.1 2.55 2.55 0 1 0 0-5.1ZM17.4 4.35a2.55 2.55 0 1 0 0 5.1 2.55 2.55 0 1 0 0-5.1Z")
        }
    }

    /** 拼图：MCP 工具。 */
    val Puzzle: ImageVector by lazy {
        icon {
            line("M4.6 6.75H8.4A2.3 2.3 0 1 1 11.7 6.75H15.5A1.75 1.75 0 0 1 17.25 8.5V12.3A2.3 2.3 0 1 1 17.25 15.6V19.4A1.75 1.75 0 0 1 15.5 21.15H4.6A1.75 1.75 0 0 1 2.85 19.4V8.5A1.75 1.75 0 0 1 4.6 6.75Z")
        }
    }

    /** 打开的文件夹：上级目录、看项目。 */
    val FolderOpen: ImageVector by lazy {
        icon {
            line("M3.4 18.6V6.6a2.75 2.75 0 0 1 2.75-2.75h2.7a1.7 1.7 0 0 1 1.27.57l1.3 1.48h5.7a2.75 2.75 0 0 1 2.75 2.75v1.2M3.4 18.6l2.3-6.7a2.3 2.3 0 0 1 2.17-1.55H20a1.3 1.3 0 0 1 1.23 1.72l-2.2 6.4a2.75 2.75 0 0 1-2.6 1.86H5.1a1.7 1.7 0 0 1-1.7-1.73Z")
        }
    }

    /** 带星的文件夹：工作目录。 */
    val FolderStar: ImageVector by lazy {
        icon {
            line("M2.85 7.1a3 3 0 0 1 3-3h3.1a1.8 1.8 0 0 1 1.35.6l1.4 1.6h6.45a3 3 0 0 1 3 3v7.6a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3Z")
            line("M12 9.4 L13.03 11.88 L15.71 12.09 L13.66 13.84 L14.29 16.46 L12 15.05 L9.71 16.46 L10.34 13.84 L8.29 12.09 L10.97 11.88Z")
        }
    }

    /** 地球：网页。 */
    val Globe: ImageVector by lazy {
        icon {
            line("M12 2.85a9.15 9.15 0 1 0 0 18.3 9.15 9.15 0 1 0 0-18.3ZM12 2.85c-2.6 2.5-3.9 5.55-3.9 9.15s1.3 6.65 3.9 9.15M12 2.85c2.6 2.5 3.9 5.55 3.9 9.15s-1.3 6.65-3.9 9.15M2.85 12h18.3")
        }
    }

    /** 灯泡：思考、提示。 */
    val Lightbulb: ImageVector by lazy {
        icon {
            line("M9.1 16.6c0-1.5-.7-2.4-1.7-3.5A6.4 6.4 0 0 1 12 2.85a6.4 6.4 0 0 1 4.6 10.25c-1 1.1-1.7 2-1.7 3.5ZM9.3 18.85h5.4M10.4 21.15h3.2")
        }
    }

    /** 断开的链接：失效的符号链接。 */
    val LinkOff: ImageVector by lazy {
        icon {
            line("M10.6 6.2l1.7-1.7a4.45 4.45 0 0 1 6.3 6.3l-1.7 1.7M13.4 17.8l-1.7 1.7a4.45 4.45 0 0 1-6.3-6.3l1.7-1.7M3.35 3.35l17.3 17.3")
        }
    }

    /** 在文字里查找：Grep、Glob。 */
    val SearchText: ImageVector by lazy {
        icon {
            line("M2.85 5.2h18.3M2.85 11.2h5.6M2.85 17.2h4.6M15.3 9.9a4.4 4.4 0 1 0 0 8.8 4.4 4.4 0 1 0 0-8.8ZM18.5 17.5l2.65 2.65")
        }
    }

    /** 带加号的气泡：新对话。 */
    val NewChat: ImageVector by lazy {
        icon {
            line("M12 2.85c5.1 0 9.15 3.8 9.15 8.55S17.1 19.95 12 19.95c-1.25 0-2.45-.22-3.55-.63L3.3 20.8l1.35-4.2A8.2 8.2 0 0 1 2.85 11.4C2.85 6.65 6.9 2.85 12 2.85ZM12 7.6v7.6M8.2 11.4h7.6")
        }
    }

    /** 门框加向外的箭头：结束对话（终端里的 /exit）。 */
    val Exit: ImageVector by lazy {
        icon {
            line("M10 2.85H6.35a3.5 3.5 0 0 0-3.5 3.5v11.3a3.5 3.5 0 0 0 3.5 3.5H10M9.5 12h11.65M16.4 7.25 21.15 12l-4.75 4.75")
        }
    }

    /** 芯片：设备状况。 */
    val Chip: ImageVector by lazy {
        icon {
            line("M8.6 5.85h6.8a2.75 2.75 0 0 1 2.75 2.75v6.8a2.75 2.75 0 0 1-2.75 2.75H8.6a2.75 2.75 0 0 1-2.75-2.75V8.6A2.75 2.75 0 0 1 8.6 5.85ZM10 2.85v3M14 2.85v3M10 18.15v3M14 18.15v3M2.85 10h3M2.85 14h3M18.15 10h3M18.15 14h3M10.4 10.4h3.2v3.2h-3.2Z")
        }
    }

    /** 带加号的文件：新建文件。 */
    val FileAdd: ImageVector by lazy {
        icon {
            line("M7.6 2.85h8.8a2.75 2.75 0 0 1 2.75 2.75v9.9l-5.65 5.65H7.6a2.75 2.75 0 0 1-2.75-2.75V5.6A2.75 2.75 0 0 1 7.6 2.85ZM19.15 15.5h-3.7a2 2 0 0 0-2 2v3.65M12 7v6M9 10h6")
        }
    }

    /** 盾牌里一个 A：权限模式「自动判断」。 */
    val ShieldA: ImageVector by lazy { shield("M9 15 12 7.4 15 15M10.2 12.5h3.6") }

    /** 盾牌里一个 P：权限模式「计划模式」。 */
    val ShieldP: ImageVector by lazy { shield("M9.3 15V7.4h3.4a2.2 2.2 0 0 1 0 4.4H9.3") }

    /** 盾牌里一个 M：权限模式「逐项确认」。 */
    val ShieldM: ImageVector by lazy { shield("M8.6 15V7.4l3.4 4.55 3.4-4.55V15") }

    /** 盾牌里一个 E：权限模式「自动接受编辑」。 */
    val ShieldE: ImageVector by lazy { shield("M15 7.4H9.3V15H15M9.3 11.2h5.1") }

    /** 盾牌里一个感叹号：权限模式「完全放行」。 */
    val ShieldAlert: ImageVector by lazy { shield("M12 7.4v5M12 15v.01") }

    /** 盾牌里一个对勾：权限健康。 */
    val ShieldCheck: ImageVector by lazy { shield("M8.7 11.9l2.3 2.3 4.3-4.5") }

    private fun shield(mark: String): ImageVector = icon {
        line("M12 2.85 4.1 6.1c-.2 2.2-.2 4.3 0 6.1.5 4.4 3.6 7.4 7.9 8.95 4.3-1.55 7.4-4.55 7.9-8.95.2-1.8.2-3.9 0-6.1Z")
        line(mark)
    }

    /** 手机：手机工具。 */
    val Phone: ImageVector by lazy {
        icon {
            line("M8.85 2.85h6.3a3 3 0 0 1 3 3v12.3a3 3 0 0 1-3 3h-6.3a3 3 0 0 1-3-3V5.85a3 3 0 0 1 3-3ZM10.6 18h2.8")
        }
    }

    /** 存储：根目录。 */
    val Storage: ImageVector by lazy {
        icon {
            line("M5.85 3.35h12.3a3 3 0 0 1 3 3v1.6a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3v-1.6a3 3 0 0 1 3-3ZM5.85 13.05h12.3a3 3 0 0 1 3 3v1.6a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3v-1.6a3 3 0 0 1 3-3Z")
            solid("M7.3 7.15m-1.2 0a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0ZM7.3 16.85m-1.2 0a1.2 1.2 0 1 0 2.4 0 1.2 1.2 0 1 0-2.4 0Z")
        }
    }

    /** 上下滑动。 */
    val Swipe: ImageVector by lazy {
        icon {
            line("M12 2.85v5.4M8.6 6.25 12 2.85l3.4 3.4M12 21.15v-5.4M8.6 17.75 12 21.15l3.4-3.4M12 9.6a2.4 2.4 0 1 0 0 4.8 2.4 2.4 0 1 0 0-4.8Z")
        }
    }

    /** 点按。 */
    val Tap: ImageVector by lazy {
        icon {
            solid("M12 14.6m-2.6 0a2.6 2.6 0 1 0 5.2 0 2.6 2.6 0 1 0-5.2 0Z")
            line("M12 8.4a6.2 6.2 0 1 0 0 12.4 6.2 6.2 0 1 0 0-12.4Z")
            line("M4.4 9.1A8.6 8.6 0 0 1 19.6 9.1")
        }
    }

    /** 终端：执行命令。 */
    val Terminal: ImageVector by lazy {
        icon {
            line("M5.85 3.85h12.3a3 3 0 0 1 3 3v10.3a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3V6.85a3 3 0 0 1 3-3ZM7.2 9l3 3-3 3M12.6 15h4.4")
        }
    }

    /** 服务器：远程主机。 */
    val Server: ImageVector by lazy {
        icon {
            line(
                "M5.85 3.85h12.3a3 3 0 0 1 3 3v1.3a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3V6.85a3 3 0 0 1 3-3Z" +
                    "M5.85 13.85h12.3a3 3 0 0 1 3 3v1.3a3 3 0 0 1-3 3H5.85a3 3 0 0 1-3-3v-1.3a3 3 0 0 1 3-3Z",
            )
            solid("M7.4 7.5m-1.1 0a1.1 1.1 0 1 0 2.2 0 1.1 1.1 0 1 0-2.2 0ZM7.4 17.5m-1.1 0a1.1 1.1 0 1 0 2.2 0 1.1 1.1 0 1 0-2.2 0Z")
        }
    }

    /** 三角警告。 */
    val Warning: ImageVector by lazy {
        icon {
            line("M10.3 4.1a1.95 1.95 0 0 1 3.4 0l7.15 12.6a1.95 1.95 0 0 1-1.7 2.9H4.85a1.95 1.95 0 0 1-1.7-2.9ZM12 9.2v4.3")
            solid("M12 16.4m-1.1 0a1.1 1.1 0 1 0 2.2 0 1.1 1.1 0 1 0-2.2 0Z")
        }
    }

    /** 历史：接着上次做。 */
    val History: ImageVector by lazy {
        icon {
            line("M3.15 12a8.85 8.85 0 1 0 2.6-6.26L3.4 8.1M3.4 3.9v4.2h4.2M12 7.4V12l3.3 2")
        }
    }

    /** 线条的房子：主目录（COUI 的 Home 是实心的，和一排线条图标放不到一起）。 */
    val Home: ImageVector by lazy {
        icon {
            line("M2.85 10.9 10.3 4.5a2.6 2.6 0 0 1 3.4 0l7.45 6.4M4.6 9.4v8.95a2.8 2.8 0 0 0 2.8 2.8h9.2a2.8 2.8 0 0 0 2.8-2.8V9.4M9.6 21.15v-4.4a2.4 2.4 0 0 1 4.8 0v4.4")
        }
    }

    /** 钟面：定时任务。 */
    val Clock: ImageVector by lazy {
        icon {
            line("M12 2.85a9.15 9.15 0 1 0 0 18.3 9.15 9.15 0 1 0 0-18.3ZM12 7v5l3.3 2.2")
        }
    }

    /** 铃铛：通知。 */
    val Bell: ImageVector by lazy {
        icon {
            line("M12 3.15a5.9 5.9 0 0 0-5.9 5.9v4.6L4.35 16.9h15.3l-1.75-3.25v-4.6A5.9 5.9 0 0 0 12 3.15ZM9.9 19.4q2.1 2.6 4.2 0")
        }
    }

    /** 太阳：屏幕常亮、亮度。 */
    val Sun: ImageVector by lazy {
        icon {
            line(
                "M12 8.4a3.6 3.6 0 1 0 0 7.2 3.6 3.6 0 1 0 0-7.2ZM12 2.85v2.85M12 18.3v2.85M2.85 12h2.85M18.3 12h2.85" +
                    "M5.53 5.53l2.01 2.01M16.46 16.46l2.01 2.01M5.53 18.47l2.01-2.01M16.46 7.54l2.01-2.01",
            )
        }
    }

    /** 心电线：诊断。 */
    val Pulse: ImageVector by lazy {
        icon {
            line("M2.85 12.6h3.6l2.4-6.6 4.4 12.4 2.6-5.8h5.3")
        }
    }

    /** 没勾的复选框。 */
    val CheckboxBlank: ImageVector by lazy {
        icon {
            line("M7.35 3.85h9.3a3.5 3.5 0 0 1 3.5 3.5v9.3a3.5 3.5 0 0 1-3.5 3.5h-9.3a3.5 3.5 0 0 1-3.5-3.5v-9.3a3.5 3.5 0 0 1 3.5-3.5Z")
        }
    }
    /**
     * 刻度表：半圆上 [steps] 格刻度，亮几格是第几档（从 1 数），指针指着当前那格；[level] 为 0 是不知道档位，刻度全暗、指针朝上。
     * 每次调用都新建，调用方自己缓存（思考强度按钮按档位建好一组）。
     */
    fun gauge(level: Int, steps: Int): ImageVector = icon {
        val cx = 12f
        val cy = 16.2f
        val radius = 8.6f
        val step = 180f / steps
        val gap = 4.5f * 5 / steps
        repeat(steps) { i ->
            val start = 180f + i * step + gap
            val end = 180f + (i + 1) * step - gap
            val (x0, y0) = polar(cx, cy, radius, start)
            val (x1, y1) = polar(cx, cy, radius, end)
            val arc = PathBuilder().moveTo(x0, y0).arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1, y1).nodes
            line(arc, alpha = if (i < level) 1f else 0.4f)
        }
        val angle = if (level == 0) 270f else 180f + (level.coerceAtMost(steps) - 0.5f) * step
        val (nx, ny) = polar(cx, cy, 5f, angle)
        line(PathBuilder().moveTo(cx, cy).lineTo(nx, ny).nodes)
        solid("M$cx ${cy}m-1.7 0a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0Z")
    }

    private fun polar(cx: Float, cy: Float, r: Float, degrees: Float): Pair<Float, Float> {
        val rad = Math.toRadians(degrees.toDouble())
        return (cx + r * cos(rad)).toFloat() to (cy + r * sin(rad)).toFloat()
    }

    private const val STROKE = 1.7f

    /** 描边：线宽 1.7，圆头圆角。 */
    private fun ImageVector.Builder.line(d: String) = line(addPathNodes(d))

    private fun ImageVector.Builder.line(nodes: List<PathNode>, alpha: Float = 1f) {
        addPath(
            nodes,
            stroke = SolidColor(Color.Black),
            strokeAlpha = alpha,
            strokeLineWidth = STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** 填充：实心的部分（停止键的方块、圆点）。 */
    private fun ImageVector.Builder.solid(d: String) {
        addPath(addPathNodes(d), fill = SolidColor(Color.Black))
    }

    private fun icon(block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply(block)
            .build()
}
