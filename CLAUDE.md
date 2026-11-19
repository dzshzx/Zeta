# Zeta 项目约束

Zeta（Ζ）是手机（Xiaomi 17，root，KernelSU）上 Claude Code 的 Android 原生客户端：Kotlin、Compose，界面用 COUI 2（ColorOS 17 风格）。包名 `com.dzshzx.zeta`（开发测试装的测试版是 `com.dzshzx.zeta.dev`，见「构建与测试」），默认分支 `master`。它要取代 Eta 做手机上的主力 agent，不只是 Claude Code 的图形界面：自带 Debian 环境的安装与管理、手机操作工具和后台虚拟屏。

## 产品取舍

用户定的，改之前先问。

- 界面用 COUI 2（`io.github.suqi8.coui.kmp`）：组件、配色、文字样式都用它自带的，它没有的才在 `ui/components/coui2` 补，不照抄 Eta 的组件。写法照它自带的指南（工作站 `~/workspace-external/coui` 的 `.claude/skills/coui-app/`），Zeta 的补充件、常见选法和坑见 `docs/ui.md`。不提 Web UI 方案。不接第三方服务，功能从简。
- Claude Code 就是 harness。Eta 里属于 harness 的部分不移植，比如终端、模型供应商、压缩与标题设置。和 Eta 比缺什么时，只看手机与界面一侧；像 harness 的功能，先查 Claude Code 自己有没有。管理 Claude Code 自身配置的页面（记忆、Skills、MCP、Subagent）可以做，读写的是 Claude Code 的真实配置，不另造一套。
- 默认有 root，不照搬 Eta 里兼容无 root 的部分。可选工具里 Go 排第一。
- 版本号长期停在 0.x.x。每次发版只升 patch（0.6.3 → 0.6.4），新功能也一样；不跳 minor，不发 1.0。
- Eta（GPL-3.0）是功能和交互的参照：照它的设计重写，不整段复制；外观、尺寸、动画以 COUI 2 为准。`ui/markdown`、侧栏骨架和主题最初是从 Eta 拷来的，所以本仓是 GPL-3.0。Eta 源码在工作站的 `~/workspace-external/Eta` 和手机的 `/workspace/claude/ref/Eta`（上游 github `Mangi-11/Eta`，对照前先 `git fetch` 再快进），ColorOS 的 COUI 源码解包在手机的 `/workspace/claude/ref/coui-src`；COUI 2 的源码在工作站的 `~/workspace-external/coui`（上游 github `suqi8/coui`，对照前先 `git pull`）。

## 架构

Zeta 进程 → `su -c` → 管理器 BusyBox 的 `nsenter -t 1 -m` → 私有挂载命名空间 → chroot 进 Debian rootfs（`/data/local/zeta/debian/rootfs`）→ `setpriv` 降到 Linux 用户 `dzshzx`（uid 5000）→ `assets/zeta_host.sh`。它用 `setsid` 起 `claude -p --input-format stream-json --output-format stream-json …`，标准输入来自 FIFO，输出追加到 `~/.cache/zeta/hosts/<key>/out.jsonl`（测试版是 `~/.cache/zeta-dev`）。Zeta 只附着在上面，所以 Zeta 被杀时对话照样跑完，下次启动再附着回去。没有中转，不开端口。

