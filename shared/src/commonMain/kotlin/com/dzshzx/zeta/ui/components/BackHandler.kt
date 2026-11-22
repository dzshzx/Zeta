package com.dzshzx.zeta.ui.components

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** 返回键（手机的返回手势、桌面的 Esc）在 [enabled] 时交给 [onBack]；手机和桌面共用。 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
}
