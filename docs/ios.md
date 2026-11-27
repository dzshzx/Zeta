# iOS 版

`ios/` 是 iPhone 和 iPad 上的 Zeta：只做连远程主机的那部分，经 Zeta 自带的 SSH 直连 Linux 设备，在上面用 Claude Code。手机一侧的功能（Debian 环境、手机工具、悬浮层、数字助理、定时任务、通知触发）都不做。界面用 iOS 27 SDK 自带的 SwiftUI 组件，最低系统 iOS 27。

## 结构

逻辑不在 Swift 里另写一份：和桌面版共用的 Go 中枢（仓根的 `hub/` 模块：中枢本身和它的 `chat`、`host`、`accounts`、`settings`）原样编进 App，Swift 只画界面。

```
hub/mobile/               iOS 版的核心：起中枢、自己的密钥认证、一个按方法名调用的 JSON 接口（下面「核心桥」），Go 测试在这里
hub/cmd/zeta-ios/         cgo 导出 ZetaStart、ZetaCall、ZetaFree，编成静态库（c-archive）
ios/project.yml           XcodeGen 的工程描述，构建时生成 Zeta.xcodeproj（不进 Git）
ios/scripts/core.sh       把核心编成真机、模拟器两份静态库，合成 ios/build/ZetaCore.xcframework
ios/scripts/build.sh      编核心、生成工程、交给 xcodebuild：ipa（不签名）、test（模拟器上的测试）、simulator（截图用）
ios/Zeta/Core/            Swift 一侧：Core（C 接口）、Models（JSON 的类型）、AppModel（界面读的状态）、KeyStore（钥匙串里的密钥）
ios/Zeta/Views/           SwiftUI 界面：会话列表、聊天页、条目、确认卡、设置
ios/ZetaTests/            模拟器上的冒烟测试：静态库链进来了、C 接口和 JSON 两头对得上
```

## 和别的客户端的约定

- 在主机上和别的客户端用同一个包：版本号、版本名同桌面版（`tools/version.sh`），经 `-ldflags` 写进 `hub/appversion`；不是干净 X.Y.Z 的构建是测试版，在主机上用 `zeta-dev` 包，App 叫 Zeta Dev（`com.dzshzx.zeta.dev`，橙色图标），和正式版（Zeta，`com.dzshzx.zeta`）能同时装着。
- 认证：Zeta 自己的一把 Ed25519 密钥，32 字节的种子存在钥匙串里（只在这台设备上、首次解锁后可读，不进备份），第一次打开时生成；公钥在设置里复制或分享，用户自己加进设备的 `~/.ssh/authorized_keys`。也可以在设置里导入自己的私钥（粘贴或选文件，OpenSSH 或 PEM 格式，Ed25519、RSA、ECDSA，有密码时填上）：核心认得出才换上，答去掉密码的 OpenSSH 格式，Swift 存进钥匙串（同样只在这台设备上），每次起核心后用 `importkey` 交回去；导入了就只用导入的那把，「换回 Zeta 的密钥」删掉它。换了以后新起的连接用新的，已经连着的不断开。地址写成 `用户@主机[:端口]`，没有 `~/.ssh/config`。主机指纹在添加设备时记下，以后只认它。
- 设置存在 App 的 Application Support 下的 `Zeta/`，格式同桌面版（`hub/settings`）；Zeta 的运行日志在它的 `diag/` 下。
- 界面语言：系统首选语言是中文时简体中文，否则英文；Swift 一侧的字在 `ios/Zeta/Localizable.xcstrings`（源语言简体中文），核心说的话（出错、提示、工具结果）由 Go 按同一种语言出好再交过来。

## 核心桥

Swift 经三个 C 函数调核心（`hub/cmd/zeta-ios`）：

- `ZetaStart(dir, seed, seedLen, lang, device, changed)`：起核心，成功返回 NULL，否则是原因。`changed` 在状态有变化时调（在 Go 的线程上），Swift 攒 50 毫秒取一次 `state`。界面语言在这里设一次（进程里全局的）。
- `ZetaCall(method, params, n)`：参数和结果都是 JSON；出错时结果是 `{"error": "原因"}`。
- `ZetaFree(p)`：释放上面两个返回的字符串。

