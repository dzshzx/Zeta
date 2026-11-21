# Zeta 项目约束

Zeta（Ζ）是 root 过的 Android 手机上 Claude Code 的原生客户端：Kotlin、Compose，界面用 COUI 2（ColorOS 17 风格）。包名 `com.dzshzx.zeta`（开发测试装的测试版是 `com.dzshzx.zeta.dev`，见「构建与测试」），默认分支 `master`。它要取代 Eta 做手机上的主力 agent，不只是 Claude Code 的图形界面：自带 Debian 环境的安装与管理、手机操作工具和后台虚拟屏。

维护者本机的环境事实（设备的 ssh 别名、模拟器、参照源码的本机位置、账号与密钥条目）不进 Git，写在仓根的 `CLAUDE.local.md`，Claude Code 在主 checkout 里会自动加载它；在任务 worktree 里时去读主 checkout 的那一份（`git rev-parse --path-format=absolute --git-common-dir` 的上一级目录）。没有这个文件时，按下面「本机配置」配好自己的设备。

## 产品取舍

用户定的，改之前先问。

- 界面用 COUI 2（`io.github.suqi8.coui.kmp`）：组件、配色、文字样式都用它自带的，它没有的才在 `ui/components/coui2` 补，不照抄 Eta 的组件。写法照它自带的指南（COUI 2 仓库里的 `.claude/skills/coui-app/`），Zeta 的补充件、常见选法和坑见 `docs/ui.md`。不提 Web UI 方案。不接第三方服务，功能从简。
- Claude Code 就是 harness。Eta 里属于 harness 的部分不移植，比如终端、模型供应商。压缩上下文和起标题不自己实现，用 Claude Code 自带的；设置里的自动压缩、自动标题只是打开它们、设阈值。和 Eta 比缺什么时，只看手机与界面一侧；像 harness 的功能，先查 Claude Code 自己有没有。管理 Claude Code 自身配置的页面（记忆、Skills、MCP、Subagent）可以做，读写的是 Claude Code 的真实配置，不另造一套。
- 默认有 root，不照搬 Eta 里兼容无 root 的部分。可选工具里 Go 排第一。
- 版本号长期停在 0.x.x，不发 1.0。正式版的版本只在发版时升一次 patch（0.7.0 → 0.7.1），用户说了才升 minor；合入不改版本号，测试版的版本跟着提交自动走（见「发版与上线」）。
- Eta（GPL-3.0）是功能和交互的参照：照它的设计重写，不整段复制；外观、尺寸、动画以 COUI 2 为准。`ui/markdown`、侧栏骨架等是从 Eta 拷来改写的（具体文件见仓根 `THIRD_PARTY.md`），所以本仓是 GPL-3.0（只写第 3 版：Eta 没有许可后续版本）。Eta 的上游是 github `Mangi-11/Eta`，COUI 2 的上游是 github `suqi8/coui`；对照前先把本地的那份更新到上游最新。

## 架构

Zeta 进程 → `su -c` → 管理器 BusyBox 的 `nsenter -t 1 -m` → 私有挂载命名空间 → chroot 进 Debian rootfs（`/data/local/zeta/debian/rootfs`）→ `setpriv` 降到安装环境时建的 Linux 用户（用户名默认 `zeta`，uid 从 5000 起）→ `assets/zeta_host.sh`。它用 `setsid` 起 `claude -p --input-format stream-json --output-format stream-json …`，标准输入来自 FIFO，输出追加到 `~/.cache/zeta/hosts/<key>/out.jsonl`（测试版是 `~/.cache/zeta-dev`；这个托管目录里各文件谁写谁读，见 `zeta_host.sh` 开头）。Zeta 只附着在上面，所以 Zeta 被杀时对话照样跑完，下次启动再附着回去。没有中转，不开端口。闲着的进程不常驻：没有应用接着时，托管脚本的看门狗约十分钟后结束它；Zeta 在后台时，当前对话闲满十分钟、切走的对话一轮结束 5 秒后由 Zeta 收掉（`ConversationHub`），内容不丢，下次发消息用 `--resume` 接上。

远程主机：Claude Code 也可以跑在别的 Linux 主机上（设置首页「设备」一组里添加，侧栏顶部的主机胶囊切换；界面上手机和远程主机都叫「设备」，代码里叫主机、`backend`）。设备的名字（`hub.backendName`）是用户起的，没起时是设备自己的：手机是系统设置里的设备名，远程主机是它报的主机名（`uname -n`，加的时候和「检查连接」时记下，早先加的在第一次读默认值时补上），还没记下时是地址。两种连法（`RemoteHost.via`）：经手机 Debian 里的 OpenSSH（`DebianSshShell`：`ssh -T`，ControlMaster 复用连接，密钥、别名、跳板都用那边 `~/.ssh` 的配置，不用往主机上另加公钥；每条命令先进 Debian，真机上多约 50 毫秒（`tools/phone-tests/remote-latency.sh` 量的），手机上多两个 ssh 进程），或 Zeta 用自带的 SSH 直连（`DirectSshShell` → `core/ssh/SshLinks`，JSch：每台主机一条连接、每个命令一个通道，闲一分钟断开），不需要 Debian 和 root。加主机时手机 Debian 就绪就默认经 Debian（`hub.defaultVia`）：从 `~/.ssh/config` 选的只能经 Debian，输入地址的两种都行（`RemoteHosts.targetFor` 按连法换写法，交给 OpenSSH 的端口写进 `ssh://`）；加好以后在主机页改连法（`hub.changeVia`：先按新连法连一次，连上才改；对话进程按原来的连法接着，所以那台上有对话在进行时不改，闲着的收掉进程，下次发消息时 `--resume` 接上）。手机换网络时两种都马上断开重连，不等保活包超时（直连的 `SshLinks.dropAll`，经 Debian 的 `DebianSshShell.closeMasters` 关掉 ssh 主连接，挂在上面的命令随即以 255 结束）。直连用 Zeta 自己的密钥（`SshKey`：Ed25519，私钥在 Android Keystore 里取不出来，公钥那一行在设置页里复制，也写在应用私有目录的 `files/zeta_ssh.pub`）；主机指纹在加主机时记下，以后只认它。Android 自带的加密库没有 Ed25519，验对方的 Ed25519 主机签名、curve25519 和 ML-KEM 密钥交换靠 BouncyCastle（JSch 只用它的轻量接口；`SshLinks` 把这几样写死成 BouncyCastle 的实现，JVM 上的测试和手机走同一份代码）。两种都把命令交给那边的 bash，连接开着 zlib 压缩：起 Claude Code 这类要用那边用户环境的命令用登录 shell（`bash -lc`，PATH 补上 `~/.local/bin`），zeta-helper、托管脚本的接上和结束、附件这类只用 bash 和 coreutils 的命令经 `LinuxShell.startPlain` 交给 `bash -c`，不读那边的登录配置（登录配置每次可能要几十毫秒）；`zeta_host.sh` 原样在远端跑，zeta-helper 第一次用时按那边 `uname -m` 装上（同手机），所以远端要是 arm64 或 x86-64 的 Linux，有 bash、coreutils 和 claude，加主机时的检查（`RemoteHosts.ProbeScript`）核对这几样。哪台主机是一个字符串（`Backends`：`local` 或远程主机的编号），在哪跑命令都经 `LinuxShell`（`hub.shell(backend)`；界面上能不能用看 `hub.usable`，要靠 Debian 的主机等环境就绪，用不了时 `hub.unavailable` 说原因；要传主机的接口都不设默认值）：对话、会话列表、历史、图片、附件、子代理按对话或侧栏所在的主机读写，新对话的默认值和 Claude Code 的配置页按设置里点开的那台主机读写（`HostSettingsScreen`，和侧栏无关）；环境安装、共享文件夹、存储、诊断、账号与额度、定时任务、数字助理只在手机上。手机上没有 Debian 时聊天页给「连接远程主机」，直连的主机照常能用；直连主机上的进程在界面起来时接回（`hub.onUiStarted`，和手机本机一样不在后台拉起时连），换 Debian 环境不收它们的对话。置顶按主机分开记（`SessionSummary.pinKey`）。托管脚本接上后先打一行 `zeta_attached`（带起始行号），远程连接断了（以 255 结束）`ClaudeProcess` 从读到的下一行重连，一直重试到接上，期间要发的消息接上后再发。

