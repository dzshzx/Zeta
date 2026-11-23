# Go 核心

Zeta 的逻辑往 Go 写的核心里搬：核心是每台主机（手机的 Debian、远程主机）上的 `zeta-helper serve`，客户端只剩界面和平台专有的功能。这一页写目标、现在做到哪一步、接下来按什么顺序做。

## 目标

- 核心：`zeta-helper serve`，和现在的 zeta-helper 是同一个程序（`helper/`）。客户端经一条常驻连接和它说话：远程主机上是一条 SSH 通道（经手机 Debian 的 OpenSSH 或直连都一样），手机本机是进 Debian 的那条管道，不开端口。状态在它的内存里（会话列表、对话的输出流归约、确认与提问的排队、后台任务），会话目录用 inotify 盯着，只推送变了的。Claude Code 自身配置的读写（记忆、Skills、MCP、Subagent、工具权限、新对话的默认值）和账号与额度也在这里，几个客户端共用，不在各客户端里各写一份。
- 一个服务端：所有平台的客户端读同一个服务端。每台主机、每个用户、每个包（`ZETA_NAME`：正式版 `zeta`，测试版 `zeta-dev`，各端的测试版同包）只有一个核心，套接字是 `~/.cache/<ZETA_NAME>/core/core.sock`，不按 helper 版本分。逻辑只在核心里一份：serve 只管连接、拉起核心和 ping，所有方法（含 `sessions.`、`config.`、`accounts.`、`quota.`、`storage.`、`files.`、`images.`、`system.`）都转给核心，核心里按方法前缀分发（`core.Mux`，`helper/corehandlers.go`）。
- 新的接手：helper 编进构建序号（`helper/` 最后一次提交的时间，`git log -1 --format=%ct -- helper`，经 `-ldflags` 写入），`zeta_hello` 带 `build`（序号）和 `version`（内容哈希）。serve 比在跑的核心新时发 `core.handover`：旧核心停止接新连接，关掉到全部托管进程的连接（进程照跑），删套接字退出；serve 随即用自己的程序拉起核心，新核心扫托管进程，把在回答、等确认、有后台任务的接上，闲着的不动（看门狗按闲置超时收），客户端下次发消息时照常接回。serve 比核心旧、或序号相同（同一份 helper 源码的不同开发构建）时直接用在跑的核心，不交班。
- 换一个看得到新挂载的核心：手机上每次进 Debian 都在一个新的私有挂载环境里（`zeta_enter.sh` 的 `unshare -m`），共享文件夹在进入时挂上，常驻的核心和它起的 Claude Code 只看得到核心启动那一刻的挂载和组。共享文件夹增删以后，客户端经一次新的进入跑 `zeta-helper core-takeover`：在跑的核心不论版本都交班，由这个进程拉起新核心（`helper/takeover.go`）。进行中的对话照「新的接手」接上；已经在跑、看不到新文件夹的 Claude Code 由客户端按 `host_key` 判断要不要重起：先结束它（`conv.end`），再跑 `core-takeover`，下次发消息时由新核心起。顺序不能反：新核心不接闲着的托管进程，先换核心的话结束不到它，下次 start 又会接回这个看不到的（Android 的引用文件夹、路径对话框，`ui/chat/AttachPathDialogs.kt`）。
- 协议只做加法：新方法、新字段可以加，已有的不改不删；客户端忽略不认识的字段，核心对缺的字段按零值处理。非改不可的不兼容改动提升 `protocol`，比核心旧的客户端连上时明说「主机上的 Zeta 服务比这个客户端新，请更新」。
- 托管层不动：`zeta_host.sh` 照旧托管 Claude Code 进程，serve 重启、升级或崩溃都不结束进行中的对话；serve 里存的只是能重建的状态。
- 客户端：Android 只用 Kotlin（界面用 COUI）写 Android 原生层（界面、手机工具里调系统接口的部分、悬浮层、无障碍、虚拟屏、数字助理、语音、通知与前台服务、闹钟、应用内更新、进 Debian 的管道），加一层协议转换：核心推来的 JSON 换成界面状态、用户操作换成请求，不写业务规则，核心算好的值（用量的比例与档位、确认的种类与说明等）直接用；其余能用 Go 写的都用 Go；Windows 是 Go + mygo 的原生界面（`windows/`）。客户端共用、和界面无关的 Go 代码在仓根 `client/`（SSH 直连 `client/sshlink`；在主机上跑命令、装 zeta-helper、到 serve 的连接 `client/hostlink`）：Windows 直接链接；Android 经随 APK 带的 Go 程序 zeta-ssh 用它直连远程主机，手机不装 Debian 也能连（见「Android 直连（zeta-ssh）」）。`shared/` 里的逻辑搬完后这个模块撤掉，界面回 `app/`。
- 手机工具：Claude 调手机工具时，serve 把请求转给给这个对话登记了手机能力的客户端（和现在 `PhoneBridge` 的做法一样）。
- 测试：核心的测试都是 Go 测试，在 Linux 上跑；界面测试用录好的协议消息喂界面，不依赖真核心。

## 协议

一行一个 JSON，写法和方法清单以 `helper/serve.go` 开头为准。要点：

- 连上先收到 `zeta_hello`，带协议版本 `protocol`（`helper/serve.go` 的 `serveProtocol`，Kotlin 一侧是 `HelperServe.PROTOCOL`）、这个 serve 的构建序号 `build` 和版本 `version`，以及主机名 `hostname`（`os.Hostname`，和 `uname -n` 同一个值；查不到时是空串）。`protocol` 在改已有消息的含义时加一，只加方法、字段不加。客户端每次连上时拿 `hostname` 更新设备的主机名（界面上没起名字的设备就叫它；Kotlin 是 `ConversationHub.hostnameReported`），不另跑 `uname -n`；加设备时的检查（`RemoteHosts.ProbeScript`、Windows 的 `host.Probe`）在装 zeta-helper 之前跑，照旧自己查。
- 客户端认得这些新方法、新字段，就说明它连的核心也有：客户端起的是自己带的那个版本的 serve，核心不比 serve 旧（见下一条和「目标」的新的接手）。所以客户端不用先探测核心支不支持。
- 请求带 `id` 和 `method`，每个请求回一行 `zeta_reply`；订阅类的方法之后另有推送。客户端要按原因分别处理的错误带 `code`（各方法的说明里列着），没带的只给人看。慢的方法（`config.`、`accounts.`、`quota.`、`storage.`、`files.`、`images.`、`system.`，以及 `conv.` 里要等 Claude Code 应答或等别处进程的：`conv.earlier`、`conv.quota`、`conv.task_output`、`conv.code_changes`、`conv.occupant`、`conv.handoff`，`sessions.` 里订阅以外的，见「会话」）各自做完再答，不挡着同一条连接后面的请求，应答的先后不一定和请求一样，客户端按 `id` 对应答。
- 客户端每 30 秒发一次 `ping`；serve 输入结束或 90 秒收不到任何一行就退出，不在主机上留下没人用的进程。
- 版本：每个客户端起自己那个版本的 serve（程序按内容哈希装，见 `HelperProgram`），serve 只转发；同一个包只有一个核心，版本新的接手（见「目标」）。
- 会话列表：`sessions.watch` 订阅，先推一行整份的 `zeta_sessions`（`full` 为真），之后会话目录有变化时只推变了的几项（`helper/watch.go`）；再发一次再给一份整份的；`sessions.unwatch` 退订，同一条连接上可以接着做别的。每一项是 `{id, file, cwd, title, preview, updated, external, archived, pinned, live}`（`live`：有还活着的 Claude Code 进程登记着这个会话，Zeta 托管的、终端里的都算；为假是已结束的会话，侧栏灰着显示。进程被杀时登记文件常常留着，所以订阅每 10 秒还核对一次登记的进程在不在，变了就推）；列的是最新的 300 个会话，加上置顶了却不在这 300 个里的（排在最后），所以置顶的总在列表里。
- 协议里的类型和客户端也要照着做的规则（导出文件名与几份导出的合成、标题怎么清理、接管的类型与状态、错误代号、任务清单的项、子代理页的状态）在 `helper/sessionproto`：只放类型、常量和纯函数，不依赖核心的其余部分，核心和 Go 写的客户端（Windows）都导入它，不各写一份。
- Go 写的客户端调核心经 `helper/coreapi`：下面各表里的每个请求方法在那里有一个带类型的函数（参数是 Go 参数或结构体，结果解成结构体），客户端不自己拼方法名、参数和结果类型；推送不经它，客户端自己解。加方法、改名时这里的表和 `coreapi` 一起改，`TestMethodsMatchTheDocs` 核对两边一一对上。

## 对话

