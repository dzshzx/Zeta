package com.dzshzx.zeta.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntSize

/*
 * 卡片里展开、收起一段内容用的动画，时长固定。Compose 默认用弹簧，内容越高收得越慢：
 * 十几个步骤的工作卡收起要将近一秒，正在看的人只能等着。
 */

val FoldEnter: EnterTransition = fadeIn(tween(150)) + expandVertically(tween(180))

val FoldExit: ExitTransition = fadeOut(tween(110)) + shrinkVertically(tween(150))

/** 给 animateContentSize 用，和 [FoldExit] 一样快。 */
val FoldResize: FiniteAnimationSpec<IntSize> = tween(150)
