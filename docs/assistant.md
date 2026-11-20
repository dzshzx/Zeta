# 数字助理与长按电源键

Zeta 可以被选为系统的默认数字助理。唤起后在当前应用上弹出浮窗，带上当前屏幕问 Claude：发出去时新开一个对话，截图作图片附件，应用内容整理成精简的文字接在问题后面。

## 入口

- 系统助理会话：`assistant/ZetaVoiceInteraction.kt`。会话收系统给的截图（`onHandleScreenshot`）和应用内容（`onHandleAssist`），存进 `AssistCapture`，再用 `startAssistantActivity` 打开浮窗。会话自己不画界面。元数据在 `res/xml/zeta_voice_interaction.xml`，要截图、应用内容和屏幕文字（Android 17 的 `usesAssistStructureScreenContent`），不支持锁屏上唤起。
- `android.intent.action.ASSIST`：直接打开 `AssistantActivity`，只带前台应用的包名（`EXTRA_ASSIST_PACKAGE`），没有截图。带 `query`（`SearchManager.QUERY`）时把它填进输入框，不自动发。
- 识别服务 `ZetaRecognitionService`：数字助理必须带一个才能被选上，选上后系统的默认语音识别也换成它。它不做识别，转给手机原有的识别服务（小米的 `com.xiaomi.mibrain.speech/.asr.AsrService`）。下游按调用方核对麦克风权限，所以 Zeta 要有 RECORD_AUDIO。

## 浮窗

`AssistantActivity` 是透明的 activity，不进最近任务，底部一张 COUI 面板（`AssistantPanel.kt`）：

- 上面一条是带上的屏幕：缩略图和应用名，可以去掉，去掉后只发问题。
- 建议问题点了就发；在主屏上打开时输入框自动拿到焦点。
- 发出去以后对话放在后台跑，不切换 Zeta 主界面正开着的对话，回答显示在面板上，可以接着问。Claude 还没开口时屏幕边缘流光。
- 上推面板或点「在 Zeta 中继续」打开 Zeta 并切到这个对话。还没发就上推，问题草稿和截图放进 Zeta 的输入栏（照分享进来的做法）。
- 往下拖、点外面、返回键关掉；切走（按主屏、锁屏）也关掉。后台的对话照样跑完，Zeta 照常提醒。
- 浮窗开着时再唤起一次，屏幕就是浮窗自己，不再采集，浮窗保持原样。
- 语音输入的接入点是 `AssistantScreen` 的 `voice`：语音识别模块接好后在 `AssistantActivity` 里给出，面板上就有话筒键。

数字助理新开的对话用 设置 › 能力 › 数字助理 里单独定的模型和思考强度（`assistantModel`、`assistantEffort`，经 `Conversation.preset`）；没定时跟随 Claude Code 的默认（和新对话一样，见设置首页）。只用在这个对话上，不改新对话的默认。

屏幕内容的整理是纯函数（`ScreenDigest`）：按界面顺序收文字，去重，跳过不可见、密码框和应用声明不给助理看的部分，一个应用最多 150 行、3000 字。消息里放在 `<screen_context>` 块里。

## 长按电源键（HyperOS）

HyperOS 长按电源键由 system_server 直接拉起小爱，不看默认数字助理是谁。Zeta 自带一个 LSPosed 模块（`xposed/`，libxposed API 102，入口登记在 `resources/META-INF/xposed/`，依赖只 compileOnly），作用域只有系统框架：

- 拦 `com.miui.server.input.util.ShortCutActionsUtils.triggerFunction`。HyperOS 4（OS4.0.6）上有四参数和五参数两个重载，四参数的直接转调五参数的，所以有五参数的就只拦它；返回类型不是 boolean、两个都对不上时不装，并在日志里写出系统里有的签名。
- 只处理功能 `launch_voice_assistant`、来源 `long_press_power_key` 或 `imperceptible_press_power_key` 的那几次。默认数字助理（secure `voice_interaction_service`）是本包、没锁屏时，请系统显示当前默认助理的会话（`showSessionForActiveService`，和 SystemUI 唤起助理同一个入口）；成功才消费这次动作，同一次按键 1 秒内的重复派发直接吞掉；失败、锁屏、默认助理不是本包都放行（给另一个包的模块，都不接管时到小爱）。别的按键和动作原样放行。
- 诊断：装没装上、每次电源键走了哪条路，写进 LSPosed 的模块日志和 logcat，标签正式版 `ZetaPowerKey`、测试版 `ZetaDevPowerKey`。Zeta 的 设置 › 诊断 › 数字助理 读这两处里本包的标签（要 root），显示默认助理是谁、系统语音识别是谁、本包模块装没装上和最近的记录。命令行：`logcat -d -s ZetaPowerKey ZetaDevPowerKey`。

