package com.dzshzx.zeta.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dzshzx.zeta.core.Settings
import com.dzshzx.zeta.core.update.UpdateException
import com.dzshzx.zeta.core.update.UpdateHttp
import com.dzshzx.zeta.core.update.UpdateManifest
import com.dzshzx.zeta.core.update.UpdatePackage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置「关于」里检查更新那一行显示的状态。 */
sealed interface DesktopUpdateState {
    /** 这次启动以来还没查过。 */
    data object Idle : DesktopUpdateState

    data object Checking : DesktopUpdateState

    data object UpToDate : DesktopUpdateState

    data class CheckFailed(val reason: String) : DesktopUpdateState

    /** 有新版本 [manifest]（它的 [UpdateManifest.msi] 不是 null），下载、安装走到了 [step]。 */
    data class Available(
        val manifest: UpdateManifest,
        val step: DesktopUpdateStep = DesktopUpdateStep.Ready,
    ) : DesktopUpdateState {
        val msi: UpdatePackage
            get() = checkNotNull(manifest.msi)
    }
}

/** 有新版本以后的一步。 */
sealed interface DesktopUpdateStep {
    data object Ready : DesktopUpdateStep

    /** 下了 [done] 字节。 */
    data class Downloading(val done: Long) : DesktopUpdateStep

    data object Verifying : DesktopUpdateStep

    /** 安装程序起来了，Zeta 随即退出，装好后自动重新打开。 */
    data object Installing : DesktopUpdateStep

    data class Failed(val reason: String) : DesktopUpdateStep
}

/** 取清单、下载安装包；真的走 [UpdateHttp]（[HttpUpdateFeed]），测试换成假的。 */
interface UpdateFeed {
    suspend fun manifest(): UpdateManifest

    suspend fun download(pkg: UpdatePackage, dest: File, onProgress: (Long) -> Unit): File
}

/** 从 GitHub 上最新正式版的 update.json 取（连不上走镜像，见 [UpdateHttp]）。 */
class HttpUpdateFeed(version: DesktopVersion, private val url: String = UpdateManifest.LATEST_URL) :
    UpdateFeed {
    private val http = UpdateHttp(userAgent = "Zeta-Desktop/${version.name}")

    override suspend fun manifest(): UpdateManifest = http.manifest(url)

    override suspend fun download(
        pkg: UpdatePackage,
        dest: File,
        onProgress: (Long) -> Unit,
    ): File = http.download(pkg, dest, onProgress)
}

/**
 * 桌面版的应用内更新，和手机正式版（app 的 AppUpdates）一样读最新 Release 的 update.json：versionCode 比自己大、清单里有桌面版的 MSI
 * 才算新版本。 只有正式发版打的包查（[DesktopVersion.isRelease]）。启动时（[onStart]）每天最多自动查一次，跟着设置里的「自动检查更新」，查到新版本用
 * [notify] 提示一句（同一个版本只提示一次）；设置「关于」里随时手动查。
 *
 * MSI 下载到 [dir]，大小和 sha256 对不上清单就删掉、说原因（[UpdateHttp.download]）。装由 [installer] 起，起好后调 [exit] 退出本程序，
 * 让安装程序换掉文件、装好再打开 Zeta。状态是 mutableStateOf，界面读了跟着变；都在 [scope] 上跑（界面线程）。
 */