- `core/`：Linux 环境（`LinuxEnv`、`EnvSetup`、`SshService`，环境检查、安装、维护、SSH 和账号登录状态的界面状态在 `EnvController`，文件浏览 `LinuxFiles`，共享文件夹 `SharedFolders`），Claude 进程与对话状态（`ClaudeProcess`、`Conversation`、`ConversationHub`、`TranscriptReducer`；发给 Claude Code 的控制请求经 `ClaudeProcess.request`，应答只按请求号交回发请求时给的回调；对话里权限模式的规则在 `PermissionModeState`，子代理的跟踪在 `SubagentTracker`；等用户回应的请求（工具确认、计划审批、提问、MCP 表单）照终端版的选项读请求、拼应答在 `Prompts.kt`，按到达顺序排队、一次弹一个；还原代码在 `Rewind.kt` 的 `CodeRewind`），侧栏的会话列表（`SessionList`：读、改名、归档、删除），顶部胶囊要显示的对话（`ConversationFocus`），一轮的进展怎么提醒（`TurnAlerts`：胶囊挑哪个对话、完成与确认提醒、结果卡片、自动朗读；提醒每个对话一条，跟着等用户的请求发、撤，上面写什么、带哪些按钮在 `AlertNotice`，按钮按下后由 `AlertReplyReceiver` 回答），前台服务 `AgentService`（有对话在回答、顶部胶囊没显示时常驻通知提升为实时通知；挑哪个对话、写什么字、节流在 `LiveUpdate`），附件与图片，各对话没发出的输入草稿（`ComposerDrafts`：按会话号保管、存进单独的设置文件 `zeta_drafts`，Zeta 重开后还在）。
- `core/claude/`：设置里各主机页面上的新对话默认值和「Claude Code 配置」读写那台 Claude Code 自身的配置（settings.json、记忆、Skills、MCP、Subagent、工具权限）。解析、校验、路径映射是纯函数；读写文件经 `ClaudeScripts` 的小段壳脚本以 Linux 用户身份做，内容和 Token 只走标准输入；MCP 的增删走 `claude mcp`。
- `core/voice/`：语音，只用手机自带的服务：`SpeechInput` 是和界面无关的识别（系统 `SpeechRecognizer`，HyperOS 上显式指定小米的识别服务，识别期间前台绑定它的进程才录得了音），输入栏的按住说话（`ui/chat/HoldToTalk.kt`）用它；`ReadAloud` 用系统默认的朗读引擎念回答（`SpeechText` 把 Markdown 换成念得顺的文字），出声期间开媒体播放类前台服务。
- `phone/`：手机工具 `PhoneTools`（SDK 类型的 MCP 服务器 `phone`，每个对话一份；系统应用开给系统智能体的工具经 `phone/tools/AppTools.kt` 全部接入，工具清单运行时从各应用 APK 的 `assets/app_tools.json` 读；交给 Claude 的整份清单和每个工具的中文说法在 `PhoneToolCatalog`，工具权限页、聊天和胶囊都从这里取；屏幕这一套的定义在 `ScreenToolDefs`，暂停、接管的闸门是 `ControlGate`，对话的后台屏幕怎么建、睡、醒、释放在 `AgentDisplaySlot`），无障碍服务（启用、停用和被系统关掉后加回在 `AccessibilityGuard`），后台虚拟屏（`VirtualScreen`、root 帮手 `VdHelper`、`VdSurfaceProvider`，屏上应用不抢音频焦点的 `AudioFocusGuard`），悬浮层的接口 `Overlay.kt`。
- `assistant/`：数字助理（系统的助理会话、ASSIST 入口、浮窗 `AssistantActivity`、转给小米的识别服务）；`xposed/`：Zeta 自带的 LSPosed 模块，HyperOS 长按电源键交给 Zeta，并放行本包绑定系统应用的工具服务（正式版、测试版各带一个，各管各的）。做法、用户要启用的步骤和诊断见 `docs/assistant.md`。
- `ui/`：盖在聊天页上的页面栈 `PageStack.kt`（一层层盖上去的页面都用 `ui/components/SlideInPage.kt`），聊天 `ui/chat`、侧栏 `ui/pane`、设置 `ui/settings`（首页 `SettingsScreen.kt`、各台主机的页面 `HostSettingsScreen.kt` 和各子页），Linux 环境和 Claude Code 配置页 `ui/setup`，后台任务面板 `ui/tasks`，Agent 浏览器 `ui/browser`、悬浮层 `ui/overlay`（顶部状态胶囊与展开画面、主屏边缘流光与触点指示、一轮结束后屏幕底部的结果卡片，都是无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口；结果卡片弹不弹、何时收起见 `core/ResultCard.kt`）、Markdown 渲染、COUI 2 的补充件 `ui/components/coui2`。
- `helper/`（仓根，Go）：Linux 一侧的程序 zeta-helper，读写 `~/.claude/projects` 下的会话（列表、历史、删除、改名等；列表和 Claude Code 自己的一样只读每份记录的头尾，见 `helper/sessions.go` 的 summarize），也做设置里存储页的统计和清理（`storage`、`clean`，删除规矩见 `helper/storage.go` 开头）、子代理查看页的增量读取（`subagent`）、文件浏览和共享文件夹的检查。构建时（Gradle 任务 `zetaHelper`）编成 linux 的 arm64、amd64 两份打进 assets；应用第一次用时按那边 `uname -m` 把对应的一份经标准输入装到 `~/.cache/<ZETA_NAME>/bin/zeta-helper-<版本>`，之后直接执行，版本是两份程序的哈希（`core/HelperProgram.kt`）。只改它时 `cd helper && go test ./...`；Kotlin 的测试在临时 HOME 上跑本机架构的那一份（`TestHelper`）。
- `assets/`：`zeta_host.sh` 起 Claude 进程；`zeta_setup_*.sh` 安装 Debian 环境与可选工具；`zeta_ssh_android.sh` 管 SSH 服务，`zeta_enter.sh` 是它写到手机上、以 root 进 Debian 的入口；`zeta_accounts.sh` 替账号页跑 claude-switch（开源的 Go 程序 `github.com/dzshzx/claude-switch`，是 `helper/go.mod` 里 tool 一行的模块：Gradle 任务 `claudeSwitch` 照 zeta-helper 编成两份打进 assets，第一次用时同样经 `HelperProgram` 装到 `~/.cache/<ZETA_NAME>/bin/claude-switch-<版本>`；它链接的模块列在 `helper/go-tools-allowlist.txt`，不算 zeta-helper 的依赖）。

