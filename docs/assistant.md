# 数字助理与长按电源键

Zeta 可以被选为系统的默认数字助理。唤起后在当前应用上弹出浮窗，带上当前屏幕问 Claude：发出去时新开一个对话，截图作图片附件，应用内容整理成精简的文字接在问题后面。

## 入口

- 系统助理会话：`assistant/ZetaVoiceInteraction.kt`。会话收系统给的截图（`onHandleScreenshot`）和应用内容（`onHandleAssist`），存进 `AssistCapture`，再用 `startAssistantActivity` 打开浮窗。会话自己不画界面。元数据在 `res/xml/zeta_voice_interaction.xml`，要截图、应用内容和屏幕文字（Android 17 的 `usesAssistStructureScreenContent`），不支持锁屏上唤起。
- `android.intent.action.ASSIST`：直接打开 `AssistantActivity`，只带前台应用的包名（`EXTRA_ASSIST_PACKAGE`），没有截图。带 `query`（`SearchManager.QUERY`）时把它填进输入框，不自动发。
- 识别服务 `ZetaRecognitionService`：数字助理必须带一个才能被选上，选上后系统的默认语音识别也换成它。它不做识别，转给手机原有的识别服务（小米的 `com.xiaomi.mibrain.speech/.asr.AsrService`）。下游按调用方核对麦克风权限，所以 Zeta 要有 RECORD_AUDIO。

## 浮窗

`AssistantActivity` 是透明的 activity，不进最近任务。界面是从 Eta 的助理浮窗（只取打字，不做语音）拷来再改写的，登记在 `core/about/ThirdParty.kt` 的 `ETA_FILES`：`AssistantPanel.kt`（压暗与托底、进出场、回答面板的外形和把手，对应 Eta 的 `EtaVoicePanel.kt`）、`AssistantSurface.kt`（配色、G2 顶角、浮起胶囊、按钮图标、按压回弹，对应 `AssistantPanelSurface.kt`）、`AssistantComposer.kt`（输入栏）、`AssistantSuggestionRow.kt`（建议问题）、`AssistantEdgeGlow.kt`（唤起光效）、`AssistantReply.kt`（回答面板里一问一答的排法）。