对话的状态在核心进程 `zeta-helper core` 里（服务端框架在 `helper/core`，对话逻辑是挂在上面的处理器 `helper/conv`）：每台主机上每个 Linux 用户、每个包（`ZETA_NAME`）一个，监听 `~/.cache/<ZETA_NAME>/core/core.sock`（目录只有本人能进；测试用环境变量 `ZETA_CORE_DIR` 换个目录）。只起一个靠同目录的 `core.lock`：正在跑的核心持着它（flock，里面写着进程号），已有核心在听时新起的直接退出，上一个崩溃留下的套接字文件由拿到锁的那个清掉再听。连上时核心先发一行 `core_hello`（`build`、`version`），serve 读掉它决定用这个核心还是让它交班，不转给客户端。客户端不直接连核心：serve 收到 ping 以外的第一个请求时连上它，没人在听就用自己的程序拉起一个（`core` 子命令，自成一个会话，标准错误追加到同目录的 `core.log`），等它听上，约 5 秒为限，连不上时这个请求答出错；几个 serve 同时拉起时由锁保证只留一个。之后请求原样转过去，核心的应答和推送原样写回客户端。核心没了（崩溃、被结束、交班）时 serve 跟着退出，客户端照常重连，重新订阅、打开它的对话。

- 托管不变：核心经 `zeta_host.sh`（随 zeta-helper 编进程序里，`helper/host/`）起和接 Claude Code 进程，自己算一个接着的一方。核心重启、升级、崩溃都不结束进行中的对话：核心起来时扫一遍托管进程（`zeta-helper hosts`），在回答、等确认、有后台任务的接上（有会话记录的先载入历史，从输出日志的下一行读起，还没答的确认重新排上，用户在这个对话里的权限模式照托管目录的 `user_mode` 恢复：核心在它变了时写），闲着的断开；客户端之后打开同一个会话号拿到的就是接上的那个；Android 客户端收到 `conv.activity` 时，把在进行、自己还没打开的会话按会话号打开放在后台（`ConversationHub.openRunning`，每个会话只开一次），提醒和胶囊跟得上它。闲着被断开的，下次发消息时 `zeta_host.sh start` 照常接回同一个进程。
- 谁接着：一个对话被打开它的客户端「拿着」（`conv.open` 到 `conv.close`，连接断了也算放开）。没人拿着、又闲着（不在回答、不在等确认、没有后台任务）满 30 秒（还没发过消息的分支满 10 分钟：它没有会话号，收掉就没了，客户端断线重连时按 `key` 重开），核心断开那个托管进程（`Link.Leave`：只关连接，进程照跑；没有别的客户端接着时，看门狗过这台主机设的闲置时长后收掉它，默认十分钟，见「主机」的 `system.idle`；这之前打开那个会话发消息，接回的是同一个进程）；没人拿着但还在进行的，核心接着归约，进行完了再这样断开。核心没有连接、也没有接着的对话（处理器说自己闲着）满 60 秒就退出；收到 SIGTERM 时也干净退出，都会删掉自己的套接字文件。
- 同一个会话只有一份状态：几个客户端（手机、电脑）打开同一个会话号拿到的是同一个对话，各自收到同样的推送，谁发的消息、谁答的确认都对所有人生效。

请求都带 `conv`：客户端自己给对话起的名字，在这条连接里不重复，`conv.open` 时起好，之后的请求和推送都用它（名字先定下来，打开后紧跟着的推送就不会比应答先到却对不上号）。参数和 `id`、`method` 平级，请求的写法以 `helper/conv/manager.go` 的 `request` 为准：

| 方法 | 参数 | 做什么 |
|---|---|---|
| `conv.open` | `session`（接着已有会话时）、`file`（它的会话记录，可省：只给会话号时核心自己找）、`key`、`cwd`、`title`、`options` | 打开一个对话。带 `key`（`conv.state` 里的）时重新打开核心里的那个对话，它已经不在时：带了会话号就按会话号打开，没带就答出错，`code` 为 `conversation_gone`。有会话号且核心里已有这个会话时拿到的就是它；接着推一份整份的 `conv.state` 和 `conv.items`。历史只载入最近一段（约 800 条事件），更早的有多少记在 `earlier` |
| `conv.close` | | 这个客户端放开它 |
| `conv.options` | `options` | 改起进程用的设置（见下），下次起进程时生效 |
| `conv.send` | `text`、`images`（`[{media_type, data}]`）、`files`（主机上的路径：客户端自己先把本地文件传到主机上，核心照终端版在消息前加 `@"路径"`）、`mode`、`rules` | 发一条消息；回答进行中时排队。`!` 开头的一行（没有附件时）照终端版直接执行命令（`bash_command`）；输出回放出来以后，核心把命令和输出两行原样当 `shouldQuery` 为假的用户消息交回去，和终端版一样进上下文和会话记录，下一轮模型看得到，这两条不算一轮（`helper/conv` 的 `shellIntoContext`）。整条只是 `/exit` 或 `/quit`（Claude Code 的 exit 命令和别名，没有附件时）不发给 Claude Code（它在 stream-json 下不认），照 `conv.end` 结束进程，不进会话记录。`mode`、`rules` 是后台运行（定时任务、通知触发）这一条用的权限模式和叠加的规则，用户自己发的不带 |
| `conv.interrupt` | | 停止这一轮，排队的消息撤回 |
| `conv.steer` | | 立即插话，同终端的 send now：Claude Code 认 `interrupt_send_now_v1`（init 的 capabilities）时发带 `send_now` 和排队第一条 `message_uuid` 的 interrupt。应答 `delivering` 时正在跑的工具挪到后台、消息并进这一轮，这一轮不停；`stopped`、`interrupted` 时这一轮以「已打断，先处理插话」这条提示收尾、排队的消息接着开始（被打断的那一轮不单推 `conv.turn` finished，插话答完时推一次）；`nothing_waiting` 时什么也不做。不认时发普通的 interrupt |
| `conv.cancel` | `uuid`、`recall` | 撤回一条排队的消息；`recall` 时放回输入框（`recalled`） |
| `conv.take_recalled` | | 输入框取走 `recalled`，`result` 是那条消息（`{uuid, text, images, files}`，没有时为 `null`） |
| `conv.answer` | `request_id`、`reply` | 回应工具确认、计划审批、提问（`reply` 见下） |
| `conv.elicit` | `request_id`、`action`、`values` | 回应 MCP 表单（`action` 是 accept、decline、cancel）。`values` 是字段键 → 字符串数组（多选几项就几个，其余一个），核心按表单的类型换成 Claude Code 要的值；有字段不合要求时不交出去，`result` 是 `{errors: {键: 原因}}` |
| `conv.model`、`conv.effort`、`conv.mode` | `value` | 只管这个对话的模型、思考强度、权限模式；`null` 回到默认 |
| `conv.preset` | `model`、`effort`、`hint` | 定时任务、数字助理给这个对话预设模型、思考强度，`hint` 追加进 `--append-system-prompt`（下次起进程时生效）；空的那项照旧 |
| `conv.compact` | | 压缩上下文 |
| `conv.prepare` | | 只起好进程、不发消息（不开一轮），`commands`、`models` 随 initialize 的应答到来，输入斜杠命令、看模型清单前用。按这个客户端的 `options` 起；已有进程、会话被别处占着（`blocked`）、历史还在载入、正在剪会话时什么也不做；起不来时同发消息时一样在列表里加一条「启动失败」。起好的进程闲着，没人拿着时照常满 30 秒断开 |
| `conv.stop_task` | `task` | 停一个后台任务 |
| `conv.task_output` | `task`、`watch` | 不带 `watch` 时读一次后台命令的最后 8 KiB，`result` 是 `{text, total_bytes, truncated, error}`。带 `watch` 时立即应答（`result` 为 `null`）：为真时开始给这个客户端推这个任务的 `conv.task_output`，为假时停；放开对话、断开时也停。有人看着时核心每秒问一次 Claude Code，输出变了才推；任务结束后再读一次就不再问。客户端不用自己定时拉 |
| `conv.end` | | 结束进程（等于终端里 `/exit`），对话内容留着，下次发消息时 `--resume` 接上 |
| `conv.occupant` | | Zeta 之外占着这个会话的进程（终端里的 Claude Code，或另一个包托管的）的样子：`result` 是 `{status, waiting_for, hands_over}`（`helper/sessionproto` 的 `Occupancy`），没人占着时 `status` 为 `null`。`blocked` 时核心自己盯着，推在 `conv.state` 的 `occupant` 里，「别处运行」卡片照那份画，不用轮询这个方法 |
| `conv.handoff` | | 用户点「接管」时发：把会话从别处接过来（同 `zeta-helper handoff`：那边闲着或在等确认时让它退出，正忙时不动），要等那边退出，最多十几秒。`result` 是 `{handed, status}`（`sessionproto.Handoff`）；交出来了（没人占着也算）就解开 `blocked` 并推 `conv.state`，没交出来时 `status` 是那边的状态，发了中断还不退的是 `stuck`。那边自己退出时核心自动解开，客户端不用代发 |
| `conv.mcp_reply` | `request_id`、`response` | 手机工具的应答（见 `conv.mcp`） |
| `conv.earlier` | `rest` | 载入更早的一页接到最前面（`rest` 为真时载完剩下的全部，对话内查找用）。新的一页经 `conv.items` 推成一次替换（`at` 0、`remove` 0），不推整份，客户端据此保住正看着的位置；读完才回应答。同一时间只读一页，正在读时答出错。会话记录在别处被改过（比如终端里剪到了更早的地方）时答出错，`code` 为 `history_changed`（「会话记录在别处改过，重新打开这个对话再看更早的内容」），重试没用 |
| `conv.rewrite` | `target`、`text`、`images`、`files`、`resend` | 编辑或重新生成：`target` 是那条用户消息的条目 `id`。请 Claude Code 剪掉它和之后的内容（`rewind_conversation`，会话号不变），剪成了再发新消息；没剪成时列表恢复原样、新消息放回 `recalled`，列表里加一条出错提示，这一轮以 `failed` 结束。`resend` 为真时是重新生成：照 `target` 那条消息原样重发，文字、图片（会话记录里的由核心自己取回）和附件路径都由核心从那一项取，`text`、`images`、`files` 不看；取不回图片时答出错、什么也不剪。`can_rewind` 为假、找不到这一项时答出错；剪的期间 `conv.send` 也答出错，客户端把字留在输入框里 |
| `conv.drop` | `target` | 删掉这一轮和之后的（只剪不发），没剪成时恢复并提示原因 |
| `conv.code_changes` | `target` | 把文件改回这条消息之前会动到哪些（`rewind_files` 的 dry_run，只看不改），`result` 是 `{files, insertions, deletions}`，没有可还原的为 `null` |
| `conv.restore_code` | `target`、`drop`、`text`、`images`、`files`、`resend` | 把文件改回这条消息之前（终端 `/rewind` 的 Restore code）。还原成了再接着做：带了新消息（`text`、`images`、`files` 同 `conv.rewrite`）或 `resend` 为真（照 `target` 原样重发，同 `conv.rewrite`）时照 `conv.rewrite` 剪掉这一轮再发，剪不成同样放回 `recalled`；没带新消息、`drop` 为真时删这一轮。没还原成时不剪，带着的新消息放回 `recalled`（还没开始一轮，不推 `conv.turn`）。结果以列表里的提示告知 |
| `conv.title` | `title` | 改名：照 `zeta-helper rename` 往会话记录里追加一行 custom-title（空白压成一个空格、最多 200 字），写成了才换 `title` 并推送；进程开着时也请 Claude Code 改名（`rename_session`）。这是用户起的名字，之后不再自动起标题。还没有会话号（新对话、分支还没发过消息）时答出错 |
| `conv.fork` | `as`、`last`、`options` | 从条目 `last` 所在那一轮之后分支：新对话由这个客户端以名字 `as` 拿着（同 `conv.open`，推整份；不带 `options` 时沿用这个客户端给原对话的），原对话不动。原对话还没有会话号、或下一轮剪不准时答出错。新对话先显示到那一轮为止的内容，标题是「分支 · 原标题」（重名时编号），发出第一条消息时 Claude Code 才从原会话复制出新会话（`--fork-session`）；从中间分支时先剪掉下一轮 |
| `conv.quota` | | 借这个对话开着的进程问一次订阅额度（`get_usage`），`result` 是应答里的 `response` 原样（交给 `quota.usage`，见「账号与额度」）；没有进程时出错。核心自己也会存额度（见「账号与额度」），这个方法只在客户端要马上刷新时用 |
| `conv.subagent` | `tool_use_id`、`watch` | 子代理查看页：`watch` 为真时开始给这个客户端推这个子代理（`tool_use_id` 是发起它的 Agent 调用）的 `conv.subagent`，为假时停；放开对话、断开时也停。有人看着时核心增量读它的记录 |
| `conv.activity` | `watch` | 不带 `conv`。订阅（`watch` 省掉或为真，可以和 `sessions.watch` 一样只发方法名）或退订这个核心里各会话的忙闲，见推送 `conv.activity` |