- `core/`：Linux 环境（`LinuxEnv`、`EnvSetup`、`SshService`，文件浏览 `LinuxFiles`，共享文件夹 `SharedFolders`），Claude 进程与对话状态（`ClaudeProcess`、`Conversation`、`ConversationHub`、`TranscriptReducer`），顶部胶囊要显示的对话（`ConversationFocus`），前台服务 `AgentService`（有对话在回答时常驻通知提升为实时通知；挑哪个对话、写什么字、节流在 `LiveUpdate`），附件与图片。
- `core/claude/`：设置里“上下文与扩展”读写 Claude Code 自身配置（记忆、Skills、MCP、Subagent）。解析、校验、路径映射是纯函数；读写文件经 `ClaudeScripts` 的小段壳脚本以 Linux 用户身份做，内容和 Token 只走标准输入；MCP 的增删走 `claude mcp`。
- `core/voice/`：语音，只用手机自带的服务：`SpeechInput` 是和界面无关的识别（系统 `SpeechRecognizer`，HyperOS 上显式指定小米的识别服务，识别期间前台绑定它的进程才录得了音），输入栏的按住说话（`ui/chat/HoldToTalk.kt`）用它；`ReadAloud` 用系统默认的朗读引擎念回答（`SpeechText` 把 Markdown 换成念得顺的文字），出声期间开媒体播放类前台服务。
- `phone/`：手机工具 `PhoneTools`（SDK 类型的 MCP 服务器 `phone`，每个对话一份），无障碍服务，后台虚拟屏（`VirtualScreen`、root 帮手 `VdHelper`、`VdSurfaceProvider`，屏上应用不抢音频焦点的 `AudioFocusGuard`），悬浮层的接口 `Overlay.kt`。
- `ui/`：聊天 `ui/chat`、侧栏 `ui/pane`、设置与环境页（`SettingsScreen.kt`、`ui/setup`）、悬浮层 `ui/overlay`（顶部状态胶囊与展开画面、主屏边缘流光与触点指示、一轮结束后屏幕底部的结果卡片，都是无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口；结果卡片弹不弹、何时收起见 `core/ResultCard.kt`）、Markdown 渲染、COUI 2 的补充件 `ui/components/coui2`。
- `assets/`：`zeta_host.sh` 起 Claude 进程；`zeta_helper.py` 读写 `~/.claude/projects` 下的会话（列表、历史、删除、改名等），也做设置里存储页的统计和清理（`storage`、`clean`，删除规矩见脚本里“存储”一节）；`zeta_setup_*.sh` 安装 Debian 环境与可选工具；`zeta_ssh_android.sh` 管 SSH 服务。

## 两端开发

- 工作站（nerv-wsl）是主开发处：仓在 `~/workspace/Zeta`，改动在任务树里做，用 `wt land` 跑门禁并合入 master。
- 手机 Debian 里留一份 `/workspace/claude/zeta`，用来临时开发和实机测试，能编、能装、能测。开工前先 `git pull`。要留下的改动提交到分支、推到 GitHub，再在工作站上用 `wt land` 合入；手机上没有 `wt`，不直接推 master。
- 两边都只经 GitHub 的 `origin` 同步。
- 工作站进手机：`ssh xiaomi-17-debian` 进 Debian，`sudo nsenter -t 1 -m -- /system/bin/sh -c '<命令>'` 以 root 进 Android 一侧。`/workspace` 是 Android 的 `/data/local/tmp/eta` 挂进来的；从 chroot 里看不到 `/data/local/zeta`，要经 `nsenter` 看。

## 构建与测试

