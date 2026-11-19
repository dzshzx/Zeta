# 迁到 COUI 2

界面从 COUI-Kit（`io.github.itoseo:coui`，包 `com.itos.coui`）加 Miuix（`top.yukonga.miuix.kmp`）换成 COUI 2（`io.github.suqi8.coui.kmp`，ColorOS 17 风格，Miuix 的分支）。迁完删掉两个旧依赖、`ui/components/coui` 下的旧封装和这份文件。

## 怎么写

- 写法照 COUI 2 自带的指南：工作站 `~/workspace-external/coui/.claude/skills/coui-app/SKILL.md`，以及同目录 `references/` 下的 `components.md`（ColorOS 控件对应哪个组件）、`pitfalls.md`（踩过的坑）、`migration.md`（Miuix 的配色、文字样式改名）。组件的参数以源码为准：`~/workspace-external/coui/coui-ui/src/commonMain`、`coui-preference/src/commonMain`；组件的样子看 `docs/components/<名字>.md`。
- 指南里这几条 Zeta 不照做：字符串照旧直接写中文，不用 Compose resources、不做双语；页面切换照旧由 Zeta 自己管（`ui/OverlayPages.kt`），不用 coui-nav；页面骨架不再复制，用 `ui/components/coui2`。
- Zeta 自己只补 COUI 2 没有的，都在 `ui/components/coui2`：
  - `CouiPage`、`CouiPageScaffold`：页面骨架，带顶栏光晕和毛玻璃，处理好宽屏和键盘。
  - `PreferenceGroup`、`PreferenceValue`：设置分组、设置行右侧的值。
  - `PageDialog`、`ConfirmDialog`、`NoticeDialog`、`TextInputDialog`：带页面毛玻璃的对话框。
  - `CouiBlur.kt`：毛玻璃的接线。

  要加新的共用件先问负责迁移的会话，不各自在页面文件里另写一份。
- 一个页面迁完，文件里不再导入 `com.itos.coui`、`top.yukonga.miuix`、`com.dzshzx.zeta.ui.components.coui`。截图测试（`tools/shots`）前后各跑一次，看对照图；没有截图测试的页面先补一张。

## 对照表

### 页面骨架

| 旧 | COUI 2 |
| --- | --- |
| `CouiScaffoldPage(title, onBack) { Column 内容 }` | `CouiPage(title, onBack = onBack, overlays = { 对话框 }) { item(key = …) { … } }`。内容短的页面整页放进一个 `item` 也行 |
| `CouiLazyScaffoldPage` | `CouiPage`，列表项直接写进去 |
| `CouiScaffold`（自定义容器）、COUI-Kit 的 `Scaffold`、`TopAppBar`、`LargeTopAppBar` | `CouiPageScaffold { scope -> … }`。列表用 `Modifier.couiPageScroll(scope)` 和 `contentPadding = scope.contentPadding` |
| `compactTitle = true` | `topBarStyle = CouiTopBarStyle.Small` |
| `overlay = { … }` | `overlays = { … }`（`CouiPageScaffold` 里放在内容里、和列表并排） |
| `floatingAction` | `floatingActionButton = { FloatingActionButton(onClick) { Icon(…) } }` |
| `CouiBackButton` | 骨架的 `onBack` |
| `CouiTopAppBarIconButton(icon, desc, onClick)` | `actions` 里 `IconButton(onClick) { Icon(icon, desc) }` |
| `CouiTopAppBarTextButton` | `actions` 里 `ToolbarTextButton`（COUI 2 顶栏的文字按钮，如「保存」「清除已结束」） |
| `CouiPageBottomSpacer`、`rememberCouiSystemToolbarStyle`、`CouiPageDefaults`、`CouiPageColors` | 删掉，骨架自带 |
| `couiVerticalScroll`、`rememberCouiOverscrollEffect`、`CouiOverscrollEffect` | `Modifier.verticalScroll(state).overScrollVertical()`，页面列表用 `couiPageScroll` |

### 设置分组和设置行

每一行写在 `PreferenceGroup { item { … } }` 里。`PreferenceGroup` 的花括号里不是 composable，`remember`、`stringResource` 这类调用要写进 `item { }` 里面。分隔线、行在卡片里的上下位置由 `CardListGroup` 管，不画 `HorizontalDivider`，不设 `cardListPosition`。行首图标放 `startAction`，`Modifier.size(24.dp)`，不加内边距。