`options`（都可省，写法见 `helper/conv/conv.go` 的 `Options`）：`env`（只给这个 Claude Code 进程的环境变量，比如自动压缩）、`system_prompt`（`--append-system-prompt`）、`mcp_config`（`--mcp-config`，手机工具）、`phone`（这个客户端处理手机工具的调用）、`auto_title`（一轮后请 Claude Code 起标题）、`compact_percent`（自动压缩的百分比，用量条用）、`desk`（这台主机是远程主机，不是客户端所在的设备：新会话拿到会话号时、之后每次起进程时，核心在远程的「Zeta」tmux 会话里补开它的窗口，回到电脑前 `tmux attach -t zeta` 接着；删除、归档时由 `sessions.delete`、`sessions.archive` 关窗口，见「会话」）。

`options` 按客户端分开记：起进程时用发起这次起进程的那个客户端的（发消息、压缩等），`mcp_config` 只在有声明 `phone` 的客户端拿着这个对话时才带上。

`reply`（`helper/prompt/reply.go` 的 `Reply`）：`{"kind":"allow"}`、`{"kind":"allow_always"}`（照建议以后不再问）、`{"kind":"deny","feedback":…}`（不写理由时和终端里按 Esc 一样停下这一轮）、`{"kind":"approve_plan","mode":…}`、`{"kind":"answer","answers":{问题:回答},"notes":{问题:备注}}`、`{"kind":"clarify","answers":…,"notes":…}`。

推送（都带 `conv`）：