## 两端开发

- 工作站（x86-64 的 Linux）是主开发处：改动在任务树里做，用 `wt land` 跑门禁并合入 master。
- 手机 Debian 里也可以留一份仓，用来临时开发和实机测试，能编、能装、能测。开工前先 `git pull`。要留下的改动提交到分支、推到 GitHub，再在工作站上用 `wt land` 合入；手机上没有 `wt`，不直接推 master。
- 两边都只经 GitHub 的 `origin` 同步。
- 工作站进手机：`ssh <手机 Debian 的 ssh 别名>` 进 Debian（别名见「本机配置」的 `zeta.phoneSsh`），`sudo nsenter -t 1 -m -- /system/bin/sh -c '<命令>'` 以 root 进 Android 一侧。`/workspace` 是 Android 的 `/data/local/zeta/workspace` 挂进来的；从 chroot 里看不到 `/data/local/zeta`，要经 `nsenter` 看。

## 本机配置

`tools/` 下的脚本要知道的几项本机设置：先看环境变量，再看本仓的 `git config zeta.<键>`（写在 `.git/config` 里，不进提交，各个 worktree 共用，设一次就行），都没有就用默认值。读法在 `tools/config.sh`（`vd.py` 里同样的一份是 `setting`）。

| 环境变量 | git config | 用途 | 默认 |
|---|---|---|---|
| `ZETA_PHONE_SSH` | `zeta.phoneSsh` | 工作站上连手机 Debian 的 ssh 别名（`ZETA_DEVICE=phone`、`--release` 时要） | 无：要用时没配就报错 |
| `ZETA_AVD` | `zeta.avd` | `tools/emu` 开的 AVD | 本机只有一个 AVD 时就用它 |
| `ZETA_EMU_DISPLAY` | `zeta.emuDisplay` | 模拟器窗口开在哪个 X 显示上（比如 xpra 的虚拟显示） | 不开窗口（`-no-window`） |
| `ZETA_LINUX_USER` | `zeta.linuxUser` | 设备 Debian 里 Zeta 用的 Linux 用户：模拟器上进 Debian、设备脚本拼家目录和 ssh 目标时用（`tools/phone-run` 导出给脚本） | `zeta`（装环境时的默认用户名） |
| `LATENCY_ALIAS` | `zeta.latencyAlias` | `remote-latency.sh` 在手机上量延迟时连的主机（手机 Debian 里的 ssh 别名） | 无 |

Android SDK 取 `ANDROID_HOME`，默认 `~/Android/Sdk`。每次调用时才设的（`ZETA_DEVICE`、`ZETA_PHONE_OWNER`、`ZETA_R8`、`ZETA_TEST_SSH` 等）见下面各条。

## 构建与测试

