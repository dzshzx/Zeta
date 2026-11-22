package com.dzshzx.zeta.ui.app

internal const val MIN_INTERFACE_SCALE = 0.8f
internal const val MAX_INTERFACE_SCALE = 1.1f

/** 界面缩放比例（[com.dzshzx.zeta.core.Settings.interfaceScale]）收到允许的范围里；读写都经它。 */
internal fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE) else 1f