- `conv.state`：对话的状态。`full` 为真时是整份，否则只有变了的字段。字段：`key`（这个对话在核心里的标识，整个核心里不重复，`conv.open` 可带它重开）、`session`、`title`、`cwd`、`process`（有没有接着进程）、`busy`、`status`（等待指示旁的一句，比如正在重试）、`tasks`（后台任务清单，每项是 `helper/tasks/task.go` 的 `Task`）、`queued`（排队的消息）、`steering`、`prompts`（等用户回应的请求，按到达顺序，界面弹第一个；每项是 `helper/prompt/prompt.go` 的 `Prompt`，`kind` 为 `permission` 或 `elicitation`；后台子代理发起的带 `agent_id` 和 `agent_label`：确认框上它的叫法，是发起它的 Agent 调用写的说明，找不到时是「后台子代理」）、`recalled`、`model`（这个对话指定的）、`reported_model`（Claude Code 报的）、`effort`（没选时为 `null`，同 `model`）、`mode`（`{user, process, shown}`：用户在这个对话里的、进程现在用的、输入栏显示的；`shown` 是 `user`，没有时是 Claude Code 设置里的默认，都不知道时为空串）、`model_from_session`（模型由 Claude Code 照会话记录定：接着已有会话或是分支，又没在这个对话里指定模型；模型菜单这时不勾任何一项）、`commands`（斜杠命令，Claude Code 的 initialize 应答里那份，`__` 开头的内部命令和没有名字的项已滤掉。核心按工作目录记着每台主机上最近一次拿到的，存在核心目录的 `commands.json`（丢了只是少一份提示），所以还没起进程时打开对话也直接给出来：先找同一工作目录的，没有时用这台主机上最近拿到的那份；都没有时为 `null`）、`models`、`usage`（用量条，`helper/usage/snapshot.go` 的 `Usage`）、`todos`（Claude 当前的任务清单，没有时是空数组。每项 `{key, content, active_form, status}`（`sessionproto.Todo`）：`status` 是 `pending`、`in_progress`、`completed`，`active_form` 是进行中时的说法，没有时同 `content`。核心归约时顺手维护：TaskCreate、TaskUpdate 和 TodoWrite 都算，任务工具的清单不空就用它，否则用最后一次 TodoWrite；旧清单全部做完后又新建任务算开了一份新清单；接着旧会话时连同没载入的更早历史一起还原。规则在 `helper/conv/todos.go`，同 Claude Code 给远程会话还原清单的做法）、`loading_history`、`earlier`（会话记录里还没载入的更早事件数，0 是全载入了）、`earlier_loading`（正在载入更早的一页）、`can_rewind`（现在能不能编辑、重新生成、删除一轮、还原代码、分支：这一轮结束了，没有等确认、排队的消息，没在剪、没在载入历史，会话没被别处占着）、`blocked`（会话正被 Zeta 之外的进程占着：打开时有别处的进程占着就为真（核心里已有这个会话、又接着自己的进程时不查：终端里接回时 SessionStart hook 让这边的进程退出，核心随之锁上），起进程时才发现被占着也会变真；已有的对话因为另一个客户端打开而锁上时，拿着它的客户端一起收到；为真时发消息、压缩答出错，`conv.handoff` 接过来后解开，那边自己退出了核心也自动解开）、`occupant`（`blocked` 时占着会话的那个进程的样子，同 `conv.occupant` 的 `result`：`{status, waiting_for, hands_over}`，「别处运行」卡片照它说话、`hands_over` 为真时给「接管」；不 `blocked` 或还没查到时为 `null`。`blocked` 期间核心每 3 秒查一次，每个对话只查一份，变了才推；查到没人占着就解开 `blocked`）、`host_key`（接着的托管进程的标识，托管目录是 `~/.cache/<ZETA_NAME>/hosts/<host_key>`，客户端拿它找那个进程，比如看新挂上的共享文件夹它看不看得到；没接着进程时为空）、`rewinding`（正在等 Claude Code 剪会话或还原代码，谁发起的都算；这期间输入栏不收字）、`last_activity`（最近一次有动静的时刻，Unix 毫秒，取整到秒：发出消息、一轮开始、输出改了列表、重试、压缩；客户端拿它算「已等待 N 秒」，0 是还没有过）。
- `conv.items`：聊天条目（格式见 `helper/transcript/items.go`）。`full` 为真时 `items` 是整份；否则是一次替换：从下标 `at` 起去掉 `remove` 项、换成 `items`。流式回答时通常只换最后一两项。用户消息（`kind` 为 `user`）另有核心算好的这一轮能做什么，规则同 `conv.rewrite`、`conv.fork` 执行时的核对（`helper/conv/rounds.go`），变了随条目推：`rewindable`（Claude Code 认得这条消息，能编辑、重新生成、删除这一轮、还原代码）、`forkable`（对话有会话号，能从这一轮之后分支：是最后一轮，或下一轮的用户消息 Claude Code 认得）、`round_last`（这一轮的操作行挂在哪一项下面：这一轮最后一项不是灰色提示的，即回答、工作过程或出错提示；这一轮除了用户消息只有灰色提示时省掉，只能从用户消息的菜单操作）。一轮从一条用户消息起到下一条用户消息之前，第一条用户消息之前的内容不属于任何一轮。这几个只看列表，真能做还要 `conv.state` 的 `can_rewind` 为真；`conv.fork` 的 `last` 给这一轮里任何一项都行。之后还有几轮（确认框里说一起丢掉多少）由客户端数后面的用户消息，不放进条目：每加一轮前面每一项都要变、要重推整个列表。
- `conv.turn`：一轮的起止，客户端据此提醒。`phase` 是 `started` 或 `finished`；结束时带 `outcome`（`done`、`stopped`、`failed`、`interrupted`：进程意外没了）和一句 `text`。
- `conv.mcp`：Claude Code 调了手机工具（`request_id`、`message`），只发给 `options.phone` 为真的客户端；那边处理完用 `conv.mcp_reply` 答。没有这样的客户端时核心直接答出错。
- `conv.reply`：新的一条回复开始了（`message_id`，子代理的也算），只发给 `options.phone` 为真的客户端：手机工具的控件编号从这时起换成上一批结果里的那份。
- `conv.phone_cancel`：这一轮被停了，只发给 `options.phone` 为真的客户端，那边把暂停、接管着的手机操作一并作废。
- `conv.subagent`：看着的那个子代理（`tool_use_id`）的记录，只发给用 `conv.subagent` 看着它的客户端。条目和 `conv.items` 同格式、同增量规则（第一次 `full`）；另有 `meta`（`{agent_id, agent_type, description, model, finished, stopped, background}`；记录旁的 meta 还没读到时核心先用发起它的 Agent 调用的参数填上 `description`、`agent_type`（`agent_id` 为空串），连那次调用也不在已载入的列表里时为 `null`）、`outcome`（`completed`、`failed`、`stopped`，还没结束时省掉）、`prompt`（交给它的任务；记录还没读到时同样先用 Agent 调用的 `prompt`）、`tool_uses`、`running`、`loaded`、`missing`（记录文件还没有）、`failure`（读失败的原因）、`file`、`skipped`（只留最近一段，前面略去了几条），以及 `state`：查看页显示的状态，核心按先后定好，客户端只把它换成文案。取值（`sessionproto` 的 `Subagent*`）依次是 `running`（在跑，优先）、`loading`（还没读到记录）、`read_failed`（还没读到、读失败了，原因在 `failure`）、`missing`（记录文件没有）、`completed`、`failed`、`stopped`（结局），都不是时 `unfinished`（不在跑又不知道结局：进程没等它做完就没了）。客户端认不出的值（核心以后加的）和缺了这个字段时都当 `unfinished`。Windows 和 Android 都照它显示，不自己从别的字段推。条目以外的字段每次都是整份；只有那些字段变了时条目部分是空的替换（`at` 0、`remove` 0、`items` 空）。对同一个子代理再发一次 `watch` 为真时从整份重新推。
- `conv.task_output`：看着的那个后台任务（`task`）的输出，只发给用 `conv.task_output` 带 `watch` 看着它的客户端。字段同那个方法不带 `watch` 时的 `result`（`text`、`total_bytes`、`truncated`、`error`），每次都是整份（最多 8 KiB）；开始看时先推一份，之后输出变了才推。
- `conv.activity`（不带 `conv`，只发给订阅了的客户端）：`{sessions: {会话号: {activity, tasks, turn_end}}}`，每次变了推整份，订阅时先给一份。`activity` 是 `waiting`（等用户回应）、`answering`（在回答）、`background`（这一轮答完了，后台任务还在跑）、`idle`（进程开着、没在干活），进程没了时省掉；`tasks` 是还在跑的后台任务数；`turn_end` 是最后一轮结束的时刻（Unix 毫秒），客户端拿它算「不在眼前时答完了一轮、还没看」。只报 Zeta 的核心接着的对话，终端里自己开的 Claude Code 不在里面。

## 会话

侧栏对会话的操作（改名、归档、删除、导出、按会话号查、搜索、看图、别处占用与接管、远程主机上的 tmux 窗口）都是核心的 `sessions.` 方法（`helper/sessionmethods.go`），几个客户端读写同一份，改完的结果经 `sessions.watch` 推给所有订阅的客户端，不用另外重读：改名、归档、置顶、删除做成以后核心马上推这个会话（删除推 `remove`），不等 2 秒的防抖，也不管和上次推过的同不同，两个客户端先后改同一个会话时先改的那边才收得到改回去的这一项。参数和 `id`、`method` 平级，不带 `conv`；结果在 `zeta_reply` 的 `result`。`session` 是会话号（会话记录的文件名去掉 `.jsonl`），样子不对时答出错。除订阅外都异步应答：各自做完再答，不挡同一条连接后面的请求。改名、接管、删除的会话在核心里开着对话时，核心自己经那个对话做（同 `conv.title`、`conv.handoff`、`conv.end`），客户端不用先调 `conv.*`。

出错时认得出的另带 `code`（常量在 `helper/sessionproto`）：`no_session`（这个会话号没有会话记录，或 `sessions.image` 给的记录已经不在：会话在别处被删了，客户端把它从列表里拿掉）、`active`（`sessions.delete` 要删的会话正在进行，见那一行）。

