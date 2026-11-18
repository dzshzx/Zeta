# Zeta 项目约束

Zeta（Ζ）是手机（Xiaomi 17，root，KernelSU）上 Claude Code 的 Android 原生客户端：Kotlin、Compose，外观和交互照 Eta 的 COUI 模式。包名 `com.dzshzx.zeta`，默认分支 `master`。它要取代 Eta 做手机上的主力 agent，不只是 Claude Code 的图形界面：自带 Debian 环境的安装与管理、手机操作工具和后台虚拟屏。

## 产品取舍

用户定的，改之前先问。

- 外观和工作逻辑照 Eta 的 COUI 模式：组件用 `io.github.itoseo:coui`，文字与配色由 MiuixTheme 承载，不用 Miuix 的界面组件。不提 Miuix 界面或 Web UI 方案。不接第三方服务，功能从简。
- Claude Code 就是 harness。Eta 里属于 harness 的部分不移植，比如终端、模型供应商、压缩与标题设置。和 Eta 比缺什么时，只看手机与界面一侧；像 harness 的功能，先查 Claude Code 自己有没有。管理 Claude Code 自身配置的页面（记忆、Skills、MCP、Subagent）可以做，读写的是 Claude Code 的真实配置，不另造一套。
- 默认有 root，不照搬 Eta 里兼容无 root 的部分。可选工具里 Go 排第一。
- 版本号长期停在 0.x.x。每次发版只升 patch（0.6.3 → 0.6.4），新功能也一样；不跳 minor，不发 1.0。
- Eta（GPL-3.0）是参照：照它的设计、尺寸、动画重写，不整段复制。`ui/markdown`、`ui/components/coui`、侧栏骨架和主题是从 Eta 拷来的，所以本仓是 GPL-3.0。Eta 源码在手机的 `/workspace/claude/ref/Eta`（上游 github `Mangi-11/Eta`），COUI 源码解包在 `/workspace/claude/ref/coui-src`。

## 架构

Zeta 进程 → `su -c` → 管理器 BusyBox 的 `nsenter -t 1 -m` → 私有挂载命名空间 → chroot 进 Debian rootfs（`/data/local/zeta/debian/rootfs`）→ `setpriv` 降到 Linux 用户 `dzshzx`（uid 5000）→ `assets/zeta_host.sh`。它用 `setsid` 起 `claude -p --input-format stream-json --output-format stream-json …`，标准输入来自 FIFO，输出追加到 `~/.cache/zeta/hosts/<key>/out.jsonl`。Zeta 只附着在上面，所以 Zeta 被杀时对话照样跑完，下次启动再附着回去。没有中转，不开端口。

- `core/`：Linux 环境（`LinuxEnv`、`EnvSetup`、`SshService`，文件浏览 `LinuxFiles`，共享文件夹 `SharedFolders`），Claude 进程与对话状态（`ClaudeProcess`、`Conversation`、`ConversationHub`、`TranscriptReducer`），顶部胶囊要显示的对话（`ConversationFocus`），前台服务 `AgentService`，附件与图片。
- `phone/`：手机工具 `PhoneTools`（SDK 类型的 MCP 服务器 `phone`，每个对话一份），无障碍服务，后台虚拟屏（`VirtualScreen`、root 帮手 `VdHelper`、`VdSurfaceProvider`），悬浮层的接口 `Overlay.kt`。
- `ui/`：聊天 `ui/chat`、侧栏 `ui/pane`、设置与环境页（`SettingsScreen.kt`、`ui/setup`）、悬浮层 `ui/overlay`（顶部状态胶囊与展开画面、主屏边缘流光与触点指示，都是无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口）、Markdown 渲染、COUI 组件。
- `assets/`：`zeta_host.sh` 起 Claude 进程；`zeta_helper.py` 读写 `~/.claude/projects` 下的会话（列表、历史、删除、改名等）；`zeta_setup_*.sh` 安装 Debian 环境与可选工具；`zeta_ssh_android.sh` 管 SSH 服务。

## 两端开发

