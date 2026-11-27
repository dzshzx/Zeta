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
| `devices` | | 设置里的各台（`DeviceInfo`）：名字、地址、家目录、默认工作目录、连法、指纹、「连接」，和首页那一行右边写的（在用的中转名 `relay`，或最要紧的额度窗口已用百分比 `used`，没有时 -1） |
| `settings.open` | | 打开设置时让各台还没读过的账号、额度读一遍 |
| `device.rename` | `id`、`name` | 改名，空是用设备自己的名字；和别的设备重名时出错 |
| `device.checkcwd` | `id`、`path` | 改默认工作目录：先到设备上核对，进得去就存；答 `{path, problem, unchecked, off}`（进不去的原因、连不上的原因、「连接」关着），后两种不存；阻塞 |
| `device.setcwd` | `id`、`path` | 核对不了时用户选「仍然保存」 |
| `prefs`、`prefs.set` | `auto_load_remote_images` | 「常规」里存在设置文件的几项 |
| `accounts` | `id` | 一台的账号页（`mobile.Accounts`）：账号、中转、正在做的事、出错、谁挡着换号、正在进行的登录、在用的中转；订阅额度只在中枢说显示（`Quota.Shown`）时有，正在编辑的中转草稿 |
| `accounts.open`、`accounts.refresh` | `id`（`refresh` 另有 `force`） | 读一遍账号和额度；`force` 时向 Claude Code 要最新的额度 |
| `accounts.switch`、`accounts.remove`、`accounts.save` | `id`、`name` | 切换、删除账号，记下在用的登录 |
| `accounts.login`、`accounts.code`、`accounts.cancel` | `id`（`code` 另有 `code`） | 添加账号：核心在设备上起登录命令，`accounts` 的 `login.url` 是授权网址；交回授权码；取消 |
| `accounts.dismiss` | `id` | 清掉上一件事的出错 |
| `relay.switch`、`relay.remove` | `id`、`name` | 切到中转、删除中转 |
| `relay.edit`、`relay.draft`、`relay.fetch`、`relay.save`、`relay.close` | `id`、`name`（`draft` 另有 `base_url`、`key`、`model`） | 中转的草稿留在核心里：开始添加（`name` 空）或编辑、改字段（名称只在添加时改）、拉模型列表、保存（成了草稿的 `saved` 为真）、关掉 |
| `diaglog`、`diaglog.clear` | `topic`、`level` | Zeta 自己的运行日志（`hub/runlog`，设置目录的 `diag/` 下）按类别、轻重筛，新的在前；清空 |
| `diag.export` | `header`、`env` | 打诊断包（`hub/diagbundle`，同桌面版），写进临时目录，答 `{path}`；阻塞 |
| `about`、`license` | `file`（`license`） | 版本、源码的 tag 或提交和链接、开源许可清单（`ios`、`host` 两组）；一项许可的全文 |

`send`、`interrupt`、`answer`、`earlier` 在后台做，没成的原因放在下一次 `state` 的 `chat.failed` 里。

## 设置

首页照手机版：常规、设备（每台一行，右边写那台的额度已用比例，在用中转时写「中转 名字」，「连接」关着写已断开）、密钥（公钥的复制分享、导入私钥）、诊断、关于。页面都在 `ios/Zeta/Views/Settings/`，读的是上面那组方法；`AppModel.revision` 每取到一次 `state` 加一，各页按它重读自己的那份。

- 设备页：最上面「连接」开关；名字、地址、默认工作目录（改时照桌面版先核对，连不上或「连接」关着时问仍然保存还是取消）；「账号与额度」和「Claude Code 配置」两行（「连接」关着时灰着）；主机指纹；删除。
- 账号与额度：账号和中转的逻辑都在中枢的 `hub.Remote(id).Accounts`、`.Quota`，界面只画核心给的。切换账号、中转先确认；添加账号时显示授权网址（在 Safari 里打开）和填授权码的框；中转的名称加好以后不能改，密钥遮着、能显示，Zeta 改不了的（`keyEditable` 为假）灰着说明。在用中转时没有订阅额度一组。
- 诊断：运行日志、环境（版本、系统、机型、各台怎么连）、各台设备上的日志（核心的 `diag.files`、`diag.tail`，经 `core` 转发，看最后 64 KB，能复制、刷新），导出诊断包后用分享面板交出去。
- 关于：版本、源码、鸣谢（只列 iOS 版用到的）、开源许可（`hub/assets` 里的全文：Zeta 自己的、App 链接的 `ios` 一组、带到设备上的 `host` 一组）。
- 常规：「自动加载网络图片」（`autoLoadRemoteImages`，存进 `hub/settings`）；界面语言跟随系统，一行跳到系统设置里本 App 的语言；外观跟随系统。

## 构建与测试

本机和本机 runner 都没有 macOS，iOS 版只在 GitHub 托管的 macOS 上编（`.github/workflows/ios.yml`，`xcode-27` 镜像，挑其中版本最高的正式版 Xcode）：推送改到它的提交时跑，任务分支也跑；`gh workflow run ios.yml --ref <分支>` 手动跑。一次运行：Linux 上跑 `hub/scripts/assets.sh`，macOS 上 `build.sh test simulator ipa`（核心和工程只编、生成一次）跑模拟器上的测试、打不签名的 ipa，再在模拟器里截简体中文和英文两张首屏图；ipa 和截图在这次运行的 artifacts 里。master 上的构建另外发成预发布版本 `ios-dev-<提交号>`（不用登录就能下载，全部预构建见 `https://github.com/dzshzx/Zeta/releases?q=ios-dev&expanded=true`），tag 不以 v 开头、标成预发布，不影响版本号和应用内更新。

- Go 一侧的改动照常在本机测：`cd hub && go test ./mobile`（先跑过 `hub/scripts/assets.sh`），合入门禁也跑它。
- Swift 在本机只过 SwiftLint 和圈复杂度（`scripts/format.sh --lint`，合入门禁里跑）；编译、模拟器测试只在 CI 上。
- ipa 不签名：用自己的开发者账号重签后装到设备上。

## 现在没有的

账号与额度、Claude Code 配置、附件和图片、模型和权限模式的选择、子代理和后台任务页、会话的重命名、删除、置顶、搜索、MCP 的表单和网址确认、通知；Markdown 只排行内样式和代码块。iOS 退到后台后 SSH 连接会断，回到前台时中枢照常重连。