| 方法 | 参数 | 结果 |
|---|---|---|
| `sessions.watch` | | 订阅会话列表：先推一行整份的 `zeta_sessions`，之后只推变了的几项，见「协议」 |
| `sessions.unwatch` | | 退订会话列表，同一条连接上可以接着做别的 |
| `sessions.rename` | `session`、`title` | 照 `zeta-helper rename` 往会话记录末尾追加一行 custom-title（`sessionproto.CleanTitle`：空白压成一个空格、最多 200 字；同一个会话号在几个项目目录里都有记录时写最新的那份）。`result` 是 `{title}`：实际写下的标题。压完是空的答出错。核心里开着这个会话的对话时同 `conv.title`：对话的标题跟着变，开着的 Claude Code 也改名 |
| `sessions.archive` | `session`、`archived` | 归档（`archived` 为真）或取消归档（为假）：只是列表里不显示，记录不动，标记在 `~/.config/<ZETA_NAME>/archived`（同 `zeta-helper archive`，顺手清掉记录已经不在的；改这个文件时持着同目录的 `archived.lock`）。归档时顺手关掉它在 tmux 会话 `zeta` 里的窗口。`result` 是 `{archived}` |
| `sessions.pin` | `session`、`pinned` | 置顶（`pinned` 为真）或取消置顶（为假）：只是 Zeta 的标记，记录不动。标记在 `~/.config/<ZETA_NAME>/pinned`，做法同归档（一行一个会话号，顺手清掉记录已经不在的，改时持着 `pinned.lock`），所以置顶按主机、按包分开，几个客户端看到的是同一份。`zeta_sessions` 和 `sessions.lookup` 的每一项带 `pinned`；置顶的会话不在最新的 300 个里时也列出来。`result` 是 `{pinned}` |
| `sessions.delete` | `session` | 会话正在进行时不删，答出错，`code` 为 `active`（「对话正在进行，结束后再删」）。正在进行指：核心里开着它的对话在回答、等确认或还有后台任务（`conv.activity` 的 `answering`、`waiting`、`background`）；Zeta 之外的进程占着它（列表里 `external` 为真）；本包托管、核心没接着的进程在 `~/.claude/sessions` 登记着它，状态是 busy、waiting 或 shell。不在进行时：核心里开着这个会话的对话先结束它的进程（同 `conv.end`，对话留着）、等进程退出，再删掉这个会话号的会话记录（各项目目录里同名的都删），连同 Claude Code 存在同名目录里的东西（同 `zeta-helper delete`，目录里的符号链接只删链接），再关掉它在 tmux 会话 `zeta` 里的窗口。`result` 是 `{deleted: [{file, dir}], closed}`：删掉的每份记录和它的同名目录删没删掉，`closed` 是关掉的窗口数。已经没有记录时 `deleted` 为空，不算出错 |
| `sessions.export` | `session` 或 `sessions`（会话号的数组）、`utc_offset` | 把会话导出成 Markdown（同 `zeta-helper export`，照 Eta 的格式）。`result` 是 `{title, markdown, file_name}`：`file_name` 是建议的文件名 `Zeta-<标题>-<年月日>-<时分>.md`（`sessionproto.ExportFileName`：标题去掉文件名里不能用的字符、最多 40 字）。时刻按 `utc_offset`：客户端所在时区比 UTC 快多少分钟（东八区是 480，西五区是 -300）；不带时按主机的本地时间。带 `sessions`（至少一个，带了就不看 `session`）时同一台主机上的几个会话由核心合成一份：按给的顺序，导不出来的跳过；只导出成一个时和单个导出一样，几个时 `title` 是「N个对话」（N 是导出成的个数），`markdown` 是各自的导出去掉末尾空白、各补一个换行，中间用 `\n---\n\n` 隔开（`sessionproto.CombineExports`）。这时 `result` 另带 `exported`、`failed`（导出成的、没导出成的会话号）和 `parts`（各自的导出 `[{session, title, markdown}]`，`sessionproto.ExportPart`，按给的顺序）。选了几台设备上的会话时，客户端对每台发一次带 `sessions` 的请求，把各台的 `parts` 按用户选的顺序排好，用 `CombineExports` 合成、用 `ExportFileName` 起文件名（时刻是客户端的本地时间）；只选了一台、一个会话时也走这一条，客户端不另写一种。一个都没导出成时答出错，都是因为记录不在时 `code` 为 `no_session`。客户端自己把结果存到本地 |
| `sessions.lookup` | `sessions`（会话号的数组） | `result` 是会话摘要的数组，每项和 `zeta_sessions` 推送里 `upsert` 的一项相同（`{id, file, cwd, title, preview, updated, external, archived, pinned, live}`），按请求的顺序，找不到的不在里面 |
| `sessions.search` | `query`、`search` | 在最近 300 个会话里找用户和 Claude 说的话（同 `zeta-helper search`：每个会话取第一处命中，10 秒、80 个为限）。命中只经推送给：边找边推 `{"type":"sessions.match","search":<名字>,"id":<会话号>,"role":"user"\|"assistant","snippet":…}`；`search` 是客户端给这次搜索起的名字（任意 JSON 值，原样带回），所以客户端在写出请求之前就知道该认哪些命中；没带时是这个请求的 `id`。找完答汇总 `{scanned, total, matched, complete}`，`complete` 为假是到了时限、个数被截断，或被取消。同一条连接再发 `sessions.search` 时取消上一个还在跑的（上一个照样答汇总，`complete` 为假），客户端断开时也停；客户端按推送的 `search` 只认最新那次的命中。关键词是空白时答出错 |
| `sessions.image` | `file`（会话记录的路径，在 `~/.claude/projects` 里）、`ref`（`conv.items` 里图片的位置，`行号.块序号[.内层序号]`） | `result` 是 `{media_type, data}`（base64）。位置不对、那一块不是图时答出错 |
| `sessions.occupant` | `session` | Zeta 之外占着这个会话的进程的样子（同 `conv.occupant`）：`{status, waiting_for, hands_over}`，没人占着时 `status` 为 `null` |
| `sessions.handoff` | `session` | 最多十几秒。把会话从别处接过来（同 `conv.handoff`）：`{handed, status}`。核心里开着这个会话的对话时，交出来了就解开它的 `blocked` 并推 `conv.state` |
| `sessions.end` | `session` | 最多十几秒。结束本包占着这个会话的 Claude Code 进程（等于终端里 `/exit`，对话内容留着，下次发消息时 `--resume` 接上）：核心里开着这个会话时同 `conv.end`，没开着、本包托管的进程还在时照 `zeta_host.sh` 的 `end_claude` 结束它；等进程退出再答，马上推的列表项里 `live` 已经为假。`result` 是 `{ended}`：结束了几个，没有时是 0。Zeta 之外占着的不管，那是 `sessions.end_elsewhere` |
| `sessions.end_elsewhere` | `session` | 最多十几秒。结束 Zeta 之外占着这个会话的 Claude Code 进程（同 `zeta-helper end`：先 SIGTERM，十秒不退 SIGKILL），不管那边闲不闲；`sessions.handoff` 只接管闲着（或在等确认）的。`result` 是 `{ended}`：结束了几个，没有时是 0 |
| `sessions.desk_open` | `session` | 在 tmux 会话 `zeta` 里给它开窗口，已经有就不动（同 `zeta-helper desk-open`）；窗口的工作目录是会话记录里的 `cwd`，还没有记录时是家目录。`result` 是 `{window}`：tmux 的窗口号，主机上没有 tmux 时为 `null` |
| `sessions.desk_close` | `session` | 关掉它的窗口（同 `zeta-helper desk-close`），`result` 是 `{closed}`：关掉了几个 |

对应的 zeta-helper 子命令（`delete`、`rename`、`archive`、`export`、`lookup`、`search`、`image`、`occupant`、`handoff`、`desk-open`、`desk-close`）先留着：手机版的 Kotlin（`SessionList`、`SessionTools`、`ImageStore`）还在经 shell 调它们。各客户端都改走这些方法以后删掉子命令，连同 `main.go` 里的用法说明和它们的测试。

客户端改走这些方法时一并删掉各自重复写的规则（Windows 版已经改走，下面列的是 Kotlin 还要做的；Windows 的置顶迁移在 `windows/internal/hub/pins.go`，删的时机写在那里）：

- 置顶：Kotlin 的 `Settings.pinnedSessions`（连同 `SessionSummary.pinKey`、设置备份里的 `pinnedSessions` 一项）、侧栏取回不在最近 300 个里的置顶会话的 `missingPinned`、`syncFarPinned` 和 `farPinned`（`withFarSessions` 留给存储页定位用）、`SessionList.delete` 里去掉置顶的那段。置顶看列表项的 `pinned`，点置顶发 `sessions.pin`。
- 本地置顶一次性迁过来：客户端对一台设备的会话列表订阅连上（收到 `zeta_hello`；对话连接的招呼不算）时，本地设置里还有这台的置顶键（手机本机的就是会话号，远程设备的是 `<设备编号>/<会话号>`）就逐个发 `sessions.pin`（`pinned` 为真），答成功或答 `no_session` 的从本地设置里删掉这个键，别的出错留着；同一个进程里每台设备只迁一次，没迁成的等下次启动再迁；还没迁完时列表里的置顶取 `pinned` 和本地键的并集，取消置顶也同时删本地键。本地没有键了就不再做什么。这段迁移代码在带它的手机正式版和 Windows 版都发出以后的下一次发版时删掉，连同读旧键的代码：到那时没连过的设备上的置顶会丢，只是标记，会话不丢。
- 导出：Kotlin 的 `exportMarkdown`、`exportMarkdowns`、`combineExports`、`exportName` 改成发 `sessions.export`（总是带 `sessions` 和 `utc_offset`），照上表用 `parts` 按选的顺序合成、起文件名（同 Windows 的 `hub.Export`）；`SessionTools.kt` 的 `exportFileName` 只剩设置备份在用。
- 删除：Kotlin 的 `deletable` 和 `confirmedDeletion` 里按「正在进行」的筛选、`DeletePrompt.Blocked` 的判断改成看 `sessions.delete` 是不是答 `active`：答了就提示「对话正在进行，结束后再删」，批量删除时其余照删。
- 搜索：客户端给每次搜索起名字（比如递增的序号）放进 `search`，只认推送里 `search` 等于最新名字的命中。各客户端都带 `search` 以后，核心里没带时用请求 `id` 的那条退路（`sessionSearches.Request` 里的 `searchName`）可以删掉，这一行文档同时改掉。
- 主机名：见「协议」里 `zeta_hello` 的 `hostname`。
- Go 写的客户端（Windows）用到导出文件名、标题清理、接管的 `Occupancy`、`Handoff` 和状态时导入 `helper/sessionproto`，不自己写。

## Claude Code 配置

设置里各设备页上的新对话默认和「Claude Code 配置」各页（记忆、Skills、MCP、Subagent、工具权限）读写的是那台主机上 Claude Code 自己的配置（`~/.claude`、`~/.claude.json`），那边终端里的 `claude` 同样生效。逻辑在 `helper/claudecfg`，核心的 `config.` 方法（`helper/config.go`）把它交给客户端：每个请求在自己的 goroutine 里做，做完回复，结果在应答的 `result` 里；失败时 `error` 是给人看的中文说明（不含 Token）。参数和 `id`、`method` 平级。`cwd` 是默认工作目录，省掉时是家目录。标「慢」的要跑 `claude` 或连 MCP 服务器，十几秒到一分钟。

