# 界面写法

手机版的界面用 Miuix（`top.yukonga.miuix.kmp`，HyperOS 风格），顶栏、对话框用它 `miuix-glass` 的 HyperOS 4 玻璃样式。桌面版不照这里，见 `docs/desktop.md`「界面」。

## 照什么写

- 组件参数以 Miuix 源码为准（github `compose-miuix-ui/miuix`，克隆一份到本地，对照前先 `git pull`）：`miuix-ui`、`miuix-preference`、`miuix-glass`、`miuix-blur`、`miuix-squircle` 的 `src/commonMain`；组件的样子看 `docs/components/`。
- Miuix 没有的东西先看 Eta（github `Mangi-11/Eta`，也用 Miuix、也是 HyperOS 4 的玻璃样式）有没有现成写法，有就拷来再改，拷来的文件登记在 `core/about/ThirdParty.kt` 的 `ETA_FILES`。
- 字符串不用 Compose resources，走 Zeta 自己的文字表（见 `docs/i18n.md`）；页面切换由 Zeta 自己管（聊天页上的页面栈 `ui/PageStack.kt`；一层层盖上去的页面都用 `ui/components/SlideInPage.kt`，带返回键时给 `onBack`；页面要带着一个值打开时用带值的写法 `SlideInPage(value) { … }`，值变空就关，退场动画期间仍用最后的值画），不用 miuix-nav；页面骨架用 `ui/components/miuix` 的，不再复制。

## Zeta 的补充件

Miuix 没有的才在 `ui/components/miuix` 补，页面文件里不各写一份；要加新的共用件就加在这里。

- `MiuixPage`（列表页）、`MiuixPageScaffold`（内容随意）：页面骨架。顶栏是 Miuix 的玻璃顶栏（`GlassTopAppBar`：手机上大标题随上滑折叠，宽屏时是固定的小标题；页面滚离顶部时显出渐进模糊），页面录进背景图给顶栏和弹出的东西做毛玻璃，宽屏时内容居中，键盘弹出时让位。顶栏的按钮槽里放 `IconActionButton`，它自己画成玻璃圆钮（`LocalInGlassTopBar`）。
- `MiuixBlur.kt`：毛玻璃接线。页面骨架用 `rememberPageBlur()` 建这一页的毛玻璃，`Modifier.pageBlurSource(blur)` 标出录进背景图的范围，`PageBlurHost(blur) { }` 包住要弹东西的槽位（顶栏、内容），四处骨架（`MiuixPageScaffold`、聊天页、侧栏容器、浏览器）都这么接；盖在录制范围上面、从屏幕底边往上淡掉的毛玻璃用 `Modifier.bottomBarGlass(backdrop)`（和玻璃顶栏的渐进模糊对称，看大图的底栏这么画）；`LocalMiuixBlurEnabled`（截图测试里关掉）。
- `PreferenceGroup`、`PreferenceValue`、`FooterHint`：设置页的一组（分类标题 `SmallTitle`、卡片、卡片下的说明），设置行右侧的值（状态色用 `color`），卡片下或列表末尾的一段说明。HyperOS 的卡片里行与行之间不画分隔线。标题加当前值的一行用 `ValueRow`（`ui/settings/SettingsRows.kt`），值的颜色跟着行能不能点走，规则见 `PreferenceValue` 的注释。
- `SettingsIcon(图标)`：设置首页行首的图标，放进行的 `startAction`。
- `CardListSegment`：几百行的长列表逐行放进 LazyColumn 时，仍画成一整张卡片。
- `DropdownValuePreference`：右边显示这一行自己的值，点开弹出操作菜单。
- `FieldGroup`、`SwitchWithDividerPreference`、`SwitchLoadingPreference`（`InputRows.kt`）：编辑页里的一个输入项（小标题、直接放在页面上的输入框、下面的说明，照 Eta 的角色编辑页；输入框自带底色，不套卡片）；点行进编辑页、右边开关单独管的行（开关前一条竖线）；开关在等异步结果时换成转圈的行。
- `IconActionButton`、`FlatIconButton`、`CopyIconButton`（`IconActionButton.kt`）：只放一个图标的按钮。`IconActionButton` 参数照 Miuix 的 `IconButton`（顶栏的新建、刷新、返回，在顶栏里是玻璃圆钮）；`FlatIconButton(size, iconSize)` 是平的小圆钮（查找栏、消息下方、排队条）；`CopyIconButton` 是点了复制、短暂显示对勾的复制键。图标按钮都从这三个建，不直接用 Miuix 的 `IconButton`、`GlassIconButton`。
- `MenuItem`、`MenuEntry`、`AnchoredMenu`、`GlassMenu`、`TopBarMoreMenu`、`RowMoreMenu`（`Menus.kt`）、`LongPressMenu`：菜单的一项和一组；挂在胶囊、按钮、标题上的玻璃菜单（照 Eta：Miuix 的 `GlassPopup` 和 `GlassPopupItem`，图标在文字前，警示项文字和图标都是红色，二级菜单是 `GlassSecondaryPopup`）；顶栏和列表行里的「更多」；长按内容弹出的菜单（不吃掉普通点按）。
- `PageDialog`、`ConfirmDialog`、`NoticeDialog`、`TextInputDialog`、`DialogTextField`、`DialogButtonBar`（`Dialogs.kt`）：玻璃对话框（模糊页面背景），对话框里带标题的输入框，对话框底部平分整行的按钮栏（`DialogButtonBarAction`：`recommended` 主题色、`destructive` 红色）。
- `TextField`（`TextFields.kt`）：参数照 Miuix 的 `TextField`，加了 `showClearButton`，光标由 Zeta 管（见「补上游缺口」）；应用代码里的输入框都用它。
- `CompactButton`、`TopBarTextButton`、`LinkText`：卡片里、列表行里的小号文字按钮；顶栏右边的文字按钮（保存、清除已结束；在玻璃顶栏里是一直显出底色的胶囊，带返回钮那档阴影）；一句可点的主题色文字（对话框、卡片里不值得占一个按钮的次要操作）。
- `SquircleShape(半径)`、`ZetaCorner`（`SquircleShape.kt`）：要拿 Shape 的地方用的超椭圆圆角（Miuix 只给修饰符和路径），圆角的几档。
- `ZetaIcons`：Miuix 没有的图标，照它的画法自己画（见下面「图标」）；`ZetaIcons.gauge(档位, 格数)` 是刻度表。
- 配色补充（`ui/components/ZetaColors.kt`）：Miuix 配色里没有的槽，写成配色对象的扩展：`isDark`、`success`、`StatusWarning`、半透明中性底 `fill4`/`fill8`/`fill16`、`textHighlight`、几种强调色 `accentBlue` 等。