- 唤起：屏幕边缘播一次唤起光效（`AssistantEdgeGlow.kt`）：右侧电源键的位置亮起一团光，一条流光沿右边框往下，绕过右下角的屏幕圆角滑到底边，底部光场升起，整段约 0.64 秒，放完淡出。光效一开始，页面就轻轻压暗、底部铺一层渐变托底，内容约 0.05 秒后从下面 14dp 处淡入：带上的屏幕（缩略图、应用名，可以去掉，去掉后只发问题）、一行建议问题（三个短标签，一行放得下，点了就发）、输入栏（输入框和右边的圆键在同一个胶囊里）。建议问题一个个错开出现；唤起约 0.85 秒后在主屏上输入框拿到焦点、弹键盘（照 Eta 的录屏，约 1.2 秒时键盘已经弹上来）。
- 输入栏：空着时提示「问问 Claude」，右边是灰的发送键，有字时变主色；这一轮还在进行（正在分析、正在处理、在出回答）时是停止键（深色底、实心方块），点了打断这一轮（`Conversation.interrupt`），这期间输入框不能输入。Eta 空闲时右边是语音键，Zeta 没有语音，换成发送键。
- 发出去以后键盘收起，从底部升起贴住屏幕底边的半屏面板（顶部 G2 连续圆角、底部直角，铺到导航栏下面），页面压暗加深。面板里照 Eta 一问一答排下来：用户问题是右对齐的灰色气泡，第一问上面是带上的屏幕（小缩略图和应用名，去掉了就没有）；还没出回答时是带灯泡图标的「正在分析任务」；有思考或工具调用时是一行可折叠的「思考 N 次 · 调用 N 个工具」（工具在跑时写「正在处理 · 第 N 步 · 做什么」，没有思考和工具调用就没有这一行，点开是逐步的时间线）；然后回答流式出字，答完正文下面是复制键。要用户去 Zeta 里确认时，状态写「等你处理：…」。可以接着问，新的一问接在下面。对话放在后台跑，不切换 Zeta 主界面正开着的对话。Claude 还没开口时屏幕边缘流光（`ui/overlay/EdgeGlow`，和 Zeta 操作手机时同一种光）。
- 面板高度：一发出去就升到基础高度（把手到输入栏上沿 304dp，放不下时取能放的最大高度），回答变长时按需要平滑长高、只长不缩，长到接近全屏（离状态栏 88dp）后在面板里滚动，进行中时跟到底。照 Eta 分几档：最低（基础高度和已长到的高度取小）、基础、中间（基础到最高的 58%）、最高。拖把手，或在回答已经滚到顶时往下拖、面板还没到最高时往上拖回答区，都改面板高度，松手停到最近的一档；拖过以后不再自动长高。高度、档位和松手的判断在 `AssistantSheet.kt`，`AssistantSheetTest` 覆盖；对话怎么排成面板里的几行（`AssistantReplies`、`WorkSummary`）由 `ScreenDigestTest` 覆盖。
- 打开 Zeta 并切到这个对话：面板在最高档时从把手再往上拉 24dp（只认把手，回答区的拖动不算），或点把手、点回答区右下角的「在 Zeta 中继续」。往上拉时把手下面出现「继续上拉进入 Zeta」；拉够了，把手变宽、变成主色，提示换成「松手进入 Zeta」，并振动一次。还没发时整列上推 56dp 打开 Zeta，问题草稿和截图放进 Zeta 的输入栏（照分享进来的做法）。
- 关掉：有回答时往下拖过最低档再多拖 92dp 松手，还没发时整列下拉 96dp；点外面、返回键也关掉（内容往下 22dp 淡出）；切走（按主屏、锁屏）也关掉。后台的对话照样跑完，Zeta 照常提醒。
- 浮窗开着时再唤起一次，屏幕就是浮窗自己，不再采集，浮窗保持原样。

数字助理新开的对话用 设置 › 能力 › 数字助理 里单独定的模型和思考强度（`assistantModel`、`assistantEffort`，经 `Conversation.preset`）；没定时跟随 Claude Code 的默认（和新对话一样，见设置首页）。只用在这个对话上，不改新对话的默认。

屏幕内容的整理是纯函数（`ScreenDigest`）：按界面顺序收文字，去重，跳过不可见、密码框和应用声明不给助理看的部分，一个应用最多 150 行、3000 字。消息里放在 `<screen_context>` 块里。

## 长按电源键（HyperOS）

HyperOS 长按电源键由 system_server 直接拉起小爱，不看默认数字助理是谁。Zeta 自带一个 LSPosed 模块（`xposed/`，libxposed API 102，入口登记在 `resources/META-INF/xposed/`，依赖只 compileOnly），作用域是系统框架和系统界面（系统界面只用于下面「超级岛」一节）：

- 拦 `com.miui.server.input.util.ShortCutActionsUtils.triggerFunction`。HyperOS 4（OS4.0.6）上有四参数和五参数两个重载，四参数的直接转调五参数的，所以有五参数的就只拦它；返回类型不是 boolean、两个都对不上时不装，并在日志里写出系统里有的签名。
- 只处理功能 `launch_voice_assistant`、来源 `long_press_power_key` 或 `imperceptible_press_power_key` 的那几次。默认数字助理（secure `voice_interaction_service`）是本包、没锁屏时，请系统显示当前默认助理的会话（`showSessionForActiveService`，和 SystemUI 唤起助理同一个入口）；成功才消费这次动作，同一次按键 1 秒内的重复派发直接吞掉；失败、锁屏、默认助理不是本包都放行（给另一个包的模块，都不接管时到小爱）。别的按键和动作原样放行。
- 诊断：装没装上、每次电源键走了哪条路，写进 LSPosed 的模块日志和 logcat，标签正式版 `ZetaPowerKey`、测试版 `ZetaDevPowerKey`。Zeta 的 设置 › 诊断 › 数字助理 读这两处里本包的标签（要 root），显示默认助理是谁、系统语音识别是谁、本包模块装没装上和最近的记录。命令行：`logcat -d -s ZetaPowerKey ZetaDevPowerKey`。

