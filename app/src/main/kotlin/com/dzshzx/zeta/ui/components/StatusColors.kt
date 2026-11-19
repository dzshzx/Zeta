package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUITheme

val StatusSuccess = Color(0xFF34A853)
val StatusWarning = Color(0xFFF9AB00)
val StatusError: Color @Composable get() = COUITheme.colorScheme.error
val StatusRunning: Color @Composable get() = COUITheme.colorScheme.primary
val StatusIdle: Color @Composable get() = COUITheme.colorScheme.labelSecondary