- 工作站（nerv-wsl）是主开发处：仓在 `~/workspace/Zeta`，改动在任务树里做，用 `wt land` 跑门禁并合入 master。
- 手机 Debian 里留一份 `/workspace/claude/zeta`，用来临时开发和实机测试，能编、能装、能测。开工前先 `git pull`。要留下的改动提交到分支、推到 GitHub，再在工作站上用 `wt land` 合入；手机上没有 `wt`，不直接推 master。
- 两边都只经 GitHub 的 `origin` 同步。
- 工作站进手机：`ssh xiaomi-17-debian` 进 Debian，`sudo nsenter -t 1 -m -- /system/bin/sh -c '<命令>'` 以 root 进 Android 一侧。`/workspace` 是 Android 的 `/data/local/tmp/eta` 挂进来的；从 chroot 里看不到 `/data/local/zeta`，要经 `nsenter` 看。

## 构建与测试

- 构建：`tools/gradle <任务>`，例如 `tools/gradle :app:assembleDebug`；不直接跑 `./gradlew`。它用 `tools/java-home` 找 JDK 25（`JAVA_HOME`，再是 PATH 上的 `java`，最后是 `~/.gradle/jdks` 里 Gradle 下载过的那份），并设 `ANDROID_HOME=~/Android/Sdk`。缺的 SDK 平台和 build-tools 由 AGP 构建时自动下载，两台机器都已接受许可。
- 手机上为内存和 CPU 架构调的 Gradle 参数写在手机的 `~/.gradle/gradle.properties`，不进仓：不开守护进程、Kotlin 在进程内编译、最多 4 个 worker、aapt2 用 `~/Android/aapt2-x86_64/aapt2`（经 qemu-user 跑官方 x86-64 版，Google 不提供 linux-arm64 版）。这个 aapt2 的版本要和 AGP 一致，升 AGP 时一起换。手机空闲内存常只有几百 MB 到 3 GB，同一时间只跑一个 Gradle。
- 签名：debug 包用 `~/.android/zeta-debug.keystore` 签名，是手机上已装的 Zeta 用的那把调试密钥。工作站上是一份拷贝（权限 600），手机上是指向 `~/.android/debug.keystore` 的符号链接。签名对不上时 `pm install -r` 会失败，卸载重装会丢掉 Zeta 的全部设置，所以不换这把密钥，也不卸载。
- 门禁：`bash scripts/check.sh`。它核对 Python 清单，确认 Python 都能解析，再构建 debug 包。
- 装机：`tools/install.sh [--no-build]`。在工作站上经 ssh 装，在手机上直接装。重装会结束 Zeta 的界面，但不结束进行中的对话。用户日常在用 Zeta，装机会打断用户眼前的界面。
- 测试：用 `python3 tools/vd.py start|launch|shot <png>|tap x y|swipe x1 y1 x2 y2 [毫秒]|text <ASCII>|key <键码>|status|stop` 在后台虚拟屏上驱动 Zeta，不碰用户的真屏。工作站上每条命令走一次 ssh，约 0.4 秒。坐标按整屏 1220×2656 算；截图显示给 Claude 时缩小过，要按显示比例换算回去。`stop` 会关掉屏上的应用（建屏带 DESTROY_CONTENT_ON_REMOVAL），不会把它们挪到真屏上。
  - 冷启动后等约 10 秒再输入，否则字会丢。`vd.py text` 只能输 ASCII，要中文时改用包名之类。
  - 要把 Zeta 放到后台，就在虚拟屏上开另一个 activity，例如 `am start --display <屏号> -n com.android.traceur/.MainActivity`。Claude 在虚拟屏上开了别的应用、Zeta 被压到后面时，用 `vd.py launch` 拉回来。
  - 截图一片白时，先查 `dumpsys activity activities`，不要先当成崩溃。多半是两种情况：用户自己打开了 Zeta，任务挪到了主屏，这时等用户，不要抢回来；或者 MIUI 的 GreezeManager 冻结了 Zeta（`reason=tobg`），`vd.py launch` 能解冻。用户的「一键清理」也会杀掉虚拟屏上的 Zeta，先查事件日志。
  - 测手机工具和悬浮层时不碰真屏：Zeta 的「主屏」可以指到测试屏上。先 force-stop Zeta，往 `/data/user/0/com.dzshzx.zeta/shared_prefs/zeta.xml` 里加 `<int name="phoneDisplay" value="<屏号>" />`，再 `vd.py launch`；测完删掉这一项。
  - 测试会改 Zeta 的设置（cwd、model、permissionMode、dark），测完恢复；用户的 cwd 是 `/workspace/claude`。只删测试时建的会话，`/workspace/claude` 下的会话是用户的。
  - Zeta 的设置用 root 直接读 `/data/user/0/com.dzshzx.zeta/shared_prefs/zeta.xml`（`run-as` 不可用）。只 grep 键名，不要整份 cat，`commands` 一项很大。
  - 不经文件选择器给 Zeta 喂文件：`am start -a android.intent.action.SEND -t <mime> --eu android.intent.extra.STREAM file:///data/user/0/com.dzshzx.zeta/files/<名字>`。通知栏的「停止」可用 root 触发：`am startservice -n com.dzshzx.zeta/.core.AgentService -a com.dzshzx.zeta.action.STOP`。
  - Claude Code 会拦下前台的 `sleep N`，长时间的测试用自己会耗时的脚本。