正式版、测试版各带一个模块，在 LSPosed 里各自启用，互不干扰：每个只在默认助理是自己时接管，否则放行；钩子 id、日志标签、去重状态都按包分开。只启用一个时，默认助理是另一个包的话长按电源键到小爱。

模块代码在 system_server 里，开机时从 LSPosed 记下的 APK 路径加载。重装会换 APK 路径，LSPosed 有时到下次开机才改过来（实测：重装后第一次开机按旧路径加载失败，开机后才更新），所以重装后的第一次重启可能装不上，诊断页「长按电源键接管」不是「已装上」时再重启一次。模块代码改了也要重启才生效。

## 放行系统应用的工具服务

同一个模块还放行本包调系统应用开给系统智能体的工具（手机工具 `app_tools`、`app_tool`，见 `phone/tools/AppTools.kt`）。多数工具服务在清单里要求签名级权限 `com.aios.osbot.permission.BIND_APP_TOOL`（小爱定义的），Zeta 绑定时被系统拒绝（`Not allowed to bind to service`）。模块拦 `ActivityManagerService.checkComponentPermission`：查的是这个权限、调用方是本包时答有，别的原样交给系统（`xposed/AppToolBindHook.kt`）。只放行绑定，请求仍由 root 辅助进程以 system 身份发，因为服务还看调用方是不是系统签名。日历、电话、桌面、便签这几个的服务不要这个权限，没有模块也能用。

诊断：装没装上和第一次放行写进 LSPosed 的模块日志和 logcat，标签正式版 `ZetaAppTools`、测试版 `ZetaDevAppTools`。命令行：`logcat -d -s ZetaAppTools ZetaDevAppTools`。启用步骤和电源键的一样（下面「用户要做的」第 2、3 步）；只用工具不用电源键时，不必换默认助理。

## 超级岛

同一个模块还让 Zeta 的通知按 Zeta 自己写的参数上小米超级岛（焦点通知：通知 extras 里的 `miui.focus.param`、`miui.focus.pics`、`miui.focus.actions`，拼法在 `core/FocusParam.kt`）。带它的是对话那条通知：每个对话一条（tag 是会话号），回答进行中（不响、不弹，写当前步骤、走秒，有任务清单时带进度条）、等你确认或回答（岛自己展开，带回答按钮）、跑完或中断（不静音的弹出来停 5 秒收起，通知留在通知栏）都在这一条上原地更新。用户正看着的对话不发，顶部胶囊正显示着的对话也不发。前台服务的那条只为保活，在最低重要度的「后台运行」渠道里，不上岛、不上锁屏。

照小爱的做法（`com.miui.voiceassist` 替用户操作手机、深度思考的通知）：一件事一条通知、固定的 id 原地更新，不和保活的前台服务通知混在一起；只发普通通知加焦点参数，不申请 Android 16 的实时通知（promoted ongoing，`setRequestPromotedOngoing`）。带了这个标记，系统界面不把插件画好的卡片挂进通知栏那一行（`RowContentBindStage.requestRebind` 只挂不带它的焦点通知），卡片在通知栏里时有时无，实测 Zeta 在前台时是空白的；插件还会拿系统从实时通知转出的参数换掉 Zeta 写的。

焦点通知插件（`miui.systemui.plugin`，跑在系统界面进程里）鉴权时，国行系统上不带自定义视图的焦点通知要过 `SignatureChecker.checkSignatures(packageName)`（和系统界面同签名），否则去小米服务器核对申请过的权限，没申请过不上岛。模块在系统界面里拦它，查的是本包时答是（`xposed/FocusAuthHook.kt`）。

