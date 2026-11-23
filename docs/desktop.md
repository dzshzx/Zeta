# Windows 桌面版（Go + mygo）

`windows/` 是用 Go 和 mygo 原生界面写的 Windows 客户端，要做成全功能的桌面版：手机上和远程主机有关的功能它都有，手机专有的（手机工具、悬浮层、语音、Debian 环境、数字助理、定时任务、通知触发、存储与备份）不做。它是 Go 核心（`docs/core.md`）的第 3 步，随正式版一起发 MSI。对话的逻辑在主机上的 serve 里（第 2 步），桌面版只收发 serve 的协议、画界面，不另写一份对话状态。

## 和手机版的约定

- 只连远程主机，用 Zeta 自带的 SSH 直连（`golang.org/x/crypto/ssh`）。认证照这台电脑的 OpenSSH 配置：地址可以是 `~/.ssh/config` 里的别名，交给 `ssh -G` 算出主机、用户、端口和私钥；每台设备能另选 `~/.ssh` 里一把私钥（旁边有 `.pub` 的）。带口令的私钥、ProxyJump、ProxyCommand 不支持，遇到明说。主机指纹在加设备时记下，之后只认它。x/crypto/ssh 没有 zlib 压缩，连接不压缩。
- 设置存在 `%APPDATA%\Zeta` 下的 JSON 文件（设置 `zeta.json`，输入草稿 `zeta_drafts.json`）：每项 `{"t":类型,"v":值}`，类型是 `s`、`i`、`l`、`f`、`b`、`set`（字符串、32 位和 64 位整数、小数、真假、字符串数组），认不得的项原样留着；键和取值同手机版的设置（`shared/…/core/Settings.kt`），格式写在 `internal/settings` 的包注释里。
- 安装包是 MSI，在 Linux 上用 wixl 打（`installer/build.sh`，release.yml 的 desktop 任务跑它）：只装一个 `Zeta.exe`，按用户装到 `%LOCALAPPDATA%\Programs\Zeta`；`UpgradeCode` 各版本共用、不能改，带 `REINSTALLMODE=amus`，所以装新版直接替换旧版（`installer/installer_test.go` 钉着）。随正式版一起发，`update.json` 的 `msi`、`msiUrl`、`msiSize`、`msiSha256` 四项给它的自动更新读，手机不读。自动更新的做法（谁查、下载核对、PowerShell 脚本怎么起）见 `docs/release.md`「应用内更新」的桌面版一节。
- 在主机上和手机版用同一个包：正式版是 `zeta`，和手机正式版同包；测试版（不是干净 X.Y.Z 的构建）是 `zeta-dev`，和手机测试版同包（见 `AppVariant.linuxName`、`internal/host` 的 `LinuxName`）。同一个包在一台主机上只有一份 zeta-helper 和一个核心，同一个会话可以电脑和手机同时接着、看到的是同一份状态。托管脚本 `zeta_host.sh`、zeta-helper、claude-switch 都是手机版那几份（构建时编进程序），按内容哈希装到主机上，和手机版装的是同一份。
- 和 Claude Code 说话（起进程的参数、stream-json 输入、控制请求、确认的应答格式）由 serve 做，桌面版不直接碰 stream-json。

## 结构

```
client/               客户端共用、和界面无关的 Go 代码（仓根的模块，windows/go.mod 用 replace 指向 ../client）
  sshlink/            设备：模型、OpenSSH 配置认证、SSH 连接（每台一条、每个命令一个通道、闲一分钟断、断线重连）、主机指纹
  hostlink/           在一台主机上跑命令：remoteCommand 的写法、zeta-helper 按哈希安装与调用、serve 连接（出错应答的 code 见 ReplyError）、会话列表的格式、
                      附件的上传（~/.cache/<ZETA_NAME>/files/<随机>/，同 Kotlin 的 Uploads）与路径核对
  drafts/             输入草稿的规矩：上限、超出时丢哪些、存盘的 JSON 格式、侧栏的一行预览（同 Kotlin 的 DraftRecords；存在哪由各端自己定）
  sshtest/            测试用：x/crypto/ssh 服务器，命令在临时家目录里真跑 bash
windows/
  cmd/zeta/           入口
  internal/settings/  设置文件、各项设置（含常用指令）、草稿的存盘
  internal/attach/    输入栏收附件的规矩：图片缩到长边 1568 编成 JPEG、文件大小上限与说明
  internal/host/      桌面版碰设备的几样：中枢用的 Devices（会话列表的订阅、对话连接、取图和导出另用的连接、查目录、传附件、查路径）、加设备时的探测、
                      Zeta 在主机上的目录名 LinuxName、从 assets 取编进程序的 zeta-helper（LoadProgram）
  internal/claudecfg/ 客户端本地存的每台设备设置（自动压缩、自动标题）
  internal/accounts/  账号与额度页背后的状态：换号、记下、添加账号（登录）、额度的刷新
  internal/chat/      对话：接 serve 的对话协议，把它推来的条目、确认与提问、用量、后台任务、子代理整理成界面要的状态
  internal/hub/       中枢：当前对话与后台对话、会话列表与置顶、设备清单与切换、账号；碰设备经接口 Devices（真的是 host.Devices）
  internal/hubtest/   测试用：不连 SSH 的假设备和假 serve（对话的推送用 recorded/ 里从真 serve 和核心录的消息），中枢和界面的测试用
  internal/ui/        mygo 界面：窗口、侧栏、聊天、输入栏、各种确认、设置各页（Claude Code 配置、账号与额度这两页只调核心的 config.*、accounts.*、quota.*）
  internal/ui/widget/ 配色、字号、图标和共用组件（照 ChatGPT 客户端的样子，见「界面」）
  installer/          MSI（wixl）
```

