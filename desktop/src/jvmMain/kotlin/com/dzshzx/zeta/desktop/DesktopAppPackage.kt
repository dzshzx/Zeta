package com.dzshzx.zeta.desktop

import com.dzshzx.zeta.core.AppPackage

/**
 * 桌面版这个包。包名决定它在 Linux 那边自己的目录（`~/.cache/zeta-desktop`），和手机上的正式版、测试版分开：
 * 各自接管、结束、清理自己的后台对话，同一台主机上互不干扰。登记在 desktop 的 META-INF/services 里。
 */
class DesktopAppPackage : AppPackage {
    override val packageName: String = PACKAGE

    companion object {
        const val PACKAGE = "com.dzshzx.zeta.desktop"
    }
}
