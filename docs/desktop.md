# Windows 桌面版（Go + mygo）

`windows/` 是用 Go 和 mygo 原生界面写的 Windows 客户端，要做成全功能的桌面版：手机上和远程主机有关的功能它都有，手机专有的（手机工具、悬浮层、语音、Debian 环境、数字助理、定时任务、通知触发、存储与备份）不做。功能对齐以后替掉 Compose 桌面版（`desktop/`）。它是 Go 核心（`docs/core.md`）的第 3 步。对话的逻辑在主机上的 serve 里（第 2 步），桌面版只收发 serve 的协议、画界面，不另写一份对话状态。

## 和旧版、手机版的约定

- 只连远程主机，用 Zeta 自带的 SSH 直连（`golang.org/x/crypto/ssh`）。认证照这台电脑的 OpenSSH 配置：地址可以是 `~/.ssh/config` 里的别名，交给 `ssh -G` 算出主机、用户、端口和私钥；每台设备能另选 `~/.ssh` 里一把私钥（旁边有 `.pub` 的）。带口令的私钥、ProxyJump、ProxyCommand 不支持，遇到明说。主机指纹在加设备时记下，之后只认它。x/crypto/ssh 没有 zlib 压缩，连接不压缩。
- 设置和 Compose 版共用 `%APPDATA%\Zeta` 下的同一批 JSON 文件，格式照 `desktop/…/FilePrefStore.kt`（每项 `{"t":类型,"v":值}`），键和取值照 `shared/…/core/Settings.kt`：从旧版升上来，设备、置顶、常用指令、草稿都还在。
- 安装包是 MSI，在 Linux 上用 wixl 打，`UpgradeCode` 沿用 `desktop/windows/Zeta.wxs` 的那个，所以装新版直接替换旧版；同样带 `REINSTALLMODE=amus`。随正式版一起发，`update.json` 的 `msi`、`msiUrl`、`msiSize`、`msiSha256` 照旧。自动更新照 Compose 版的 `MsiInstall.kt` 起安装：脚本写成 UTF-8 带 BOM 的 `.ps1`，用 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File` 起，不加 `-WindowStyle Hidden`、不用 `-EncodedCommand`（有的杀毒软件拦后者，拦下后这个进程再起什么子进程都被拒）。
- 在主机上算作手机正式版同一个包（`ZETA_NAME=zeta`，见 `AppVariant.linuxName`）：同一个会话可以电脑和手机同时接着。托管脚本 `zeta_host.sh`、zeta-helper、claude-switch、`zeta_accounts.sh` 都是手机版那几份（构建时编进程序），按内容哈希装到主机上，和手机版装的是同一份。
- 和 Claude Code 说话（起进程的参数、stream-json 输入、控制请求、确认的应答格式）由 serve 做，桌面版不直接碰 stream-json。

## 结构

```
windows/
  cmd/zeta/           入口
  internal/settings/  设置文件（和 Compose 版同格式）、各项设置
  internal/remote/    设备：模型、OpenSSH 配置认证、SSH 连接（每台一条、每个命令一个通道、闲一分钟断、断线重连）、加设备时的探测
  internal/host/      在一台主机上跑命令：remoteCommand 的写法、zeta-helper 按哈希安装与调用、serve 连接、托管脚本
  internal/chat/      对话：接 serve 的对话协议，把它推来的条目、确认与提问、用量、后台任务、子代理整理成界面要的状态
  internal/hub/       中枢：当前对话与后台对话、会话列表、设备切换、账号
  internal/claudecfg/ Claude Code 的配置（记忆、Skills、MCP、Subagent、权限、新对话默认）
  internal/ui/        mygo 界面：窗口、侧栏、聊天、输入栏、各种确认、设置各页
  installer/          MSI（wixl）
```

主机无关、以后 serve 也要用的逻辑放 helper 模块（已有 `helper/transcript`），客户端只有的（SSH、设置、界面）放 `windows/`。Go 代码按 Go 的习惯写（事件解码成结构体、错误照常返回），不照搬 Kotlin 版的结构和写法；对话条目只依赖它的 JSON 格式（serve 推来的就是这个）。逻辑层只出数据，界面层只画（同 power-meter 的做法）：后台 goroutine 改了状态，经窗口的 Update 在主线程上生效、重画。

## 分段

每段合入后都能用，前一段是后一段的地基。

1. 骨架：模块、门禁、构建与 MSI；设置文件；设备（OpenSSH 配置认证、直连、探测、指纹）；在主机上跑命令、装 zeta-helper、serve 订阅会话列表；窗口、侧栏（主机切换、全部设备、置顶/今天/更早）、添加与编辑设备。
2. 看对话：打开会话读历史（helper history、Go 归约器、往上翻载入更早的），画条目（Markdown、思考与工具折叠卡、红绿对比、图片），接上还在跑的托管进程。
3. 对话进行（等第 2 步的 serve 对话协议）：起进程与接着旧会话、发消息、流式显示、停止、排队与插话、权限模式、模型与思考强度、各种确认与提问、用量与额度、闲置放手、断线重连、和别的客户端同时接着。
4. 对话的其余操作：改写、重新生成、删除一轮、分支、还原代码；附件（图片、文件、路径）、常用指令、草稿、斜杠命令、对话内查找、任务清单、后台任务页、子代理页、别处运行的接管、tmux 窗口；侧栏的改名、归档、删除、导出、搜索、多选。
5. 设置：各设备页（新对话默认、自动压缩、自动标题）、Claude Code 配置各页、账号与额度、外观、关于与自动更新。
6. 替换：发版流程改打这个 MSI，删掉 `desktop/` 和 shared 的桌面目标，文档跟着改。

## 测试

- 逻辑层是普通的 Go 测试；SSH 对着测试里起的 x/crypto/ssh 服务器，命令在临时 HOME 里真跑 bash、zeta-helper 和托管脚本，对话用 serve 加 `tools/fake-claude`。
- 界面用 mygo 的无窗口测试（`ui.NewTester`）按文字找控件、点、输入、拨时钟，断言状态和布局；不做像素比对。要看样子时按环境变量把各场景画成 PNG 人工看（同 power-meter 的 `TestPreviews`）。
- 门禁在 Linux 上跑全部测试；真窗口在本机的 Windows 上装 MSI 看。