聊天页输入栏的底栏另有 `BarButton`、`BarMenuButton`（`ui/chat/BarButtons.kt`）：只放图标的定宽按钮，一句说明同时当读屏说明和长按提示，配色取 `barChipColors`。底栏不写字，档位、百分比都在这句说明、菜单和用量弹窗里；新按钮放进 `Composer` 的 `tools` 或 `status`，间距由 `Composer` 统一给。

## 设置页

照 Eta 的设置页：首页是一张按「管什么」分组的入口清单，说明文字放进子页。

- 首页的组和顺序：设备 → 手机与工具 → 通用 → 关于。界面上手机和远程主机都叫「设备」（SSH 的术语照旧：主机指纹、主机密钥、地址里的「用户@主机」）。
  - 设备：手机和各台远程主机各一行（标题是设备的名字，右边是它新对话的默认模型和思考强度，用不了时写原因），最后一行「添加远程设备」。点进去是那台设备的页面（`HostSettingsScreen`）：新对话默认（模型、思考强度、权限模式，读写那台 Claude Code 自己的设置。默认模型随当前来源：账号用设备设置，中转用中转设置；清除中转模型后继承设备设置。模型清单的取法见 `docs/core.md` 的 `config.models.read`；对话里选的只管那个对话）、Claude（账号与额度、自动压缩、压缩时机、自动标题，以及在这台上跑的定时任务和通知触发）、Claude Code 配置（记忆、Skills、MCP、Subagent、工具权限），手机还有「自定义设备名」和 Linux 环境的入口，远程主机还有连接（「自定义设备名」在第一行）。自定义设备名留空就用设备自己的名字。凡是每台主机各是各的设置都放这里，读写的是点开的那台，和侧栏正看着哪台无关。
    - 账号与额度页（`AccountsScreen`）：订阅额度、账号、中转三组。中转一行写名字和地址（有默认模型时附上），在用的标「当前」；点行确认后切换过去（和换号一样先收闲着的对话进程，忙时说原因），行尾的编辑钮和最后一行「添加中转」进编辑页（`RelayScreen`，改动先放在草稿 `RelayDraft` 里，点顶栏「保存」才交给核心）：名称（保存后不能改）、地址（框下一句：只接 Anthropic Messages，OpenAI 协议的上游经能翻译的中转接入）、密钥（遮着，可显示；修改时留空不改；用户自己放的、核心说改不了的禁用并说明）、默认模型（手填，或「获取模型列表」拉到后在弹框里选一个，也可选「不设」；没拉成时组下写核心给的原因）、删除（在用的不能删，组下说明）。
    - 这台在用中转时没有订阅额度，界面只看核心推来的 `relay`（`QuotaService.relay`），不各自判断：额度组只说一句没有订阅额度，设备页「账号与额度」一行右侧不写百分比、摘要写中转名，聊天页用量弹窗里没有额度段，也不去问额度。换回账号后照常显示。
  - 手机与工具（手机操作、数字助理、浏览器、权限健康）、通用（外观、屏幕常亮、网络图片、朗读、存储、备份）、关于：Zeta 自己的设置，哪台主机都一样。
  - 新入口按管什么归进这几组，不为一两行另开一组。同一个设置项只放一处，别的页面用得到时在说明里指过去，不再放一份能改的。
