# Zeta 项目约束

Zeta（Ζ）是手机（Xiaomi 17，root，KernelSU）上 Claude Code 的 Android 原生客户端：Kotlin、Compose，界面用 COUI 2（ColorOS 17 风格）。包名 `com.dzshzx.zeta`（开发测试装的测试版是 `com.dzshzx.zeta.dev`，见「构建与测试」），默认分支 `master`。它要取代 Eta 做手机上的主力 agent，不只是 Claude Code 的图形界面：自带 Debian 环境的安装与管理、手机操作工具和后台虚拟屏。

## 产品取舍

用户定的，改之前先问。

- 界面用 COUI 2（`io.github.suqi8.coui.kmp`）：组件、配色、文字样式都用它自带的，它没有的才在 `ui/components/coui2` 补，不照抄 Eta 的组件。写法照它自带的指南（工作站 `~/workspace-external/coui` 的 `.claude/skills/coui-app/`），Zeta 的补充件、常见选法和坑见 `docs/ui.md`。不提 Web UI 方案。不接第三方服务，功能从简。
- Claude Code 就是 harness。Eta 里属于 harness 的部分不移植，比如终端、模型供应商。压缩上下文和起标题不自己实现，用 Claude Code 自带的；设置里的自动压缩、自动标题只是打开它们、设阈值。和 Eta 比缺什么时，只看手机与界面一侧；像 harness 的功能，先查 Claude Code 自己有没有。管理 Claude Code 自身配置的页面（记忆、Skills、MCP、Subagent）可以做，读写的是 Claude Code 的真实配置，不另造一套。
- 默认有 root，不照搬 Eta 里兼容无 root 的部分。可选工具里 Go 排第一。
- 版本号长期停在 0.x.x，不发 1.0。正式版的版本只在上线时升一次 patch（0.7.0 → 0.7.1），用户说了才升 minor；合入不改版本号，测试版的版本跟着提交自动走（见「发版与上线」）。
- Eta（GPL-3.0）是功能和交互的参照：照它的设计重写，不整段复制；外观、尺寸、动画以 COUI 2 为准。`ui/markdown`、侧栏骨架和主题最初是从 Eta 拷来的，所以本仓是 GPL-3.0。Eta 源码在工作站的 `~/workspace-external/Eta` 和手机的 `/workspace/claude/ref/Eta`（上游 github `Mangi-11/Eta`，对照前先 `git fetch` 再快进），ColorOS 的 COUI 源码解包在手机的 `/workspace/claude/ref/coui-src`；COUI 2 的源码在工作站的 `~/workspace-external/coui`（上游 github `suqi8/coui`，对照前先 `git pull`）。

## 架构

Zeta 进程 → `su -c` → 管理器 BusyBox 的 `nsenter -t 1 -m` → 私有挂载命名空间 → chroot 进 Debian rootfs（`/data/local/zeta/debian/rootfs`）→ `setpriv` 降到 Linux 用户 `dzshzx`（uid 5000）→ `assets/zeta_host.sh`。它用 `setsid` 起 `claude -p --input-format stream-json --output-format stream-json …`，标准输入来自 FIFO，输出追加到 `~/.cache/zeta/hosts/<key>/out.jsonl`（测试版是 `~/.cache/zeta-dev`；这个托管目录里各文件谁写谁读，见 `zeta_host.sh` 开头）。Zeta 只附着在上面，所以 Zeta 被杀时对话照样跑完，下次启动再附着回去。没有中转，不开端口。