- 构建：`tools/gradle <任务>`，例如 `tools/gradle :app:assembleDebug`；不直接跑 `./gradlew`。它用 `tools/java-home` 找 JDK 25（`JAVA_HOME`，再是 PATH 上的 `java`，最后是 `~/.gradle/jdks` 里 Gradle 下载过的那份），并设 `ANDROID_HOME=~/Android/Sdk`。缺的 SDK 平台和 build-tools 由 AGP 构建时自动下载，两台机器都已接受许可。
- 手机上为内存和 CPU 架构调的 Gradle 参数写在手机的 `~/.gradle/gradle.properties`，不进仓：不开守护进程、Kotlin 在进程内编译、最多 4 个 worker、aapt2 用 `~/Android/aapt2-x86_64/aapt2`（经 qemu-user 跑官方 x86-64 版，Google 不提供 linux-arm64 版）。这个 aapt2 的版本要和 AGP 一致，升 AGP 时一起换。手机空闲内存常只有几百 MB 到 3 GB，同一时间只跑一个 Gradle。
- 签名：debug 包用 `~/.android/zeta-debug.keystore` 签名，是手机上已装的 Zeta 用的那把调试密钥。工作站上是一份拷贝（权限 600），手机上是指向 `~/.android/debug.keystore` 的符号链接。签名对不上时 `pm install -r` 会失败，卸载重装会丢掉 Zeta 的全部设置，所以不换这把密钥，也不卸载。
- 门禁：`bash scripts/check.sh`。它核对 Python 清单，确认 Python 都能解析，再构建 debug 包、跑 JVM 单元测试（含截图测试）。
- 单元测试：`tools/gradle :app:testDebugUnitTest`，放在 `app/src/test/kotlin`。不碰 Android 运行时的逻辑（解析、路径、状态归约）都写成单元测试，在工作站上就能验，不用装机。`ClaudeConfigClientTest` 在临时 HOME 上端到端跑 `core/claude` 的脚本和 `claude` 命令行；加 `ZETA_TEST_SSH=xiaomi-17-debian`（和 `--rerun`）时经 ssh 在手机 Debian 里用临时 HOME 再跑一遍。
- 截图测试（看界面不用装机）：`tools/shots [过滤]` 在 JVM 上（Robolectric 原生图形 + Roborazzi）把界面画成 PNG，写到 `app/build/shots/<名字>-light.png` 和 `-dark.png`，尺寸、密度和手机一样（约 1220×2656，520 dpi），可以和真机截图并排看。过滤是测试类名的一部分（`tools/shots Settings`），不给就全跑。和上一次画的不一样时旁边多一张 `<名字>-<配色>_compare.png`（左旧、中差异、右新）：改界面前跑一次，改完再跑，看对照图。
  - 测试在 `app/src/test/kotlin/com/dzshzx/zeta/shots`，继承 `ShotTest`。界面要 `ConversationHub` 时用 `Fixtures.readyHub(app)`（环境就绪、已登录的真 hub，不起进程），聊天内容用 `hub.showChat(标题, 条目)` 摆上去。新界面加一张图：

    ```kotlin
    class MyScreenShots : ShotTest() {
        @Test
        fun main() {
            val hub = Fixtures.readyHub(app)
            // prepare 在浅色、深色下各做一遍，比如点开对话框；不需要就不写
            shoot("my-screen", prepare = { onNodeWithText("打开").performClick() }) { MyScreen(hub = hub, onBack = {}) }
        }
    }
    ```

  - 和真机的差别：字体是 Roboto 和 Noto Sans CJK（手机上是 MiSans），字宽略有出入，换行位置可能不同；动画一律直接放完，转圈停在固定一帧；毛玻璃是关掉的（`ZetaTheme(windowBackdrop = false)`）：顶栏没有光晕，按钮、对话框和菜单是不透明底色（手机上是毛玻璃）。
  - JVM 上没有 root、无障碍、悬浮窗、虚拟屏和 Claude 进程。单元测试的 PATH 里有个总是失败的 `su`（`app/src/test/no-root`），要 root 才查得到的东西在图上是查不到的样子（比如共享文件夹列不出子目录）。这些仍要上真机：悬浮层真实的窗口行为（位置、触摸、给 Claude 的手势让路、叠在别的应用上）、后台屏幕的画面、权限授予、Claude 真跑一轮，以及最终的字体和毛玻璃效果。
  - 门禁里的单元测试每次都把这些图画一遍，渲染崩溃就失败。不提交基准图、不做像素比对：并行的分支改界面时基准图必然冲突、要反复重录；JVM 渲染和真机本来就不同，工作站和手机 Debian（arm64）画出来的像素也未必一样。
  - 要滚到下面的节点用 `ShotTest` 的 `scrollIntoView()`，不直接用 `performScrollTo`：截图测试的时钟不自动前进，滚动等不到帧，`performScrollTo` 会一直重发滚动、卡住空转直到 OOM（`ShotSourcesTest` 拦着）。测试任务另有 10 分钟超时（`app/build.gradle.kts`），别的原因卡住时也到点失败。