## 发版

一个版本一件事。升 `app/build.gradle.kts` 的 `versionName`（只升 patch）和 `versionCode`（加 1），提交标题里带上版本号，用 `wt land` 合入，再用 `tools/install.sh` 装到手机，在测试屏上验一遍。没有 tag，GitHub 上也不发 Release。

## 边界

- 不替用户在 KernelSU 管理器里给 Zeta 授予 root。
- 改 `settings put secure enabled_accessibility_services` 时只追加 Zeta 的组件，列表里别的服务（bitwarden、Eta、mipay、gkd、vtools 等）一个都不能丢。
- 操作手机界面一律在后台虚拟屏上做，不占主屏前台。只读的核对走文件或 `dumpsys`，不走界面。
- 开发时不改用户真实的 `~/.claude`（CLAUDE.md、记忆、skills、agents、MCP 配置）。要试 `claude` CLI 的行为，就用临时 `HOME`。MCP 配置里有真实 token，不打印。
- 不盲调 `service call`：`service call notification 1` 曾清掉所有用户的通知。

## 坑

- Linux 里的 `/workspace` 是 Android 的 `/data/local/tmp/eta` 绑过去的，和 Eta 共用；Zeta 自己要建的目录放进 rootfs 里（共享文件夹挂在 `/mnt/shared`），不放在 `/workspace` 下。
- COUI 对话框的 `neutralAction` 一律画成红色的危险按钮，三个按钮还会竖排挤掉内容；不危险的次要操作放菜单，或做成对话框内容里的链接。
- app 不能给它的 `su` 子进程发信号（uid 不同），要经 root 杀（`LinuxEnv.kill`）。
- `zeta_host.sh` 的看门狗在 `setsid bash -c '…'` 里面，那一段里不能写单引号。
- Kotlin：在 `VirtualDisplay.() -> Unit` 这类带接收者的 lambda 里裸写 `surface`，会解析成 `VirtualDisplay.getSurface()`，不是外层的字段。
- Claude Code 的 stream-json（2.1.327 实测）：
  - 标准输入的每条用户消息可带 `uuid`；标准输出有 `command_lifecycle`（queued / started / completed / cancelled）。
  - 一轮进行中发来的消息，会在下一个工具边界并入这一轮，两条消息只出一个 `result`。`interrupt` 的回应会列出 `still_queued`；`cancel_async_message` 能撤回排队中的消息。
  - `result` 行的键序不固定，不一定以 `"type"` 开头；`result` 之后还会有 command_lifecycle、task_notification、background_tasks_changed 行。判断一轮的起止看最后一个开始或结束事件，不看最后一行。
  - 后台任务做完时，Claude Code 会自己起一轮：一条 `system init`，前面没有用户消息。Zeta 把空闲时收到的 init 当成新一轮开始。