| 方法 | 参数 | 结果 |
|---|---|---|
| `config.defaults.read` | | 新对话默认 `{model, permissionMode, efforts: [{model, level}], effort, compactWindow}`（settings.json 的 model、permissions.defaultMode、modelSettings.*.effortLevel、effortLevel、autoCompactWindow；没设的是空串、0） |
| `config.defaults.setModel` | `value`（空串是删掉） | 同上 |
| `config.defaults.setPermissionMode` | `value`（不能是 bypassPermissions） | 同上 |
| `config.defaults.setEffort` | `model`（完整模型 ID，空串是顶层那一档）、`value`（空串是删掉） | 同上 |
| `config.defaults.setCompactWindow` | `value`（token 数，0 是删掉，最小 100000） | 同上 |
| `config.memory.read` | `cwd`、`project` | 记忆页：自动记忆开关、CLAUDE.md、可选的项目（默认工作目录所在的排第一，worktree 算主仓库）、选中项目的 MEMORY.md 和每条记忆 |
| `config.memory.readFile` | `path` | `{content, sha, exists}`；只认 CLAUDE.md 和记忆目录里的 .md |
| `config.memory.writeFile` | `path`、`content`、`sha`（读到时的；原来不存在时空串）、`force` | `{sha}`；编辑期间被别人改过时 `{conflict: true}`，什么都不写 |
| `config.memory.delete` | `path` | 删一条记忆，连同 MEMORY.md 里指向它的那一行 |
| `config.memory.setAuto` | `on` | |
| `config.skills.list` | | `{user: [{name, title, description, enabled, valid}], synced: [{name, description, enabled}]}` |
| `config.skills.import` | `fileName`、`zip`（base64）、`replace` | `{name, exists, existingDescription, replaced, enabled}`；同名的已有又没 `replace` 时 `exists` 为真，什么都不装 |
| `config.skills.setEnabled` | `name`、`title`、`enabled` | 写 skillOverrides |
| `config.skills.delete` | `name` | |
| `config.agents.list` | | `[{file, name, description, model, tools, prompt, extraKeys, sha}]`（`tools` 为 null 是全部工具） |
| `config.agents.save` | `original`（编辑时是读到的那一项，新建为 null）、`draft`（`{name, description, model, tools, prompt}`） | `{file}`；改名时旧文件删掉，不认识的前言键原样留着 |
| `config.agents.delete` | `file` | |
| `config.permissions.read` | `cwd`、`server`、`tools` | 工具权限页：`rules`、`builtin`（每个内置工具的状态；`own` 是它自己那条规则，`rule` 是改它时交给 `setTool` 的名字）、`builtinBulk`、`servers`（每个 MCP 服务器整体的状态和一键开关会加删的规则）；给了 `server` 时 `serverTools` 是它这几个工具的状态 |
| `config.permissions.setTool` | `tool`、`behavior`（allow、ask、deny，空串是去掉），和 read 的参数 | 同 read |
| `config.permissions.add`、`config.permissions.remove` | `behavior`、`rule`，和 read 的参数 | 同 read |
| `config.permissions.replace` | `old`、`new`（各是 `{behavior, rule}`），和 read 的参数 | 同 read；`old` 已经不在了时出错 |
| `config.permissions.bulk` | `group`（`{tools}` 或 `{server}`）、`enable`，和 read 的参数 | 同 read |
| `config.mcp.list` | `cwd` | `[{name, scope, type, url, command, args, envKeys, headerKeys, bearer, authHeader, oauth, shadowedBy}]`，密钥只说有没有 |
| `config.mcp.status` | `cwd` | 慢。`claude mcp list` 的结果 `{servers: {"范围/名字": {state, issue}}, connectors}` |
| `config.mcp.tools` | `cwd`、`scope`、`name` | 慢。连上服务器取工具清单 `[{name, description}]`：HTTP、SSE 从主机连，stdio 经登录 shell 起 |
| `config.mcp.addHttp` | `name`、`type`（http、sse）、`url`、`token` | 慢。加到用户配置（`claude mcp add-json`，Token 只走标准输入） |
| `config.mcp.addStdio` | `name`、`command`、`args`、`env` | 慢。同上 |
| `config.mcp.remove` | `cwd`、`scope`、`name` | 慢 |
| `config.mcp.setToken` | `cwd`、`scope`（user、local）、`name`、`token`（空串是去掉） | 慢。删掉重加，重加失败时放回原样 |
## 账号与额度

核心答 `accounts.` 和 `quota.` 开头的方法（逻辑在 `helper/accounts`，入口是 `helper/accounts.go` 的 `handleAccounts`；订阅 `quota.watch` 在 `helper/corehandlers.go` 的 `accountsHandler`），每个请求在自己的 goroutine 里做、做完再答，不挡同一连接上别的请求。参数和 `id`、`method` 平级，结果在 `zeta_reply` 的 `result`；出错时 `ok` 为假，认得出的另带 `code`：`claude_switch_missing`（客户端把带着的 claude-switch 装上再试）、`busy`（有 Claude Code 开着，claude-switch 没换）、`no_account`（要换、要删的号不在了）。

账号由 claude-switch 管（存档、换号、加号、删号），这一组只是替客户端跑它：令牌只在主机上由它搬，结果里只有账号名、邮箱、组织和套餐。改登录状态的请求一个一个来，开着的 Claude Code 都是调用方自己的对话时先等它们退出（最多约 8 秒，刚收掉的要几秒），有别人的就不等（那些不会自己退），不管等没等到都交给 claude-switch，它自己会拒绝。客户端要先收掉自己闲着的对话。每个 `accounts.` 请求都可带 `program`：claude-switch 相对家目录的路径（客户端带的那个版本）；不带时在 `~/.cache/<ZETA_NAME>/bin` 下找 `claude-switch-<版本>`。这组请求要跑 claude-switch，慢。

| 方法 | 参数 | 做什么 |
|---|---|---|
| `accounts.list` | | `result` 是 `{current, accounts, procs, blocker}`：在用的登录（未登录为 `null`，认不出是哪个号时 `name` 为空）、要列的账号（在用的在前，每项 `{name, email, organization, displayName, subscription, refreshExpiresAt, current, parked}`）、`~/.claude/sessions` 登记的还开着的 Claude Code（`{pid, owner, status, kind}`，`owner` 是托管它的 Zeta 包，按进程身上的 `ZETA_HOST` 认）、别的包或终端里的进程挡着换号时的一句说明 |
| `accounts.switch` | `name` | 换成存着的这个号 |
| `accounts.save` | | 在用的登录还没记过（在终端里登录了新号）时给它起个空闲的名字记下，`result` 是 `{name}` |
| `accounts.prepare_login` | | 登录另一个号之前：起好新号的名字，把在用的号存档并退出登录，`result` 是 `{name}`；之后的登录（`claude auth login`）由客户端做，登录后的新号由 claude-switch 记成这个名字。客户端自己不跑登录命令时用 `accounts.login_start` |
| `accounts.remove` | `name` | 删掉一个不在用的号在这台主机上的存档与记录（服务端仍是登录状态） |
| `accounts.login_start` | | 在这台主机上登录一个新号：先同 `accounts.prepare_login` 把在用的号存档并退出登录，再起 `claude auth login`（登录 shell 的环境），`result` 是 `{url, name}`：授权网址和登录后新号的名字（没登录过时 `name` 为空）。客户端打开网址、让用户把授权码贴回来。核心里同时只有一个登录，再开始时结束上一个；15 分钟没交成授权码就结束它 |
| `accounts.login_code` | `code` | 把授权码交给在等的登录命令，最多等 1 分钟。成功时记下新号（同 `accounts.save`），`result` 是 `{name}`。出错时 `code` 为 `login_incomplete`：授权码不完整（少了 `#` 和后半段），登录还在等，改了再交；`no_login`：没有在等的登录（没开始、取消了、超时），要重新开始；不带 `code` 的（码没被接受、登录命令出错）登录已经结束，也要重新开始 |
| `accounts.login_cancel` | | 结束正在进行的登录，没有时什么也不做。在用的号已经存档，换号能换回去 |

额度都是 Claude Code 给的。各账号最近一次的数存在 `~/.cache/zeta-quota/`（按 `~/.claude.json` 里的登录编号分开，同一台上各个包共用），换了号读到的就是新号的。这一组只读写小文件，快。数从三条路进来，存进同一份：

- 对话输出里的 `rate_limit_event`（模型请求的响应头带着 5 小时和每周窗口，数变了 Claude Code 就发一条）：核心收到就把 `rate_limit_info` 并进存着的额度（`conv.Deps.Quota` → `helper/accounts.go` 的 `quotaSink` → `QuotaStore.Event`），客户端不用转交。
- 核心起或接上对话进程时顺便问一次 get_usage（排在 initialize 之后），成功的应答存进去（`QuotaStore.Usage`）；这台主机上 5 分钟内只问一次（`autoQuotaEvery`），Claude Code 取额度的接口会限流。
- 客户端要马上刷新时（打开额度页、点刷新）：`conv.quota` 借开着的进程问，应答交给 `quota.usage`；没有开着的进程时客户端自己单起一个只答这个问题的 Claude Code，同样交给 `quota.usage`。

