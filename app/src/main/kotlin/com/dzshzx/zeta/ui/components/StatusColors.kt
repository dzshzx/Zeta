package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

val StatusSuccess = Color(0xFF34A853)
val StatusWarning = Color(0xFFF9AB00)
val StatusError: Color @Composable get() = MiuixTheme.colorScheme.error
val StatusRunning: Color @Composable get() = MiuixTheme.colorScheme.primary
val StatusIdle: Color @Composable get() = MiuixTheme.colorScheme.onSurfaceVariantSummary