- 构建：`tools/gradle <任务>`，例如 `tools/gradle :app:assembleDebug`；不直接跑 `./gradlew`。它用 `tools/java-home` 找 JDK 25（`JAVA_HOME`，再是 PATH 上的 `java`，最后是 `~/.gradle/jdks` 里 Gradle 下载过的那份），并设 `ANDROID_HOME=~/Android/Sdk`。缺的 SDK 平台和 build-tools 由 AGP 构建时自动下载（先用 `sdkmanager --licenses` 接受许可）。
- 在手机 Debian（arm64）里构建时，为内存和 CPU 架构调的 Gradle 参数写在手机的 `~/.gradle/gradle.properties`，不进仓：不开守护进程、Kotlin 在进程内编译、最多 4 个 worker、aapt2 用 `~/Android/aapt2-x86_64/aapt2`（经 qemu-user 跑官方 x86-64 版，Google 不提供 linux-arm64 版）。这个 aapt2 的版本要和 AGP 一致，升 AGP 时一起换。手机空闲内存常只有几百 MB 到 3 GB，同一时间只跑一个 Gradle。
- 签名：测试版和 debug 包用调试密钥 `~/.android/zeta-debug.keystore`（证书 `CN=Android Debug`）签名，手机上已装的测试版就是它签的。这个文件只在维护者的机器上（原件的保管处见 `CLAUDE.local.md`）。这个文件不在时（CI 上，它不进 CI）用 AGP 自己生成的默认调试密钥，那样的包装不上已装的测试版。正式版不在本机签名：release 构建出的是未签名包，由 release.yml 用正式发版密钥（GitHub environment `release` 的 Secrets）加签名 lineage `release/lineage.bin` 只签 APK 签名方案 v3。v0.7.0 及更早的正式版是调试密钥签的，lineage 让它们直接覆盖升级到新签名。签名对不上时 `pm install -r` 会失败，卸载重装会丢掉 Zeta 的全部设置，所以不卸载；签名做法和生成步骤见 `docs/release.md`。
- 门禁：`bash scripts/check.sh`。它核对 Python 清单，确认 Python 都能解析，检查 Kotlin（`scripts/format.sh --check` 与 `--lint`：ktfmt 的 kotlinlang 风格排版，detekt 量的函数认知复杂度不超过 15，文件不超过 2000 行，不认 `@Suppress` 豁免；就地排版用 `JAVA_HOME=$(tools/java-home) scripts/format.sh`），检查 `helper/` 的 Go（版本、gofmt、vet、staticcheck、测试、认知复杂度、第三方模块只许 `helper/go-deps-allowlist.txt` 里列的），再构建 debug 包、跑 JVM 单元测试（含截图测试）。构建要有 PATH 上的 `go`（版本见 `helper/go.mod`）。第三方许可证：构建时 licensee 从运行时依赖生成清单打进 assets，许可证不在 `app/build.gradle.kts` 的允许名单里就构建失败；加、升依赖或改 `helper/go.mod` 后 `ThirdPartyTest` 会要求补 `core/about/ThirdParty.kt` 的条目、重新生成 `THIRD_PARTY.md`。
- 单元测试：`tools/gradle :app:testDebugUnitTest`，放在 `app/src/test/kotlin`。不碰 Android 运行时的逻辑（解析、路径、状态归约）都写成单元测试，在工作站上就能验，不用装机。`ClaudeConfigClientTest` 在临时 HOME 上端到端跑 `core/claude` 的脚本和 `claude` 命令行；加 `ZETA_TEST_SSH=<手机 Debian 的 ssh 别名>`（和 `--rerun`）时经 ssh 在手机 Debian 里用临时 HOME 再跑一遍。
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
- 测试设备：测试版默认装在本机的 Android 模拟器上，只有必须真机才能验的才上手机。模拟器用 Android 15 的 x86_64 系统镜像（google_apis，GKI 内核 android15-6.6，带 arm64 转译），里面照手机装好 root（见下一条）、Zeta 的 Debian、Linux 用户和 Claude Code。`tools/install.sh`、`tools/vd.py`、`tools/phone-run` 默认连它，没开就用 `tools/emu start` 开机（开哪个 AVD、窗口开在哪个显示上见「本机配置」；联网经宿主的代理，模拟器不读代理环境变量，由它用 `-http-proxy` 传进去）；设 `ZETA_DEVICE=phone` 才连手机，正式版只在手机上。
  - 模拟器的 root 用 SukiSU Ultra（KernelSU 的分支）的内核模块（LKM）模式，管理器 APK 带 x86_64 的 ksud 和 `android15-6.6` 的模块。SDK 的 `system-images/android-35/google_apis/x86_64/ramdisk.img` 用 `ksud boot-patch --ramdisk` 打补丁，原件留在旁边的 `ramdisk.img.stock`；SDK 更新这个系统镜像后要照样重打。在模拟器的 SukiSU 里给「Zeta 测试」开一次超级用户，之后重装测试版不用再授权。模拟器里的 Claude Code 没登录账号时，假 Claude 的测试照跑，要真 Claude 跑一轮的上手机（`ZETA_DEVICE=phone`）。
  - 必须上手机的：HyperOS 和小米专有的（电源键助理与 LSPosed 模块、小米的语音识别服务、GreezeManager 冻结）、Android 16 才有的（实时通知）、KernelSU 的授权流程、真机上的性能、耗电和休眠、MiSans 字体和毛玻璃的最终效果，以及上线后的正式版。
  - 模拟器可以和别的项目共用：别的项目用别的代理（比如抓包用的 `-http-proxy`）开着它时 `tools/emu` 会提示，这时 Zeta 联网都经那个代理；用假 Claude 不联网的测试照跑，要联网就 `tools/emu stop` 后重开。
- 并行开发：几棵任务树同时开发时，`tools/gradle` 最多同时跑 2 个构建（`ZETA_GRADLE_SLOTS`；一次门禁构建内存峰值约 7G），其余排队。在 agent 会话里时，Gradle 守护进程（连同它派生的测试进程）和 `tools/emu` 开的模拟器各自单独一个 systemd 分组（`tools/own-scope.sh`：守护进程每个 10G、模拟器 8G 的内存上限），不记在启动它们的会话头上，免得一个会话撞上内存上限时别人的构建和共用的模拟器跟着卡住或被杀；查用量用 `systemctl --user status 'zeta-gradle-*' 'zeta-emu-*'`。每台设备上同一个包同时只能装一个版本，所以按设备和包发租约（模拟器上的测试版、手机上的测试版、手机上的正式版各一把）：`tools/phone-run` 排队拿租约（见下面「设备脚本」），脚本里的 `vd` 在它的租约下跑；主会话在 phone-run 之外直接用 `tools/install.sh`、`tools/vd.py` 时，它们每次碰设备前自己经 `tools/phone-lock` 拿租约，被别人占着就失败（退出码 75），15 分钟没动静自动作废，测完用 `tools/phone-lock --release` 释放。占用者名字取 `ZETA_PHONE_OWNER`（默认 `main`），不是 `main` 的（子代理）在 phone-run 之外碰设备会被这两个工具拒绝。设备是并行开发的瓶颈：单元测试、临时 HOME、契约测试、截图测试能验的不占设备。
- 测试版：用户日常用的是正式版 `com.dzshzx.zeta`，是 build type `release`（`assembleRelease`）：经 R8 去掉用不到的代码和资源（约 13 MB，不经 R8 时 56 MB），不改类名，不可调试，只在发版时由 `release.yml` 构建并签名。开发测试装测试版：包名 `com.dzshzx.zeta.dev`，桌面名「Zeta 测试」，橙色图标，是 build type `dev`（`tools/gradle :app:assembleDev`，代码和签名同 debug，不经 R8）。`debug` 包名同正式版、不经 R8，只给门禁和单元测试用，不装到设备上。任务分支的提交数可能比手机上刚装的少，`tools/install.sh` 用 `pm install -d` 照装。手机上两个包同时装着，互不替换。每台设备上测试版只有一份，设备上的测试交脚本给 `tools/phone-run` 排队执行。
  - 子代理和开发测试一律装测试版、在测试版上测。正式版只在上线时由主会话装（见「发版与上线」）：`tools/install.sh`、`tools/vd.py` 默认就是测试版，碰正式版要显式加 `--release`，`ZETA_PHONE_OWNER` 不是 `main` 时会被拒绝。
  - 手机上的测试版第一次装上（或卸载后重装）时没有 root，要用户自己在 KernelSU 管理器里给「Zeta 测试」授予 root，代理不能替用户授予；没 root 时它只显示没有 root。有了 root 以后，无障碍、通知、电池、后台弹出界面等在测试版自己的权限健康页一键授予。
  - 两个包共用同一个 Debian 和 Linux 用户：Claude Code 的会话、登录状态、配置是同一份，测试版里建的会话也会出现在正式版的列表里，照样测完就删。Zeta 自己在 Linux 里的目录按包分开（正式版 `~/.cache/zeta`、`~/.config/zeta`，测试版 `zeta-dev`，由环境变量 `ZETA_NAME` 告诉脚本，见 `AppVariant`），所以两个包不会接管、结束或清理对方的后台对话和附件；正在被另一个包跑着的会话在这边算作别处占用。设置、定时任务、通知渠道、无障碍服务也各是各的。包名相关的名字（无障碍组件、Provider authority、广播 action）都从 `AppVariant` 取，不写死 `com.dzshzx.zeta`。
  - 测试版的通知标题前注明「测试版」，完成与确认提醒不出声；和正式版一样，没有对话在跑时不常驻通知。测完不让它在后台常驻：`vd.py stop` 会 force-stop 它，并撤销它的通知监听、改回 `prefs-set` 改过的设置。
  - `am force-stop <包名>` 会让系统把那个包的无障碍服务从启用列表里拿掉（AOSP `onPackagesForceStoppedLocked`），所以不对正式版 force-stop，`vd.py stop` 对正式版也只关屏。测试版被 force-stop 后，下次用手机工具时有 root 就自己加回去，也可以在权限健康页一键授予。