- `core/`：Linux 环境（`LinuxEnv`、`EnvSetup`、`SshService`，环境检查、安装、维护、SSH 和账号登录状态的界面状态在 `EnvController`，文件浏览 `LinuxFiles`，共享文件夹 `SharedFolders`），Claude 进程与对话状态（`ClaudeProcess`、`Conversation`、`ConversationHub`、`TranscriptReducer`；发给 Claude Code 的控制请求经 `ClaudeProcess.request`，应答只按请求号交回发请求时给的回调；对话里权限模式的规则在 `PermissionModeState`，子代理的跟踪在 `SubagentTracker`），侧栏的会话列表（`SessionList`：读、改名、归档、删除），顶部胶囊要显示的对话（`ConversationFocus`），一轮的进展怎么提醒（`TurnAlerts`：胶囊挑哪个对话、完成与确认提醒、结果卡片、自动朗读），前台服务 `AgentService`（有对话在回答、顶部胶囊没显示时常驻通知提升为实时通知；挑哪个对话、写什么字、节流在 `LiveUpdate`），附件与图片，各对话没发出的输入草稿（`ComposerDrafts`：按会话号保管、存进单独的设置文件 `zeta_drafts`，Zeta 重开后还在）。
- `core/claude/`：设置里“上下文与扩展”读写 Claude Code 自身配置（记忆、Skills、MCP、Subagent）。解析、校验、路径映射是纯函数；读写文件经 `ClaudeScripts` 的小段壳脚本以 Linux 用户身份做，内容和 Token 只走标准输入；MCP 的增删走 `claude mcp`。
- `core/voice/`：语音，只用手机自带的服务：`SpeechInput` 是和界面无关的识别（系统 `SpeechRecognizer`，HyperOS 上显式指定小米的识别服务，识别期间前台绑定它的进程才录得了音），输入栏的按住说话（`ui/chat/HoldToTalk.kt`）用它；`ReadAloud` 用系统默认的朗读引擎念回答（`SpeechText` 把 Markdown 换成念得顺的文字），出声期间开媒体播放类前台服务。
- `phone/`：手机工具 `PhoneTools`（SDK 类型的 MCP 服务器 `phone`，每个对话一份；系统应用开给系统智能体的工具经 `phone/tools/AppTools.kt` 全部接入，工具清单运行时从各应用 APK 的 `assets/app_tools.json` 读；交给 Claude 的整份清单和每个工具的中文说法在 `PhoneToolCatalog`，工具权限页、聊天和胶囊都从这里取；屏幕这一套的定义在 `ScreenToolDefs`，暂停、接管的闸门是 `ControlGate`，对话的后台屏幕怎么建、睡、醒、释放在 `BackgroundSlot`），无障碍服务（启用、停用和被系统关掉后加回在 `AccessibilityGuard`），后台虚拟屏（`VirtualScreen`、root 帮手 `VdHelper`、`VdSurfaceProvider`，屏上应用不抢音频焦点的 `AudioFocusGuard`），悬浮层的接口 `Overlay.kt`。
- `assistant/`：数字助理（系统的助理会话、ASSIST 入口、浮窗 `AssistantActivity`、转给小米的识别服务）；`xposed/`：Zeta 自带的 LSPosed 模块，HyperOS 长按电源键交给 Zeta，并放行本包绑定系统应用的工具服务（正式版、测试版各带一个，各管各的）。做法、用户要启用的步骤和诊断见 `docs/assistant.md`。
- `ui/`：盖在聊天页上的页面栈 `PageStack.kt`（一层层盖上去的页面都用 `ui/components/SlideInPage.kt`），聊天 `ui/chat`、侧栏 `ui/pane`、设置 `ui/settings`（首页 `SettingsScreen.kt` 和各子页），Linux 环境和 Claude Code 配置页 `ui/setup`，后台任务面板 `ui/tasks`，Agent 浏览器 `ui/browser`、悬浮层 `ui/overlay`（顶部状态胶囊与展开画面、主屏边缘流光与触点指示、一轮结束后屏幕底部的结果卡片，都是无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口；结果卡片弹不弹、何时收起见 `core/ResultCard.kt`）、Markdown 渲染、COUI 2 的补充件 `ui/components/coui2`。
- `assets/`：`zeta_host.sh` 起 Claude 进程；`zeta_helper.py` 读写 `~/.claude/projects` 下的会话（列表、历史、删除、改名等），也做设置里存储页的统计和清理（`storage`、`clean`，删除规矩见脚本里“存储”一节）、子代理查看页的增量读取（`subagent`）；`zeta_setup_*.sh` 安装 Debian 环境与可选工具；`zeta_ssh_android.sh` 管 SSH 服务，`zeta_enter.sh` 是它写到手机上、以 root 进 Debian 的入口；`zeta_accounts.sh` 替账号页跑 claude-switch。

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
  - JVM 上没有 root、无障碍、悬浮窗、虚拟屏和 Claude 进程。单元测试的 PATH 里有个总是失败的 `su`（`app/src/test/no-root`），要 root 才查得到的东西在图上是查不到的样子（比如共享文件夹列不出子目录）。这些仍要装到设备上验（见下面「测试设备」）：悬浮层真实的窗口行为（位置、触摸、给 Claude 的手势让路、叠在别的应用上）、后台屏幕的画面、权限授予、Claude 真跑一轮；最终的字体和毛玻璃效果只有手机上看得到。
  - 门禁里的单元测试每次都把这些图画一遍，渲染崩溃就失败。不提交基准图、不做像素比对：并行的分支改界面时基准图必然冲突、要反复重录；JVM 渲染和真机本来就不同，工作站和手机 Debian（arm64）画出来的像素也未必一样。
  - 要滚到下面的节点用 `ShotTest` 的 `scrollIntoView()`，不直接用 `performScrollTo`：截图测试的时钟不自动前进，滚动等不到帧，`performScrollTo` 会一直重发滚动、卡住空转直到 OOM（`ShotSourcesTest` 拦着）。测试任务另有 10 分钟超时（`app/build.gradle.kts`），别的原因卡住时也到点失败。
