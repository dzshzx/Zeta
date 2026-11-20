# 界面写法

界面用 COUI 2（`io.github.suqi8.coui.kmp`，ColorOS 17 风格，Miuix 的分支）。

## 照什么写

- 写法照 COUI 2 自带的指南：工作站 `~/workspace-external/coui/.claude/skills/coui-app/SKILL.md`，以及同目录 `references/` 下的 `components.md`（ColorOS 控件对应哪个组件）、`pitfalls.md`（踩过的坑）。组件参数以源码为准：`~/workspace-external/coui/coui-ui/src/commonMain`、`coui-preference/src/commonMain`；组件的样子看 `docs/components/<名字>.md`。对照前先在那里 `git pull`。
- 指南里这几条 Zeta 不照做：字符串直接写中文，不用 Compose resources、不做双语；页面切换由 Zeta 自己管（聊天页上的页面栈 `ui/PageStack.kt`；一层层盖上去的页面都用 `ui/components/SlideInPage.kt`，带返回键时给 `onBack`），不用 coui-nav；页面骨架用 `ui/components/coui2` 的，不再复制。

## Zeta 的补充件

COUI 2 没有的才在 `ui/components/coui2` 补，页面文件里不各写一份；要加新的共用件就加在这里。

- `CouiPage`（列表页）、`CouiPageScaffold`（内容随意）：页面骨架。顶栏带 COUI 17 光晕，页面录进背景图给弹出的东西做毛玻璃，宽屏时内容居中、顶栏变小，键盘弹出时让位。
- `CouiBlur.kt`：毛玻璃接线。`LocalCouiBlurEnabled`（截图测试里关掉）、`LocalPageBackdrop`、`panelBlur(...)`。
- `PreferenceGroup`、`PreferenceValue`：设置页的一组（分类标题、卡片、卡片下的说明），设置行右侧的值（状态色用 `color`）。
- `SettingsIcon(图标, SettingsHue.*)`：设置首页行首的彩色图标，放进行的 `startAction`；颜色按这一行管的东西分四类，见下面「设置页」。
- `CardListSegment`：几百行的长列表逐行放进 LazyColumn 时，仍画成一整张卡片；行首有图标时自己给 `dividerStart`。
- `DropdownValuePreference`：右边显示这一行自己的值，点开弹出操作菜单。
- `IconActionButton`、`FlatIconButton`、`CopyIconButton`（`IconActionButton.kt`）：只放一个图标的按钮。`IconActionButton` 参数照 COUI 的 `IconButton`（顶栏的新建、刷新、返回）；`FlatIconButton(size, iconSize)` 是平的小圆钮（查找栏、消息下方、排队条）；`CopyIconButton` 是点了复制、短暂显示对勾的复制键。图标按钮都从这三个建，不直接用 COUI 的 `IconButton`。
- `menuItem`、`AnchoredMenu`、`TopBarMoreMenu`、`RowMoreMenu`（`Menus.kt`）：带图标的菜单项；挂在胶囊、按钮、标题上的下拉菜单；顶栏和列表行里的「更多」。
- `PageDialog`、`ConfirmDialog`、`NoticeDialog`、`TextInputDialog`、`DialogTextField`（`Dialogs.kt`）：带页面毛玻璃的对话框，对话框里带标题的输入框。
- `CompactButton`：卡片里、列表行里的小号文字按钮（COUI 的 `TextButton` 最小宽 132dp，放不下时用它）。
- `ZetaIcons`：COUI 2 没有的图标，照它的画法自己画（见下面「图标」）；`ZetaIcons.gauge(档位, 格数)` 是刻度表。

聊天页输入栏的底栏另有 `BarButton`、`BarMenuButton`（`ui/chat/BarButtons.kt`）：只放图标的定宽按钮，一句说明同时当读屏说明和长按提示，配色取 `barChipColors`。底栏不写字，档位、百分比都在这句说明、菜单和用量弹窗里；新按钮放进 `Composer` 的 `tools` 或 `status`，间距由 `Composer` 统一给。

## 设置页

照 Eta 的设置页：首页是一张按「管什么」分组的入口清单，说明文字放进子页。

- 首页的组和顺序：Claude（账号；新对话的默认模型、默认权限模式、默认思考强度，读写的是 Claude Code 自己的 settings.json，思考强度只显示；对话里选的只管那个对话）→ 上下文与扩展（压缩、标题、Claude Code 自身配置、定时与通知触发）→ 手机与工具 → 通用 → 权限 → 关于。新入口按管什么归进这几组，不为一两行另开一组。同一个设置项只放一处，别的页面用得到时在说明里指过去，不再放一份能改的。
- 首页每行行首一个 `SettingsIcon`，颜色只有四类：蓝 `Claude`（Claude 与对话）、绿 `System`（手机、系统与 Zeta 本身）、橙 `Files`（文件与环境）、黄 `Automation`（自动触发与规则）。行不能用时图标跟着 `enabled = false` 变灰。
- 首页的行只写当前状态：右侧值（`PreferenceValue`，出问题时红字）或一行状态摘要（账号、下次运行时间），不写这一项是干什么的；也不加组下说明，只有「关于」留一行授权声明。
- 子页的行不放图标。摘要最多一行，说这一项是什么或当前状态；要讲清楚的放组下说明（`PreferenceGroup(footer = …)`），一段两三句。
- 首页上的开关、下拉直接改；要配几项的东西做成子页（`SettingsSubPage` 加一项，在 `SettingsScreen` 里挂一个 `SlideInPage`），首页只留一个带状态的入口。

## 补上游缺口