正式版、测试版各带一个模块，在 LSPosed 里各自启用，互不干扰：每个只在默认助理是自己时接管，否则放行；钩子 id、日志标签、去重状态都按包分开。只启用一个时，默认助理是另一个包的话长按电源键到小爱。

模块代码在 system_server 里，开机时从 LSPosed 记下的 APK 路径加载。重装会换 APK 路径，LSPosed 有时到下次开机才改过来（实测：重装后第一次开机按旧路径加载失败，开机后才更新），所以重装后的第一次重启可能装不上，诊断页「长按电源键接管」不是「已装上」时再重启一次。模块代码改了也要重启才生效。

## 放行系统应用的工具服务

同一个模块还放行本包调系统应用开给系统智能体的工具（手机工具 `app_tools`、`app_tool`，见 `phone/tools/AppTools.kt`）。多数工具服务在清单里要求签名级权限 `com.aios.osbot.permission.BIND_APP_TOOL`（小爱定义的），Zeta 绑定时被系统拒绝（`Not allowed to bind to service`）。模块拦 `ActivityManagerService.checkComponentPermission`：查的是这个权限、调用方是本包时答有，别的原样交给系统（`xposed/AppToolBindHook.kt`）。只放行绑定，请求仍由 root 辅助进程以 system 身份发，因为服务还看调用方是不是系统签名。日历、电话、桌面、便签这几个的服务不要这个权限，没有模块也能用。

诊断：装没装上和第一次放行写进 LSPosed 的模块日志和 logcat，标签正式版 `ZetaAppTools`、测试版 `ZetaDevAppTools`。命令行：`logcat -d -s ZetaAppTools ZetaDevAppTools`。启用步骤和电源键的一样（下面「用户要做的」第 2、3 步）；只用工具不用电源键时，不必换默认助理。

## 开机保持默认助理

用户在系统设置里选默认助理时，系统只改 `assistant` 和 `voice_interaction_service`，不改默认语音识别 `voice_recognition_service`。开机时（Android 16 的 VoiceInteractionManagerService.initForUser）系统先看默认识别服务：为空、找不到或不是 `selectableAsDefault` 的，就把默认助理重选成它找到的第一个可用助理（装着 Eta 时就是 Eta，所以重启后默认助理被换掉）。

所以 Zeta 的识别服务声明成可以当默认（`res/xml/zeta_recognition_service.xml`），并在系统连上 Zeta 的助理服务时（`ZetaVoiceInteractionService.onReady`：选上之后、每次开机后）用 root 把 `voice_recognition_service` 换成本包的 `ZetaRecognitionService`，已经是就不动。它转给手机原有的识别服务，别的应用的语音输入照常。诊断页「系统语音识别」显示这一项，默认助理是 Zeta 而识别服务不是本包时标红。

不直接写手机原有的小米识别服务（`com.xiaomi.mibrain.speech/.asr.AsrService`）：它的清单里没有 `android.speech` 元数据，系统不认它可选为默认，开机时照样会重选默认助理。代价是经系统默认识别器的语音输入都多转一道 Zeta。Zeta 自己的按住说话和这个代理挑下游时都跳过 Zeta 的两个包（`SpeechBackend.choose`），正式版、测试版不会互相转。

## 用户要做的

代理不替用户换默认助理、不在 LSPosed 里启用模块、不重启手机。正式版、测试版各做各的，下面以要用的那个包为准（测试版在系统设置和 LSPosed 里叫「Zeta 测试」）。

1. 系统设置 › 默认应用 › 数字助理（Zeta 设置 › 能力 › 数字助理 能跳过去），选这个包。
2. LSPosed 管理器 › 模块，启用这个包的模块，作用域勾「系统框架」。
3. 重启手机。
4. 验：这个包的 设置 › 诊断 › 数字助理：「系统语音识别」是这个包，「长按电源键接管」是「已装上」（重装过这个包后第一次重启可能还不是，再重启一次）。打开任意应用长按电源键，应弹出它的浮窗而不是小爱；记录里多一条「已显示 <包名> 的助理会话」。

## 真机回归

`tools/phone-tests/assistant.sh`：核对服务和入口注册上了；ASSIST 在测试屏上打开浮窗，预填一个不用工具的问题（不用建议问题：没有截图时 Claude 会用手机工具去读主屏，也就是用户正在用的屏幕）发出去，等到回答，上推打开 Zeta。会真跑一轮 Claude；收尾删掉测试会话，并按会话 ID 结束接着它的后台进程（不结束的话测试版下次冷启动会把它接回来）。电源键这条要用户先做完上面几步，只能用户在主屏上验。
