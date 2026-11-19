package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUITheme

/** 状态色都取 COUI 自带的配色，深浅色各一档；COUI 没有「警告」这一槽，用它的黄色。 */
val StatusSuccess: Color @Composable get() = COUITheme.colorScheme.success
val StatusWarning: Color @Composable get() = COUITheme.colorScheme.yellow
val StatusError: Color @Composable get() = COUITheme.colorScheme.error
val StatusRunning: Color @Composable get() = COUITheme.colorScheme.primary
val StatusIdle: Color @Composable get() = COUITheme.colorScheme.labelSecondary
