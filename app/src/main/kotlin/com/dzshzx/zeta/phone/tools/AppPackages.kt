package com.dzshzx.zeta.phone.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.dzshzx.zeta.core.AttachedPaths
import com.dzshzx.zeta.core.Settings
import java.io.File

/**
 * app_control 里改动应用的几样：安装 APK、卸载、清除数据，都以 root 经 pm 做。命令的拼法在 [DeviceApps]。
 *
 * 安装：Claude 给的是它在手机 Linux 环境里看到的路径，按 [AttachedPaths.androidPathFor] 换成 Android 一侧的；先以 root 抄进 Zeta
 * 的缓存目录，Zeta 读出包名、版本和应用名（覆盖 Zeta 自己和系统离不开的包就不装），再经管道交给 `pm install`。
 */
internal object AppPackages {
    private const val INSTALL_TIMEOUT_MS = 180_000L
    private const val COPY_TIMEOUT_MS = 120_000L

    /** 装 [rawPath] 上的 APK，返回装了什么。 */
    fun install(context: Context, sh: Shell, rawPath: String): String {
        val settings = Settings.of(context)
        val env = settings.env
        val source =
            AttachedPaths.androidPathFor(rawPath, settings.sharedFolders, env?.rootfs, env?.home)
                ?: throw ToolError(
                    if (env == null && rawPath.trim().startsWith("/"))
                        "手机上还没有 Linux 环境，只认 Android 一侧的路径（/sdcard/…）"
                    else "path 要是手机上的绝对路径：$rawPath",
                )
        val dir = File(context.cacheDir, "apk-install").apply { mkdirs() }
        val copy = File.createTempFile("install-", ".apk", dir)
        try {
            copyIn(sh, source, copy, rawPath)
            val apk = archive(context, copy) ?: throw ToolError("$rawPath 不是能装的 APK（读不出包名）")
            if (apk.packageName in DeviceApps.PROTECTED)
                throw ToolError("${apk.packageName} 是系统或 Zeta 自己离不开的包，不覆盖安装")
            val replacing = installed(sh, apk.packageName)
            val result =
                sh.run(DeviceApps.installCommand(copy.path, copy.length()), INSTALL_TIMEOUT_MS)
            if (result.timedOut) throw ToolError("安装超过 ${INSTALL_TIMEOUT_MS / 1000} 秒，已中止")
            if (!DeviceApps.pmSucceeded(result.stdout))
                throw ToolError("安装失败：" + pmOutput(result.stdout, result.stderr))
            return (if (replacing) "updated " else "installed ") + apk.describe()
        } finally {
            copy.delete()
        }
    }

    /** 卸载 [packageName]（只从用户 0）；[keepData] 时留着数据和缓存。[named] 把包名说成「应用名 (包名)」。 */
    fun uninstall(
        context: Context,
        sh: Shell,
        packageName: String,
        keepData: Boolean,
        named: (String) -> String,
    ): String {
        requireInstalled(sh, packageName)
        val name = named(packageName)
        val system = isSystemApp(context, packageName)
        pm(sh, DeviceApps.uninstallCommand(packageName, keepData), "卸载")
        if (installed(sh, packageName)) return "$name: pm 报了成功，但读回来还装着"
        return buildString {
            append("uninstalled ").append(name)
            if (keepData) append("（留着数据，再装同一个包时还在）")
            if (system)
                append("。这是系统应用，只从用户 0 卸掉，能用 `cmd package install-existing $packageName` 装回来")
        }
    }

    /** 清除 [packageName] 的数据和缓存（`pm clear`，应用会被停掉）。 */
    fun clearData(sh: Shell, packageName: String, named: (String) -> String): String {
        requireInstalled(sh, packageName)
        pm(sh, DeviceApps.clearCommand(packageName), "清除数据")
        return "cleared data of ${named(packageName)}"
    }

    private fun installed(sh: Shell, packageName: String): Boolean =
        DeviceApps.isInstalled(sh.out(DeviceApps.installedCommand(packageName)), packageName)

    private fun requireInstalled(sh: Shell, packageName: String) {
        if (!installed(sh, packageName)) throw ToolError("没有安装 $packageName")
    }

    /** 跑一条 pm 命令，输出里没有 Success 就算 [what] 失败。 */
    private fun pm(sh: Shell, command: String, what: String) {
        val result = sh.run(command, 60_000)
        if (!DeviceApps.pmSucceeded(result.stdout))
            throw ToolError("${what}失败：" + pmOutput(result.stdout, result.stderr))
    }

    private fun pmOutput(stdout: String, stderr: String): String =
        (stdout.trim() + "\n" + stderr.trim()).trim().take(300).ifEmpty { "pm 没有输出" }

    /** 以 root 把 Android 一侧的 [source] 抄进 Zeta 自己的文件 [target]。 */
    private fun copyIn(sh: Shell, source: String, target: File, rawPath: String) {
        val result = sh.run(DeviceApps.copyCommand(source, target.path), COPY_TIMEOUT_MS)
        when {
            result.exitCode == 0 -> Unit
            result.timedOut -> throw ToolError("读 $rawPath 超时")
            result.exitCode == 4 ->
                throw ToolError(
                    "手机上没有这个文件：$rawPath（Android 一侧是 $source；远程主机上的文件要先传到手机上）",
                )
            result.exitCode == 3 -> throw ToolError("$rawPath 不是文件")
            else -> throw ToolError("读不了 $rawPath：" + result.stderr.trim().take(200))
        }
    }

    /** APK 里的包名、版本和应用名。 */
    private class Apk(val packageName: String, val version: String?, val label: String?) {
        fun describe(): String =
            (label?.let { "$it ($packageName)" } ?: packageName) +
                (version?.let { " version $it" } ?: "")
    }

    private fun archive(context: Context, file: File): Apk? {
        val pm = context.packageManager
        val info =
            runCatching {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
            }
                .getOrNull() ?: return null
        val label =
            info.applicationInfo?.let { app ->
                // 应用名在 APK 的资源里：告诉它从哪个文件读
                app.sourceDir = file.path
                app.publicSourceDir = file.path
                runCatching { app.loadLabel(pm).toString() }.getOrNull()
            }
        return Apk(
            info.packageName,
            info.versionName,
            label?.takeIf { it.isNotBlank() && it != info.packageName },
        )
    }

    private fun isSystemApp(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager
            .getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            .flags and ApplicationInfo.FLAG_SYSTEM != 0
    }
        .getOrDefault(false)
}
