package com.dzshzx.zeta.core

import com.dzshzx.zeta.BuildConfig

/** 手机上的本包：构建时定的 applicationId（测试版带 .dev）。登记在 app 的 META-INF/services 里。 */
class AndroidAppPackage : AppPackage {
    override val packageName: String = BuildConfig.APPLICATION_ID

    /** uid 按 Android 用户分段，一段 UserHandle.PER_USER_RANGE 个。 */
    override val androidUser: Int
        get() = android.os.Process.myUid() / 100_000
}
