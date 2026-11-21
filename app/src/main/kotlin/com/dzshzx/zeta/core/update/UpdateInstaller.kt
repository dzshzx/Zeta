package com.dzshzx.zeta.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import com.dzshzx.zeta.core.LinuxEnv
import java.io.File

/**
 * 装下载好、核对过的新版本。有 root 时经 su 用 `pm install -r`，不用用户确认；没有 root 时交给 PackageInstaller 会话，
 * 由用户在系统的安装界面上确认（要先允许 Zeta「安装未知应用」）。装包会结束本应用的界面，什么时候装由 [AppUpdates] 定。
 */
internal object UpdateInstaller {
    /**
     * $2 APK，$3 它的字节数，$4 本应用的界面组件，$5 屏幕编号。APK 经管道交给 pm（同 tools/install.sh 的 `pm install -S`），
     * 装好后在原来那块屏幕上重新打开 Zeta：pm 换包时结束的是本应用，这段在 root 的进程里，照样做完。
     */
    private val ROOT_INSTALL =
        """
        out=${'$'}(cat "${'$'}2" | pm install -r -S "${'$'}3" 2>&1)
        case "${'$'}out" in
          *Success*) rm -f "${'$'}2"; am start --display "${'$'}5" -n "${'$'}4" >/dev/null 2>&1; echo Success ;;
          *) printf '%s\n' "${'$'}out"; exit 1 ;;
        esac
        """
            .trimIndent()

    /**
     * 经 root 装 [apk]，装好后在 [displayId] 上打开 [component]。装成了本应用随即被结束，多半等不到返回； 返回的是失败原因（带上 pm
     * 的最后一行输出）。在后台线程上调用。
     */
    fun installAsRoot(apk: File, component: String, displayId: Int): String? {
        val result =
            LinuxEnv.runRoot(
                LinuxEnv.androidScript(
                    ROOT_INSTALL,
                    apk.path,
                    apk.length().toString(),
                    component,
                    displayId.toString(),
                ),
                timeoutMs = 180_000,
            )
        if (result.exitCode == 0) return null
        val said =
            (result.stdout + "\n" + result.stderr).lines().lastOrNull { it.isNotBlank() }?.trim()
        return "安装失败：" + (said ?: "pm install 退出码 ${result.exitCode}")
    }

    /** 没有 root 时能不能交给系统装：用户允许了 Zeta「安装未知应用」。 */
    fun canRequestInstalls(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** 系统设置里本应用的「安装未知应用」开关。 */
    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 没有 root：建一个 PackageInstaller 会话，写入 [apk] 后提交；系统要用户确认时把确认界面交给 [UpdateInstallReceiver] 打开。
     * 提交成功返回 null，否则是原因。在后台线程上调用。
     */
    fun installWithSession(context: Context, apk: File): String? = runCatching {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // 系统往这个 Intent 里填结果，所以是 MUTABLE；它指名了本应用的接收器，别人截不走
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pending =
                PendingIntent.getBroadcast(
                    context,
                    id,
                    intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            session.commit(pending.intentSender)
        }
        null
    }
        .getOrElse { "交给系统安装失败：${it.message ?: it.javaClass.simpleName}" }
}

/** PackageInstaller 会话的结果：要用户确认时打开系统的确认界面，用户取消或装不上时告诉 [AppUpdates]。 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = AppUpdates.get(context)
        when (
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                val opened =
                    confirm != null &&
                        runCatching {
                            context.startActivity(
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                            .isSuccess
                if (!opened) updates.installFailed("打不开系统的安装确认")
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 本应用已被换成新版本，走不到这里
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates.installCancelled()
            else ->
                updates.installFailed(
                    "安装失败：" +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "状态码 $status"),
                )
        }
    }
}
