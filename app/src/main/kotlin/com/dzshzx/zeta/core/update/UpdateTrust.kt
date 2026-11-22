package com.dzshzx.zeta.core.update

import com.dzshzx.zeta.BuildConfig
import com.dzshzx.zeta.core.Settings

/**
 * 哪个包检查更新、去哪取清单。参考 NewPipe：只有官方签的包才查。正式发版证书（release/signing-certs.txt 的最后一行） 在构建时写进
 * [BuildConfig.RELEASE_CERT_SHA256]，本包现在的签名证书就是它时才查；测试版（调试证书）和别人自己编的包不查。
 *
 * 测试版可以用两项调试覆盖在模拟器上走一遍（tools/vd prefs-set，界面里没有入口）：`updateDevManifest` 换清单地址， `updateDevCert`
 * 换信任的证书（设成调试证书时测试版也查）。只有测试版的 [BuildConfig.UPDATE_DEV_OVERRIDE] 是 true； 正式版里它是常量 false，读覆盖的分支被 R8
 * 去掉。`devOverride` 参数只给单元测试用：测试跑在测试版上，要传 false 才测得到正式版的行为。
 */
internal object UpdateTrust {
    /** 清单地址：正式版是 [UpdateManifest.LATEST_URL]。 */
    fun manifestUrl(
        settings: Settings,
        devOverride: Boolean = BuildConfig.UPDATE_DEV_OVERRIDE,
    ): String = (if (devOverride) settings.updateDevManifest else null) ?: UpdateManifest.LATEST_URL

    fun trustedCert(
        settings: Settings,
        devOverride: Boolean = BuildConfig.UPDATE_DEV_OVERRIDE,
    ): String =
        (if (devOverride) settings.updateDevCert else null) ?: BuildConfig.RELEASE_CERT_SHA256

    /** 本包（[installed]）现在只由 [trusted] 这张证书签名时才检查更新。 */
    fun shouldCheck(installed: ApkFacts?, trusted: String): Boolean =
        installed != null && installed.signers == listOf(trusted)
}