- 测试设备：测试版默认装在本机的 Android 模拟器上，只有必须真机才能验的才上手机。模拟器是本机各项目共用的 AVD `retail35`（Android 15、x86_64、GKI 内核 android15-6.6，带 arm64 转译），Zeta 的 Debian、Linux 用户 `dzshzx` 和 Claude Code 照手机装好了。`tools/install.sh`、`tools/vd.py`、`tools/phone-run` 默认连它，没开就用 `tools/emu start` 开机（窗口在 xpra 的 `:99` 上，要给用户看时 `DISPLAY=:0 xpra attach :99`；联网经宿主的代理，模拟器不读代理环境变量，由它用 `-http-proxy` 传进去）；设 `ZETA_DEVICE=phone` 才连手机，正式版只在手机上。
  - root 和手机是同一套：SukiSU Ultra v4.3.0-r2 的内核模块（LKM）模式，管理器 APK 就是从手机上拷来的那份（它带 x86_64 的 ksud 和 `android15-6.6` 的模块）。SDK 的 `system-images/android-35/google_apis/x86_64/ramdisk.img` 用 `ksud boot-patch --ramdisk` 打过补丁，原件在旁边的 `ramdisk.img.stock`；SDK 更新这个系统镜像后要照样重打。模拟器上的 SukiSU 里已给「Zeta 测试」开了超级用户，重装测试版不用再授权。模拟器里的 Claude Code 还没登录账号：假 Claude 的测试照跑，要真 Claude 跑一轮的先 `ZETA_DEVICE=phone`。
  - 必须上手机的：HyperOS 和小米专有的（电源键助理与 LSPosed 模块、小米的语音识别服务、GreezeManager 冻结）、Android 16 才有的（实时通知）、KernelSU 的授权流程、真机上的性能、耗电和休眠、MiSans 字体和毛玻璃的最终效果，以及上线后的正式版。
  - 模拟器也是 xiaomi-retail 抓包用的：别的项目用别的代理（比如抓包的 `-http-proxy http://127.0.0.1:8080`）开着它时 `tools/emu` 会提示，这时 Zeta 联网都经那个代理；用假 Claude 不联网的测试照跑，要联网就 `tools/emu stop` 后重开。
