# Go 核心

Zeta 的逻辑往 Go 写的核心里搬：核心是每台主机（手机的 Debian、远程主机）上的 `zeta-helper serve`，客户端只剩界面和平台专有的功能。这一页写目标、现在做到哪一步、接下来按什么顺序做。

## 目标

- 核心：`zeta-helper serve`，和现在的 zeta-helper 是同一个程序（`helper/`）。客户端经一条常驻连接和它说话：远程主机上是一条 SSH 通道（经手机 Debian 的 OpenSSH 或直连都一样），手机本机是进 Debian 的那条管道，不开端口。状态在它的内存里（会话列表、对话的输出流归约、确认与提问的排队、后台任务），会话目录用 inotify 盯着，只推送变了的。
- 托管层不动：`zeta_host.sh` 照旧托管 Claude Code 进程，serve 重启、升级或崩溃都不结束进行中的对话；serve 里存的只是能重建的状态。
- 客户端：Android 是 Kotlin + COUI，只剩界面、手机工具、悬浮层、无障碍、虚拟屏、数字助理、语音、通知与前台服务、闹钟、应用内更新；Windows 是 Go + mygo 的原生界面，替掉现在的 Compose Desktop。`shared/` 里的逻辑搬完后这个模块撤掉，界面回 `app/`。
- 手机工具：Claude 调手机工具时，serve 把请求转给给这个对话登记了手机能力的客户端（和现在 `PhoneBridge` 的做法一样）。
- 测试：核心的测试都是 Go 测试，在 Linux 上跑；界面测试用录好的协议消息喂界面，不依赖真核心。

## 协议

一行一个 JSON，写法和方法清单以 `helper/serve.go` 开头为准。要点：

- 连上先收到 `zeta_hello`，带协议版本 `protocol`（`helper/serve.go` 的 `serveProtocol`，Kotlin 一侧是 `HelperServe.PROTOCOL`）。改已有消息的含义时加一，只加方法、字段不加。
- 请求带 `id` 和 `method`，每个请求回一行 `zeta_reply`；订阅类的方法之后另有推送。
- 客户端每 30 秒发一次 `ping`；serve 输入结束或 90 秒收不到任何一行就退出，不在主机上留下没人用的进程。
- 版本并存：每个客户端起自己那个版本的 serve（程序按内容哈希装，见 `HelperProgram`）。对话状态放在常驻的核心进程里（见下一节），同一个包、同一个 helper 版本的客户端共用一个；版本不同的各用各的核心，经 `zeta_host.sh` 的多方接着同一个托管进程。

## 对话

对话的状态在核心进程 `zeta-helper core` 里（服务端框架在 `helper/core`，对话逻辑是挂在上面的处理器）：每台主机上每个 Linux 用户、每个包（`ZETA_NAME`）、每个 helper 版本一个，监听 `~/.cache/<ZETA_NAME>/core/<版本>.sock`（版本是程序文件名 `zeta-helper-<版本>` 去掉前缀；目录只有本人能进；测试用环境变量 `ZETA_CORE_DIR` 换个目录）。只起一个靠同目录的 `<版本>.lock`：正在跑的核心持着它（flock，里面写着进程号），已有核心在听时新起的直接退出，上一个崩溃留下的套接字文件由拿到锁的那个清掉再听。客户端不直接连它：serve 收到 `conv.` 开头的请求时连上核心，没人在听就用同一个程序拉起一个（`core` 子命令，自成一个会话，标准错误追加到同目录的 `<版本>.log`），等它听上，约 5 秒为限，连不上时这个请求答出错；几个 serve 同时拉起时由锁保证只留一个。之后这类请求原样转过去，核心的应答和推送原样写回客户端，ping、`sessions.watch` 照旧由 serve 自己答。核心没了（崩溃、被结束）时 serve 跟着退出，客户端照常重连，重新打开它的对话。