- 首页每行行首一个 `SettingsIcon`。
- 首页的行只写当前状态：右侧值（`PreferenceValue`，出错时红字）或一行状态摘要（账号、下次运行时间），不写这一项是干什么的；也不加组下说明，只有「关于」组下留一行非官方声明（Claude 是 Anthropic 的商标，Zeta 不是 Anthropic 的产品，`ZetaInfo.UNOFFICIAL`）。
- 子页的行不放图标。摘要最多一行，说这一项是什么或当前状态；要讲清楚的放组下说明（`PreferenceGroup(footer = …)`），一段两三句。
- 首页上的开关、下拉直接改；要配几项的东西做成子页（`SettingsSubPage` 加一项，在 `SettingsScreen` 子页的 `when` 里加一个分支），首页只留一个带状态的入口。

## 聊天页的图片

- 回答里用 Markdown 引用的图片（本机路径、网络地址、内嵌的 data:）由 `answerImages`（`ui/chat/ChatImage.kt`）从正文里取出，排在正文下面，每段回答最多 12 张，同一张只显示一次。取出时按原文分组：只隔着空白的几张连成一组，被正文隔开的各成一组。分组只管显示：聊天内查找用的是取掉图片后的正文，复制、朗读用的是原文。
- 一组只有一张时照原样铺开（`ChatImage`）。两张及以上是图片组（`ImageGallery`，`ui/chat/ImageGallery.kt`）：和思考与工具调用的一组（`WorkGroup`）是同一张卡片（`WorkCard`），标题写「N 张图片」，点了收起或展开，默认展开。展开后横向翻页，一次一张，高度跟着当前这张的比例，最高 360dp，更高的等比缩进去；右下角标「2 / 6」。组按第一张图认，流式输出时组里多了图，翻到的页和展开与否都留着。
- 点图全屏看（`ImageViewer`，`ui/chat/ImageViewer.kt`，单张、图片组和用户消息里的缩略图共用）：Miuix 没有现成的看图页，用 Compose 的 Dialog 搭。黑底铺满整屏，铺进刘海区域，状态栏和导航栏透明、图标浅色；里面固定用 Miuix 的深色配色（`darkColorScheme`，主色跟着外面），不随外面的深浅色变。图片层是毛玻璃的录制范围（`pageBlurSource`），上面两条栏包在 `PageBlurHost` 里，后面都是往图片中间渐渐淡掉的毛玻璃，没有分明的边：顶栏是和聊天页一样的玻璃顶栏（渐进模糊），底栏是 `bottomBarGlass`（从底边往上淡掉的渐进模糊）；毛玻璃关着时（截图测试）是从屏幕边往中间淡掉的渐变压暗。顶上是 Miuix 的 `GlassTopAppBar`（不折叠），色带透明：左边玻璃返回钮（`IconActionButton` 加 `MiuixIcons.Glass.ChevronBackward`，和别的页一样），多张时标题是「2 / 6」。底部是两项「保存到相册」「分享」（图标在上、文字在下），直接落在毛玻璃上、不垫胶囊，多张时上方是圆点页码（看图页自己画的 `PageDots`）。点一下图，两条栏一起淡出或淡入，打开时露着；双指缩放、双击放大、放大后拖动；没放大时左右滑换一张，放大时不翻页。进出都淡入淡出（关掉了系统的窗口动画）。从图片组打开时从点的那张开始，关掉后组停在最后看的那张。
- 保存和分享用的是查看器显示这张图的那份原始字节：`ImageStore` 取图时把解码前的字节和解码后的图按同一个键各缓存一份（`ImageStore.original`），不重新下载或再找主机要。字节拿不到（还没取到、取不到、已被挤出缓存）或按文件头认不出类型时，两项置灰。类型、文件名在 `core/ImageExport.kt`：文件名用原文件名或网络地址里的文件名，扩展名跟着内容走，取不出名字时是 `Zeta_年月日_时分秒`。保存写进系统相册的 Pictures/Zeta（MediaStore，自己建的条目不用存储权限），成没成都弹一句 Toast；分享把图写到缓存的 `shared_images/`，经本包的 FileProvider（authority `<包名>.images`，只开放这一个目录）交给系统分享面板。
- 网络图片在没开「自动加载网络图片」时，单张和组里的每一页都是「加载图片」按钮，点了才取。
- 回答里写的图片文件读不到（被删了、挪走了）时，改显示 Claude 用 Read 读这个文件时工具结果里的那张图（`readImage`）；没读过的照样显示「图片无法显示」。