| 旧 | COUI 2 |
| --- | --- |
| `Group(title, footer) { 行 }` | `PreferenceGroup(title = …, footer = …) { item { 行 } }` |
| `CouiGroupItem`、`Item`、`Divider`、`CouiIconDivider` | 删掉 |
| `CouiPreference`，点了进别的页 | `ArrowPreference(title, summary, startAction, endActions = { PreferenceValue(value) }, onClick)` |
| `CouiPreference`，点了执行操作或开对话框 | `BasicComponent(title, summary, startAction, endActions = { PreferenceValue(value) }, onClick)`（不带箭头） |
| `CouiPreference`，只显示 | `BasicComponent`，不给 `onClick` |
| `CouiPreferenceRow`（自定义右侧） | `BasicComponent(endActions = { … })` |
| `CouiSwitchPreference` | `SwitchPreference`；`loading = true` 的用 `SwitchLoadingPreference(isLoading = …)` |
| `CouiCheckboxPreference` | `CheckboxPreference` |
| `CouiSelectPreference`（单选） | `RadioButtonPreference(title, selected, onClick)` |
| `CouiDropdownPreference` | `OverlayDropdownPreference(items, selectedIndex, title, onSelectedIndexChange = …)` |
| `CouiPopupPreference`（带 `CouiMenuItem` 列表） | `OverlayDropdownMenu(entries = listOf(DropdownEntry(items = …)), title)`，每项 `DropdownItem(text, selected, onClick, icon, summary)` |
| `CouiSliderPreference` | `SliderPreference`：有 `steps` 自带刻度点，去掉 `showTickMarks` |
| `CouiSectionTitle(text, trailing)` | `PreferenceCategory(title, endWidget = trailing)`；不是分组标题、只是一行小字的用 `SmallTitle` |
| `CouiHint(text, error)` | `PagerFooterPreference(summary = text)`，错误时加 `summaryColor = COUITheme.colorScheme.error` |
| `CouiSettingsIcon`、`CouiIcon` | `Icon(imageVector, null, Modifier.size(24.dp), tint = COUITheme.colorScheme.primary)` |

### 控件

| 旧 | COUI 2 |
| --- | --- |
| `CouiButton(role = Primary)` | `TextButton(text, onClick, colors = ButtonDefaults.textButtonColorsPrimary())` |
| `CouiButton(role = Secondary)` | `TextButton(text, onClick)` |
| `CouiButton(role = Destructive)` | `TextButton(text, onClick, colors = ButtonDefaults.textButtonColors(textColor = COUITheme.colorScheme.error))` |
| `CouiButton(role = DestructiveFilled)` | `TextButton(text, onClick, colors = ButtonDefaults.textButtonColorsPrimary(color = COUITheme.colorScheme.error))` |
| `CouiTextField`，上面带标题、放在卡片里 | `InputView(value, onValueChange, title = 标题, label = 占位提示, errorMessage, showCount, maxCount)` |
| `CouiTextField`，其他 | `TextField(value, onValueChange, label = 占位提示, backgroundMode = TextFieldMode.Rectangle 或默认的 Line)` |
| `CouiSearchBarBox`、COUI-Kit `SearchBar` | 顶栏下的搜索用 `TopAppBarSearchBar` + `rememberTopAppBarSearchState`（指南核心规则 8）；其余用 `SearchBar` + `InputField` |
| `CouiChipTabs` | `ChipTabRow(tabs, selectedTabIndex, onTabSelected)` |
| `CouiSegmentedTabs`、`SegmentedControl` | `SegmentButton(items, selectedIndex, onSelectedIndexChange)` |
| COUI-Kit `Switch`、`Checkbox`、`RadioButton`、`Slider`、`Chip` | COUI 2 同名组件（`io.github.suqi8.coui.kmp.basic`） |
| `WheelPicker`、`CalendarPicker` | `NumberPicker` / `DatePicker` / `TimePicker`、`CalendarPicker` |
| Miuix `InfiniteProgressIndicator`、`LinearProgressIndicator`、`IconButton` | COUI 2 同名组件 |

### 对话框和菜单

对话框都放在 `CouiPage(overlays = { … })` 里，或 `CouiPageScaffold` 内容里和列表并排，不放进列表项。Overlay 组件必须在 COUI 2 的 `Scaffold` 之内才显示；不在任何 Scaffold 里的地方（悬浮层窗口）用 `Window*` 版本，而且不给它页面毛玻璃。