- 并行开发：几棵任务树同时开发时，`tools/gradle` 最多同时跑 3 个构建（`ZETA_GRADLE_SLOTS`），其余排队。每台设备上同一个包同时只能装一个版本，所以按设备和包发租约（模拟器上的测试版、手机上的测试版、手机上的正式版各一把）：`tools/phone-run` 排队拿租约（见下面「设备脚本」），脚本里的 `vd` 在它的租约下跑；主会话在 phone-run 之外直接用 `tools/install.sh`、`tools/vd.py` 时，它们每次碰设备前自己经 `tools/phone-lock` 拿租约，被别人占着就失败（退出码 75），15 分钟没动静自动作废，测完用 `tools/phone-lock --release` 释放。占用者名字取 `ZETA_PHONE_OWNER`（默认 `main`），不是 `main` 的（子代理）在 phone-run 之外碰设备会被这两个工具拒绝。设备是并行开发的瓶颈：单元测试、临时 HOME、契约测试、截图测试能验的不占设备。
- 测试版：用户日常用的是正式版 `com.dzshzx.zeta`（debug 包）。开发测试装测试版：包名 `com.dzshzx.zeta.dev`，桌面名「Zeta 测试」，橙色图标，是 build type `dev`（`tools/gradle :app:assembleDev`，代码和签名同 debug，`assembleDebug` 照旧只出正式版）。任务分支的提交数可能比手机上刚装的少，`tools/install.sh` 用 `pm install -d` 照装。手机上两个包同时装着，互不替换。每台设备上测试版只有一份，设备上的测试交脚本给 `tools/phone-run` 排队执行。
  - 子代理和开发测试一律装测试版、在测试版上测。正式版只在上线时由主会话装（见「发版与上线」）：`tools/install.sh`、`tools/vd.py` 默认就是测试版，碰正式版要显式加 `--release`，`ZETA_PHONE_OWNER` 不是 `main` 时会被拒绝。
  - 手机上的测试版第一次装上（或卸载后重装）时没有 root，要用户自己在 KernelSU 管理器里给「Zeta 测试」授予 root，代理不能替用户授予；没 root 时它只显示没有 root。有了 root 以后，无障碍、通知、电池、后台弹出界面等在测试版自己的权限健康页一键授予。
  - 两个包共用同一个 Debian 和 Linux 用户：Claude Code 的会话、登录状态、配置是同一份，测试版里建的会话也会出现在正式版的列表里，照样测完就删。Zeta 自己在 Linux 里的目录按包分开（正式版 `~/.cache/zeta`、`~/.config/zeta`，测试版 `zeta-dev`，由环境变量 `ZETA_NAME` 告诉脚本，见 `AppVariant`），所以两个包不会接管、结束或清理对方的后台对话和附件；正在被另一个包跑着的会话在这边算作别处占用。设置、定时任务、通知渠道、无障碍服务也各是各的。包名相关的名字（无障碍组件、Provider authority、广播 action）都从 `AppVariant` 取，不写死 `com.dzshzx.zeta`。
  - 测试版的通知标题前注明「测试版」，完成与确认提醒不出声；和正式版一样，没有对话在跑时不常驻通知。测完不让它在后台常驻：`vd.py stop` 会 force-stop 它，并撤销它的通知监听、改回 `prefs-set` 改过的设置。
  - `am force-stop <包名>` 会让系统把那个包的无障碍服务从启用列表里拿掉（AOSP `onPackagesForceStoppedLocked`），所以不对正式版 force-stop，`vd.py stop` 对正式版也只关屏。测试版被 force-stop 后，下次用手机工具时有 root 就自己加回去，也可以在权限健康页一键授予。