下面两处是 COUI 2.0.0 的缺口，Zeta 在本地补着。`CouiGapsTest` 断言缺口还在：升级 COUI 后它哪条失败，说明上游已经补上，删掉对应的补丁和那条断言。

- 图标按钮不能点时不变灰，也没有按钮角色（`IconButtonContainer` 不给内容提供 `LocalContentColor`，禁用时不加 `clickable`）。补在 `IconActionButton`：自己换 tint，补上按钮角色和「已停用」。
- 菜单行给文字上色却不给图标上色（图标槽没有 `LocalContentColor`）。补在 `menuItem`：图标颜色照 `DropdownColors` 按可用、警示状态取。

## 图标

- 先用 COUI 2 自带的（`COUIIcons.*`，全部样子见 `~/workspace-external/coui/docs/components/icon.md` 或源码 `coui-icons`），按意思选，不按名字：COUI 的 `ExpandMore` 是全屏的四个角，向下的折角用 `ZetaIcons.ChevronDown`。不用 Material 图标，依赖里已经没有它。
- COUI 没有、或者它的是实心而旁边都是线条的，在 `ui/components/coui2/ZetaIcons.kt` 里画，规格写在那个文件开头。画完看 `tools/shots Icons` 出的 `zeta-icons` 总览图，和开头几个 COUI 图标比粗细、大小；图形出界由同一个测试拦下。

## 常见的选法

| 要做的 | 用 |
| --- | --- |
| 设置页 | `CouiPage(title, onBack, dialogs = { 对话框 }) { item { PreferenceGroup(title, footer) { item { 行 } } } }`，首页和子页的分别见上面「设置页」 |
| 点了进别的页 | `ArrowPreference` |
| 点了执行操作、开对话框 | `BasicComponent(onClick = …)`（不带箭头），右侧值 `endActions = { PreferenceValue(…) }` |
| 开关、勾选、单选、下拉选一个、滑杆 | `SwitchPreference`（加载中 `SwitchLoadingPreference`）、`CheckboxPreference`、`RadioButtonPreference`、`OverlayDropdownPreference`、`SliderPreference` |
| 列表行带开关、点行进编辑页 | `SwitchWithDividerPreference` |
| 分组标题、一行小字、页面底部说明 | `PreferenceCategory`、`SmallTitle`、`PagerFooterPreference` |
| 图标按钮、顶栏的文字按钮 | `IconActionButton`（平的小圆钮 `FlatIconButton`，复制键 `CopyIconButton`）、`ToolbarTextButton` |
| 按钮 | `TextButton(text, onClick)`；主操作 `colors = ButtonDefaults.textButtonColorsPrimary()`；放不下 132dp 宽时 `CompactButton` |
| 输入框 | 卡片里带标题的 `InputView`，对话框里带标题的 `DialogTextField`，其他 `TextField` |
| 确认、告知、单行输入 | `ConfirmDialog`（危险操作 `destructive = true`，COUI 画成红字；要区分「拒绝」和「随手关掉」给 `onCancel`）、`NoticeDialog`、`TextInputDialog` |
| 自定义对话框 | `PageDialog(customContent = { 内容，左右留 DialogContentPadding }) { DialogButtonBar(…) }`，主操作 `DialogButtonBarAction(…, recommended = true)` |
| 「更多」菜单 | 顶栏 `TopBarMoreMenu(entries)`，列表行 `RowMoreMenu(说明, entries)` |
| 长按菜单 | `OverlayContextMenu` |
| 菜单项、挂在别的元素上的菜单 | `menuItem(文字, 图标, onClick)`（图标颜色跟着可用、警示状态，带图标的菜单项都从它建）；`Box { 元素; AnchoredMenu(show, entries, onDismiss) }`，元素和菜单同在一个 `Box` 里 |

## 坑

- Overlay 组件（对话框、菜单、弹窗）只在 COUI 2 的 `Scaffold` 之内才显示。悬浮层窗口和应用最外层（比如「放进哪个对话」）不在任何 Scaffold 里，用 `Window*` 版本，而且不给页面毛玻璃。对话框放在 `CouiPage` 的 `dialogs` 里，不放进列表项：列表项滚出屏幕时对话框跟着没了。
- 对话框的自定义内容放 `customContent`，不和按钮栏一起放进 `content`：否则只有标题的对话框多出 16dp 空白，内容也不会在面板不够高时自己滚动。
- `PreferenceGroup` 的花括号里不是 composable，`remember`、`stringResource` 这类调用要写进 `item { }`。
- 浮层（对话框、胶囊、弹窗这类白底面板）上不用卡片配色：浅色下 `cardBackground` 是纯白，深色下 `cardPressed` 是半透明白会发亮。按钮底用 `secondaryButton`，输入框底 `fillThin`，实心占位 `fillSolid`，提示字 `hintNeutral`，描边 `labelQuaternary`。
- 换主题时 COUI 就地改同一个配色对象，`remember(colorScheme)` 会拿到旧颜色；要按具体的颜色值做键。
- COUI 2 的 `TextField` 只有一个 label（占位提示和浮动标题二选一），要标题在框上方就用 `InputView` 或 `DialogTextField`；`InputView` 的多行形态固定 152dp 高。
- 截图测试画不出：平滑圆角（着色器裁剪，块会整块空白，截图里可以临时关掉 `LocalSquircleEnabled`）、毛玻璃和顶栏光晕、公式。这些上真机看。`ScheduleStore`、`NotifyRuleStore` 是进程级单例，同一个 JVM 里前面测试留下的数据会串到后面，用到的测试先清空。
