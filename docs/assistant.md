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

屏幕内容的整理是纯函数（`ScreenDigest`）：按界面顺序收文字，去重，跳过不可见、密码框和应用声明不给助理看的部分，一个应用最多 150 行、3000 字。消息里放在 `<screen_context>` 块里。

## 长按电源键（HyperOS）

HyperOS 长按电源键由 system_server 直接拉起小爱，不看默认数字助理是谁。Zeta 自带一个 LSPosed 模块（`xposed/`，libxposed API 102，入口登记在 `resources/META-INF/xposed/`，依赖只 compileOnly），作用域只有系统框架：

- 拦 `com.miui.server.input.util.ShortCutActionsUtils.triggerFunction`。HyperOS 4（OS4.0.6）上有四参数和五参数两个重载，四参数的直接转调五参数的，所以有五参数的就只拦它；返回类型不是 boolean、两个都对不上时不装，并在日志里写出系统里有的签名。
- 只处理功能 `launch_voice_assistant`、来源 `long_press_power_key` 或 `imperceptible_press_power_key` 的那几次。默认数字助理（secure `voice_interaction_service`）是 Zeta 的正式版或测试版、没锁屏时，请系统显示当前默认助理的会话（`showSessionForActiveService`，和 SystemUI 唤起助理同一个入口）；成功才消费这次动作，同一次按键 1 秒内的重复派发直接吞掉；失败、锁屏、默认助理不是 Zeta 都走原逻辑（小爱）。别的按键和动作原样放行。
- 诊断：装没装上、每次电源键走了哪条路，写进 LSPosed 的模块日志和 logcat，标签 `ZetaPowerKey`。Zeta 的 设置 › 诊断 › 数字助理 读这两处（要 root），显示默认助理是谁、模块装没装上和最近的记录。命令行：`logcat -d -s ZetaPowerKey`。

两个包都带这个模块，在 LSPosed 里启用一个就够，它对两个包的默认助理都起作用。

## 用户要做的

代理不替用户换默认助理、不在 LSPosed 里启用模块、不重启手机。

1. 系统设置 › 默认应用 › 数字助理（Zeta 设置 › 能力 › 数字助理 能跳过去），选「Zeta」（`com.dzshzx.zeta`）。测试版在这里叫「Zeta 测试」。
2. LSPosed 管理器 › 模块，启用「Zeta」（`com.dzshzx.zeta`），作用域勾「系统框架」。
3. 重启手机。
4. 验：Zeta 设置 › 诊断 › 数字助理，「长按电源键接管」应为「已装上」。打开任意应用长按电源键，应弹出 Zeta 浮窗而不是小爱；诊断页的记录里多一条「已显示 com.dzshzx.zeta 的助理会话」。

Eta 的模块也拦同一个方法：它的电源键目标留在「系统默认」，两边才不打架。

## 真机回归

`tools/phone-tests/assistant.sh`：核对服务和入口注册上了；ASSIST 在测试屏上打开浮窗，预填一个不用工具的问题（不用建议问题：没有截图时 Claude 会用手机工具去读主屏，也就是用户正在用的屏幕）发出去，等到回答，上推打开 Zeta。会真跑一轮 Claude；收尾删掉测试会话，并按会话 ID 结束接着它的后台进程（不结束的话测试版下次冷启动会把它接回来）。电源键这条要用户先做完上面几步，只能用户在主屏上验。