| 旧 | COUI 2 |
| --- | --- |
| `CouiConfirmDialog(visible, title, message, confirmText, onConfirm, onDismissRequest, destructive)` | `ConfirmDialog(show, title, summary, confirmText, onConfirm, onDismissRequest, destructive = …)` |
| `CouiNoticeDialog` | `NoticeDialog` |
| `CouiInputDialog`、COUI-Kit `InputDialog` | `TextInputDialog`（`placeholder` → `label`，字数计数用 `maxCount`） |
| 其他自定义对话框 | `PageDialog(customContent = { 自己的内容，左右留 DialogContentPadding }) { DialogButtonBar(negative, positive, neutral) }`。自己的内容不放进按钮栏那一格：放在 `customContent`，只有标题时不多空白，面板不够高时它自己滚动 |
| `neutralAction` | COUI 2 的 `neutral` 一样画成红色的危险按钮，只给危险操作；主操作 `DialogButtonBarAction(…, recommended = true)` |
| 底部面板 | `OverlayBottomSheet`，毛玻璃照 `PageDialog` 的写法用 `panelBlur(MaterialBlurDefaults::bottomSheet)` |
| `Menu` + `couiMenuAnchor`，挂在图标按钮上 | `OverlayIconDropdownMenu(entries = listOf(DropdownEntry(items = listOf(DropdownItem(text, onClick = …, icon = …))))) { Icon(…) }`，它自己就是那个图标按钮 |
| `Menu`，长按弹出 | `OverlayContextMenu(entries) { 被长按的内容 }` |
| `Menu`，挂在别的元素上 | `Box { 元素; OverlayListPopup(show, onDismissRequest) { ListPopupColumn { … } } }`，按钮和弹窗同在一个 `Box` 里 |
| `CouiMenuItem`、`CouiMenuStyle`、`CouiMenuDefaults`、`ProvideCouiMenuBackdrop`、`rememberCouiWindowBackdrop` | 删掉，菜单毛玻璃由骨架提供 |

### 配色、文字、图标、效果

| 旧 | COUI 2 |
| --- | --- |
| `MiuixTheme.colorScheme.onSurface`、`CouiTheme.colorScheme.onSurface` | `COUITheme.colorScheme.labelPrimary` |
| `MiuixTheme.colorScheme.onSurfaceVariantSummary`、`onSurfaceVariant` | `labelSecondary`（设置行摘要的颜色）；更淡的值文字用 `labelTertiary` |
| `MiuixTheme.colorScheme.primary`、`CouiTheme.colorScheme.accent` | `primary` |
| `MiuixTheme.colorScheme.error` | `error` |
| `surfaceContainer`、`surfaceContainerHigh` | 页面上的卡片用 `cardBackground`、`cardPressed`。浮层（对话框、胶囊、弹窗这类白底面板）上不能用它们：浅色下 `cardBackground` 是纯白，深色下 `cardPressed` 是半透明白会发亮。浮层上按用途选：按钮底 `secondaryButton`，输入框底 `fillThin`，实心占位底 `fillSolid`，提示字 `hintNeutral`，描边 `labelQuaternary` |
| 页面底色（`surface`、`background`） | 有卡片的页面 `backgroundWithCard`（骨架默认），整页内容（聊天）`background`；浮层面板 `surface` |
| 其他配色名 | 查 COUI 指南 `references/migration.md` 的配色表 |
| `CouiTheme.isDark` | `COUITheme.colorScheme.isDark` |
| `MiuixTheme.textStyles.body1` / `body2` / `footnote1` / `footnote2` | `COUITheme.textStyles.bodyXL` / `bodyM` / `bodyS` / `bodyXS`（其余见 COUI 指南的文字样式表） |
| `CouiTextKind.ItemTitle` / `ItemSummary` / `ItemValue` | `bodyXL` / `bodyS` / `bodyM`；能用设置行组件的就用组件，不自己排 |
| Miuix `Text`、`Icon`，COUI-Kit `Text` | `io.github.suqi8.coui.kmp.basic.Text`、`Icon` |
| `CouiIcons.Outlined.Add` / `More` / `Back` / `Next` | `COUIIcons.Add`、`More`、`Back`（`io.github.suqi8.coui.kmp.icon.extended.*`）；没有的照旧用 material-icons |
| `CouiShape`、`CouiCapsuleShape`、Miuix `squircle*` | `io.github.suqi8.coui.kmp.squircle` 的 `SquircleShape`、`Modifier.squircleBackground` / `squircleClip` / `squircleBorder`；胶囊用 `CircleShape` |
| `couiPressMotion`、`couiTouchMotion`、`couiButtonFeedback` | COUI 2 组件自带按压反馈；自己画的可点区域用 `Modifier.pressable(interactionSource, indication)` |
| `CouiContour`、`LocalCouiContourGlowEnabled`、`rememberCouiSpotlight`、`CouiFloatingRenderer` | 删掉，材质和光晕由 COUI 2 组件和骨架画 |
| `performCouiHapticFeedback(CouiHapticFeedback.X)` | `val haptic = rememberCouiHaptic(); haptic(CouiHapticEffect.X)` |