主机无关、手机版也要用的逻辑放 helper 模块，由 serve 提供（`helper/transcript`、Claude Code 配置 `helper/claudecfg`、账号与额度 `helper/accounts`）。调核心的请求一律经 `helper/coreapi`（每个方法一个带类型的函数），桌面版不自己拼方法名、参数和结果类型。客户端一侧和界面无关的（SSH 直连、在主机上装程序和跑命令、到 serve 的连接）放 `client/`：Windows 链接它，Android 以后经随 APK 带的 Go 程序用它直连远程主机（见 `docs/core.md`）；只有桌面版要的（设置、界面、编进程序的 assets、目录名）放 `windows/`。Go 代码按 Go 的习惯写（事件解码成结构体、错误照常返回），不照搬 Kotlin 版的结构和写法。serve 推来的确认、后台任务、用量直接解成 helper 里的类型（`helper/prompt`、`helper/tasks`、`helper/usage`，`windows/go.mod` 用 replace 指向 `../helper`），两边一起编、一起发，格式不会对不上；对话条目只依赖它的 JSON 格式（`internal/chat/items`）。逻辑层只出数据，界面层只画（同 power-meter 的做法）：后台 goroutine 改了状态，经窗口的 Update 在主线程上生效、重画。

## 界面

照 ChatGPT 的 Windows 客户端（原 Codex 客户端）：配色和快捷键照它，尺寸按鼠标；布局和交互逻辑是 Zeta 自己的，不照搬它的面板结构，手机版和 COUI 的交互也不搬过来。配色、字号和共用组件在 `internal/ui/widget`。拿不准时按这几条：

- 配色是黑白灰，照 ChatGPT 客户端默认的 codex 外观：浅色主区和侧栏都是 #FCFCFC，深色都是纯黑，两边只靠分隔线分开；输入栏浅色白、深色 #1B1B1B；主按钮、发送键、开关、勾选是黑（深色里 #EDEDED），次按钮透明底一圈细边，危险操作红底白字，蓝色只给链接和主题色的字。值在 `widget/colors.go`。在真客户端上量色前先看它设置「外观」里的主题是不是默认的 codex：换过主题（比如 Notion）量到的是那套主题的色。
- 窗口：左边侧栏能拖宽（`ui.Split`，宽度存在设置里）、Ctrl+B 收起；侧栏顶上依次是新对话、搜索、设备切换，底下是设置。窗口的位置和大小由 mygo 的 `StateKey` 记住。
- 会话行一行标题，目录在悬停提示里；右键或行尾的「…」是会话操作。多选照资源管理器：Ctrl、Shift+点击，在选中的行上右键是批量操作，Delete 删除、Esc 取消。
- 设置不是一层层的页：进设置后侧栏换成分类（常规、各台设备、添加设备、各台的账号与额度、关于），顶上「返回应用」，Esc 也回去；右边的页不带返回键。
- 等你回应的确认、提问是输入栏位置上的卡片（`widget.Panel`），不遮住对话；Enter 点推荐的按钮，Esc 点另一个。只有要先答完才能往下的小事（重命名、删除确认、常用指令）用居中的对话框：标题左对齐，按钮靠右、主按钮在最右并带焦点。
- 输入栏底下是写着当前值的文字胶囊（权限模式、模型、思考强度），不是只有图标的圆钮；上下文圆环左边是那台主机的负载「CPU 45%」（对话有了消息后每 5 秒问一次 system.cpu，忙时变色）；新对话还没发消息时，顶栏第二行（设备 · 工作目录）能点，换个目录重开；查找栏浮在对话区右上角，输入栏不动。
- 快捷键在 `internal/ui/layout.go` 的 `shortcuts` 表里，同一张表接键和在设置「常规」里列出来；加快捷键先看 ChatGPT 客户端用的是哪个键：它的默认键在安装包 `resources/app.asar` 的命令表里（每条命令的 `electron.defaultKeybindings`，Windows 看 `platformDefaultKeybindings.default`），它有的同功能键都接上，Zeta 自己的功能另起不撞它的键。
- 界面缩放（手机版没有）：Ctrl++ 放大、Ctrl+- 缩小、Ctrl+0 回到 100%（同 ChatGPT 客户端），设置「常规」里也能选，存在设置的 `desktopZoom`（百分数）。档位用 Chromium 浏览器的预设（67% 到 200%）。缩放经 mygo 的 `Context.SetZoom`，在系统 DPI 缩放之上再乘这个倍数，`widget` 里的尺寸照旧按 100% 写。小键盘的加号 mygo 认不出，不管用。

