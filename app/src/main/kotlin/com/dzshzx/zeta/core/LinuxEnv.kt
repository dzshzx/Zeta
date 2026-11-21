package com.dzshzx.zeta.core

import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Debian 环境里，Claude Code 所属的那个 Linux 用户。 */
data class LinuxEnvConfig(
    val rootfs: String,
    val user: String,
    val uid: Int,
    val gid: Int,
    val home: String,
    val defaultCwd: String,
)

class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    /** 到了时限还没结束、已经结束掉了（[LinuxEnv] 的各个 run 这样标：退出码 -1，stderr 是 [TIMEOUT]，stdout 是到那时为止的输出）。 */
    val timedOut: Boolean
        get() = exitCode == -1 && stderr == TIMEOUT

    companion object {
        const val TIMEOUT = "timeout"
    }
}

/** 同 [CommandResult]，标准输出是原样的字节（[LinuxEnv.runRootBytes]）。 */
class BytesResult(val exitCode: Int, val stdout: ByteArray, val stderr: String) {
    val timedOut: Boolean
        get() = exitCode == -1 && stderr == CommandResult.TIMEOUT
}

/**
 * 进入 Debian 环境的方式照 Eta：`su -c` → 管理器自带的 BusyBox → 私有挂载命名空间 → chroot。 与 Eta 不同的两点：先 `nsenter -t 1
 * -m` 回到全局挂载命名空间（`su` 默认留在本应用的挂载命名空间里， 那里有 Android 的应用数据隔离，看不到别的应用的数据目录，共享文件夹指到那里就绑不上）， 进去以后用
 * setpriv 降到普通用户，Claude Code 的登录状态和配置都在那个用户的家目录里。
 */
object LinuxEnv {
    /** Zeta 自己装的 Debian 放在这里。特意不放进应用数据目录：那里的东西会随卸载、清除数据一起消失， 而这个环境里有 Claude Code 的全部会话和登录状态。 */
    const val OWN_BASE = "/data/local/zeta/debian"
    const val OWN_ROOTFS = "$OWN_BASE/rootfs"

    /**
     * Linux 里的 /workspace 绑的是这个目录：用户自己的文件，正式版和测试版共用。放在 rootfs 外面，换环境、重装环境都不动它。
     * 进入环境时还没有就建出来，交给进来的用户（装环境时进来的是 root，建用户那一步再把空的它交给新用户）。
     */
    const val WORKSPACE = "/data/local/zeta/workspace"

    /** 以 root 身份进入 [rootfs]（装环境时用，这时还没有普通用户）。 */
    fun rootConfig(rootfs: String) = LinuxEnvConfig(rootfs, "root", 0, 0, "/root", "/")

    private const val FIND_BUSYBOX =
        "BB=; for b in /data/adb/ksu/bin/busybox /data/adb/magisk/busybox /data/adb/ap/bin/busybox; do " +
            "[ -x \"\$b\" ] && BB=\$b && break; done; [ -n \"\$BB\" ] || { echo 'zeta: busybox not found' >&2; exit 126; }; "

