package com.dzshzx.zeta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay

/** 一直循环的提示动画每秒走几步。 */
const val IndicatorFps = 20

/**
 * 一直循环的提示动画（转圈、呼吸、闪烁的点）的时钟：从第一次出现起过了多少毫秒，每秒只走 [IndicatorFps] 步。 用 [turn]、[pingPong]
 * 换算成角度、透明度；同一个提示里几样东西周期不同时共用一个时钟，每步只重画一次。
 *
 * 不用 `rememberInfiniteTransition`：它每一帧都要一次新画面，手机上是每秒 120 帧整窗重画，
 * 回答进行中等工具跑时屏上没有新字也一直画，绘制线程加主线程常占一个核的一半上下。 这些提示转得慢、变得慢，每秒 20 步看得过去，重画少到约六分之一。
 *
 * 值只在绘制阶段读（`graphicsLayer {}`、`drawBehind`、`Canvas`），每走一步只重画、不重组。
 * 步子跟着帧时钟走：界面停止、帧时钟暂停时也停；截图测试的时钟不走，停在 0。
 */
@Composable
fun rememberIndicatorClock(): State<Long> {
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            delay(1000L / IndicatorFps)
            elapsed.longValue = withFrameMillis { it } - start
        }
    }
    return elapsed
}

/** 匀速转圈：[elapsed] 毫秒时转到的角度（0 到 360），每 [periodMs] 毫秒一圈。 */
fun turn(elapsed: Long, periodMs: Int): Float = (elapsed % periodMs) * 360f / periodMs

/**
 * 来回变的值：每 [halfMs] 毫秒从 [from] 缓动到 [to]，再同样缓动回来，同 `infiniteRepeatable(tween(halfMs),
 * RepeatMode.Reverse)`。 [delayMs] 把起点往后错开，几个点依次闪时用。
 */
fun pingPong(elapsed: Long, halfMs: Int, from: Float, to: Float, delayMs: Int = 0): Float {
    val c = (elapsed - delayMs).mod(2L * halfMs).toFloat() / halfMs
    val t = FastOutSlowInEasing.transform(if (c < 1f) c else 2f - c)
    return from + (to - from) * t
}
