# 界面写法

界面用 COUI 2（`io.github.suqi8.coui.kmp`，ColorOS 17 风格，Miuix 的分支）。

## 照什么写

- 写法照 COUI 2 自带的指南：工作站 `~/workspace-external/coui/.claude/skills/coui-app/SKILL.md`，以及同目录 `references/` 下的 `components.md`（ColorOS 控件对应哪个组件）、`pitfalls.md`（踩过的坑）。组件参数以源码为准：`~/workspace-external/coui/coui-ui/src/commonMain`、`coui-preference/src/commonMain`；组件的样子看 `docs/components/<名字>.md`。对照前先在那里 `git pull`。
- 指南里这几条 Zeta 不照做：字符串直接写中文，不用 Compose resources、不做双语；页面切换由 Zeta 自己管（`ui/OverlayPages.kt`），不用 coui-nav；页面骨架用 `ui/components/coui2` 的，不再复制。

## Zeta 的补充件

COUI 2 没有的才在 `ui/components/coui2` 补，页面文件里不各写一份；要加新的共用件就加在这里。

- `CouiPage`（列表页）、`CouiPageScaffold`（内容随意）：页面骨架。顶栏带 COUI 17 光晕，页面录进背景图给弹出的东西做毛玻璃，宽屏时内容居中、顶栏变小，键盘弹出时让位。
- `CouiBlur.kt`：毛玻璃接线。`LocalCouiBlurEnabled`（截图测试里关掉）、`LocalPageBackdrop`、`panelBlur(...)`。
- `PreferenceGroup`、`PreferenceValue`：设置页的一组（分类标题、卡片、卡片下的说明），设置行右侧的值。
- `CardListSegment`：几百行的长列表逐行放进 LazyColumn 时，仍画成一整张卡片；行首有图标时分隔线从 `RowDividerStartWithIcon` 开始。
- `DropdownValuePreference`：右边显示这一行自己的值，点开弹出操作菜单。
- `TopBarIconButton`：顶栏右边的图标按钮，不能点时图标变灰（COUI 2 的 `IconButton` 不会）。
- `PageDialog`、`ConfirmDialog`、`NoticeDialog`、`TextInputDialog`：带页面毛玻璃的对话框。

## 常见的选法

| 要做的 | 用 |
| --- | --- |
| 设置页 | `CouiPage(title, onBack, overlays = { 对话框 }) { item { PreferenceGroup(title, footer) { item { 行 } } } }` |
| 点了进别的页 | `ArrowPreference` |
| 点了执行操作、开对话框 | `BasicComponent(onClick = …)`（不带箭头），右侧值 `endActions = { PreferenceValue(…) }` |
| 开关、勾选、单选、下拉选一个、滑杆 | `SwitchPreference`（加载中 `SwitchLoadingPreference`）、`CheckboxPreference`、`RadioButtonPreference`、`OverlayDropdownPreference`、`SliderPreference` |
| 列表行带开关、点行进编辑页 | `SwitchWithDividerPreference` |
| 分组标题、一行小字、页面底部说明 | `PreferenceCategory`、`SmallTitle`、`PagerFooterPreference` |
| 顶栏的图标、文字按钮 | `TopBarIconButton`、`ToolbarTextButton` |
| 按钮 | `TextButton(text, onClick)`；主操作 `colors = ButtonDefaults.textButtonColorsPrimary()` |
| 输入框 | 卡片里带标题的 `InputView`，其他 `TextField` |
| 确认、告知、单行输入 | `ConfirmDialog`（危险操作 `destructive = true`，COUI 画成红字；要区分「拒绝」和「随手关掉」给 `onCancel`）、`NoticeDialog`、`TextInputDialog` |
| 自定义对话框 | `PageDialog(customContent = { 内容，左右留 DialogContentPadding }) { DialogButtonBar(…) }`，主操作 `DialogButtonBarAction(…, recommended = true)` |
| 图标按钮弹菜单 | `OverlayIconDropdownMenu`；列表行里的「更多」用 `IconButton(material = false)` 加 `OverlayDropdownPopup`（材质按钮的阴影在列表里会被裁掉） |
| 长按菜单 | `OverlayContextMenu` |
| 挂在别的元素上的菜单 | `Box { 元素; OverlayDropdownPopup(…) }`，元素和菜单同在一个 `Box` 里 |

## 坑

- Overlay 组件（对话框、菜单、弹窗）只在 COUI 2 的 `Scaffold` 之内才显示。悬浮层窗口和应用最外层（比如「放进哪个对话」）不在任何 Scaffold 里，用 `Window*` 版本，而且不给页面毛玻璃。对话框放在 `CouiPage` 的 `overlays` 里，不放进列表项：列表项滚出屏幕时对话框跟着没了。
- 对话框的自定义内容放 `customContent`，不和按钮栏一起放进 `content`：否则只有标题的对话框多出 16dp 空白，内容也不会在面板不够高时自己滚动。
- `PreferenceGroup` 的花括号里不是 composable，`remember`、`stringResource` 这类调用要写进 `item { }`。
- 浮层（对话框、胶囊、弹窗这类白底面板）上不用卡片配色：浅色下 `cardBackground` 是纯白，深色下 `cardPressed` 是半透明白会发亮。按钮底用 `secondaryButton`，输入框底 `fillThin`，实心占位 `fillSolid`，提示字 `hintNeutral`，描边 `labelQuaternary`。
- 换主题时 COUI 就地改同一个配色对象，`remember(colorScheme)` 会拿到旧颜色；要按具体的颜色值做键。
- COUI 2 的 `TextField` 只有一个 label（占位提示和浮动标题二选一），要标题在框上方就用 `InputView` 或自己加一行小字；`InputView` 的多行形态固定 152dp 高。`TextButton` 最小宽 132dp，两个并排放不下时自己给 `minWidth`。
- 截图测试画不出：平滑圆角（着色器裁剪，块会整块空白，截图里可以临时关掉 `LocalSquircleEnabled`）、毛玻璃和顶栏光晕、公式。这些上真机看。`ScheduleStore`、`NotifyRuleStore` 是进程级单例，同一个 JVM 里前面测试留下的数据会串到后面，用到的测试先清空。
