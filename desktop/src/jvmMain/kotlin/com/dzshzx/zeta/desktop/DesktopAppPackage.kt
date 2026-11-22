package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.AppPackage
import com.dzshzx.zeta.core.AppVariant

/**
 * 桌面版这个包。它在 Linux 那边的目录和手机正式版是同一个（`~/.cache/zeta`，见 [AppVariant.linuxName]）：同一台远程主机上两边算同一个包，
 * 电脑和手机能同时接着同一个托管进程、一起用一个会话；手机测试版仍是自己的目录。登记在 desktop 的 META-INF/services 里。
 */
class DesktopAppPackage : AppPackage {
    override val packageName: String = PACKAGE

    companion object {
        const val PACKAGE = AppVariant.DESKTOP_PACKAGE
    }
}
