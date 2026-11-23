package com.dzshzx.zeta.ui.settings

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.dzshzx.zeta.core.ConversationHub
import com.dzshzx.zeta.i18n.Strings
import com.dzshzx.zeta.ui.app.MAX_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.MIN_INTERFACE_SCALE
import com.dzshzx.zeta.ui.app.normalizeInterfaceScale
import com.dzshzx.zeta.ui.components.coui2.CouiPage
import com.dzshzx.zeta.ui.components.coui2.PreferenceGroup
import com.dzshzx.zeta.ui.components.coui2.TextInputDialog
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SliderPreference
import kotlin.math.roundToInt

/** 外观与主题：深浅色、界面缩放。照 Eta 的外观页，去掉了 Zeta 用不到的样式切换和返回手势。 */
@Composable
internal fun AppearanceScreen(hub: ConversationHub, onBack: () -> Unit) {
    val settings = hub.settings
    val interfaceScale = normalizeInterfaceScale(settings.interfaceScale)
    // 拖动时只改草稿，松手才写入：每一档都重排整个界面太晃
    var scaleDraft by remember(interfaceScale) { mutableFloatStateOf(interfaceScale * 100f) }
    var showScaleDialog by remember { mutableStateOf(false) }
    var scaleInput by remember { mutableStateOf("") }
    val parsedScale = scaleInput.toIntOrNull()

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        settings.interfaceScale = scale
    }

    val themeModes = listOf<Boolean?>(null, false, true)
    CouiPage(
        title = Strings.settingsTitleAppearance(),
        onBack = onBack,
        dialogs = {
            TextInputDialog(
                show = showScaleDialog,
                title = Strings.settingsAppearanceScale(),
                summary = Strings.settingsAppearanceScaleHint(),
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = Strings.settingsAppearancePercent(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                confirmText = Strings.commonOk(),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
                onDismissRequest = { showScaleDialog = false },
            )
        },
    ) {
        // 两行都管外观，一组就够，不分类
        item(key = "appearance") {
            PreferenceGroup {
                item {
                    OverlayDropdownPreference(
                        title = Strings.settingsAppearanceTheme(),
                        items =
                            listOf(
                                Strings.commonFollowSystem(),
                                Strings.commonThemeLight(),
                                Strings.commonThemeDark(),
                            ),
                        selectedIndex = themeModes.indexOf(hub.settings.darkTheme),
                        onSelectedIndexChange = { index ->
                            if (index in themeModes.indices)
                                hub.settings.darkTheme = themeModes[index]
                        },
                    )
                }
                item {
                    // 只缩放 Zeta 自己的页面，不改系统的显示大小；悬浮在其他应用上的提示（胶囊、结果卡片）保持原样。范围 80%–110%，点开输入框时有提示
                    SliderPreference(
                        title = Strings.settingsAppearanceScale(),
                        summary = Strings.settingsAppearanceScaleSummary(),
                        value =
                            scaleDraft.coerceIn(
                                MIN_INTERFACE_SCALE * 100f,
                                MAX_INTERFACE_SCALE * 100f,
                            ),
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