- 装机：`tools/install.sh [--no-build]` 装测试版（默认装到模拟器，`ZETA_DEVICE=phone` 时装到手机）；`--release` 往手机上装正式版，只用于上线。工作站上连模拟器经 adb、连手机经 ssh，在手机 Debian 里直接装。重装会结束这个包的界面，但不结束进行中的对话。
- 设备脚本：`ZETA_PHONE_OWNER=<任务名> [ZETA_DEVICE=phone] tools/phone-run [--no-build] <脚本>`，放到后台跑（可能要排队）。它在排队之外构建测试版、开模拟器，再按先来先到排队（`tools/phone-lock` 发号，同一个任务的两个 phone-run 也一个一个来）；轮到后装包，在后台虚拟屏上冷启动 Zeta、把它的「主屏」指到这块测试屏并等 10 秒，限时跑脚本（`ZETA_PHONE_RUN_TIMEOUT`，默认也是最多 300 秒）；收尾时截 `final.png`，取本包的崩溃日志 `crash.txt`，用 `vd.py stop` 收拾测试版，释放租约。脚本按 bash 跑，当前目录是产物目录 `build/phone-run/<时间>`，里面能用 `vd <命令>`（即下一条的 `vd.py`）、`android '<命令>'`（即 `vd.py sh`，在 Android 一侧以 root 跑）和 `$PKG`、`$DISPLAY_ID`、`$ZETA_ROOT`（这棵树的根）。看完产物再改脚本重跑，想下一步的时候不占设备。一个脚本里最多等 Claude 真跑一轮，要验的几样合进同一个脚本，没改东西不重跑；验得多就拆成几个脚本，别人能插在中间。退出码：脚本自己的，124 是超时，75 是排队超时。要留作回归的脚本放 `tools/phone-tests/`（两种设备通用），样例是 `smoke.sh`；往输入栏发字、等假 Claude 一轮的脚本先 source `tools/phone-tests/lib.sh`（`send`、`find_log`、`wait_result`、`HOSTS`、输入栏坐标）。
  - 远程主机：把设备自己的 Debian 当远程主机（127.0.0.1:8022 的 sshd，家目录相同，托管目录和假 Claude 的剧本都在老地方），走一轮、掐断连接、重连后再走一轮。`tools/phone-tests/remote-host.sh` 经 Debian 的 OpenSSH（`ssh://<Linux 用户>@127.0.0.1:8022`，要这个用户的公钥在自己的 `authorized_keys` 里）；`remote-direct.sh` 把测试版的 Linux 环境指到不存在的地方，Zeta 直连（测试版的公钥由脚本加进去）；`remote-network-change.sh` 经 Debian 连，中间开关一次飞行模式代替掐断，核对 Zeta 自己关掉了 ssh 主连接（只在模拟器上跑）。`remote-latency.sh` 量每条命令要多久：手机本机、经 Debian、直连三条路都走测试版平常跑命令的代码（`core/diag/LatencyBench`，只在测试版的 manifest 里登记，脚本用 root 发广播叫它），复用连接（登录 shell 和不登录的各一组）和重新握手分开记；手机上连 Debian 里的一个 ssh 别名（`LATENCY_ALIAS`，见「本机配置」），直连用它解析出的同一个地址，测试版的公钥要已经在那台主机上；测完在手机 Debian 里用 tailscale ping 记一组网络往返和路径。`NETWORK=cellular` 时先关 Wi-Fi 走蜂窝、测完开回（只在手机上；手机上另起一个 10 分钟后开回 Wi-Fi 的兜底，收尾删掉它认的标记），用户正用手机（比如看视频）时别跑：负载会混进数字，流量也走蜂窝。sshd 要先在 Debian 里装好（设置 › Linux 环境 › SSH 服务），没在跑时脚本自己起。单元测试里直连对着 JVM 里的 SSH 服务器（`TestSshServer`，Apache MINA SSHD，中间的中转能掐断连接），断线重连的用例两种连法各跑一遍（`RemoteLinkContract`）。
  - 流式输出的开销：`tools/phone-tests/stream-cpu.sh`。只用来比改动前后：同一台设备上交替各跑几次，要量正式版的样子加 `ZETA_R8=1`；模拟器的 RenderThread 被 GPU 模拟放大、帧率卡在 25 帧每秒上下，帧数看手机。
  - 假 Claude：只看界面、状态胶囊、通知、悬浮层、结果卡片、手机工具调完后的样子，不在乎模型说什么时，脚本里先 `vd fake <剧本>`，测试版之后起的对话改由 `tools/fake-claude/fake-claude.sh` 照剧本回话，一轮几秒、每次一样；`vd stop`（phone-run 收尾会做）删掉剧本，测试版回到真的 claude，正式版永远是真的。剧本一行一轮，文字、思考、经 Zeta 真调一次手机工具、等批准、停顿、出错都写得出，被打断时照真 Claude Code 收尾，写法见脚本开头；样例是 `tools/phone-tests/fake/` 和用它们的回归 `tools/phone-tests/fake-claude.sh`。它不写会话记录，没有要删的测试会话。
  - 还得真跑的：Claude Code 自己的行为（插话并进当前这一轮、后台任务和子代理、压缩、权限规则、会话记录与续接、分支和改写）、真模型会不会用对工具，以及发版前的真实链路验收。假 Claude 和真 Claude Code 的输出由 `FakeClaudeContractTest` 逐条比对（真 claude 跑在临时 HOME 上连假 API），升级 Claude Code 后跑一遍，对不上就改脚本。
