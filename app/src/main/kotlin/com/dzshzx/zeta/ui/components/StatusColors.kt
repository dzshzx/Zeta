package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 警告色。成功、出错直接用 COUI 的 `success`、`error`；COUI 没有「警告」这一槽：深色下用它的黄色， 浅色下黄色在白底上只有约
 * 1.8:1，字读不清，换成压暗的琥珀色（白底上约 4.9:1）。 文字、图标、圆环、淡底都用这一个，额度、上下文、MCP 状态这类「快不行了」的提示都用它。
 */
val StatusWarning: Color
    @Composable get() = COUITheme.colorScheme.let { if (it.isDark) it.yellow else WarningOnLight }

private val WarningOnLight = Color(0xFFA36200)