方法登记在 `hub/mobile/routes.go` 的方法表里，各领域在自己的文件里登记、自己解参数。中枢的方法都在核心自己的一个 goroutine 上跑（同桌面版的界面线程），`ZetaCall` 排进去等它跑完，所以很快返回；要等网络的（`probe`、`core`、`key` 这几个）在调用方的线程上等，Swift 经 `AppModel.request` 在后台线程上调。每批跑完通知一次界面，只有 `state` 这样的读取不算变化，不然界面取一次又被叫来取一次。方法：

| 方法 | 参数 | 结果 |
|---|---|---|
| `key` | | `{public, imported, version}`：在用的那把的公钥（authorized_keys 格式）、是不是导入的、版本名 |
| `importkey` | `key`、`passphrase` | 换用导入的私钥，答同 `key`，另有 `pem`：去掉密码的 OpenSSH 格式，界面存进钥匙串；认不出、没填密码时出错，不换 |
| `resetkey` | | 换回 Zeta 自己的密钥，答同 `key` |
| `state` | | 界面要画的全部（`mobile.State`）：设备、会话列表、聊天页；聊天页的 `items` 是上次 `state` 以后条目的变化（同桌面版的 `Take`），换了对话时第一项是整份 |
| `resync` | | 下一次 `state` 整份交聊天页的条目（界面套变化对不上时） |
| `probe` | `target` | 连一次查到的：`{token, summary, hostname, fingerprint}`；阻塞，最多约 45 秒 |
| `add` | `token` | 添加探测过的设备 |
| `remove`、`connect` | `id`（`connect` 另有 `on`） | 删设备；设备页的「连接」开关 |
| `list` | `id` | 会话列表列哪台，空是全部设备 |
| `refresh` | | 重新订阅列着的设备 |
| `open`、`new`、`close` | `backend`、`id`；`backend` | 点开会话、新对话、关掉聊天页 |
| `send`、`interrupt`、`earlier` | `text` | 对聊天页的对话发消息、停止、载入更早的 |
| `answer` | `request_id`、`reply` | 回应确认或提问，`reply` 是 `helper/prompt` 的 Reply |
| `core` | `backend`、`method`、`params` | 把 `method` 转给设备 `backend` 的核心，结果原样交回（`AppModel.device`）；只转 `config.*`、`diag.*`、`system.*`（各方法见 `docs/core.md`），对话、会话列表、账号这些有中枢自己的状态，走中枢的方法；阻塞，最多 60 秒 |
| `composer.draft` | `text`、`caret` | 输入框里的字和光标（UTF-16）交给核心存草稿；答 `{commands}`：光标前在打斜杠命令时要提示的（开始打 / 而对话没起进程时先让核心起好）；不算变化 |
| `composer.complete` | `text`、`caret`、`name` | 选了斜杠命令：答 `{text, caret}`，只换掉光标处那个词 |
| `composer.send` | `text` | 发输入框里的字和传好了的附件，答 `{sent}`；还有在传的、没东西可发时不发；没发出去时字和附件放回输入栏 |
| `composer.attach` | `files`（`[{path}]`） | 收 App 临时目录里选好的文件：传到对话所在的设备上，图片另做缩小图；收不了的原因放进 `composer.problems` |
| `composer.remove`、`composer.problems` | `id`；无 | 移除一个待发的附件（在传的取消）；清掉没收进来的原因 |
| `composer.model`、`composer.effort`、`composer.mode` | `value`（省掉是回到默认） | 这个对话的模型、思考强度、权限模式 |
| `composer.steer`、`composer.cancel`、`composer.compact` | 无；`uuid`、`recall` | 立即插话；撤回一条排队的（`recall` 时放回输入框）；压缩上下文 |
| `composer.quota` | `force` | 让对话所在设备的订阅额度刷新（不够新才问，`force` 时一定问） |
| `composer.flush` | | 马上把草稿写盘（退到后台时）；阻塞 |
| `prompts`、`prompts.add`、`prompts.update`、`prompts.remove`、`prompts.move` | `id`、`name`、`text`、`delta`、`dedupe` | 常用指令：答 `{list, full}`；`dedupe` 时已有同样内容的不加、答出错 |