- 测试：用 `python3 tools/vd.py start|launch|shot <png>|tap x y|swipe x1 y1 x2 y2 [毫秒]|text <ASCII>|key <键码>|prefs <键名…>|prefs-set <键[:int]=值…>|fake <剧本>|debian [--stdin] <命令>|status|stop` 在后台虚拟屏上驱动测试版，不碰用户的真屏。测试版和正式版各有自己的虚拟屏，带 `--release` 才是正式版那块。连手机时每条命令走一次 ssh，约 0.4 秒；连模拟器走 adb。坐标按整屏 1220×2656 算（模拟器上的虚拟屏也按这个尺寸建）；截图显示给 Claude 时缩小过，要按显示比例换算回去。`stop` 会关掉屏上的应用（建屏带 DESTROY_CONTENT_ON_REMOVAL），不会把它们挪到真屏上；测试版还会被 force-stop。
  - 冷启动后等约 10 秒再输入，否则字会丢。`vd.py text` 只能输 ASCII，要中文时改用包名之类。打字很慢、丢字或顺序乱时，看 logcat 有没有 `Excessive delay in interceptKeyBeforeQueueing` 和 `Timed out waiting for animations`：主屏上有窗口动画卡住时，注入的每个按键都要等 5 秒再被当成过期事件丢掉；`am force-stop` 那个主屏上的应用就好（模拟器上见过 SukiSU 管理器这样）。
  - 要把 Zeta 放到后台，就在虚拟屏上开另一个 activity，例如 `am start --display <屏号> -n com.android.traceur/.MainActivity`。Claude 在虚拟屏上开了别的应用、Zeta 被压到后面时，用 `vd.py launch` 拉回来。自己用 `am start` 开 Zeta 或别的页面时一律带 `--display <屏号>`，不带就开在用户的主屏上；测试留下的任务记录由 `vd.py stop` 从用户的「最近任务」里清掉。
  - 截图一片白时，先查 `dumpsys activity activities`，不要先当成崩溃。多半是两种情况：用户自己打开了 Zeta，任务挪到了主屏，这时等用户，不要抢回来；或者 MIUI 的 GreezeManager 冻结了 Zeta（`reason=tobg`），`vd.py launch` 能解冻。用户的「一键清理」也会杀掉虚拟屏上的 Zeta，先查事件日志。
  - 下面的路径和组件名写的是 `<包名>`：测试版是 `com.dzshzx.zeta.dev`，正式版是 `com.dzshzx.zeta`。类名不随包名变，组件要写全，例如 `com.dzshzx.zeta.dev/com.dzshzx.zeta.ui.MainActivity`。
  - 测手机工具和悬浮层时不碰真屏：Zeta 的「主屏」指到测试屏上。`tools/phone-run` 每次都替脚本做好（`vd.py prefs-set phoneDisplay:int=<屏号>`，再 `vd.py launch`；写不上就不跑），测试里的 Claude 用手机工具也碰不到用户的主屏。手动用 vd.py 时照这个顺序自己做；`vd.py stop` 会删掉这一项。
  - 测试会改 Zeta 的设置（比如 cwd、dark），由 `vd stop` 改回去（见下一条）。只删测试时建的会话，别的会话是用户的（用户自己的工作目录见 `CLAUDE.local.md`）。
  - Zeta 的设置用 `vd.py prefs <键名…>` 读，或用 root 直接读 `/data/user/0/<包名>/shared_prefs/zeta.xml`（`run-as` 不可用）。只 grep 键名，不要整份 cat，`commands` 一项很大。测试要临时改设置用 `vd.py prefs-set`（先 force-stop、原地改写，属主和 SELinux 标签不变），每个键第一次改时记下原值，`vd stop`（phone-run 收尾）改回去，脚本不自己恢复。
  - 不经文件选择器给 Zeta 喂文件：`am start --display <屏号> -n <包名>/com.dzshzx.zeta.ui.MainActivity -a android.intent.action.SEND -t <mime> --eu android.intent.extra.STREAM file:///data/user/0/<包名>/files/<名字>`。通知栏的「停止」可用 root 触发：`am startservice -n <包名>/com.dzshzx.zeta.core.AgentService -a <包名>.action.STOP`。
  - Claude Code 会拦下前台的 `sleep N`，长时间的测试用自己会耗时的脚本。

## 发版与上线

一件事一个提交，合入不改版本号。版本照 SemVer 和 setuptools-scm 的通行写法从 git 推出来（`app/build.gradle.kts`），不手写：

- versionCode 是 HEAD 的提交数，每合入一次自动加一。同一个提交编出的测试版和正式版相同，设置页「关于」和诊断页的「版本名（versionCode）」一对就知道是哪个提交。
- 正式版在发版时打的 tag（`v0.7.1`）上，版本名就是 `0.7.1`。
- 别的提交是下一个版本的预发布：`0.7.2-dev.3+gabc1234` 是 0.7.1 之后第 3 个提交、正往 0.7.2 走、提交号 abc1234。按 SemVer 它排在 0.7.1 之后、0.7.2 之前，`+` 后面的构建元数据不参与比大小。测试版装的都是这种。
- tag 只标正式版，由 release.yml 经 GitHub API 在发版的提交上打附注 tag，本机不打；推上去后不移动、不删除、不复用。每个 tag 对应一个 GitHub Release，资产是签好的 `Zeta-vX.Y.Z.apk`、它的 `.sha256` 和应用内更新用的 `update.json`（格式见 `docs/release.md`）。