要跟着变的客户端发 `quota.watch` 订阅（不带 `watch` 或为真；`watch` 为假时退订，断开连接也退订）：先推一行 `{"type":"quota.snapshot","snapshot":…}`（同 `quota.get` 的 `snapshot`），之后这个核心存了新的数（上面三条路）或换了号时，存着的额度（连同 `updatedAt`）和上次推的不一样就再推一行。别的包的核心、终端里的 claude-switch 改的推不到，只读一次的用 `quota.get`。

| 方法 | 参数 | 做什么 |
|---|---|---|
| `quota.get` | | `result` 是 `{snapshot}`：在用的账号最近一次的额度，没有时为 `null` |
| `quota.watch` | `watch` | 订阅（省掉或为真）、退订（为假）额度的推送 `quota.snapshot`，见上 |
| `quota.usage` | `response`（get_usage 的应答，control_response 的 `response.response`） | 读成额度并存起来，`result` 是 `{snapshot, fresh}`；Claude Code 这次没取到服务器的数而已有窗口时留着已有的，`fresh` 为假 |
| `quota.event` | `info`（rate_limit_event 的 `rate_limit_info`） | 并进存着的额度，`result` 是 `{snapshot, changed}`；核心接着的对话里的事件核心已经存了，不用再交 |

`snapshot` 照 `helper/accounts/quota.go` 的 `Snapshot`：`windows`（每项 `{kind, group, scope, used, resetsAt, severity, active, label}`，`used` 是 0～1 的比例，`severity` 是 `normal`、`warning`、`critical`，`label` 是界面上的名字）、`extra`（额外用量）、`subscription`、`unavailable`（这种登录没有订阅额度）、`blocked`、`blockedUntil`、`updatedAt`。过了重置时刻的窗口怎么显示由客户端按自己的时钟算。

## 存储与文件

设置里的存储页、文件浏览和按路径取图片，做完就答（`helper/oneshots.go`），各在自己的 goroutine 里做，慢的不挡同一条连接后面的请求。每个和同名的子命令共用一份实现（`storage`、`storage-sessions`、`clean`、`ls`、`read`、`file-image`）。参数和 `id`、`method` 平级；不合要求（会话号不像会话号、路径不是绝对路径、类别不认识）时整个请求出错，什么也不做。

| 方法 | 参数 | 结果 |
|---|---|---|
| `storage.scan` | `keep`（客户端认为正在进行、要保护的会话号） | `{kinds, projects, cleanupPeriodDays}`：各类（`{key, bytes, files, freeable, items, busy, parts: [{name, bytes, items}], error}`，`busy` 是正在运行、这类先不清的工具，`error` 为真是这一类没统计成）、`~/.claude/projects` 下各项目（`{dir, bytes, files, sessions, cwd}`）、settings.json 的 `cleanupPeriodDays`（没设为 `null`）。慢，几秒到几分钟 |
| `storage.sessions` | `project`、`keep` | `{sessions: [{id, file, bytes, updated, live}], rest}`：一个项目里每个会话（记录加同名目录）占多少，`rest` 是不属于任何会话的 |
| `storage.clean` | `kind`（`storage.scan` 的 `key`，`apt` 除外：它要 root）、`keep` | `{key, freed, removed, failed, busy}`：清掉这类里能安全删的（规矩见 `helper/storage.go` 开头），`busy` 不为 `null` 时整类没清 |
| `files.ls` | `path`（绝对路径） | `{path, total, truncated, entries}`：目录在前、再按名字排，只给前 2000 项；每项 `{name, dir, kind, size, mtime, target, broken}`（`kind` 是 dir、file、link、other；`dir` 为真的能点进去；`target`、`broken` 只有符号链接有） |
| `files.read` | `path`、`bytes`（读多少，省掉是 256 KiB，最多 4 MiB） | `{size, truncated, text}`；看着像二进制时 `{size, binary: true}` |
| `images.file` | `path`（`~` 开头的从家目录算） | `{media_type, data}`：主机上的一个图片文件（按扩展名认类型，最大 30 MB），`data` 是 base64 |

`files.ls`、`files.read` 读不了时答出错，`code` 是 `missing`（不存在）、`not_dir`（不是目录）、`not_file`（不是普通文件）、`denied`（没有权限）或 `error`。

看到的是核心进程自己的文件系统。手机上核心只看得到它启动那一刻挂着的共享文件夹和组，共享文件夹页增删以后才换一个新的（`core-takeover`，见「目标」）；单起的子命令每次进 Debian 时现挂，总是现在的样子。所以手机客户端浏览本机文件、读本机的图片文件仍各起一次子命令，不依赖换核心是否及时；查共享文件夹（`zeta-helper mounts`）要看的正是新进入的进程挂不挂得上，而且在换核心之前做，也走子命令，核心里不设 `files.mounts`。远程主机上没有这回事。

## 主机

| 方法 | 参数 | 结果 |
|---|---|---|
| `system.cpu` | 无 | `{percent, cores}`：这台主机整机的 CPU 忙碌百分比（0～100 的整数，读不了 `/proc/stat` 时为 `null`）和逻辑核数。最多等 0.1 秒 |
| `system.idle` | 无 | `{minutes}`：这台主机设的闲置超时（多久结束进程，分钟，0 是不自动结束），没设时是 10 |
| `system.set_idle` | `minutes`（0～1440 的整数） | 写下这台主机的闲置时长，结果同 `system.idle`；不合要求时出错、不写 |
| `system.claude` | 无 | `{version}`：这台主机上 Claude Code 的版本（`claude --version` 的版本号），没装或跑不起来时为 `null`。和对话起的是同一个 claude：登录 shell 的环境，PATH 末尾补 `~/.local/bin`（`helper/claudeupdate.go`） |
| `system.claude_update` | 无 | 在这台主机上跑 `claude update`（最多 5 分钟），`{ok, before, version, output}`：`ok` 是它退出码为 0，`before`、`version` 是更新前后的版本（没装为 `null`），`output` 是输出的最后 8 行。同一个核心里同时只跑一次，后到的答出错。已在跑的 Claude Code 进程不受影响，之后起的用新版本。设置页每台远程主机一行「Claude Code」（`ClaudeCodeUpdateRow`）；手机本机的在 Linux 环境页，走 Debian 里的同一条命令 |

闲置时长存在主机上的 `~/.config/<ZETA_NAME>/idle-minutes`（`helper/idle.go`），托管脚本的看门狗每轮（一分钟）重读（`zeta_host.sh` 的 `idle_limit`）：没有客户端接着、又闲着的 Claude Code 进程数满这么多分钟就结束，改了对已经在跑的进程马上生效；几个客户端连同一台主机看到的是同一个值。设置页每台主机一项「闲置超时」。客户端只在有人看着时拿着对话：切走的、Zeta 切到后台时的当前对话，闲着就在几秒后放开（`ConversationHub.retireWhenIdle`）；手机上的前台服务只在有对话在忙时保着 Zeta，闲着的进程不靠它（托管的进程不随应用退出），所以进程什么时候结束只看这一个时长。例外是等不了的几处：换账号、登录前（`accounts.*` 里改登录的，claude-switch 有 Claude Code 开着就不换，留着的进程以后被接回时还用着旧登录）和 `core-takeover` 换核心看新挂载时，核心先结束本包闲着的托管进程（`endIdleHosted`），在忙的不动。

算法和 Claude Code 状态栏常用的 cpu% 一样（`helper/cpu.go`）：两次读 `/proc/stat` 汇总行，忙碌占比是 1 减去空闲增量除以总增量，空闲含 iowait。上一次的读数在核心的内存里，几个客户端共用；离上一次不到 0.5 秒直接答上一次的数，没有上一次或上一次超过 5 分钟时就地采样 0.1 秒。聊天页输入栏上的负载标识（`ui/chat/HostLoad.kt`）在聊天页看得见时每 5 秒借那台主机对话用的连接问一次（对话里有了消息以后才显示、才问；还没发过消息的新对话不显示，也不为它连主机），显示的是对话所在那台主机（手机本机的 Debian 或远程主机）的负载；问不到时显示一道横线。

## Android 直连（zeta-ssh）

手机直连远程主机（`RemoteHost.Via.Direct`，不要 Debian 和 root）的 SSH 在 Go 程序 zeta-ssh 里（`client/cmd/zeta-ssh`，逻辑在 `client/sshbridge`，连接用和 Windows 版同一份的 `client/sshlink`、`client/hostlink`）。Kotlin 一侧是 `shared` 的 `core/ssh/SshLinks.kt`（`HubPlatform.sshLinks`，手机上由 `AndroidHubPlatform` 给出程序路径和 Keystore 里的密钥），只做协议转换和 Android 原生的三件事：用 Keystore 里的私钥签名、经系统解析域名、把远程命令当成 Java 的 `Process`。重连、闲置断开、保活、主机指纹的核对、交给那边 shell 的写法（登录 shell 与否，`hostlink.RemoteCommand`）、出错的说法都在 Go 里。