`send`、`interrupt`、`answer`、`earlier` 在后台做，没成的原因放在下一次 `state` 的 `chat.failed` 里。

## 输入栏

规矩照桌面版的输入栏（`docs/desktop.md`「功能」），界面在 `ios/Zeta/Views/Composer/`，交互照手机版。`state` 的 `chat.composer`（`hub/mobile/composer.go` 的 `Composer`）是输入栏要画的整份，对话还没打开成时没有。

- 输入框里的字归 Swift 管，每改一次经 `composer.draft` 交给核心；核心改了字（撤回的消息放回来、没发出去放回来、换了对话恢复草稿）时 `rev` 加一，界面见 `rev` 变了才换上 `text`。光标都按 UTF-16 算。
- 草稿按会话号存（还没开始的新对话共用一份，拿到会话号时挪过去），存在设置目录的 `zeta_drafts.json`，格式和上限见 `client/drafts`，和桌面版、手机版一样；停下约一秒存一次，换对话、退到后台时马上存。
- 附件：相册里选图（PhotosPicker）、选文件（fileImporter），Swift 写进 App 临时目录（每个一个单独的目录，文件名就是传上去的名字；相册的 HEIC 等核心解不开的先转成 JPEG），把路径交给 `composer.attach`；传到设备、缩小图、大小上限都照 `hub/attach`。PhotosPicker 在 App 之外的进程里选，不要相册权限。没有拍照。
- 模型菜单照桌面版：在用中转时只列中转报的，否则是 Claude Code 报的清单分两层（`chat.SplitModels`，旧版本收进「更多模型」），还没报时是常用的别名。
- 斜杠命令：光标前的词以 / 开头（句中也算）时在输入栏上方提示，选了只换掉那个词；没有桌面版的 `/config`、`/permissions` 两条设置命令。
- 排队：回答中发的消息进核心的队列，输入栏上方列出，点正文撤回到输入框，删除键撤回不要，「立即插话」把排队的送进这一轮。
- 常用指令：「+」菜单里列出，点了填进输入框（空着就换上，有字插在光标处），「管理常用指令」里新建、编辑、删除、排序。消息上的「收藏为常用指令」调 `AppModel.savePrompt`（已有同样内容的不加）。
- 用量：输入栏上的圆环是上下文用了多少（按程度变色），点开是订阅额度（在用中转时没有这一节）、上下文、本对话累计、各轮明细和「压缩上下文」。

## 构建与测试

本机和本机 runner 都没有 macOS，iOS 版只在 GitHub 托管的 macOS 上编（`.github/workflows/ios.yml`，`xcode-27` 镜像，挑其中版本最高的正式版 Xcode）：推送改到它的提交时跑，任务分支也跑；`gh workflow run ios.yml --ref <分支>` 手动跑。一次运行：Linux 上跑 `hub/scripts/assets.sh`，macOS 上 `build.sh test simulator ipa`（核心和工程只编、生成一次）跑模拟器上的测试、打不签名的 ipa，再在模拟器里截简体中文和英文两张首屏图；ipa 和截图在这次运行的 artifacts 里。master 上的构建另外发成预发布版本 `ios-dev-<提交号>`（不用登录就能下载，全部预构建见 `https://github.com/dzshzx/Zeta/releases?q=ios-dev&expanded=true`），tag 不以 v 开头、标成预发布，不影响版本号和应用内更新。

- Go 一侧的改动照常在本机测：`cd hub && go test ./mobile`（先跑过 `hub/scripts/assets.sh`），合入门禁也跑它。
- Swift 在本机只过 SwiftLint 和圈复杂度（`scripts/format.sh --lint`，合入门禁里跑）；编译、模拟器测试只在 CI 上。
- ipa 不签名：用自己的开发者账号重签后装到设备上。

## 现在没有的

账号与额度、Claude Code 配置、附件和图片、模型和权限模式的选择、子代理和后台任务页、会话的重命名、删除、置顶、搜索、MCP 的表单和网址确认、通知；Markdown 只排行内样式和代码块。iOS 退到后台后 SSH 连接会断，回到前台时中枢照常重连。
