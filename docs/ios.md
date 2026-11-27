# iOS 版

`ios/` 是 iPhone 和 iPad 上的 Zeta：经 Zeta 自带的 SSH 直连 Linux 设备，在上面用 Claude Code。和远程主机有关的功能同手机版（会话管理、对话与各种确认、输入栏、后台任务与子代理、账号与额度和中转、Claude Code 配置、诊断、通知；手机版的超级岛在这里是灵动岛），差别见最后「现在没有的」；依赖手机本机环境的（Debian 环境、手机工具、悬浮层、语音与数字助理、定时任务、通知触发、存储与备份）不做。界面用 iOS 27 SDK 自带的 SwiftUI 组件，最低系统 iOS 27。

## 结构

逻辑不在 Swift 里另写一份：和桌面版共用的 Go 中枢（仓根的 `hub/` 模块：中枢本身和它的 `chat`、`host`、`accounts`、`settings`）原样编进 App，Swift 只画界面。

```
hub/mobile/               iOS 版的核心：起中枢、自己的密钥认证、一个按方法名调用的 JSON 接口（下面「核心桥」），Go 测试在这里
hub/cmd/zeta-ios/         cgo 导出 ZetaStart、ZetaCall、ZetaFree，编成静态库（c-archive）
ios/project.yml           XcodeGen 的工程描述，构建时生成 Zeta.xcodeproj（不进 Git）
ios/scripts/core.sh       把核心编成真机、模拟器两份静态库，合成 ios/build/ZetaCore.xcframework
ios/scripts/build.sh      编核心、生成工程、交给 xcodebuild：ipa（不签名）、test（模拟器上的测试）、simulator（截图用）
ios/Zeta/Core/            Swift 一侧：Core（C 接口）、Models（JSON 的类型）、AppModel（界面读的状态）、KeyStore（钥匙串里的密钥）
ios/Zeta/Views/           SwiftUI 界面：会话列表与后台任务（Sidebar、Tasks）、聊天页（Chat）、输入栏（Composer）、设置（Settings）、Claude Code 配置（ClaudeConfig）
ios/ZetaActivity/         扩展：画灵动岛和锁屏上的实时活动（WidgetKit）
ios/Shared/               App 和扩展共用的：实时活动的定义 ZetaActivityAttributes
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
| `interrupt`、`earlier` | | 对聊天页的对话停止、载入更早的（发消息经输入栏的 `composer.send`） |
| `answer` | `request_id`、`reply` | 回应确认或提问，`reply` 是 `helper/prompt` 的 Reply |
| `core` | `backend`、`method`、`params` | 把 `method` 转给设备 `backend` 的核心，结果原样交回（`AppModel.device`）；只转 `config.*`、`diag.*`、`system.*`（各方法见 `docs/core.md`），对话、会话列表、账号这些有中枢自己的状态，走中枢的方法；阻塞，最多 60 秒，要连 MCP 服务器或跑 claude update 的几个（`routes.go` 的 `slowMethods`）等 3 到 6 分钟 |
| `claudecfg.local` | `backend` | 客户端本地存的这台的 `{autoCompact, compactPercent, compactPercents, autoTitle}`（`hub/claudecfg`，0 是 Claude Code 自己的压缩时机），和这台的默认工作目录 `cwd` |
| `claudecfg.setlocal` | `backend`，`autoCompact`、`compactPercent`、`autoTitle` 给哪个改哪个 | 同 `claudecfg.local` |
| `claudecfg.models` | `backend` | 默认模型下拉的清单 `{source, models: [{value, label, id, older}]}`：`source` 是 `relay`（中转报的具体 ID）、`harness`（Claude Code 报的清单分好两层，`older` 的收进「更多模型」；聊天页开着这台的对话、报过清单就用它，不然开一个不发消息的对话去要，要不到时是常用的别名）或 `unknown`（读不到、账号在切换，默认模型不能改）；阻塞，最多约 1 分钟 |
| `pin`、`rename` | `backend`、`id`；`rename` 另有 `title` | 置顶或取消、改名（中枢的 TogglePin、Rename） |
| `archive`、`delete`、`end` | `sessions`（`[{backend, id}]`），`archive` 另有 `archived` | 归档或取消、删除、结束 Claude Code 进程（Zeta 的经 `sessions.end`，别处运行的经 `sessions.end_elsewhere`）；没成的原因在下一次 `state` 的 `side.failed` |
| `export` | `sessions` | `{name, content}`：导出成的 Markdown；阻塞，等各台答来 |
| `search`、`sort` | `query`；`sort`（`replied`、`created`、`manual`） | 搜索框的字（在搜时 `sessions` 换成搜索结果，行上带 `snippet`）、排序 |
| `canmove`、`move` | `backend`、`id`、`target`（`{backend, id}`）、`below` | 手动排序时能不能、把会话拖到 target 的上半或下半；答 `{ok}` |
| `alerts` | | 开始收别的会话的提醒（`side.alerts`：`finished`、`waiting`、`settled`），起核心后调一次 |
| `stoptask` | `task` | 停聊天页对话的一个后台任务（后台做） |
| `watch` | `kind`（`output`、`subagent`）、`id`、`on` | 开始或停止看着后台命令的输出、子代理的记录（后台做） |
| `taskoutput`、`subagent` | `task`；`tool_use_id` | 看着的输出（`{got, text, total_bytes, truncated, error}`，已去掉终端转义）、子代理（`chat.Subagent` 的字段，`items` 整份、`rev`） |
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
| `rewrite` | `target`、`text`、`files` | 改写：剪掉条目 `target`（用户消息）和之后的，再发改过的（`conv.rewrite`） |
| `regenerate`、`drop` | `target`、`code` | 重新生成、删除这一轮；`code` 时先还原代码 |
| `restorecode` | `target`、`drop` | 把文件改回这条消息之前，`drop` 时再删这一轮 |
| `codechanges` | `target` | 还原代码会动到的 `{files, insertions, deletions}`，没有可还原的是 null；阻塞 |
| `fork` | `last` | 从条目 `last` 所在那一轮之后分支（`hub.Fork`），聊天页换到新对话 |
| `handover` | | 把别处运行的会话接过来（`hub.HandOver`） |
| `elicit` | `request_id`、`action`、`values` | 回应 MCP 表单或网址；表单不对时答 `{problems}`（键 → 原因），表单留着；阻塞 |
| `image` | `kind`、`key`、`file`、`ref`、`url` | 取聊天里的一张图（`items.Image` 的取法，同桌面版的 ImageLoader 三种），答 `{data}`（base64）；阻塞 |
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

`send`、`interrupt`、`answer`、`earlier`、`rewrite`、`regenerate`、`drop`、`restorecode`、`fork` 在后台做，没成的原因放在下一次 `state` 的 `chat.failed` 里。

## 聊天

聊天页的界面在 `ios/Zeta/Views/Chat/`，行为照桌面版的 `chatview`，交互照手机版。

- Markdown：`MarkdownView` 自己拆块（标题、列表与任务列表、引用、表格、代码块、公式、分隔线、单独一行的图），行内样式交给 `AttributedString(markdown:)`；每块是 Equatable 的视图，流式输出时只重画变了的块。表格和代码块横向滚动，代码块能复制。公式（`$$…$$`、`\[…\]`、`math` 代码块）先按原文等宽显示，还没有 RaTeX。
- 条目：工作组是一张折叠卡，思考显示想了几秒，改文件的工具画红绿对比（`clipped` 时注明只显示了一部分）；另一个会话发来的消息注明来自哪个会话。图片（工具结果里的、随消息发的、回答里引用的）经 `image` 取，按 `cacheKey` 只取一次；网络图片等点一下才下载，设置里开了自动加载（`chat.auto_images`）时直接下载。点图看大图：缩放、拖动、双击复原、保存到相册、分享。
- 长按消息：复制、收藏为常用指令、改写、还原代码、删除这一轮；一轮的最后一项上还有重新生成、删除这一轮、从这里分支（这一轮在进行时不出现，`can_rewind` 为假时变灰）。重新生成、删除、还原代码先确认，确认框里先问 `codechanges`。改写只带文字和附带的路径，图片不带。
- 工具栏的放大镜在对话内查找：按条目的文字（工作组按各步的文字）找，上一个、下一个滚到那一项并铺底色。
- 会话在别处运行时，输入栏上方是接管卡片（照 `chat.occupant` 说那边在哪、在做什么，能接管时点「接管」）。
- 往上翻到顶自动载入更早的；离开底部时右下角有回到底部。

## 会话列表、后台任务与通知

- 会话列表（`Views/Sidebar/`）：行尾标核心报的忙闲（`state` 会话行的 `activity`、`tasks`：等你回应、在回答、后台任务在跑、有新回答），已结束的标题灰。右滑置顶，左滑归档、删除，长按有置顶、改名、归档、结束、导出、删除；删除、结束先确认。导出交回的 Markdown 存进临时目录，用系统分享面板交出去。顶栏「更多」里选排序、进多选（List 的 EditMode，底栏批量结束、归档、删除）、看已归档；手动排序时编辑状态下拖动行（`move`）。`.searchable` 的字交给 `search`。
- 后台任务面板（`Views/Tasks/`）：会话列表顶上一行进，行尾是后台对话加当前对话在跑的任务的个数；上面是后台对话（`side.background`，点了切过去），下面是聊天页这个对话的后台任务，点进去看详情和子代理页。聊天页输入栏上方是任务清单条和后台任务条（`ChatTaskBar`）。详情页、子代理页打开时 `watch`，每次 `state` 变了重取 `taskoutput`、`subagent`，离开时停止看着。
- 通知（`Core/Notifier.swift`，照桌面版）：App 不在前台，或在前台但聊天页开着的不是那个会话时（banner），聊天页的对话答完一轮（`chat.finished`）、来了确认或提问，别的会话照 `side.alerts` 答完一轮或开始等你时发一条；每个会话一个编号（`zeta-<设备>/<会话>`），后一条换掉前一条，点了打开那个会话；等你回应的那条在请求处理掉后撤掉。第一次要发时请求通知权限。每件事（开始在跑、等你、答完、不再等）也交给 `Notifier.onEvent`。退到后台时聊天页的对话在跑就申请一段后台时间（`beginBackgroundTask`），答完或系统收回时结束；系统给多久不由 App 定，之后 SSH 断开，回到前台再重连。
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

## 灵动岛与实时活动

每个在回答或等你回应的对话在灵动岛和锁屏上有一个实时活动（ActivityKit 的 Live Activity，同手机版超级岛的「每个对话一条」）：在回答时写核心给的那句、按这一轮开始的时刻走秒，等你回应时写等的是什么，不再在跑时改成「答完了」、8 秒后收起；点它打开那个会话（网址的 scheme 是 App 的包名，正式版和测试版各开各的）。聊天页开着的对话照它自己的状态，别的会话照会话列表报的忙闲；还没有会话号的新对话不显示。

- 定义在 `ios/Shared/ZetaActivity.swift`（App 和扩展都编它），App 一侧每份新的 state 对一遍（`ios/Zeta/Core/LiveActivities.swift`：该有的建上或更新，不该有的结束），画法在扩展 `ios/ZetaActivity/`。显示的字由 App 按界面语言出好交给扩展，扩展里没有文字表。
- 只由 App 更新，没有推送服务（APNs）：App 退到后台、SSH 断开后活动停在最后的样子，回到前台再对上。启动时先收掉上次运行没来得及收的活动。
- 扩展的包名是 App 的加 `.activity`：`scripts/build.sh` 经 `ZETA_BUNDLE_ID` 定 App 的包名，不在命令行上直接改 `PRODUCT_BUNDLE_IDENTIFIER`（那样扩展也会被改成同一个，嵌不进 App）。

## 设置

首页照手机版：常规、设备（每台一行，右边写那台的额度已用比例，在用中转时写「中转 名字」，「连接」关着写已断开）、密钥（公钥的复制分享、导入私钥）、诊断、关于。页面都在 `ios/Zeta/Views/Settings/`，读的是上面那组方法；`AppModel.revision` 每取到一次 `state` 加一，各页按它重读自己的那份。

- 设备页：最上面「连接」开关；名字、地址、默认工作目录（改时照桌面版先核对，连不上或「连接」关着时问仍然保存还是取消）；「账号与额度」和「Claude Code 配置」两行（「连接」关着时灰着）；主机指纹；删除。
- 账号与额度：账号和中转的逻辑都在中枢的 `hub.Remote(id).Accounts`、`.Quota`，界面只画核心给的。切换账号、中转先确认；添加账号时显示授权网址（在 Safari 里打开）和填授权码的框；中转的名称加好以后不能改，密钥遮着、能显示，Zeta 改不了的（`keyEditable` 为假）灰着说明。在用中转时没有订阅额度一组。
- 诊断：运行日志、环境（版本、系统、机型、各台怎么连）、各台设备上的日志（核心的 `diag.files`、`diag.tail`，经 `core` 转发，看最后 64 KB，能复制、刷新），导出诊断包后用分享面板交出去。
- 关于：版本、源码、鸣谢（只列 iOS 版用到的）、开源许可（`hub/assets` 里的全文：Zeta 自己的、App 链接的 `ios` 一组、带到设备上的 `host` 一组）。
- 常规：「自动加载网络图片」（`autoLoadRemoteImages`，存进 `hub/settings`）；界面语言跟随系统，一行跳到系统设置里本 App 的语言；外观跟随系统。

## Claude Code 配置

设备页上「Claude Code 配置」一行进去（`ClaudeConfigView(backend:)`，`ios/Zeta/Views/ClaudeConfig/`），行为同桌面版 `windows/internal/ui/claudecfg/`：新对话默认（默认模型、默认思考强度、默认权限模式、上下文窗口）、Claude 一组（自动压缩、压缩时机、自动标题、闲置超时、那台的 Claude Code 版本和「更新」）、记忆与 CLAUDE.md、Skills、MCP 服务器、Subagent、工具权限。那台主机上的配置经 `core` 转给核心的 `config.*`、`system.*`，解析、校验和改写都在核心，Swift 只按 `helper/coreapi/config.go` 的结果画；工具权限每行的状态、一键开关会加删的规则也由核心算好。只有自动压缩、压缩时机、自动标题存在这台手机上（`claudecfg.local`）。记忆页的文件有内容时打开先预览，能切到编辑；预览照聊天里回答的样子排（`MarkdownView`，去掉开头的 --- 前言块）。Skill 从「文件」里选 ZIP 导入，最大 45 MB。

## 构建与测试

本机和本机 runner 都没有 macOS，iOS 版只在 GitHub 托管的 macOS 上编（`.github/workflows/ios.yml`，`xcode-27` 镜像，挑其中版本最高的正式版 Xcode）：只手动触发，`gh workflow run ios.yml --ref <分支>`，推送不跑。一次运行：Linux 上跑 `hub/scripts/assets.sh`，macOS 上 `build.sh test simulator ipa`（核心和工程只编、生成一次）跑模拟器上的测试、打不签名的 ipa，再在模拟器里截简体中文和英文两张首屏图；ipa 和截图在这次运行的 artifacts 里。在 master 上跑的另外发成预发布版本 `ios-dev-<提交号>`（不用登录就能下载，全部预构建见 `https://github.com/dzshzx/Zeta/releases?q=ios-dev&expanded=true`），tag 不以 v 开头、标成预发布，不影响版本号和应用内更新。

- Go 一侧的改动照常在本机测：`cd hub && go test ./mobile`（先跑过 `hub/scripts/assets.sh`），合入门禁也跑它。
- Swift 在本机只过 SwiftLint 和圈复杂度（`scripts/format.sh --lint`，合入门禁里跑）；编译、模拟器测试只在 CI 上。
- ipa 不签名：用自己的开发者账号重签后装到设备上。

## 现在没有的

和手机版比，下面这些没做或不一样：

- 公式按原文等宽显示，没有排版（手机版和桌面版用 RaTeX）。
- 输入栏不能拍照（能从相册和「文件」里选）；斜杠命令里没有 `/config`、`/permissions` 这两条跳设置页的。
- 改写一条消息时只带回文字和文件路径，原消息里的图不带回去。
- 通知上没有「允许」「拒绝」这类按钮，要回应就点通知回到 App（同桌面版）。
- iOS 退到后台后 SSH 连接很快会断：通知和灵动岛只在退到后台后系统给的那一小段后台时间里更新（时长由系统定），之后停在最后的样子，回到前台时中枢照常重连、再对上。要在后台一直更新得有推送服务，Zeta 没有。
