package com.dzshzx.zeta.ui.settings

/**
 * 设置页往下的一层页面（各台主机的页面另算，见 [HostSettingsScreen]）。
 * [link] 是通知深链里写的页名（[SettingsLink]），各页不同、定了就不改：已经发出、还挂在通知栏里的通知带的是它。
 * 枚举名随便改，不影响深链。
 */
internal enum class SettingsSubPage(val link: String) {
    Appearance("Appearance"),
    PermissionHealth("Permissions"),
    Phone("Phone"),
    Schedules("Schedules"),
    Accounts("Accounts"),
    NotifyTriggers("NotifyTriggers"),
    Diagnostics("Diagnostics"),
    Storage("Storage"),
    Assistant("Assistant"),
    Backup("Backup"),
    RemoteHosts("RemoteHosts"),
    License("License"),
    OpenSource("OpenSource"),
    Credits("Credits"),
    Update("Update"),
}