- 装机：`tools/install.sh [--no-build]` 装测试版（默认装到模拟器，`ZETA_DEVICE=phone` 时装到手机）；`--release` 往手机上装正式版，只用于上线。工作站上连模拟器经 adb、连手机经 ssh，在手机 Debian 里直接装。重装会结束这个包的界面，但不结束进行中的对话。
- 设备脚本：`ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run [--no-build] <脚本>`，放到后台跑（可能要排队）。它在排队之外构建测试版、开模拟器，再按先来先到排队（`tools/phone-lock` 发号，同一个任务的两个 phone-run 也一个一个来）；轮到后装包，在后台虚拟屏上冷启动 Zeta、把它的「主屏」指到这块测试屏并等 10 秒，限时跑脚本（`ZETA_PHONE_RUN_TIMEOUT`，默认也是最多 300 秒）；收尾时截 `final.png`，取本包的崩溃日志 `crash.txt`，用 `vd.py stop` 收拾测试版，释放租约。脚本按 bash 跑，当前目录是产物目录 `build/phone-run/<时间>`，里面能用 `vd <命令>`（即下一条的 `vd.py`）、`android '<命令>'`（即 `vd.py sh`，在 Android 一侧以 root 跑）和 `$PKG`、`$DISPLAY_ID`、`$ZETA_ROOT`（这棵树的根）。看完产物再改脚本重跑，想下一步的时候不占设备。一个脚本里最多等 Claude 真跑一轮，要验的几样合进同一个脚本，没改东西不重跑；验得多就拆成几个脚本，别人能插在中间。退出码：脚本自己的，124 是超时，75 是排队超时。要留作回归的脚本放 `tools/phone-tests/`（两种设备通用），样例是 `smoke.sh`；往输入栏发字、等假 Claude 一轮的脚本先 source `tools/phone-tests/lib.sh`（`send`、`find_log`、`wait_result`、`HOSTS`、输入栏坐标）。
  - 假 Claude：只看界面、状态胶囊、通知、悬浮层、结果卡片、手机工具调完后的样子，不在乎模型说什么时，脚本里先 `vd fake <剧本>`，测试版之后起的对话改由 `tools/fake-claude/fake-claude.sh` 照剧本回话，一轮几秒、每次一样；`vd stop`（phone-run 收尾会做）删掉剧本，测试版回到真的 claude，正式版永远是真的。剧本一行一轮，文字、思考、经 Zeta 真调一次手机工具、等批准、停顿、出错都写得出，被打断时照真 Claude Code 收尾，写法见脚本开头；样例是 `tools/phone-tests/fake/` 和用它们的回归 `tools/phone-tests/fake-claude.sh`。它不写会话记录，没有要删的测试会话。
  - 还得真跑的：Claude Code 自己的行为（插话并进当前这一轮、后台任务和子代理、压缩、权限规则、会话记录与续接、分支和改写）、真模型会不会用对工具，以及发版前的真实链路验收。假 Claude 和真 Claude Code 的输出由 `FakeClaudeContractTest` 逐条比对（真 claude 跑在临时 HOME 上连假 API），升级 Claude Code 后跑一遍，对不上就改脚本。
- 测试：用 `python3 tools/vd.py start|launch|shot <png>|tap x y|swipe x1 y1 x2 y2 [毫秒]|text <ASCII>|key <键码>|prefs <键名…>|prefs-set <键[:int]=值…>|fake <剧本>|status|stop` 在后台虚拟屏上驱动测试版，不碰用户的真屏。测试版和正式版各有自己的虚拟屏，带 `--release` 才是正式版那块。连手机时每条命令走一次 ssh，约 0.4 秒；连模拟器走 adb。坐标按整屏 1220×2656 算（模拟器上的虚拟屏也按这个尺寸建）；截图显示给 Claude 时缩小过，要按显示比例换算回去。`stop` 会关掉屏上的应用（建屏带 DESTROY_CONTENT_ON_REMOVAL），不会把它们挪到真屏上；测试版还会被 force-stop。
  - 冷启动后等约 10 秒再输入，否则字会丢。`vd.py text` 只能输 ASCII，要中文时改用包名之类。打字很慢、丢字或顺序乱时，看 logcat 有没有 `Excessive delay in interceptKeyBeforeQueueing` 和 `Timed out waiting for animations`：主屏上有窗口动画卡住时，注入的每个按键都要等 5 秒再被当成过期事件丢掉；`am force-stop` 那个主屏上的应用就好（模拟器上见过 SukiSU 管理器这样）。
  - 要把 Zeta 放到后台，就在虚拟屏上开另一个 activity，例如 `am start --display <屏号> -n com.android.traceur/.MainActivity`。Claude 在虚拟屏上开了别的应用、Zeta 被压到后面时，用 `vd.py launch` 拉回来。自己用 `am start` 开 Zeta 或别的页面时一律带 `--display <屏号>`，不带就开在用户的主屏上；测试留下的任务记录由 `vd.py stop` 从用户的「最近任务」里清掉。
  - 截图一片白时，先查 `dumpsys activity activities`，不要先当成崩溃。多半是两种情况：用户自己打开了 Zeta，任务挪到了主屏，这时等用户，不要抢回来；或者 MIUI 的 GreezeManager 冻结了 Zeta（`reason=tobg`），`vd.py launch` 能解冻。用户的「一键清理」也会杀掉虚拟屏上的 Zeta，先查事件日志。
  - 下面的路径和组件名写的是 `<包名>`：测试版是 `com.dzshzx.zeta.dev`，正式版是 `com.dzshzx.zeta`。类名不随包名变，组件要写全，例如 `com.dzshzx.zeta.dev/com.dzshzx.zeta.ui.MainActivity`。
  - 测手机工具和悬浮层时不碰真屏：Zeta 的「主屏」指到测试屏上。`tools/phone-run` 每次都替脚本做好（`vd.py prefs-set phoneDisplay:int=<屏号>`，再 `vd.py launch`；写不上就不跑），测试里的 Claude 用手机工具也碰不到用户的主屏。手动用 vd.py 时照这个顺序自己做；`vd.py stop` 会删掉这一项。
  - 测试会改 Zeta 的设置（比如 cwd、dark），由 `vd stop` 改回去（见下一条）；用户的 cwd 是 `/workspace/claude`。只删测试时建的会话，`/workspace/claude` 下的会话是用户的。
  - Zeta 的设置用 `vd.py prefs <键名…>` 读，或用 root 直接读 `/data/user/0/<包名>/shared_prefs/zeta.xml`（`run-as` 不可用）。只 grep 键名，不要整份 cat，`commands` 一项很大。测试要临时改设置用 `vd.py prefs-set`（先 force-stop、原地改写，属主和 SELinux 标签不变），每个键第一次改时记下原值，`vd stop`（phone-run 收尾）改回去，脚本不自己恢复。
  - 不经文件选择器给 Zeta 喂文件：`am start --display <屏号> -n <包名>/com.dzshzx.zeta.ui.MainActivity -a android.intent.action.SEND -t <mime> --eu android.intent.extra.STREAM file:///data/user/0/<包名>/files/<名字>`。通知栏的「停止」可用 root 触发：`am startservice -n <包名>/com.dzshzx.zeta.core.AgentService -a <包名>.action.STOP`。
  - Claude Code 会拦下前台的 `sleep N`，长时间的测试用自己会耗时的脚本。