## 补上游缺口

下面几处是 Miuix 的缺口，Zeta 在本地补着。`MiuixGapsTest` 断言缺口还在：升级 Miuix 后它哪条失败，说明上游已经补上，删掉对应的补丁和那条断言。

- 图标按钮不能点时不变灰，也没有按钮角色（`IconButtonContainer` 不给内容提供 `LocalContentColor`，禁用时不加 `clickable`）。补在 `IconActionButton`：自己换 tint，补上按钮角色和「已停用」。
- 分段标签 `TabRowWithContour` 没有 `enabled`，也不认调用方给的宽度（先 `fillMaxWidth()` 再接 `modifier`，给的宽度不起作用）。补在 `SegmentedTabs`：外面套一层 `Box` 承接 `modifier`，不能点时忽略点击，文字和选中块换成 Miuix 不可用的颜色（`disabledOnSecondaryVariant`、`disabledSecondaryVariant`）。应用代码里的分段标签都用它。`MiuixGapsTest` 只测得出宽度这一条。
- 输入框 `TextField` 的 String 版本把光标初始化在开头，文字从外面改了（清除按钮、调用方重设）以后光标也不跟到末尾：自动聚焦的输入框里打的字插到原有内容前面，点清除后打完第一个字光标又跳回开头。补在 `TextField`（`TextFields.kt`）：用 Miuix 的 TextFieldValue 版本，光标自己管；`TextInputDialog`、`DialogTextField`、`FieldGroup` 里的输入框都用它。应用代码不直接用 Miuix 的 `TextField`（`TextFieldSourcesTest` 断言）。`MiuixGapsTest` 只测得出第一个现象；第二个现象要真的输入法才出现，在设备上验：打开「外观与主题 › 界面缩放」，点清除，依次输入 9、5，应是 95（有缺口时是 59）。

## 图标

- 先用 Miuix 自带的（`MiuixIcons.*`，全部样子见 Miuix 仓库的 `miuix-icons` 源码和 `docs/components/icon.md`），按意思选，不按名字：`ExpandMore` 是全屏的四个角，向下的折角用 `ZetaIcons.ChevronDown`。玻璃顶栏里的返回键用 `miuix-glass-icons` 的 `MiuixIcons.Glass.ChevronBackward`。不用 Material 图标，依赖里没有它。
- Miuix 没有、或者它的是实心而旁边都是线条的，在 `ui/components/miuix/ZetaIcons.kt` 里画，规格写在那个文件开头。画完看 `tools/shots Icons` 出的 `zeta-icons` 总览图，和开头几个 Miuix 图标比粗细、大小；图形出界由同一个测试拦下。

## 常见的选法