- 打包与启动：Gradle 任务 `zetaSsh` 把它编成 android/arm64（不用 cgo），当原生库 `lib/arm64-v8a/libzeta_ssh.so` 打进 APK，`useLegacyPackaging` 让安装时解到应用的 `nativeLibraryDir`：Android 10 起应用不许执行自己数据目录里的文件，`nativeLibraryDir` 里的可以。各段按 64 KB 对齐（`-ldflags -R`，16 KB 页的设备要至少 16 KB）。测试版的原生库在包里不压缩，正式版照旧压缩，为什么见 `app/build.gradle.kts` 里测试版那段。第一次要直连时 `SshLinks` 起它，整个应用进程一个，是应用进程的子进程（不在 Debian 里），跟着应用一起被冻结、被杀；标准输入结束它就断开所有连接退出。它退出了，上面的命令都以 255 结束（原因「Zeta 的 SSH 程序退出了」），下一次要用时再起。单元测试起 `zetaSshHost` 编的本机那一份（`SshLinksTest`）。测试版（debug 变体）另带一份 linux/amd64 的 `lib/arm64-v8a/libzeta_ssh_x86_64.so`（任务 `zetaSshEmulator`），首选 ABI 是 x86_64 且有这个文件时用它：x86_64 模拟器靠 arm64 转译跑本应用，转译能执行 arm64 的 C 程序，Go 编的 arm64 程序却一启动就段错误。不放进 `lib/x86_64/`，是因为有这个目录安装器就把整个应用换成 x86_64，只有 arm64 的库（如 RaTeX）就装不上了。正式版不带它，`mergeReleaseNativeLibs` 上有核对。不再在 x86_64 模拟器上测直连时连同核对一起删掉。
- 密钥：Ed25519，私钥在 Android Keystore 里，取不出来。zeta-ssh 第一次登录时请应用给公钥（`key`），之后每次登录要签名时请应用签（`sign`），应用调 `SshKey.sign` 在 Keystore 里签、答原始的 64 字节签名，Go 一侧包成 `ssh-ed25519` 签名。密钥交换（`mlkem768x25519-sha256`、`curve25519-sha256` 优先）和验对方的主机签名都用 golang.org/x/crypto/ssh 自带的。
- 域名：不用 cgo 的 Go 在 Android 上解析不了域名（没有 `/etc/resolv.conf`），地址不是 IP 时请应用经 `InetAddress` 解析（`resolve`），照系统的 DNS、私人 DNS 和 VPN，再按给的先后一个个连。
- 没有压缩：x/crypto/ssh 不支持 SSH 的 zlib 压缩（和 Windows 版一样）。

协议：两个方向都是一行一个 JSON，`data` 一律是 base64。zeta-ssh 先发 `{"type":"hello","protocol":1,"window":262144}`。

应用发给 zeta-ssh 的（`method`）：

| 方法 | 字段 | 说明 |
|---|---|---|
| `start` | `p`（应用给的进程编号）、`host{id,target,host_key}`、`command`、`login`、`name`（`ZETA_NAME`）、`tz` | 在那台主机上起命令：连接没有就先连，每个命令一个通道。不回应答，之后有这个进程的 `out`、`wrote`、`exit` |
| `stdin` | `p`、`data`，或 `eof: true` | 写进标准输入；每条 `data` 写进通道（或写不进）后回一条 `wrote`，应用等到它再写下一条。`eof` 关掉输入，不回 |
| `ack` | `p`、`stream`（`stdout`/`stderr`）、`n` | 应用读掉了 `n` 字节，那一路可以再送这么多 |
| `close` | `p` | 结束它：还在连的不连了（命令不会在那边跑起来），开着的通道关掉；之后照样有 `exit` |
| `probe` | `id`、`target`、`host_key`（空是谁都认）、`command`、`name`、`tz`、`timeout_ms` | 连一次、在登录 shell 里跑完命令（加设备、检查连接），连接不进连接池；回 `reply` |
| `retain` | `ids` | 只留下这几台的连接（删了设备），别的断开 |
| `drop_all` | 无 | 断开所有连接（换了网络） |
| `answer` | `id`、`data` 或 `addrs`，不成时 `error` | 答 zeta-ssh 的 `call` |

zeta-ssh 发给应用的（`type`）：

| 类型 | 字段 | 说明 |
|---|---|---|
| `out` | `p`、`stream`、`data` | 一段输出（最多 32 KiB） |
| `wrote` | `p`，写不进时 `error` | 一条 `stdin` 写完了 |
| `exit` | `p`、`code`，连不上时 `problem` | 进程结束，在它的输出都送完以后。连不上、连接断了的 `code` 是 255；`problem` 是给人看的原因，应用以 `zeta-ssh: ` 开头写进标准错误（`RemoteHosts.sshProblem` 取它） |
| `reply` | `id`、`host_key`（看到的对方主机公钥）、`changed`（和请求里记下的不一样）、`problem`，或 `code`、`stdout`、`stderr`、`timed_out` | `probe` 的结果 |
| `call` | `id`、`method`（`key`；`sign` 带 `data`；`resolve` 带 `host`） | 请应用做的事，最多等 30 秒 |

输出按额度送：每个进程的每一路，发出去还没被 `ack` 的不超过招呼里的 `window`（256 KiB）。应用不读时 zeta-ssh 就不读通道，SSH 的窗口满了那边的命令停下来等，不在应用里越堆越多，也不挡别的进程；应用的管道按 `window` 开，收的那条线程从不等。应用不读了（关掉那一路）时，剩下的和之后收到的都马上确认掉。两边读消息的那一路都不等：连接、开通道、写输入、回调各在自己的 goroutine 或线程上。

## 进度

1. 会话列表（已做）：`sessions.watch` 订阅，先整份、再只推变了的（`helper/watch.go`）。客户端是 `core/HelperServe`，`SessionList` 在界面前台时订阅侧栏列着的各台主机，退到后台断开；答完一轮、改名、删除、终端里开的会话都不用另外重读。设备回归 `tools/phone-tests/session-list-live.sh`。侧栏的操作（改名、归档、删除、导出、查、搜索、看图、接管、tmux 窗口）也已挂成核心的 `sessions.` 方法（见「会话」，`helper/sessionmethods_test.go` 经真的 serve 和核心走一遍）；手机版客户端已改走它们（「别处运行」卡片读对话 `conv.state` 的 `occupant`、接管走 `conv.handoff`），还剩删掉对应的子命令。
2. 对话（已做）：输出流归约、确认与提问、控制请求、子代理与后台任务都在核心里。第一批：常驻核心与 serve 转发、输出流归约（`helper/transcript`，对照用例在 `helper/testdata/transcript`）、确认与提问（`helper/prompt`）、后台任务（`helper/tasks`）、用量（`helper/usage`）、托管连接（`helper/host`）和对话状态机（`helper/conv`），上一节的方法都能用；`helper/conv_e2e_test.go` 从 serve 经核心、`zeta_host.sh` 到假 Claude 走通一轮。第二批已做：更早历史分页、编辑与重新生成、删除一轮、还原代码、分支、子代理查看、额度、核心起来时接回还在干活的托管进程、各会话的忙闲订阅、远程主机的 tmux 窗口，核心按方法前缀分发（`core.Mux`）。一个服务端也已做：每个包一个核心，新版本的 serve 让旧核心交班（`helper/handover_test.go`、`helper/conv_e2e_test.go` 钉住交班时进行中的一轮不断），serve 只转发，会话列表、配置、账号与额度都在核心里。Android 的对话也走这套协议：`Conversation` 是核心那份对话的镜像（`core/conv` 的 `ConvClient`、`CoreConversation`，带应答的请求经 `HelperServe.call`，端到端回归 `ConvEndToEndTest`、`ConversationEndToEndTest`），Kotlin 里的归约、起进程、确认与提问的解析都已删掉。客户端各写一份的对话规则也收进了核心：任务清单（`todos`）、重新生成的原样重发（`resend`）、各轮能做什么（用户条目的 `rewindable`、`forkable`、`round_last`）、别处占用（`occupant`，自动解开 `blocked`）、还没起进程时的斜杠命令、后台命令输出的推送、子代理概要；Android 已全部改读这些并删掉了自己那份（`TodoBoard` 的归约、`Rewind.kt` 的 `turnActions`、`SessionHandoff` 的轮询、设置里存的 `commands`、后台任务页的定时拉取），Windows 客户端也已改读、删掉了自己那份。
3. Windows 客户端（已做）：用 mygo 写的全功能桌面版（`windows/`，设计见 `docs/desktop.md`），对话直接用第 2 步的 serve 协议，随正式版一起发 MSI。
4. Android：手机本机的连法搬完、Claude Code 配置和账号额度改用核心的方法（随核心一起做，不另搬；额度已改用：`QuotaService` 订阅 `quota.watch`，界面在前台时连着，刷新时把 get_usage 的应答交给 `quota.usage`）以后，删掉 `shared/` 里的逻辑，`shared/` 撤掉，界面搬回 `app/`，KMP 配置一并去掉。直连已改用 Go 的 zeta-ssh（见「Android 直连（zeta-ssh）」），不再带 JSch 和 BouncyCastle。