合入：功能分支交回前自己变基到最新的 origin/master、解决冲突、压成一个提交。负责合入的会话先用 `tools/phone-run tools/phone-tests/smoke.sh`（和 `tools/phone-tests/` 下与这次改动有关的脚本）在测试版上过一遍，再用 `wt land` 合入。合入不发版、不装正式版。

发版指在 GitHub 上把 master 的最新提交做成 Release：手动触发 `.github/workflows/release.yml`，先 `gh workflow run release.yml --ref master -f dry_run=true`，看过运行摘要（版本、源提交、资产的 sha256、Release 说明）再 `gh workflow run release.yml --ref master -f bump=patch`；用户说升 minor 时才用 `-f bump=minor`。它算版本（这个提交已发过版就失败）、门禁（wt land 合入时已过门禁的跳过；本机 runner 不在线时加 `-f runner=github-hosted`）、在本机 runner 上构建、在 GitHub 托管机上签名（签名要读发版密钥，本机 runner 的 job 不带密钥）、核对、发布。失败了修好后用同一条命令重跑，能续上，不手工删 tag 或草稿。步骤、签名、Secrets 和出错后怎么办见 `docs/release.md`。

上线指把正式版装成最新发布的版本。正式版是用户日常在用的，开发再快它也要稳定，所以合入的版本先只在测试版上，用户在 Zeta 的语境里说「上线」时才做：master 上有没发版的提交就先发版，再在主 checkout 上跑 `tools/install.sh --release`（装指定版本时加 tag，如 `tools/install.sh --release v0.7.1`）。它用 `gh release download` 取 Release 的 APK，核对 sha256、签名证书（`release/signing-certs.txt` 登记的）、包名和版本后装到手机，不在本机构建。装完在正式版的测试屏上验一遍（`vd.py --release` 带 start、launch、shot，验完 stop）。重装会结束正式版的界面，进行中的对话照样跑完，但正在执行的手机工具调用会失败。

正式版自己也检查更新（设置「关于」，回到前台时每天最多一次）：读最新 Release 的 `update.json`，核对后经 root 的 `pm install -r` 或系统的安装界面装，有对话在回答时等它结束。只有正式发版证书签的包才查；做法和在模拟器上用测试版试的办法见 `docs/release.md`「应用内更新」。

## 边界

- 手机上不替用户在 KernelSU 管理器里给 Zeta 授予 root。
- 改 `settings put secure enabled_accessibility_services` 时只追加 Zeta 的组件，列表里别的应用的服务一个都不能丢。
- 操作手机界面一律在后台虚拟屏上做，不占主屏前台。只读的核对走文件或 `dumpsys`，不走界面。
- 开发时不改用户真实的 `~/.claude`（CLAUDE.md、记忆、skills、agents、MCP 配置）。要试 `claude` CLI 的行为，就用临时 `HOME`。MCP 配置里有真实 token，不打印。
- 不盲调 `service call`：`service call notification 1` 曾清掉所有用户的通知。

## 坑