## 功能

- 设备：OpenSSH 配置认证、直连、探测、指纹；侧栏的主机切换、全部设备、置顶/今天/更早，已结束的会话（核心报 `live` 为假）灰着；添加与编辑设备。
- 看对话：读历史（helper history、Go 归约器、往上翻载入更早的），画条目（Markdown、思考与工具折叠卡、红绿对比、图片；回答里用 Markdown 引用的图同手机版的 answerImages 从正文里取出来画在回答下面，主机上的文件经核心的 images.file 取，网络图点了才下载），点图看大图（滚轮以指针为中心缩放，放大后拖动，双击切换原大），接上还在跑的托管进程。
- 新对话的空白页：中间「有什么可以帮你？」和四张建议卡片（同手机版的 EmptyState），点一张直接把它的提问发出去，输入栏里没发的字留着；工作目录在顶栏第二行换，空白页上不重复。
- 对话进行：起进程与接着旧会话、发消息、流式显示、停止、排队与插话、权限模式、模型与思考强度、各种确认与提问、用量与额度（输入栏的上下文圆环点开：订阅额度、上下文、本对话累计、各轮明细，同手机版的用量弹框）、闲置放手、断线重连、和别的客户端同时接着；起进程前去设备上核对默认工作目录能不能进。
- 对话的其余操作：改写、重新生成、删除一轮、分支、还原代码；常用指令（附件菜单里的列表，自己发过的消息右键「收藏为常用指令」直接存进去，存过的不重复存，结果用 toast 说一声）；草稿（侧栏里有没发出的草稿的会话行尾标灰字「草稿」，悬停提示里是草稿的开头，同手机版）、斜杠命令、对话内查找、任务清单、后台任务页、子代理页、别处运行的接管、tmux 窗口；侧栏的置顶、改名、归档、结束对话（还在运行的；还在进行或在别处运行的先问，Zeta 自己的经 sessions.end，别处运行的经 sessions.end_elsewhere，输入 /exit 也结束）、删除、导出、多选（都是核心的 sessions.* 方法）。
- 设置：常规（外观、界面缩放、快捷键一览）、设备、账号与额度、关于；各设备页（连接，最上面是「连接」开关：关掉时断开这台的会话列表订阅、serve 连接和 SSH 连接、不再自动重连，聊天窗格里那台的对话放手、说已断开并给「连接」，打开时按会话号重新打开，同手机版的 `hub.setConnected`，中枢里是 `hub.SetConnected`；新对话默认、自动压缩、自动标题、闲置超时，那台主机上 Claude Code 的版本和「更新」（system.claude、system.claude_update）；设备的主机名每次连上时从 serve 的招呼里取）、Claude Code 配置各页、账号与额度（逻辑在主机的 serve 里，客户端只画，请求走那台给对话用的 serve 连接）；关于（版本、这个版本的源码在 GitHub 上的 tag 或提交、鸣谢、开源许可；鸣谢只列桌面版用到的：Eta、Claude Code、mygo、x/crypto、x/image，和图标取自的 COUI 2，Go 依赖和 `go.mod` 对不上时 `about` 的测试失败）与自动更新（启动时检查，有新版本时窗口底部提示）。

## 测试

- 逻辑层是普通的 Go 测试；SSH 对着测试里起的 x/crypto/ssh 服务器，命令在临时 HOME 里真跑 bash、zeta-helper 和托管脚本，对话用 serve 加 `tools/fake-claude`（`internal/hub` 的 `TestConversationThroughCore`，设 `ZETA_RECORD_DIR` 时顺带重录 `hubtest/recorded`）。
- 界面用 mygo 的无窗口测试（`ui.NewTester`）配 `internal/hubtest` 的假设备，按文字找控件、点、输入、拨时钟，断言状态和布局；不做像素比对。要看样子时设 `ZETA_PREVIEW_DIR`，`internal/ui` 的 `TestPreviews` 把各场景画成 PNG 人工看（同 power-meter）。
- 门禁在 Linux 上跑全部测试；真窗口在本机的 Windows 上装 MSI 看。