    // 参数：$1 BusyBox，$2 rootfs，$3 要执行的命令，$4 家目录，$5 用户名，$6 uid，$7 gid，$8 时区，
    // $9 共享文件夹（每行“名字<Tab>Android 侧目录”，见 SharedFolders.mountSpec）。
    // 环境变量 ZETA_NAME 告诉 Debian 里的脚本 Zeta 自己的目录叫什么（正式版 zeta，测试版 zeta-dev，见 AppVariant）。
    private val MOUNT_AND_CHROOT =
        """
        BB=${'$'}1; R=${'$'}2
        [ -x "${'$'}R/bin/sh" ] || { echo "zeta: rootfs not found: ${'$'}R" >&2; exit 125; }
        "${'$'}BB" mount -o bind "${'$'}R" "${'$'}R" && "${'$'}BB" mount -o remount,bind,suid "${'$'}R" "${'$'}R"
        "${'$'}BB" mount -t proc proc "${'$'}R/proc" || exit 125
        "${'$'}BB" mount -o rbind /dev "${'$'}R/dev" || exit 125
        "${'$'}BB" mount -o rbind /sys "${'$'}R/sys" 2>/dev/null
        [ -d /storage/emulated/0 ] && "${'$'}BB" mount -o bind /storage/emulated/0 "${'$'}R/storage/emulated/0" 2>/dev/null
        "${'$'}BB" mkdir -p "${'$'}R/data/local/tmp" "${'$'}R/workspace"
        [ -d $WORKSPACE ] || { "${'$'}BB" mkdir -p $WORKSPACE && "${'$'}BB" chown "${'$'}6:${'$'}7" $WORKSPACE; }
        "${'$'}BB" mount -o bind /data/local/tmp "${'$'}R/data/local/tmp"
        "${'$'}BB" mount -o bind $WORKSPACE "${'$'}R/workspace"
        # 共享文件夹：跟上面一样只绑在这个命名空间里，命令结束就没了；源目录不在的跳过，不影响进入环境
        [ -n "${'$'}9" ] && printf '%s\n' "${'$'}9" | while IFS="${'$'}(printf '\t')" read -r n s; do
          [ -n "${'$'}n" ] && [ -d "${'$'}s" ] && "${'$'}BB" mkdir -p "${'$'}R${SharedFolders.LINUX_ROOT}/${'$'}n" &&
            "${'$'}BB" mount -o bind "${'$'}s" "${'$'}R${SharedFolders.LINUX_ROOT}/${'$'}n"
        done 2>/dev/null
        exec "${'$'}BB" chroot "${'$'}R" /usr/bin/env -i HOME="${'$'}4" USER="${'$'}5" LOGNAME="${'$'}5" SHELL=/bin/bash \
          LANG=C.UTF-8 TERM=dumb TZ="${'$'}8" ${AppVariant.LINUX_NAME_ENV}=${quote(AppVariant.current.linuxName)} /usr/bin/setpriv --reuid="${'$'}6" --regid="${'$'}7" --init-groups /bin/bash -lc "${'$'}3"
    """
            .trimIndent()

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 给 `su -c` 的整段脚本：在 Debian 里以 [config] 的用户身份、登录 shell 的环境执行 [command]。 */
    fun enterScript(config: LinuxEnvConfig, command: String): String =
        FIND_BUSYBOX +
            "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" unshare -m --propagation private \"\$BB\" sh -c " +
            quote(MOUNT_AND_CHROOT) +
            " zeta \"\$BB\" " +
            quote(config.rootfs) +
            " " +
            quote(command) +
            " " +
            quote(config.home) +
            " " +
            quote(config.user) +
            " " +
            config.uid +
            " " +
            config.gid +
            " " +
            // 手机的时区：Debian 那边的系统时区多半是 UTC，不传的话 Claude Code 看到的日期时间会差几个小时
            quote(java.util.TimeZone.getDefault().id) +
            " " +
            quote(SharedFolders.mountSpec())

    fun start(config: LinuxEnvConfig, command: String): Process =
        ProcessBuilder("su", "-c", enterScript(config, command)).start()

    /**
     * 本应用所在的 Android 用户的编号（uid 按用户分段，一段 UserHandle.PER_USER_RANGE 个），拼
     * /data/user/<n>、/data/media/<n> 用。
     */
    val androidUser: Int
        get() = android.os.Process.myUid() / 100_000

    /**
     * 给 `su -c` 的整段脚本：在 Android 一侧（全局挂载命名空间）用管理器的 BusyBox 执行 [script]。 脚本收到的 $1 是 BusyBox 的路径，后面是
     * [arguments]。
     */
    fun androidScript(script: String, vararg arguments: String): String =
        FIND_BUSYBOX +
            "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" sh -c " +
            quote(script) +
            " zeta-setup \"\$BB\"" +
            arguments.joinToString("") { " " + quote(it) }

    /**
     * 结束一个经 su 启动的进程，连同它派生的子进程。 这些进程以 root 或 Debian 里的用户身份运行，应用自己发的信号到不了它们，[Process.destroy]
     * 只是关掉管道，所以要再经 root 去杀。
     */
    fun kill(process: Process) {
        val pid = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
        val alive = runCatching { process.isAlive }.getOrDefault(true)
        process.destroy()
        if (pid == null || !alive) return
        thread(isDaemon = true) {
            runCatching {
                // 这条命令自己超时就算了，不再去杀它（否则会没完没了）
                capture(
                    ProcessBuilder(
                            "su",
                            "-c",
                            FIND_BUSYBOX +
                                "kt() { for c in \$(\"\$BB\" pgrep -P \"\$1\"); do kt \"\$c\"; done; kill -9 \"\$1\" 2>/dev/null; }; kt $pid",
                        )
                        .start(),
                    null,
                    5_000,
                    onTimeout = Process::destroy,
                )
            }
        }
    }

    fun startRoot(script: String): Process =
        ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()

    /** 以 root 在 Android 侧直接执行一段脚本（不进 Debian）。[stdin] 不进命令行，别的进程从进程列表里看不到。 */
    fun runRoot(script: String, timeoutMs: Long = 15_000, stdin: String? = null): CommandResult =
        capture(ProcessBuilder("su", "-c", script).start(), stdin?.toByteArray(), timeoutMs)

