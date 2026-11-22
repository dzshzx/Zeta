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
- 版本并存：每个客户端起自己那个版本的 serve（程序按内容哈希装，见 `HelperProgram`），彼此不共享状态，手机正式版、测试版和桌面版各用各的。几个客户端共用同一份状态要等对话搬进来时再做（下面第 2 步）。

## 进度

1. 会话列表（已做）：`sessions.watch` 订阅，先整份、再只推变了的（`helper/watch.go`）。客户端是 `core/HelperServe`，`SessionList` 在界面前台时订阅侧栏列着的各台主机，退到后台断开；答完一轮、改名、删除、终端里开的会话都不用另外重读。设备回归 `tools/phone-tests/session-list-live.sh`。
2. 对话：输出流归约（`TranscriptReducer`）、确认与提问（`Prompts`）、控制请求、子代理与后台任务搬进 serve。输出流归约的 Go 版已有（`helper/transcript`，和 Kotlin 版由对照用例钉成一样，条目的 JSON 就是要推给客户端的格式），还没接进 serve。同一会话的几个客户端改成订阅同一份状态：一台主机上同一个包的 serve 只留一个常驻进程，各客户端起的 serve 只把自己的输入输出转给它。
3. Windows 客户端：用 mygo 写聊天、会话列表、设置、添加远程设备，替掉 `desktop/` 和它打包的 JRE，接进发版流程。
4. Android：手机本机的连法、额度与账号、Claude Code 配置的读写搬完以后，删掉 `shared/` 里的逻辑，`shared/` 撤掉，界面搬回 `app/`；KMP 配置、JSch、BouncyCastle 一并去掉。