- 并行开发：几棵任务树同时开发时，`tools/gradle` 最多同时跑 3 个构建（`ZETA_GRADLE_SLOTS`），其余排队。手机只有一台，同一个包同时只能装一个版本，所以按包发租约（测试版、正式版各一把）：工作站上的 `tools/install.sh` 和 `tools/vd.py` 每次碰手机前先经 `tools/phone-lock` 拿租约，占用者名字取 `ZETA_PHONE_OWNER`（默认 `main`）；被别人占着就失败（退出码 75），15 分钟没动静自动作废；测完用 `tools/phone-lock --release` 释放手上的全部租约。手机是并行开发的瓶颈：子代理碰手机只用 `tools/phone-run`（见下面「真机脚本」），不自己拿租约一步步点；只在真机上验必须真机才能验的部分，单元测试、临时 HOME、契约测试、截图测试能验的不占手机。
- 测试版：用户日常用的是正式版 `com.dzshzx.zeta`（debug 包）。开发测试装测试版：包名 `com.dzshzx.zeta.dev`，桌面名「Zeta 测试」，橙色图标，是 build type `dev`（`tools/gradle :app:assembleDev`，代码和签名同 debug，`assembleDebug` 照旧只出正式版）。两个包同时装在手机上，互不替换。测试版只有一份，真机测试交脚本给 `tools/phone-run` 排队执行。
  - 子代理和开发测试一律装测试版、在测试版上测。正式版只在上线时由主会话装（见「发版与上线」）：`ZETA_PHONE_OWNER` 不是 `main` 时，`tools/install.sh` 和 `tools/vd.py` 的 start、launch、stop、prefs 不肯碰正式版。
  - 测试版第一次装上（或卸载后重装）时没有 root，要用户自己在 KernelSU 管理器里给「Zeta 测试」授予 root，代理不能替用户授予；没 root 时它只显示没有 root。有了 root 以后，无障碍、通知、电池、后台弹出界面等在测试版自己的权限健康页一键授予。
  - 两个包共用同一个 Debian 和 Linux 用户：Claude Code 的会话、登录状态、配置是同一份，测试版里建的会话也会出现在正式版的列表里，照样测完就删。Zeta 自己在 Linux 里的目录按包分开（正式版 `~/.cache/zeta`、`~/.config/zeta`，测试版 `zeta-dev`，由环境变量 `ZETA_NAME` 告诉脚本，见 `AppVariant`），所以两个包不会接管、结束或清理对方的后台对话和附件；正在被另一个包跑着的会话在这边算作别处占用。设置、定时任务、通知渠道、无障碍服务也各是各的。包名相关的名字（无障碍组件、Provider authority、广播 action）都从 `AppVariant` 取，不写死 `com.dzshzx.zeta`。
  - 测试版的通知标题前注明「测试版」，完成与确认提醒不出声；和正式版一样，没有对话在跑时不常驻通知。测完不让它在后台常驻：`vd.py --dev stop` 会 force-stop 它，并撤销它的通知监听、删掉 `phoneDisplay`。
  - `am force-stop <包名>` 会让系统把那个包的无障碍服务从启用列表里拿掉（AOSP `onPackagesForceStoppedLocked`），所以不对正式版 force-stop，`vd.py stop` 对正式版也只关屏。测试版被 force-stop 后，下次用手机工具时有 root 就自己加回去，也可以在权限健康页一键授予。