| 要做的 | 用 |
| --- | --- |
| 设置页 | `MiuixPage(title, onBack, dialogs = { 对话框 }) { item { PreferenceGroup(title, footer) { item { 行 } } } }`，首页和子页的分别见上面「设置页」 |
| 点了进别的页 | `ArrowPreference` |
| 点了执行操作、开对话框 | `BasicComponent(onClick = …)`（不带箭头），右侧值 `endActions = { PreferenceValue(…) }` |
| 开关、勾选、单选、下拉选一个、滑杆 | `SwitchPreference`（加载中 `SwitchLoadingPreference`）、`CheckboxPreference`、`RadioButtonPreference`、`DropdownPreference`（Miuix 的玻璃下拉行，自己带上页面背景）、`SliderPreference` |
| 列表行带开关、点行进编辑页 | `SwitchWithDividerPreference` |
| 分组标题、页面底部说明 | `SmallTitle`、`FooterHint` |
| 两三个选项切换 | `SegmentedTabs(tabs, selectedIndex, onSelect, modifier, enabled)`；忙着时传 `enabled = false`，要撑满整行传 `Modifier.fillMaxWidth()` |
| 选时间、日期、数字 | 对话框里放几列 Miuix 的 `NumberPicker`（见 `ui/settings/ScheduleScreen.kt` 的 `WheelColumn`） |
| 列表上的搜索 | Miuix 的 `SearchBar` 放进 `MiuixPageScaffold` 的 `topBarBottomContent`，搜索中把列表换成结果列表（见 `ui/settings/NotifyTriggerScreen.kt`） |
| 图标按钮、顶栏的文字按钮 | `IconActionButton`（平的小圆钮 `FlatIconButton`，复制键 `CopyIconButton`）、`TopBarTextButton` |
| 按钮 | `TextButton(text, onClick)`；主操作 `colors = ButtonDefaults.textButtonColorsPrimary()`；卡片、列表行里放小按钮用 `CompactButton`；次要操作做成一句 `LinkText` |
| 输入框 | 编辑页里一项一项填的用 `FieldGroup(title, footer) { TextField(…) }`（照 Eta 的角色编辑页：小标题，输入框直接放在页面上、不套卡片），对话框里带标题的 `DialogTextField`，其他用 Zeta 的 `TextField` |
| 确认、告知、单行输入 | `ConfirmDialog`（危险操作 `destructive = true`，确认键画成红色；要区分「拒绝」和「随手关掉」给 `onCancel`）、`NoticeDialog`、`TextInputDialog` |
| 自定义对话框 | `PageDialog(customContent = { 内容，左右留 DialogContentPadding }) { DialogButtonBar(…) }`，主操作 `DialogButtonBarAction(…, recommended = true)` |
| 「更多」菜单 | 顶栏 `TopBarMoreMenu(entries)`，列表行 `RowMoreMenu(说明, entries)` |
| 长按菜单 | `LongPressMenu(entries) { 内容 }` |
| 菜单项、挂在别的元素上的菜单 | `MenuItem(文字, 图标, onClick)`，删除这类 `alert = true`；`Box { 元素; AnchoredMenu(show, entries, onDismiss) }`，元素和菜单同在一个 `Box` 里；锚是整行、拿不到 `Box` 时用 `GlassMenu(show, entries, anchorBounds, onDismiss)` |
| 二级菜单（如模型菜单的「更多模型」） | 那一项写 `MenuItem(文字, summary = 选中的子项, children = 子项)`，点它在旁边弹出下一层（`GlassSecondaryPopup`，头一行点了回到上一层）；两层的选择菜单用 `choiceEntries` |

## 坑

- Overlay 组件（对话框、菜单、弹窗）只在 Miuix 的 `Scaffold` 之内才显示。悬浮层窗口和应用最外层（比如「放进哪个对话」）不在任何 Scaffold 里，用 `Window*` 版本，而且不给页面毛玻璃。对话框放在 `MiuixPage` 的 `dialogs` 里，不放进列表项：列表项滚出屏幕时对话框跟着没了。
- 对话框的自定义内容放 `customContent`，不和按钮栏一起放进 `content`：否则内容不会在面板不够高时自己滚动，按钮栏会被挤出去。
- `PreferenceGroup` 的花括号里不是 composable，`remember`、取字这类调用要写进 `item { }`。
- 浮层（对话框、胶囊、弹窗这类面板）上不用卡片配色 `surfaceContainer`：深色下它和面板底色一样，分不出层次。按钮底用 `secondaryVariant`，输入框底 `secondaryContainer`，提示字 `onSecondaryContainer`。
- 换主题时 Miuix 就地改同一个配色对象，`remember(colorScheme)` 会拿到旧颜色；要按具体的颜色值做键。
- 截图测试画不出：平滑圆角（着色器裁剪，块会整块空白，截图里可以临时关掉 `LocalSquircleEnabled`）、毛玻璃和玻璃顶栏的渐进模糊、公式。这些上真机看。`ScheduleStore`、`NotifyRuleStore` 是进程级单例，同一个 JVM 里前面测试留下的数据会串到后面，用到的测试先清空。
- 一直循环的提示动画（转圈、呼吸、闪烁的点）用 `ui/components/IndicatorClock.kt` 的 `rememberIndicatorClock()`，配 `turn`、`pingPong` 换算，值只在绘制阶段读；不用 `rememberInfiniteTransition`、`infiniteRepeatable`（`IndicatorClockTest` 拦着）：它们每帧要一次新画面，回答进行中整窗按刷新率一直重画，耗电。Miuix 的 `InfiniteProgressIndicator` 只用在短暂的加载里。
