package com.dzshzx.zeta.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.ui.app.AccentChoice
import com.dzshzx.zeta.ui.app.MAX_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.MIN_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.UiPrefs
import com.dzshzx.zeta.ui.app.normalizeInterfaceScale
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SliderPreference
import kotlin.math.roundToInt

/** 外观与主题：深浅色、强调色、界面缩放。照 Eta 的外观页，去掉了 Zeta 用不到的样式切换和返回手势。 */
@Composable
internal fun AppearanceScreen(hub: ConversationHub, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { UiPrefs.get(context) }
    // 拖动时只改草稿，松手才写入：每一档都重排整个界面太晃
    var scaleDraft by remember(prefs.interfaceScale) { mutableFloatStateOf(prefs.interfaceScale * 100f) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val parsedScale = scaleInput.toIntOrNull()

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        prefs.chooseInterfaceScale(scale)
    }

    val themeModes = listOf<Boolean?>(null, false, true)
    val accents = AccentChoice.entries
    CouiPage(
        title = "外观与主题",
        onBack = onBack,
        overlays = {
            TextInputDialog(
                show = showScaleDialog,
                title = "界面缩放",
                summary = "请输入 80 至 110 之间的整数",
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = "百分比",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                confirmText = "确定",
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                onDismissRequest = { showScaleDialog = false },
            )
        },
    ) {
        item(key = "theme") {
            PreferenceGroup(title = "颜色与主题") {
                item {
                    OverlayDropdownPreference(
                        title = "主题模式",
                        items = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = themeModes.indexOf(hub.darkTheme),
                        onSelectedIndexChange = { index -> if (index in themeModes.indices) hub.chooseTheme(themeModes[index]) },
                    )
                }
                item {
                    OverlayDropdownPreference(
                        title = "强调色",
                        summary = if (prefs.accent == AccentChoice.System) "跟随壁纸的系统动态取色（Monet）" else null,
                        items = accents.map { it.label },
                        selectedIndex = accents.indexOf(prefs.accent),
                        onSelectedIndexChange = { index -> accents.getOrNull(index)?.let(prefs::chooseAccent) },
                    )
                }
            }
        }
        item(key = "interface") {
            PreferenceGroup(title = "界面", footer = "界面缩放只影响 Zeta 自己的页面，不改系统的显示大小；悬浮在其他应用上的提示保持原样。") {
                item {
                    SliderPreference(
                        title = "界面缩放",
                        summary = "调整全局显示比例（80%–110%）",
                        value = scaleDraft.coerceIn(MIN_INTERFACE_SCALE * 100f, MAX_INTERFACE_SCALE * 100f),
                        onValueChange = { scaleDraft = it },
                        valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                        steps = 5,
                        onValueChangeFinished = { commitScale(scaleDraft) },
                        valueText = "${scaleDraft.roundToInt()}%",
                        onClick = {
                            scaleInput = scaleDraft.roundToInt().toString()
                            showScaleDialog = true
                        },
                    )
                }
            }
        }
    }
}