- 托管不变：核心经 `zeta_host.sh`（随 zeta-helper 编进程序里，`helper/host/`）起和接 Claude Code 进程，自己算一个接着的一方。核心重启、升级、崩溃都不结束进行中的对话：下次打开时载入会话记录、接上还在跑的进程。
- 谁接着：一个对话被打开它的客户端「拿着」（`conv.open` 到 `conv.close`，连接断了也算放开）。没人拿着、又闲着（不在回答、不在等确认、没有后台任务）满 30 秒，核心断开那个托管进程（`detach`，看门狗约十分钟后收掉进程）；没人拿着但还在进行的，核心接着归约，进行完了再这样断开。核心没有连接、也没有接着的对话（处理器说自己闲着）满 60 秒就退出；收到 SIGTERM 时也干净退出，都会删掉自己的套接字文件。
- 同一个会话在一个核心里只有一份状态：几个客户端打开同一个会话号拿到的是同一个对话，各自收到同样的推送，谁发的消息、谁答的确认都对所有人生效。不同 helper 版本的核心（比如手机和电脑装的版本不同）可以同时各有一份同一个会话的状态，各自接着同一个托管进程，行为和现在两个客户端同时接着一样（见 `zeta_host.sh` 开头）。

请求都带 `conv`（`conv.open` 除外），参数和 `id`、`method` 平级：

| 方法 | 参数 | 做什么 |
|---|---|---|
| `conv.open` | `session`（接着已有会话时）、`file`（它的会话记录，载入历史用）、`cwd`、`title`、`options` | 打开一个对话，应答的 `result` 带 `conv`。有会话号且核心里已有这个会话时拿到的就是它；接着推一份整份的 `conv.state` 和 `conv.items`。历史只载入最近一段（约 800 条事件），更早的有多少记在 `earlier` |
| `conv.close` | | 这个客户端放开它 |
| `conv.options` | `options` | 改起进程用的设置（见下），下次起进程时生效 |
| `conv.send` | `text`、`images`（`[{media_type, data}]`）、`files`（主机上的路径：客户端自己先把本地文件传到主机上）、`mode`、`rules` | 发一条消息；回答进行中时排队。`!` 开头的一行（没有附件时）照终端版直接执行命令。`mode`、`rules` 是后台运行（定时任务、通知触发）这一条用的权限模式和叠加的规则，用户自己发的不带 |
| `conv.interrupt` | | 停止这一轮，排队的消息撤回 |
| `conv.steer` | | 立即插话，同终端的 send now：Claude Code 认 `interrupt_send_now_v1`（init 的 capabilities）时发带 `send_now` 和排队第一条 `message_uuid` 的 interrupt。应答 `delivering` 时正在跑的工具挪到后台、消息并进这一轮，这一轮不停；`stopped`、`interrupted` 时这一轮以「已打断，先处理插话」收尾、排队的消息接着开始；`nothing_waiting` 时什么也不做。不认时发普通的 interrupt |
| `conv.cancel` | `uuid`、`recall` | 撤回一条排队的消息；`recall` 时放回输入框（`recalled`） |
| `conv.take_recalled` | | 输入框取走了 `recalled` |
| `conv.answer` | `request_id`、`reply` | 回应工具确认、计划审批、提问（`reply` 见下） |
| `conv.elicit` | `request_id`、`action`、`content` | 回应 MCP 表单（accept、decline、cancel） |
| `conv.model`、`conv.effort`、`conv.mode` | `value` | 只管这个对话的模型、思考强度、权限模式；`null` 回到默认 |
| `conv.compact` | | 压缩上下文 |
| `conv.stop_task` | `task` | 停一个后台任务 |
| `conv.task_output` | `task` | 读后台命令的最后 8 KiB，`result` 是 `{text, total_bytes, truncated, error}` |
| `conv.end` | | 结束进程（等于终端里 `/exit`），对话内容留着，下次发消息时 `--resume` 接上 |
| `conv.mcp_reply` | `request_id`、`response` | 手机工具的应答（见 `conv.mcp`） |