- Linux 里的 `/workspace` 是 Android 的 `/data/local/zeta/workspace`（`LinuxEnv.WORKSPACE`）绑过去的，是用户自己的文件，正式版和测试版共用；它在 rootfs 外面，换环境、重装环境都不动它。Zeta 自己要建的目录放进 rootfs 里（共享文件夹挂在 `/mnt/shared`），不放在 `/workspace` 下。Zeta 不碰 Eta 的 `/data/local/tmp/eta`（别人设备上那可能是 Eta 正在用的工作区）；从前 `/workspace` 绑它的设备用 `tools/migrate-workspace.sh` 迁一次：原地改名到新目录，旧位置留一个指向新目录的符号链接，没升级的旧版本照样绑到同一份文件。
- COUI 对话框按钮栏的 `neutral` 一律画成红色的危险按钮，三个按钮还会竖排挤掉内容；不危险的次要操作放菜单，或做成对话框内容里的链接。
- app 不能给它的 `su` 子进程发信号（uid 不同），要经 root 杀（`LinuxEnv.kill`）。
- Android 上 `Process.waitFor(超时)` 是 java.lang.Process 的默认实现，每 100 毫秒才看一次进程结束没有，每条命令平均白等约 50 毫秒；等进程用 `Process.exitsWithin`（`core/ProcessWait.kt`，`ProcessWaitTest` 拦着应用代码里带超时的 `waitFor`）。
- Compose 在界面停止后只暂停动画帧，重组和测量照做：随回答流式输出频繁变的状态在整页读时经 `whileStarted`（`ui/components/Remembered.kt`），界面看不见时不跟着变。
- `zeta_host.sh` 的看门狗在 `setsid bash -c '…'` 里面，那一段里不能写单引号。
- 正式版经 R8，测试版和单元测试不经：运行时才按类名找的类（`app_process` 起的辅助进程、LSPosed 的入口、JSch 按配置加载的算法、别的反射）R8 看不出有人用，会被去掉，只有正式版出错。新增这类入口时登记在 `app/proguard-rules.pro`；改了它、加了这类入口或升级依赖后，用 `ZETA_R8=1 tools/phone-run …` 让测试版照正式版那样经 R8 构建，跑相关的设备脚本。它不可调试：在手机上装过以后，用 `ZETA_DEVICE=phone tools/install.sh` 装回普通测试版，否则之后提交数更少的测试版在手机上装不上（`pm install -d` 降版本只对已装的可调试包放行；模拟器不受限）。
- Kotlin：在 `VirtualDisplay.() -> Unit` 这类带接收者的 lambda 里裸写 `surface`，会解析成 `VirtualDisplay.getSurface()`，不是外层的字段。
- Claude Code 的 stream-json（2.1.327 实测）：
  - 标准输入的每条用户消息可带 `uuid`，它就是这条消息在会话记录里的 uuid；标准输出有 `command_lifecycle`（queued / started / completed / cancelled）。
  - 控制请求 `rewind_conversation` 把会话剪到一条用户消息之前（`target_message_uuid`），会话号不变，第一条也能剪。要带 `last_seen_user_message_uuid`（界面认得的最后一条用户消息），否则目标后面还有轮次时拒绝（`stale_target`）。剪不成也答 success，看 `rewound` 和 `reason`。记录文件只追加一行 `last-prompt`（`explicit`、`rewound`、`cutUuid`），被剪的内容还在文件里，读历史时要照这一行去掉。
  - `--resume <id> --fork-session` 的新会话在第一条消息时才写出记录，`--name` 给它定标题。
  - 接着旧会话（`--resume`，含 `--fork-session`）不带 `--model` 时用会话记录里的模型，中间新开的会话不受影响；`--model default` 是内置的默认模型（盖过 settings.json 的 model 和会话记录）。思考强度不恢复。记录里的模型下线了也照用，API 拒绝后这一轮以出错结束，set_model 换一个就好（2.1.328，`ClaudeResumeContractTest` 钉住）。所以 Zeta 接旧会话不带 `--model`（除非用户在这个对话里选过），顶栏显示 init 报的模型；思考强度照会话记录自己恢复。隐藏参数 `--resume-session-at <uuid>` 也能截断，但不留剪切记号，按文件顺序读会把旧内容一起读出来，Zeta 不用它。
  - 权限模式（2.1.329，`UnattendedRulesContractTest` 钉住）：`set_permission_mode` 不等应答、紧接着发消息，这一轮就按新模式跑，每轮开头的 init 报的就是它。进程起的时候不带 `--allow-dangerously-skip-permissions` 就切不进 `bypassPermissions`（答 error，`bypass_not_launched`），所以 Zeta 起进程都带它；它本身不放行任何东西，实测带不带它发给模型的内容一样；模式怎么切，发给模型的工具名单和系统提示词也不变。系统提示词开头的计费标记跟着会话的第一条消息变，跨进程比对提示词时第一条要相同。
  - 默认值（2.1.329，同一个测试钉住）：不带 `--permission-mode`、`--model` 起的进程用 settings.json 的 `permissions.defaultMode`、`model`，initialize 的应答和 init 照实报。控制请求 `update_settings`（source `userSettings`，和 `/effort` 存为默认一样）把思考强度写进 `modelSettings.<模型的完整 ID>.effortLevel`。Zeta 新对话起进程都不带这三样，设置里各主机的页面读写的就是那台 settings.json 的这几个键，所以每台主机的默认值各是各的；对话里选的只管那个对话，进程活多久它就管多久（接回进程时照 `hostModes` 恢复用户的模式）。
  - 会话记录的 `parentUuid` 不是一条直链：并行的工具调用各挂各的，从最后一行顺着往回走会漏掉步骤。
  - 结束会话（2.1.329，`ClaudeCodeContractTest` 钉住）：-p 模式不认 `/exit`（答“isn't available in this environment”），对应的是控制请求 `end_session`：答 success，进行中的这一轮按打断收尾，SessionEnd hook 照跑（reason 是 `other`），输入管道开着也自己退出（闲着时退出码 0，停在半路是 1）。Zeta 结束进程（`zeta_host.sh` 的 stop 和看门狗）都经它的 `end_claude` 发它，不退再 SIGTERM、SIGKILL；stop 的等待在单独的会话里，Zeta 被杀也做完。
  - 一轮进行中发来的消息也有自己的 command_lifecycle started，会在下一个工具边界并入这一轮，两条消息只出一个 `result`（2.1.329，`ClaudeCodeContractTest` 钉住；Zeta 的排队条和一轮的结束靠它）。`interrupt` 的回应会列出 `still_queued`；`cancel_async_message` 能撤回排队中的消息。
  - `result` 行的键序不固定，不一定以 `"type"` 开头；`result` 之后还会有 command_lifecycle、task_notification、background_tasks_changed 行。判断一轮的起止看最后一个开始或结束事件，不看最后一行。
  - 后台任务做完时，Claude Code 会自己起一轮：一条 `system init`，前面没有用户消息。Zeta 把空闲时收到的 init 当成新一轮开始。
  - 后台任务（2.1.328，`ClaudeTaskContractTest` 钉住）：`background_tasks_changed` 是在跑的完整清单，先于 `task_started` 到；结局是 `task_updated`（patch.status）和 `task_notification`。控制请求 `get_task_output` 读后台命令、监视器输出的最后 8 KiB（子代理答 error）；`stop_task` 对不认识的 id 也答 success，停没停看随后的事件。停后台命令不另起一轮，子代理结束（做完或被停）后会。Monitor 的 task_type 也是 `local_bash`，靠发起它的工具调用区分。
  - 对齐终端版的交互（2.1.330，`ClaudeInteractionContractTest` 钉住）：发给 Zeta 的请求只有 can_use_tool（工具确认、ExitPlanMode、AskUserQuestion）和 elicitation（MCP 服务器的表单或网址），request_user_dialog、hook_callback 要客户端声明才发，Zeta 不声明。主对话和后台子代理的确认会同时挂着（子代理的带 `agent_id`），重新 initialize 的应答里 `pending_permission_requests` 列出全部没答的确认，不含 elicitation。“以后不再问”把 `permission_suggestions` 原样交回 `updatedPermissions`；拒绝时带 `interrupt` 是终端里按 Esc（这一轮以 aborted_tools 停下），不带时 `message` 原样交给模型；批准计划时 `updatedPermissions` 的 setMode 就是之后的模式。计划写进计划文件后 ExitPlanMode 的 input 才带 `plan`。`{"type":"bash_command","uuid","command"}` 是终端的 `!`：直接执行，回放 `<bash-input>`、`<bash-stdout>` 两行用户消息，只有 command_lifecycle，没有 result，不请求模型。文件快照要起进程时设 `CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING=1`，`rewind_files`（`user_message_id`、`dry_run`）还原到那条消息之前，换个进程 `--resume` 照样能用。
  - 子代理（2.1.328，同一个测试钉住）：Agent 默认放后台，前台要 `run_in_background: false`。记录在 `<项目>/<会话 id>/subagents/agent-<id>.jsonl`，id 就是 `task_started` 的 task_id，也在 Agent 结果的 `tool_use_result.agentId` 里；旁边的 `agent-<id>.meta.json` 记着发起它的调用（`toolUseId`），做完加 `finished`，被停加 `stoppedByUser`。输出流里子代理内部的事件带 `parent_tool_use_id`，但前台子代理只送工具调用和结果、不送它说的话，所以子代理查看页以记录为准（`zeta-helper subagent` 按偏移增量读）。结局（`finished`、`task_notification`）可能比最后一句报告先写下，`finished` 也可能比 `task_notification` 晚到，先后都不一定：结局以 `task_notification` 为准，结束后要等记录停下来再停止读。
