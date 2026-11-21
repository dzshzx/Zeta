package com.dzshzx.zeta.core

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * MIUI / HyperOS 的“后台弹出界面”权限：AppOps 10021（MIUI 的 OP_BACKGROUND_START_ACTIVITY）。 没有它时，Zeta 在后台用
 * PendingIntent 打开自己的界面会被系统悄悄拦下（照样回“已送达”）；有它就能直接打开。
 *
 * - 读：AppOpsManager.checkOpNoThrow(int, int, String) 是隐藏接口里仍允许反射调用的一类（unsupported），不用 root。
 * - 写：经 root `cmd appops set --user <用户> <包名> 10021 allow`。
 *
 * 在 HyperOS 4.0（Android 17）上核实过：`cmd appops get <包名> 10021` 读出 `MIUIOP(10021): ignore;
 * rejectTime=…`， set allow 之后读出 `MIUIOP(10021): allow`；框架里 MIUI 自有操作的默认值是 allow，第三方应用装上时由安全中心显式设成
 * ignore。
 */
internal object BackgroundStart {
    const val OP = 10021

    /** 经 root 授予（`$2` 包名，`$3` 用户）。 */
    const val GRANT_SCRIPT = "cmd appops set --user \"\$3\" \"\$2\" $OP allow"

    /** Zeta 有没有这个权限；不是 MIUI、读不到时为 null。 */
    fun allowed(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val check =
            AppOpsManager::class
                .java
                .getMethod(
                    "checkOpNoThrow",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
        backgroundStartModeAllowed(
            check.invoke(ops, OP, Process.myUid(), context.packageName) as Int,
        )
    }
        .getOrNull()
}

/** checkOpNoThrow 的返回值：allow 是有，ignore、deny（errored）是没有；default、foreground 说不准。 */
internal fun backgroundStartModeAllowed(mode: Int): Boolean? =
    when (mode) {
        0 -> true // MODE_ALLOWED
        1,
        2 -> false // MODE_IGNORED、MODE_ERRORED
        else -> null
    }

/** 从后台打开 Zeta 的界面走哪条路。 */
internal enum class OpenRoute {
    /** 直接 PendingIntent：Zeta 在前台，或有“后台弹出界面”权限。 */
    PendingIntent,

    /** 直接经 root 的 am start：确定会被拦下，不再先试 PendingIntent、等它超时。 */
    Root,

    /** 说不准：先试 PendingIntent，过一会儿还没到前台再经 root。 */
    PendingIntentThenRoot;

    companion object {
        fun choose(
            foreground: Boolean,
            backgroundStartAllowed: Boolean?,
            root: Boolean,
        ): OpenRoute =
            when {
                foreground || backgroundStartAllowed == true || !root -> PendingIntent
                backgroundStartAllowed == false -> Root
                else -> PendingIntentThenRoot
            }
    }
}