    /** 同 [runRoot]，标准输出按字节原样收（以 root 读文件用），不经文本、不在别处落盘。 */
    fun runRootBytes(script: String, timeoutMs: Long = 15_000): BytesResult =
        captureBytes(ProcessBuilder("su", "-c", script).start(), null, timeoutMs, drainMs = 5_000)

    fun run(
        config: LinuxEnvConfig,
        command: String,
        stdin: String? = null,
        timeoutMs: Long = 30_000,
    ): CommandResult = EnvShell(config).run(command, stdin, timeoutMs)

    /**
     * 收齐 [process] 的输出（见 [captureBytes]），到 [timeoutMs] 还没结束就交给 [onTimeout] 结束它（默认经 root，[kill]）。
     */
    internal fun capture(
        process: Process,
        stdin: ByteArray?,
        timeoutMs: Long,
        onTimeout: (Process) -> Unit = ::kill,
    ): CommandResult =
        captureBytes(process, stdin, timeoutMs, onTimeout).let {
            CommandResult(it.exitCode, it.stdout.toString(Charsets.UTF_8), it.stderr)
        }

    /**
     * 收齐 [process] 的输出：到 [timeoutMs] 还没结束就交给 [onTimeout] 结束它（默认经 root，[kill]），结果标成超时。
     * 进程结束后再等读输出的线程最多 [drainMs]，管道里剩下的读完。
     */
    private fun captureBytes(
        process: Process,
        stdin: ByteArray?,
        timeoutMs: Long,
        onTimeout: (Process) -> Unit = ::kill,
        drainMs: Long = 2_000,
    ): BytesResult {
        val out = ByteArrayOutputStream()
        val err = StringBuilder()
        val t1 = Thread {
            runCatching { process.inputStream.use { it.copyTo(out) } }
        }
            .apply { start() }
        val t2 = Thread {
            runCatching { err.append(process.errorStream.bufferedReader().readText()) }
        }
            .apply { start() }
        runCatching {
            process.outputStream.use { if (stdin != null) it.write(stdin) }
        }
        if (!process.exitsWithin(timeoutMs)) {
            onTimeout(process)
            return BytesResult(-1, out.toByteArray(), CommandResult.TIMEOUT)
        }
        t1.join(drainMs)
        t2.join(2_000)
        return BytesResult(process.exitValue(), out.toByteArray(), err.toString())
    }

    /** 能否拿到 root。没授权时 `su` 会直接失败或不存在。 */
    fun hasRoot(): Boolean = runCatching {
        runRoot("id -u", 30_000).let { it.exitCode == 0 && it.stdout.trim() == "0" }
    }
        .getOrDefault(false)

    /** [rootfs] 那里是不是真有一个系统。 */
    fun exists(rootfs: String): Boolean = runCatching {
        runRoot(
                FIND_BUSYBOX +
                    "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" test -x " +
                    quote("$rootfs/bin/bash"),
            )
            .exitCode == 0
    }
        .getOrDefault(false)

    /**
     * 从 rootfs 的 passwd 里找出装了 Claude Code 的普通用户（家目录下有 .claude），找不到就取第一个普通用户。 返回 null 表示 rootfs
     * 不存在或里面没有普通用户。
     */
    fun detect(rootfs: String): LinuxEnvConfig? {
        val script =
            FIND_BUSYBOX +
                "exec \"\$BB\" nsenter -t 1 -m -- \"\$BB\" sh -c " +
                quote(
                    "R=\$1; [ -r \"\$R/etc/passwd\" ] || exit 3; " +
                        "while IFS=: read -r n p u g c h s; do [ \"\$u\" -ge 1000 ] 2>/dev/null && [ \"\$u\" -lt 60000 ] && " +
                        "{ [ -d \"\$R\$h/.claude\" ] && echo \"C:\$n:\$u:\$g:\$h\" || echo \"U:\$n:\$u:\$g:\$h\"; }; done < \"\$R/etc/passwd\"; exit 0",
                ) +
                " zeta " +
                quote(rootfs)
        val result = runCatching { runRoot(script) }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        val lines = result.stdout.lines().filter { it.length > 2 }
        val line = lines.firstOrNull { it.startsWith("C:") } ?: lines.firstOrNull() ?: return null
        val f = line.split(":")
        if (f.size < 5) return null
        val uid = f[2].toIntOrNull() ?: return null
        val gid = f[3].toIntOrNull() ?: return null
        // 默认工作目录是用户的家目录
        return LinuxEnvConfig(rootfs, f[1], uid, gid, f[4], f[4])
    }
}
