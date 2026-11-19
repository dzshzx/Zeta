package com.dzshzx.zeta.ui.components.coui2

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.theme.COUITheme

/**
 * 设置首页行首图标的颜色，按这一行管的东西分四类，颜色取 COUI 2 配色里的色相（深色模式各有对应值）。
 * 同一类的行用同一种颜色，新加的行照这四类归，不加第五种。
 */
internal enum class SettingsHue {
    /** 蓝：Claude 与对话（账号、模型、思考、上下文、MCP、Subagent、对话里的图片和朗读）。 */
    Claude,

    /** 绿：手机、系统与 Zeta 本身（手机操作、数字助理、浏览器、外观、屏幕、权限、版本）。 */
    System,

    /** 橙：文件与环境（记忆、Skills、Linux 环境、存储、备份、诊断日志）。 */
    Files,

    /** 黄：自动触发与规则（定时任务、通知触发、工具权限）。 */
    Automation,
}

/**
 * 设置首页一行的行首图标：放进行的 `startAction`，`startAction = { SettingsIcon(ZetaIcons.Phone, SettingsHue.System) }`。
 * 只用在设置首页这类入口列表上；子页里的设置行不放图标。行不能用时给 [enabled] = false，图标跟着标题变灰。
 */
@Composable
internal fun SettingsIcon(icon: ImageVector, hue: SettingsHue, enabled: Boolean = true) {
    val colors = COUITheme.colorScheme
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = when {
            !enabled -> colors.disabledNeutral
            else -> when (hue) {
                SettingsHue.Claude -> colors.blue
                SettingsHue.System -> colors.green
                SettingsHue.Files -> colors.orange
                SettingsHue.Automation -> colors.yellow
            }
        },
    )
}
