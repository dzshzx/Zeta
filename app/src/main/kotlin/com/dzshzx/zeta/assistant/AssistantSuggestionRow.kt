package com.dzshzx.zeta.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import kotlinx.coroutines.launch

// 拷自 Eta 的 agent/voice/EtaAssistantSuggestions.kt（GPL-3.0）：建议问题改由调用方给，
// 依次错开出现的间隔从协程里的 delay 换成动画自己的 delayMillis（截图测试里动画时长按 0 算，一样放完）。

/** 第一个建议问题等浮窗淡入一点以后再出来，之后每个错开这么久（照 Eta 录屏里一个个冒出来的节奏）。 */
private const val SuggestionLeadMs = 120
private const val SuggestionStaggerMs = 90

/** 输入栏上面一行建议问题：浮起的小胶囊，一个个错开浮上来；点了直接发出去。发出去以后收起。 */
@Composable
internal fun AssistantSuggestionRow(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    colors: AssistantColors,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit =
            shrinkVertically(
                shrinkTowards = Alignment.Bottom,
                animationSpec = tween(160, easing = FastOutSlowInEasing),
            ) + fadeOut(tween(120)),
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    // 阴影与按压回弹留在横向滚动裁剪框内部，避免上下边缘被水平视口截平。
                    .padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(10.dp))
            suggestions.forEachIndexed { index, suggestion ->
                SuggestionCapsule(index, suggestion, visible, colors, onSuggestionClick)
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}

@Composable
private fun SuggestionCapsule(
    index: Int,
    suggestion: String,
    visible: Boolean,
    colors: AssistantColors,
    onSuggestionClick: (String) -> Unit,
) {
    val progress = remember(suggestion) { Animatable(0f) }
    val opacity = remember(suggestion) { Animatable(0f) }
    val interactionSource = remember(suggestion) { MutableInteractionSource() }
    val press = rememberCouiPressMotion()
    // 只管入场：出场由外层的 AnimatedVisibility 收起整行（建议问题只在发出去以后或列表变空时消失）
    LaunchedEffect(suggestion) {
        val lead = SuggestionLeadMs + index * SuggestionStaggerMs
        launch {
            progress.animateTo(1f, tween(250, delayMillis = lead, easing = CouiEnterEasing))
        }
        launch {
            opacity.animateTo(1f, tween(180, delayMillis = lead, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier =
            Modifier.height(36.dp)
                .graphicsLayer {
                    val p = progress.value
                    alpha = opacity.value
                    translationY = (1f - p) * 10.dp.toPx()
                    val scale = (0.93f + 0.07f * p) * press.scale.value
                    scaleX = scale
                    scaleY = scale
                }
                .couiPressMotion(press, enabled = visible)
                .couiAssistantCapsule(
                    colors = colors,
                    elevation = AssistantCapsuleElevation.Chip,
                    interactionSource = if (visible) interactionSource else null,
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = visible,
                    role = Role.Button,
                ) {
                    onSuggestionClick(suggestion)
                }
                .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = suggestion,
            color = colors.inputPrimary.copy(alpha = 0.88f),
            fontSize = 13.5.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