这个类在插件的 ClassLoader 里，插件晚于系统界面加载，模块等 `PluginInstance$PluginFactory.createPluginContext` 建出插件的 Context 再装。模块没启用时不上岛：对话那条是普通通知，跑完和等你照常是横幅。

诊断：装没装上和第一次放行写进 LSPosed 的模块日志和 logcat，标签正式版 `ZetaIsland`、测试版 `ZetaDevIsland`。命令行：`logcat -d -s ZetaIsland ZetaDevIsland`；岛用了谁的参数看 `logcat -d -s DynamicIslandEventCoordinator` 里本包那条的 `tickerData`（图标是 `miui.focus.pic_app_icon` 的是 Zeta 的）。模拟器 `dumpsys` 不列出 `miui.focus.param`，核对 `miui.focus.pics`。不向小米申请官方焦点通知权限，也不改用 Android 实时通知；对照小爱通知结构可参考 `com.xiaomi.voiceassist.baselibrary.uiagent.common.b`（替用户操作手机）与 `com.xiaomi.voiceassist.deepthink.a`（深度思考）。重装后的旧 APK 路径问题（上面「长按电源键」末段）在系统界面里一样：重装后系统界面按旧路径加载失败，重启手机后才装上。

## 开机保持默认助理

用户在系统设置里选默认助理时，系统只改 `assistant` 和 `voice_interaction_service`，不改默认语音识别 `voice_recognition_service`。开机时（Android 16 的 VoiceInteractionManagerService.initForUser）系统先看默认识别服务：为空、找不到或不是 `selectableAsDefault` 的，就把默认助理重选成它找到的第一个可用助理（装着 Eta 时就是 Eta，所以重启后默认助理被换掉）。

所以 Zeta 的识别服务声明成可以当默认（`res/xml/zeta_recognition_service.xml`），并在系统连上 Zeta 的助理服务时（`ZetaVoiceInteractionService.onReady`：选上之后、每次开机后）用 root 把 `voice_recognition_service` 换成本包的 `ZetaRecognitionService`，已经是就不动。它转给手机原有的识别服务，别的应用的语音输入照常。诊断页「系统语音识别」显示这一项，默认助理是 Zeta 而识别服务不是本包时标红。

不直接写手机原有的小米识别服务（`com.xiaomi.mibrain.speech/.asr.AsrService`）：它的清单里没有 `android.speech` 元数据，系统不认它可选为默认，开机时照样会重选默认助理。代价是经系统默认识别器的语音输入都多转一道 Zeta。Zeta 自己的按住说话和这个代理挑下游时都跳过 Zeta 的两个包（`SpeechBackend.choose`），正式版、测试版不会互相转。

## 用户要做的

代理不替用户换默认助理、不在 LSPosed 里启用模块、不重启手机。正式版、测试版各做各的，下面以要用的那个包为准（测试版在系统设置和 LSPosed 里叫「Zeta 测试」）。

1. 系统设置 › 默认应用 › 数字助理（Zeta 设置 › 能力 › 数字助理 能跳过去），选这个包。
2. LSPosed 管理器 › 模块，启用这个包的模块，作用域勾「系统框架」和「系统界面」（后者只为超级岛）。
3. 重启手机。
4. 验：这个包的 设置 › 诊断 › 数字助理：「系统语音识别」是这个包，「长按电源键接管」是「已装上」（重装过这个包后第一次重启可能还不是，再重启一次）。打开任意应用长按电源键，应弹出它的浮窗而不是小爱；记录里多一条「已显示 <包名> 的助理会话」。

## 真机回归

`tools/phone-tests/assistant.sh`：核对服务和入口注册上了；ASSIST 在测试屏上打开浮窗，预填一个不用工具的问题（不用建议问题：没有截图时 Claude 会用手机工具去读主屏，也就是用户正在用的屏幕）发出去，等到回答，点「在 Zeta 中继续」打开 Zeta。会真跑一轮 Claude；收尾删掉测试会话，并按会话 ID 结束接着它的后台进程（不结束的话测试版下次冷启动会把它接回来）。电源键这条要用户先做完上面几步，只能用户在主屏上验。