- 装机：`tools/install.sh --dev [--no-build]` 装测试版，不带 `--dev` 装正式版。在工作站上经 ssh 装，在手机上直接装。重装会结束这个包的界面，但不结束进行中的对话。
- 真机脚本：`ZETA_PHONE_OWNER=<任务名> tools/phone-run [--no-build] <脚本>`，放到后台跑（可能要排队）。它在排队之外构建测试版，再按先来先到排队（`tools/phone-lock` 发号，同一个任务的两个 phone-run 也一个一个来）；轮到后装包，在后台虚拟屏上冷启动 Zeta 并等 10 秒，限时跑脚本（`ZETA_PHONE_RUN_TIMEOUT`，默认也是最多 300 秒）；收尾时截 `final.png`，取本包的崩溃日志 `crash.txt`，用 `vd.py --dev stop` 收拾测试版，释放租约。脚本按 bash 跑，当前目录是产物目录 `build/phone-run/<时间>`，里面能用 `vd <命令>`（即下一条的 `vd.py --dev`）、`android '<命令>'`（即 `vd.py --dev sh`，在 Android 一侧以 root 跑）和 `$PKG`、`$DISPLAY_ID`。看完产物再改脚本重跑，想下一步的时候不占手机。一个脚本里最多等 Claude 真跑一轮，要验的几样合进同一个脚本，没改东西不重跑；验得多就拆成几个脚本，别人能插在中间。退出码：脚本自己的，124 是超时，75 是排队超时。要留作回归的脚本放 `tools/phone-tests/`，样例是 `smoke.sh`。
- 测试：用 `python3 tools/vd.py --dev start|launch|shot <png>|tap x y|swipe x1 y1 x2 y2 [毫秒]|text <ASCII>|key <键码>|prefs <键名…>|status|stop` 在后台虚拟屏上驱动测试版，不碰用户的真屏。测试版和正式版各有自己的虚拟屏，所以每条命令都要带 `--dev`（build 除外）；不带就是正式版那块屏，`ZETA_PHONE_OWNER` 不是 `main` 时会被拒绝。工作站上每条命令走一次 ssh，约 0.4 秒。坐标按整屏 1220×2656 算；截图显示给 Claude 时缩小过，要按显示比例换算回去。`stop` 会关掉屏上的应用（建屏带 DESTROY_CONTENT_ON_REMOVAL），不会把它们挪到真屏上；测试版还会被 force-stop。
  - 冷启动后等约 10 秒再输入，否则字会丢。`vd.py text` 只能输 ASCII，要中文时改用包名之类。
  - 要把 Zeta 放到后台，就在虚拟屏上开另一个 activity，例如 `am start --display <屏号> -n com.android.traceur/.MainActivity`。Claude 在虚拟屏上开了别的应用、Zeta 被压到后面时，用 `vd.py launch` 拉回来。自己用 `am start` 开 Zeta 或别的页面时一律带 `--display <屏号>`，不带就开在用户的主屏上；测试留下的任务记录由 `vd.py --dev stop` 从用户的「最近任务」里清掉。
  - 截图一片白时，先查 `dumpsys activity activities`，不要先当成崩溃。多半是两种情况：用户自己打开了 Zeta，任务挪到了主屏，这时等用户，不要抢回来；或者 MIUI 的 GreezeManager 冻结了 Zeta（`reason=tobg`），`vd.py launch` 能解冻。用户的「一键清理」也会杀掉虚拟屏上的 Zeta，先查事件日志。
  - 下面的路径和组件名写的是 `<包名>`：测试版是 `com.dzshzx.zeta.dev`，正式版是 `com.dzshzx.zeta`。类名不随包名变，组件要写全，例如 `com.dzshzx.zeta.dev/com.dzshzx.zeta.ui.MainActivity`。
  - 测手机工具和悬浮层时不碰真屏：Zeta 的「主屏」可以指到测试屏上。先 force-stop Zeta，往 `/data/user/0/<包名>/shared_prefs/zeta.xml` 里加 `<int name="phoneDisplay" value="<屏号>" />`，再 `vd.py launch`；`vd.py --dev stop` 会删掉这一项。
  - 测试会改 Zeta 的设置（cwd、model、permissionMode、dark），测完恢复；用户的 cwd 是 `/workspace/claude`。只删测试时建的会话，`/workspace/claude` 下的会话是用户的。
  - Zeta 的设置用 `vd.py --dev prefs <键名…>` 读，或用 root 直接读 `/data/user/0/<包名>/shared_prefs/zeta.xml`（`run-as` 不可用）。只 grep 键名，不要整份 cat，`commands` 一项很大。
  - 不经文件选择器给 Zeta 喂文件：`am start --display <屏号> -n <包名>/com.dzshzx.zeta.ui.MainActivity -a android.intent.action.SEND -t <mime> --eu android.intent.extra.STREAM file:///data/user/0/<包名>/files/<名字>`。通知栏的「停止」可用 root 触发：`am startservice -n <包名>/com.dzshzx.zeta.core.AgentService -a <包名>.action.STOP`。
  - Claude Code 会拦下前台的 `sleep N`，长时间的测试用自己会耗时的脚本。

## 发版与上线

一个版本一件事。功能分支不改版本号，交回前自己变基到最新的 origin/master、解决冲突、压成一个提交；合入前由负责合入的会话升 `app/build.gradle.kts` 的 `versionName`（只升 patch）和 `versionCode`（加 1），免得并行的分支在这里必然冲突。提交标题里带上版本号，先用 `tools/phone-run tools/phone-tests/smoke.sh`（和 `tools/phone-tests/` 下与这次改动有关的脚本）在测试版上过一遍，再用 `wt land` 合入。合入不装正式版。没有 tag，GitHub 上也不发 Release。

