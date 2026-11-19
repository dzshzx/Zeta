package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 警告色。成功、出错直接用 COUI 的 `success`、`error`；COUI 没有「警告」这一槽，Zeta 定成它的黄色（深浅色各一档），
 * 额度、上下文、MCP 状态这类「快不行了」的提示都用它。
 */
val StatusWarning: Color @Composable get() = COUITheme.colorScheme.yellow