## 发版与上线

一件事一个提交，合入不改版本号。版本照 SemVer 和 setuptools-scm 的通行写法从 git 推出来（`app/build.gradle.kts`），不手写：

- versionCode 是 HEAD 的提交数，每合入一次自动加一。同一个提交编出的测试版和正式版相同，设置页「关于」和诊断页的「版本名（versionCode）」一对就知道是哪个提交。
- 正式版在上线时打的 tag（`v0.7.1`）上，版本名就是 `0.7.1`。
- 别的提交是下一个版本的预发布：`0.7.2-dev.3+gabc1234` 是 0.7.1 之后第 3 个提交、正往 0.7.2 走、提交号 abc1234。按 SemVer 它排在 0.7.1 之后、0.7.2 之前，`+` 后面的构建元数据不参与比大小。测试版装的都是这种。
- tag 只标正式版的版本，推到 origin 后不改（GitHub 上 tag 不可改写）；GitHub 上不发 Release。

合入：功能分支交回前自己变基到最新的 origin/master、解决冲突、压成一个提交。负责合入的会话先用 `tools/phone-run tools/phone-tests/smoke.sh`（和 `tools/phone-tests/` 下与这次改动有关的脚本）在测试版上过一遍，再用 `wt land` 合入。合入不装正式版。

上线指把正式版装成 master 的最新版本。正式版是用户日常在用的，开发再快它也要稳定，所以合入的版本先只在测试版上，用户在 Zeta 的语境里说「上线」时才装：在主 checkout 同步到 origin/master 后跑 `tools/install.sh --release`（用户说升 minor 时再加 `--minor`）。它在 HEAD 上打下一个版本的 tag，构建、核对版本名、安装，装好后才把 tag 推到 origin，中间的提交直接跳过；中途失败就删掉这个本地 tag，改好后重跑。HEAD 已有 tag 就重装这个版本（tag 还没推上去的顺带推上去）。装完在正式版的测试屏上验一遍（`vd.py --release` 带 start、launch、shot，验完 stop）。重装会结束正式版的界面，进行中的对话照样跑完，但正在执行的手机工具调用会失败。

## 边界