上线指把正式版装成 master 的最新版本。正式版是用户日常在用的，开发再快它也要稳定，所以合入的版本先只在测试版上，用户在 Zeta 的语境里说「上线」时才装：`tools/install.sh`（不带 `--dev`），中间的版本直接跳过；装完在正式版的测试屏上验一遍（`vd.py launch`、`vd.py shot`）。重装会结束正式版的界面，进行中的对话照样跑完，但正在执行的手机工具调用会失败。

## 边界

- 不替用户在 KernelSU 管理器里给 Zeta 授予 root。
- 改 `settings put secure enabled_accessibility_services` 时只追加 Zeta 的组件，列表里别的服务（bitwarden、Eta、mipay、gkd、vtools 等）一个都不能丢。
- 操作手机界面一律在后台虚拟屏上做，不占主屏前台。只读的核对走文件或 `dumpsys`，不走界面。
- 开发时不改用户真实的 `~/.claude`（CLAUDE.md、记忆、skills、agents、MCP 配置）。要试 `claude` CLI 的行为，就用临时 `HOME`。MCP 配置里有真实 token，不打印。
- 不盲调 `service call`：`service call notification 1` 曾清掉所有用户的通知。

## 坑

- Linux 里的 `/workspace` 是 Android 的 `/data/local/tmp/eta` 绑过去的，和 Eta 共用；Zeta 自己要建的目录放进 rootfs 里（共享文件夹挂在 `/mnt/shared`），不放在 `/workspace` 下。
- COUI 对话框按钮栏的 `neutral` 一律画成红色的危险按钮，三个按钮还会竖排挤掉内容；不危险的次要操作放菜单，或做成对话框内容里的链接。
- app 不能给它的 `su` 子进程发信号（uid 不同），要经 root 杀（`LinuxEnv.kill`）。
- `zeta_host.sh` 的看门狗在 `setsid bash -c '…'` 里面，那一段里不能写单引号。
- Kotlin：在 `VirtualDisplay.() -> Unit` 这类带接收者的 lambda 里裸写 `surface`，会解析成 `VirtualDisplay.getSurface()`，不是外层的字段。
- Claude Code 的 stream-json（2.1.327 实测）：
  - 标准输入的每条用户消息可带 `uuid`，它就是这条消息在会话记录里的 uuid；标准输出有 `command_lifecycle`（queued / started / completed / cancelled）。
  - 控制请求 `rewind_conversation` 把会话剪到一条用户消息之前（`target_message_uuid`），会话号不变，第一条也能剪。要带 `last_seen_user_message_uuid`（界面认得的最后一条用户消息），否则目标后面还有轮次时拒绝（`stale_target`）。剪不成也答 success，看 `rewound` 和 `reason`。记录文件只追加一行 `last-prompt`（`explicit`、`rewound`、`cutUuid`），被剪的内容还在文件里，读历史时要照这一行去掉。
  - `--resume <id> --fork-session` 的新会话在第一条消息时才写出记录，`--name` 给它定标题。隐藏参数 `--resume-session-at <uuid>` 也能截断，但不留剪切记号，按文件顺序读会把旧内容一起读出来，Zeta 不用它。
  - 会话记录的 `parentUuid` 不是一条直链：并行的工具调用各挂各的，从最后一行顺着往回走会漏掉步骤。
  - 一轮进行中发来的消息，会在下一个工具边界并入这一轮，两条消息只出一个 `result`。`interrupt` 的回应会列出 `still_queued`；`cancel_async_message` 能撤回排队中的消息。
  - `result` 行的键序不固定，不一定以 `"type"` 开头；`result` 之后还会有 command_lifecycle、task_notification、background_tasks_changed 行。判断一轮的起止看最后一个开始或结束事件，不看最后一行。
  - 后台任务做完时，Claude Code 会自己起一轮：一条 `system init`，前面没有用户消息。Zeta 把空闲时收到的 init 当成新一轮开始。
  - 后台任务（2.1.328，`ClaudeTaskContractTest` 钉住）：`background_tasks_changed` 是在跑的完整清单，先于 `task_started` 到；结局是 `task_updated`（patch.status）和 `task_notification`。控制请求 `get_task_output` 读后台命令、监视器输出的最后 8 KiB（子代理答 error）；`stop_task` 对不认识的 id 也答 success，停没停看随后的事件。停后台命令不另起一轮，子代理结束（做完或被停）后会。Monitor 的 task_type 也是 `local_bash`，靠发起它的工具调用区分。
