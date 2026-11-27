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
| `claudecfg.local` | `backend` | 客户端本地存的这台的 `{autoCompact, compactPercent, compactPercents, autoTitle}`（`hub/claudecfg`，0 是 Claude Code 自己的压缩时机），和这台的默认工作目录 `cwd` |
| `claudecfg.setlocal` | `backend`，`autoCompact`、`compactPercent`、`autoTitle` 给哪个改哪个 | 同 `claudecfg.local` |
| `claudecfg.models` | `backend` | 默认模型下拉的清单 `{source, models: [{value, label, id, older}]}`：`source` 是 `relay`（中转报的具体 ID）、`harness`（Claude Code 报的清单分好两层，`older` 的收进「更多模型」；聊天页开着这台的对话、报过清单就用它，不然开一个不发消息的对话去要，要不到时是常用的别名）或 `unknown`（读不到、账号在切换，默认模型不能改）；阻塞，最多约 1 分钟 |
| `claudecfg.slow` | 同 `core` | 同 `core`，只转要跑 claude 或连 MCP 服务器的几个（`config.mcp.status`、`tools`、`addHttp`、`addStdio`、`remove`、`setToken` 最多等 3 分钟，`system.claude_update` 6 分钟） |
| `pin`、`rename` | `backend`、`id`；`rename` 另有 `title` | 置顶或取消、改名（中枢的 TogglePin、Rename） |
| `archive`、`delete`、`end` | `sessions`（`[{backend, id}]`），`archive` 另有 `archived` | 归档或取消、删除、结束 Claude Code 进程（Zeta 的经 `sessions.end`，别处运行的经 `sessions.end_elsewhere`）；没成的原因在下一次 `state` 的 `side.failed` |
| `export` | `sessions` | `{name, content}`：导出成的 Markdown；阻塞，等各台答来 |
| `search`、`sort` | `query`；`sort`（`replied`、`created`、`manual`） | 搜索框的字（在搜时 `sessions` 换成搜索结果，行上带 `snippet`）、排序 |
| `canmove`、`move` | `backend`、`id`、`target`（`{backend, id}`）、`below` | 手动排序时能不能、把会话拖到 target 的上半或下半；答 `{ok}` |
| `alerts` | | 开始收别的会话的提醒（`side.alerts`：`finished`、`waiting`、`settled`），起核心后调一次 |
| `stoptask` | `task` | 停聊天页对话的一个后台任务（后台做） |
| `watch` | `kind`（`output`、`subagent`）、`id`、`on` | 开始或停止看着后台命令的输出、子代理的记录（后台做） |
| `taskoutput`、`subagent` | `task`；`tool_use_id` | 看着的输出（`{got, text, total_bytes, truncated, error}`，已去掉终端转义）、子代理（`chat.Subagent` 的字段，`items` 整份、`rev`） |

`send`、`interrupt`、`answer`、`earlier` 在后台做，没成的原因放在下一次 `state` 的 `chat.failed` 里。

## 会话列表、后台任务与通知

- 会话列表（`Views/Sidebar/`）：行尾标核心报的忙闲（`state` 会话行的 `activity`、`tasks`：等你回应、在回答、后台任务在跑、有新回答），已结束的标题灰。右滑置顶，左滑归档、删除，长按有置顶、改名、归档、结束、导出、删除；删除、结束先确认。导出交回的 Markdown 存进临时目录，用系统分享面板交出去。顶栏「更多」里选排序、进多选（List 的 EditMode，底栏批量结束、归档、删除）、看已归档；手动排序时编辑状态下拖动行（`move`）。`.searchable` 的字交给 `search`。
- 后台任务面板（`Views/Tasks/`）：会话列表顶上一行进，行尾是后台对话加当前对话在跑的任务的个数；上面是后台对话（`side.background`，点了切过去），下面是聊天页这个对话的后台任务，点进去看详情和子代理页。聊天页输入栏上方是任务清单条和后台任务条（`ChatTaskBar`）。详情页、子代理页打开时 `watch`，每次 `state` 变了重取 `taskoutput`、`subagent`，离开时停止看着。
- 通知（`Core/Notifier.swift`，照桌面版）：App 不在前台，或在前台但聊天页开着的不是那个会话时（banner），聊天页的对话答完一轮（`chat.finished`）、来了确认或提问，别的会话照 `side.alerts` 答完一轮或开始等你时发一条；每个会话一个编号（`zeta-<设备>/<会话>`），后一条换掉前一条，点了打开那个会话；等你回应的那条在请求处理掉后撤掉。第一次要发时请求通知权限。每件事（开始在跑、等你、答完、不再等）也交给 `Notifier.onEvent`。退到后台时聊天页的对话在跑就申请一段后台时间（`beginBackgroundTask`），答完或系统收回时结束；系统给多久不由 App 定，之后 SSH 断开，回到前台再重连。

## 构建与测试

本机和本机 runner 都没有 macOS，iOS 版只在 GitHub 托管的 macOS 上编（`.github/workflows/ios.yml`，`xcode-27` 镜像，挑其中版本最高的正式版 Xcode）：推送改到它的提交时跑，任务分支也跑；`gh workflow run ios.yml --ref <分支>` 手动跑。一次运行：Linux 上跑 `hub/scripts/assets.sh`，macOS 上 `build.sh test simulator ipa`（核心和工程只编、生成一次）跑模拟器上的测试、打不签名的 ipa，再在模拟器里截简体中文和英文两张首屏图；ipa 和截图在这次运行的 artifacts 里。master 上的构建另外发成预发布版本 `ios-dev-<提交号>`（不用登录就能下载，全部预构建见 `https://github.com/dzshzx/Zeta/releases?q=ios-dev&expanded=true`），tag 不以 v 开头、标成预发布，不影响版本号和应用内更新。

- Go 一侧的改动照常在本机测：`cd hub && go test ./mobile`（先跑过 `hub/scripts/assets.sh`），合入门禁也跑它。
- Swift 在本机只过 SwiftLint 和圈复杂度（`scripts/format.sh --lint`，合入门禁里跑）；编译、模拟器测试只在 CI 上。
- ipa 不签名：用自己的开发者账号重签后装到设备上。

## Claude Code 配置

设备页上「Claude Code 配置」一行进去（`ClaudeConfigView(backend:)`，`ios/Zeta/Views/ClaudeConfig/`），行为同桌面版 `windows/internal/ui/claudecfg/`：新对话默认（默认模型、默认思考强度、默认权限模式、上下文窗口）、Claude 一组（自动压缩、压缩时机、自动标题、闲置超时、那台的 Claude Code 版本和「更新」）、记忆与 CLAUDE.md、Skills、MCP 服务器、Subagent、工具权限。那台主机上的配置经 `core`（慢的经 `claudecfg.slow`）转给核心的 `config.*`、`system.*`，解析、校验和改写都在核心，Swift 只按 `helper/coreapi/config.go` 的结果画；工具权限每行的状态、一键开关会加删的规则也由核心算好。只有自动压缩、压缩时机、自动标题存在这台手机上（`claudecfg.local`）。记忆页的文件有内容时打开先预览，能切到编辑；预览用 `AttributedString(markdown:)` 只排行内样式。Skill 从「文件」里选 ZIP 导入，最大 45 MB。

## 现在没有的

账号与额度、Claude Code 配置、附件和图片、模型和权限模式的选择、子代理和后台任务页、会话的重命名、删除、置顶、搜索、MCP 的表单和网址确认、通知；Markdown 只排行内样式和代码块。iOS 退到后台后 SSH 连接会断，回到前台时中枢照常重连。
