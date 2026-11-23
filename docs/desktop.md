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
  drafts/             输入草稿的规矩：上限、超出时丢哪些、存盘的 JSON 格式（同 Kotlin 的 DraftRecords；存在哪由各端自己定）
  sshtest/            测试用：x/crypto/ssh 服务器，命令在临时家目录里真跑 bash
windows/
  cmd/zeta/           入口
  internal/settings/  设置文件、各项设置（含常用指令）、草稿的存盘
  internal/attach/    输入栏收附件的规矩：图片缩到长边 1568 编成 JPEG、文件大小上限与说明
  internal/host/      桌面版碰设备的几样：中枢用的 Devices（会话列表的订阅、对话连接、取图和导出另用的连接、查目录、传附件、查路径）、加设备时的探测、
                      Zeta 在主机上的目录名 LinuxName、从 assets 取编进程序的 zeta-helper（LoadProgram）
  internal/chat/      对话：接 serve 的对话协议，把它推来的条目、确认与提问、用量、后台任务、子代理整理成界面要的状态
  internal/hub/       中枢：当前对话与后台对话、会话列表与置顶、设备清单与切换、账号；碰设备经接口 Devices（真的是 host.Devices）
  internal/hubtest/   测试用：不连 SSH 的假设备和假 serve（对话的推送用 recorded/ 里从真 serve 和核心录的消息），中枢和界面的测试用
  internal/ui/        mygo 界面：窗口、侧栏、聊天、输入栏、各种确认、设置各页（Claude Code 配置、账号与额度这两页只调 serve 的 config.*、accounts.*、quota.*）
  installer/          MSI（wixl）
```

主机无关、手机版也要用的逻辑放 helper 模块，由 serve 提供（`helper/transcript`、Claude Code 配置 `helper/claudecfg`、账号与额度 `helper/accounts`）。客户端一侧和界面无关的（SSH 直连、在主机上装程序和跑命令、到 serve 的连接）放 `client/`：Windows 链接它，Android 以后经随 APK 带的 Go 程序用它直连远程主机（见 `docs/core.md`）；只有桌面版要的（设置、界面、编进程序的 assets、目录名）放 `windows/`。Go 代码按 Go 的习惯写（事件解码成结构体、错误照常返回），不照搬 Kotlin 版的结构和写法。serve 推来的确认、后台任务、用量直接解成 helper 里的类型（`helper/prompt`、`helper/tasks`、`helper/usage`，`windows/go.mod` 用 replace 指向 `../helper`），两边一起编、一起发，格式不会对不上；对话条目只依赖它的 JSON 格式（`internal/chat/items`）。逻辑层只出数据，界面层只画（同 power-meter 的做法）：后台 goroutine 改了状态，经窗口的 Update 在主线程上生效、重画。

## 功能

- 设备：OpenSSH 配置认证、直连、探测、指纹；侧栏的主机切换、全部设备、置顶/今天/更早，已结束的会话（核心报 `live` 为假）灰着；添加与编辑设备。
- 看对话：读历史（helper history、Go 归约器、往上翻载入更早的），画条目（Markdown、思考与工具折叠卡、红绿对比、图片；回答里用 Markdown 引用的图同手机版的 answerImages 从正文里取出来画在回答下面，主机上的文件经核心的 images.file 取，网络图点了才下载），点图看大图（滚轮以指针为中心缩放，放大后拖动，双击切换原大），接上还在跑的托管进程。
- 对话进行：起进程与接着旧会话、发消息、流式显示、停止、排队与插话、权限模式、模型与思考强度、各种确认与提问、用量与额度、闲置放手、断线重连、和别的客户端同时接着；起进程前去设备上核对默认工作目录能不能进。
- 对话的其余操作：改写、重新生成、删除一轮、分支、还原代码；草稿、斜杠命令、对话内查找、任务清单、后台任务页、子代理页、别处运行的接管、tmux 窗口；侧栏的置顶、改名、归档、删除、导出、多选（都是核心的 sessions.* 方法）。
- 设置：设备、账号与额度、外观、关于；各设备页（连接，最上面是「连接」开关：关掉时断开这台的会话列表订阅、serve 连接和 SSH 连接、不再自动重连，聊天窗格里那台的对话放手、说已断开并给「连接」，打开时按会话号重新打开，同手机版的 `hub.setConnected`，中枢里是 `hub.SetConnected`；新对话默认、自动压缩、自动标题；设备的主机名每次连上时从 serve 的招呼里取）、Claude Code 配置各页、账号与额度（逻辑在主机的 serve 里，客户端只画，请求走那台给对话用的 serve 连接）；关于与自动更新（启动时检查，有新版本时窗口底部提示）。

## 测试

- 逻辑层是普通的 Go 测试；SSH 对着测试里起的 x/crypto/ssh 服务器，命令在临时 HOME 里真跑 bash、zeta-helper 和托管脚本，对话用 serve 加 `tools/fake-claude`（`internal/hub` 的 `TestConversationThroughCore`，设 `ZETA_RECORD_DIR` 时顺带重录 `hubtest/recorded`）。
- 界面用 mygo 的无窗口测试（`ui.NewTester`）配 `internal/hubtest` 的假设备，按文字找控件、点、输入、拨时钟，断言状态和布局；不做像素比对。要看样子时设 `ZETA_PREVIEW_DIR`，`internal/ui` 的 `TestPreviews` 把各场景画成 PNG 人工看（同 power-meter）。
- 门禁在 Linux 上跑全部测试；真窗口在本机的 Windows 上装 MSI 看。