class DesktopUpdates(
    private val settings: Settings,
    val version: DesktopVersion,
    private val dir: File,
    private val feed: UpdateFeed,
    private val installer: MsiInstaller,
    private val notify: (String) -> Unit,
    private val exit: () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val now: () -> Long = System::currentTimeMillis,
) {
    var state by mutableStateOf<DesktopUpdateState>(DesktopUpdateState.Idle)
        private set

    private var job: Job? = null

    /** 每次开始下载、取消时加一：被取消的那次留下的进度回调不再改状态。 */
    @Volatile private var run = 0

    /** 这个包检查更新吗：只有正式发版打的。 */
    val enabled: Boolean
        get() = version.isRelease

    /** 启动时：删掉上次留下的安装包；打开了自动检查、今天还没查过就查一次，有新版本提示一句。 */
    fun onStart(): Job? {
        if (!enabled) return null
        return scope.launch {
            withContext(Dispatchers.IO) { clean() }
            if (settings.autoUpdateCheck && state == DesktopUpdateState.Idle && !checkedToday()) {
                settings.updateCheckedAt = now()
                checkNow(announce = true)
            }
        }
    }

    /** 设置里点「检查更新」。正在查、已经有新版本时不再查。 */
    fun check(): Job? {
        if (
            !enabled ||
                state == DesktopUpdateState.Checking ||
                state is DesktopUpdateState.Available
        )
            return null
        settings.updateCheckedAt = now()
        state = DesktopUpdateState.Checking
        return scope.launch { checkNow(announce = false) }
    }

    /** 「下载并安装」：下载、核对，起安装程序后退出。失败后的「重试」也是它。 */
    fun start(): Job? {
        val available = state as? DesktopUpdateState.Available ?: return null
        if (job?.isActive == true) return null
        val id = ++run
        return scope.launch { download(id, available) }.also { job = it }
    }

    /** 下载中取消，回到还没下载。 */
    fun cancel() {
        val available = state as? DesktopUpdateState.Available ?: return
        if (available.step !is DesktopUpdateStep.Downloading) return
        run++
        job?.cancel()
        state = available.copy(step = DesktopUpdateStep.Ready)
    }

    private fun checkedToday(): Boolean {
        val zone = ZoneId.systemDefault()
        return LocalDate.ofInstant(Instant.ofEpochMilli(settings.updateCheckedAt), zone) ==
            LocalDate.ofInstant(Instant.ofEpochMilli(now()), zone)
    }

    private suspend fun checkNow(announce: Boolean) {
        state = DesktopUpdateState.Checking
        state =
            try {
                val manifest = feed.manifest()
                if (manifest.msi != null && manifest.isNewerThan(version.code)) {
                    if (announce) announceOnce(manifest)
                    DesktopUpdateState.Available(manifest)
                } else {
                    DesktopUpdateState.UpToDate
                }
            } catch (e: UpdateException) {
                DesktopUpdateState.CheckFailed(e.reason)
            }
    }

    private fun announceOnce(manifest: UpdateManifest) {
        if (settings.updateNotified == manifest.versionCode) return
        settings.updateNotified = manifest.versionCode
        notify("Zeta ${manifest.versionName} 已发布，可在设置中更新")
    }

    private suspend fun download(id: Int, available: DesktopUpdateState.Available) {
        val msi = available.msi
        val file = File(dir, msi.file)
        try {
            setStep(id, DesktopUpdateStep.Verifying)
            if (!withContext(Dispatchers.IO) { downloaded(file, msi) }) {
                withContext(Dispatchers.IO) {
                    clean()
                    dir.mkdirs()
                }
                fetch(id, msi, file)
            }
            if (id != run) return
            setStep(id, DesktopUpdateStep.Installing)
            val reason = withContext(Dispatchers.IO) { installer.start(file) }
            if (reason != null) setStep(id, DesktopUpdateStep.Failed(reason)) else exit()
        } catch (e: UpdateException) {
            setStep(id, DesktopUpdateStep.Failed(e.reason))
        }
    }

    /** 下载 [msi]，大约每 1% 报一次进度；下完到核对完显示「正在校验」。 */
    private suspend fun fetch(id: Int, msi: UpdatePackage, file: File) {
        setStep(id, DesktopUpdateStep.Downloading(0))
        var shown = 0L
        feed.download(msi, file) { done ->
            if (done == msi.size) {
                setStep(id, DesktopUpdateStep.Verifying)
            } else if (done - shown >= msi.size / 100) {
                shown = done
                setStep(id, DesktopUpdateStep.Downloading(done))
            }
        }
    }

    /** 上次已经下好的同一个文件（大小、sha256 对得上）就不再下。 */
    private fun downloaded(file: File, msi: UpdatePackage): Boolean =
        file.isFile && file.length() == msi.size && UpdateHttp.sha256(file) == msi.sha256

    /** 删掉 [dir] 下的旧文件：装好以后剩下的、下到一半的。 */
    private fun clean() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun setStep(id: Int, step: DesktopUpdateStep) {
        val available = state as? DesktopUpdateState.Available ?: return
        if (id == run) state = available.copy(step = step)
    }

    /** 截图测试用：不联网，直接摆出 [state]。 */
    fun preview(state: DesktopUpdateState) {
        this.state = state
    }

    companion object {
        /** 窗口程序用的那一份：本进程的版本、GitHub 上的清单、Windows 的安装；查到新版本在窗口底部提示，装的时候 [exit] 退出程序。 */
        fun forApp(platform: DesktopHubPlatform, exit: () -> Unit): DesktopUpdates {
            val version = DesktopVersion.current
            return DesktopUpdates(
                settings = platform.settings,
                version = version,
                dir = downloadDir(platform.filesDir),
                feed = HttpUpdateFeed(version),
                installer = WindowsMsiInstaller(File(platform.filesDir, "diag/msi-install.log")),
                notify = platform::toast,
                exit = exit,
            )
        }

        /** 下载安装包的目录：Windows 上放在 %LOCALAPPDATA%\Zeta\update（不进漫游的 %APPDATA%），别的系统上在 [filesDir] 下。 */
        fun downloadDir(filesDir: File): File =
            System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(File(it, "Zeta"), "update") } ?: File(filesDir, "update")
    }
}