- 手机上不替用户在 KernelSU 管理器里给 Zeta 授予 root。
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
  - `--resume <id> --fork-session` 的新会话在第一条消息时才写出记录，`--name` 给它定标题。
  - 接着旧会话（`--resume`，含 `--fork-session`）不带 `--model` 时用会话记录里的模型，中间新开的会话不受影响；`--model default` 是内置的默认模型（盖过 settings.json 的 model 和会话记录）。思考强度不恢复。记录里的模型下线了也照用，API 拒绝后这一轮以出错结束，set_model 换一个就好（2.1.328，`ClaudeResumeContractTest` 钉住）。所以 Zeta 接旧会话不带 `--model`（除非用户在这个对话里选过），顶栏显示 init 报的模型；思考强度照会话记录自己恢复。隐藏参数 `--resume-session-at <uuid>` 也能截断，但不留剪切记号，按文件顺序读会把旧内容一起读出来，Zeta 不用它。
  - 权限模式（2.1.329，`UnattendedRulesContractTest` 钉住）：`set_permission_mode` 不等应答、紧接着发消息，这一轮就按新模式跑，每轮开头的 init 报的就是它。进程起的时候不带 `--allow-dangerously-skip-permissions` 就切不进 `bypassPermissions`（答 error，`bypass_not_launched`），所以 Zeta 起进程都带它；它本身不放行任何东西，实测带不带它发给模型的内容一样；模式怎么切，发给模型的工具名单和系统提示词也不变。系统提示词开头的计费标记跟着会话的第一条消息变，跨进程比对提示词时第一条要相同。
  - 默认值（2.1.329，同一个测试钉住）：不带 `--permission-mode`、`--model` 起的进程用 settings.json 的 `permissions.defaultMode`、`model`，initialize 的应答和 init 照实报。控制请求 `update_settings`（source `userSettings`，和 `/effort` 存为默认一样）把思考强度写进 `modelSettings.<模型的完整 ID>.effortLevel`。Zeta 新对话起进程都不带这三样，设置首页读写的就是这几个键；对话里选的只管那个对话，进程活多久它就管多久（接回进程时照 `hostModes` 恢复用户的模式）。
  - 会话记录的 `parentUuid` 不是一条直链：并行的工具调用各挂各的，从最后一行顺着往回走会漏掉步骤。
  - 一轮进行中发来的消息也有自己的 command_lifecycle started，会在下一个工具边界并入这一轮，两条消息只出一个 `result`（2.1.329，`ClaudeCodeContractTest` 钉住；Zeta 的排队条和一轮的结束靠它）。`interrupt` 的回应会列出 `still_queued`；`cancel_async_message` 能撤回排队中的消息。
  - `result` 行的键序不固定，不一定以 `"type"` 开头；`result` 之后还会有 command_lifecycle、task_notification、background_tasks_changed 行。判断一轮的起止看最后一个开始或结束事件，不看最后一行。
  - 后台任务做完时，Claude Code 会自己起一轮：一条 `system init`，前面没有用户消息。Zeta 把空闲时收到的 init 当成新一轮开始。
  - 后台任务（2.1.328，`ClaudeTaskContractTest` 钉住）：`background_tasks_changed` 是在跑的完整清单，先于 `task_started` 到；结局是 `task_updated`（patch.status）和 `task_notification`。控制请求 `get_task_output` 读后台命令、监视器输出的最后 8 KiB（子代理答 error）；`stop_task` 对不认识的 id 也答 success，停没停看随后的事件。停后台命令不另起一轮，子代理结束（做完或被停）后会。Monitor 的 task_type 也是 `local_bash`，靠发起它的工具调用区分。
  - 子代理（2.1.328，同一个测试钉住）：Agent 默认放后台，前台要 `run_in_background: false`。记录在 `<项目>/<会话 id>/subagents/agent-<id>.jsonl`，id 就是 `task_started` 的 task_id，也在 Agent 结果的 `tool_use_result.agentId` 里；旁边的 `agent-<id>.meta.json` 记着发起它的调用（`toolUseId`），做完加 `finished`，被停加 `stoppedByUser`。输出流里子代理内部的事件带 `parent_tool_use_id`，但前台子代理只送工具调用和结果、不送它说的话，所以子代理查看页以记录为准（`zeta_helper.py subagent` 按偏移增量读）。结局（`finished`、`task_notification`）可能比最后一句报告先写下，`finished` 也可能比 `task_notification` 晚到，先后都不一定：结局以 `task_notification` 为准，结束后要等记录停下来再停止读。