`options`（都可省）：`env`（只给这个 Claude Code 进程的环境变量，比如自动压缩）、`system_prompt`（`--append-system-prompt`）、`mcp_config`（`--mcp-config`，手机工具）、`phone`（这个客户端处理手机工具的调用）、`auto_title`（一轮后请 Claude Code 起标题）、`compact_percent`（自动压缩的百分比，用量条用）。

`options` 按客户端分开记：起进程时用发起这次起进程的那个客户端的（发消息、压缩等），`mcp_config` 只在有声明 `phone` 的客户端拿着这个对话时才带上。

`reply`：`{"kind":"allow"}`、`{"kind":"allow_always"}`（照建议以后不再问）、`{"kind":"deny","feedback":…}`（不写理由时和终端里按 Esc 一样停下这一轮）、`{"kind":"approve_plan","mode":…}`、`{"kind":"answer","answers":{问题:回答},"notes":{问题:备注}}`、`{"kind":"clarify","answers":…,"notes":…}`。

推送（都带 `conv`）：

- `conv.state`：对话的状态。`full` 为真时是整份，否则只有变了的字段。字段：`session`、`title`、`cwd`、`process`（有没有接着进程）、`busy`、`status`（等待指示旁的一句，比如正在重试）、`tasks`（后台任务清单）、`queued`（排队的消息）、`steering`、`prompts`（等用户回应的请求，按到达顺序，界面弹第一个）、`recalled`、`model`（这个对话指定的）、`reported_model`（Claude Code 报的）、`effort`、`mode`（`{user, process}`）、`commands`、`models`、`usage`、`loading_history`、`earlier`（会话记录里还没载入的更早事件数，0 是全载入了）、`blocked`（会话正被 Zeta 之外的进程占着）。
- `conv.items`：聊天条目（格式见 `helper/transcript/items.go`）。`full` 为真时 `items` 是整份；否则是一次替换：从下标 `at` 起去掉 `remove` 项、换成 `items`。流式回答时通常只换最后一两项。
- `conv.turn`：一轮的起止，客户端据此提醒。`phase` 是 `started` 或 `finished`；结束时带 `outcome`（`done`、`stopped`、`failed`、`interrupted`：进程意外没了）和一句 `text`。
- `conv.mcp`：Claude Code 调了手机工具（`request_id`、`message`），只发给 `options.phone` 为真的客户端；那边处理完用 `conv.mcp_reply` 答。没有这样的客户端时核心直接答出错。
- `conv.closed`：对话在核心里没了（结束、换了会话号以外的原因），客户端丢掉它。

## 进度

1. 会话列表（已做）：`sessions.watch` 订阅，先整份、再只推变了的（`helper/watch.go`）。客户端是 `core/HelperServe`，`SessionList` 在界面前台时订阅侧栏列着的各台主机，退到后台断开；答完一轮、改名、删除、终端里开的会话都不用另外重读。设备回归 `tools/phone-tests/session-list-live.sh`。
2. 对话：输出流归约（`TranscriptReducer`）、确认与提问（`Prompts`）、控制请求、子代理与后台任务搬进 serve。输出流归约的 Go 版已有（`helper/transcript`，和 Kotlin 版由对照用例钉成一样，条目的 JSON 就是要推给客户端的格式），还没接进 serve。同一会话的几个客户端改成订阅同一份状态：一台主机上同一个包的 serve 只留一个常驻进程，各客户端起的 serve 只把自己的输入输出转给它。
3. Windows 客户端：用 mygo 写的全功能桌面版（`windows/`，设计和分段见 `docs/desktop.md`），对话直接用第 2 步的 serve 协议；功能对齐后替掉 `desktop/` 和它打包的 JRE，接进发版流程。
4. Android：手机本机的连法、额度与账号、Claude Code 配置的读写搬完以后，删掉 `shared/` 里的逻辑，`shared/` 撤掉，界面搬回 `app/`；KMP 配置、JSch、BouncyCastle 一并去掉。
