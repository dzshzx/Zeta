package com.dzshzx.zeta.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import kotlinx.coroutines.flow.first

// 拷自 Eta 的 agent/voice/EtaAssistantComposer.kt（GPL-3.0）的打字输入栏：去掉了语音那一半
// （聆听态、按住说话、麦克风键），空闲时右边是发送键，空着时是灰的。

private val ComposerModeEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)

/** 输入框空着时的提示（照 Eta 的「发消息问 Eta…」）。 */
internal const val InputHint = "问问 Claude"

/**
 * 输入栏：浮起的胶囊，左边输入框，右边圆形按钮。能发时是主色的发送键，空着时是灰的；这一轮还在进行时（[busy]）是停止键，输入框暂时不能输入。
 *
 * [focusRequestKey] 变成非负数时把焦点给输入框、弹出键盘（等窗口拿到焦点以后）。
 */
@Composable
internal fun AssistantInputBar(
    input: TextFieldState,
    busy: Boolean,
    sending: Boolean,
    focusRequestKey: Int,
    keyboardVisible: Boolean,
    colors: AssistantColors,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputEnabled = !busy && !sending
    val canSubmit = input.text.isNotBlank() && inputEnabled
    val focusRequester = remember { FocusRequester() }
    InputFocus(focusRequestKey, inputEnabled, focusRequester)
    var textFocused by remember { mutableStateOf(false) }
    val inputHighlighted = textFocused || keyboardVisible
    val inputBackground by
        animateColorAsState(
            targetValue = if (inputHighlighted) colors.focusedInput else colors.input,
            animationSpec = tween(190, easing = LinearOutSlowInEasing),
            label = "assistant_input_background",
        )
    val highlightProgress by
        animateFloatAsState(
            targetValue = if (inputHighlighted) 1f else 0f,
            animationSpec = tween(210, easing = FastOutSlowInEasing),
            label = "assistant_input_highlight",
        )
    Row(
        modifier =
            modifier
                .heightIn(min = 50.dp)
                .couiAssistantCapsule(
                    surfaceColor = inputBackground,
                    strokeColor = colors.chipStroke,
                    dark = colors.dark,
                    elevation = AssistantCapsuleElevation.Composer,
                    highlightProgress = highlightProgress,
                    highlightColor = colors.accent.copy(alpha = if (colors.dark) 0.44f else 0.30f),
                )
                .clickable(interactionSource = null, indication = null, onClick = {})
                .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            state = input,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
            modifier =
                Modifier.weight(1f)
                    .padding(end = 8.dp, top = 9.dp, bottom = 9.dp)
                    .onFocusChanged { textFocused = it.isFocused }
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = "输入问题" },
            enabled = inputEnabled,
            textStyle =
                TextStyle(color = colors.inputPrimary, fontSize = 15.5.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            onKeyboardAction = { if (canSubmit) onSubmit() },
            decorator = { innerTextField ->
                HintDecorator(input.text.isEmpty(), colors) { innerTextField() }
            },
        )
        TrailingButton(busy, canSubmit, colors, onSubmit, onStop)
    }
}

/** 输入框空着时在它下面垫一行提示。 */
@Composable
private fun HintDecorator(empty: Boolean, colors: AssistantColors, field: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart) {
        if (empty) {
            Text(
                text = InputHint,
                color = colors.inputTertiary,
                fontSize = 15.5.sp,
                lineHeight = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        field()
    }
}

/** 输入栏挂载且浮窗取得焦点后再连接输入法，避免动画切换时请求落在尚不存在的输入框上。 */
@Composable
private fun InputFocus(key: Int, enabled: Boolean, focusRequester: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(key, enabled) {
        if (key < 0 || !enabled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
}

/** 输入栏右边的键：停止、发送（能发时主色，空着时灰）。 */
@Composable
private fun TrailingButton(
    busy: Boolean,
    canSubmit: Boolean,
    colors: AssistantColors,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = canSubmit || busy
    val containerColor by
        animateColorAsState(
            targetValue =
                when {
                    busy -> colors.inputPrimary
                    canSubmit -> colors.accent
                    else -> colors.controlSurface
                },
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "assistant_trailing_bg",
        )
    val iconTint by
        animateColorAsState(
            targetValue =
                when {
                    canSubmit -> colors.onAccent
                    busy -> if (colors.dark) Color(0xFF17191E) else Color.White
                    else -> colors.inputTertiary.copy(alpha = 0.42f)
                },
            animationSpec = tween(180, easing = FastOutSlowInEasing),
            label = "assistant_trailing_tint",
        )
    AssistantTrailingButton(
        glyphKind = if (busy) AssistantGlyphKind.Stop else AssistantGlyphKind.Send,
        contentDescription = if (busy) "停止" else "发送",
        containerColor = containerColor,
        iconTint = iconTint,
        enabled = enabled,
        onClick = if (busy) onStop else onSubmit,
    )
}

/** 输入栏尾部按钮：按下时缩一下（COUI 的按压回弹），点了轻振一下。 */
@Composable
private fun AssistantTrailingButton(
    glyphKind: AssistantGlyphKind,
    contentDescription: String,
    containerColor: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val press = rememberCouiPressMotion()
    val currentClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            Modifier.size(38.dp)
                .graphicsLayer {
                    scaleX = press.scale.value
                    scaleY = press.scale.value
                }
                .clip(CircleShape)
                .background(containerColor, CircleShape)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick {
                        if (enabled) currentClick()
                        enabled
                    }
                }
                .pointerInput(enabled, press) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            press.press(size)
                            tryAwaitRelease()
                            press.release()
                        },
                        onTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            currentClick()
                        },
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = glyphKind,
            transitionSpec = {
                (fadeIn(tween(140)) +
                        scaleIn(tween(170, easing = ComposerModeEasing), initialScale = 0.72f))
                    .togetherWith(fadeOut(tween(90)) + scaleOut(tween(110), targetScale = 0.72f))
            },
            label = "assistant_trailing_glyph",
        ) { targetGlyph ->
            AssistantActionGlyph(
                kind = targetGlyph,
                tint = iconTint,
                modifier =
                    Modifier.size(if (targetGlyph == AssistantGlyphKind.Stop) 18.dp else 19.dp),
            )
        }
    }
}
