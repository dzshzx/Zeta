// Package coui 是桌面版的外观：浅色、深色两套配色，字号，和界面常用的几样组件（页面与卡片分组、列表行、开关、按钮、
// 输入框、菜单、对话框、提示条）。配色和交互照 ChatGPT 的 Windows 客户端（黑白灰，主按钮黑底白字，蓝色只给链接），
// 尺寸是鼠标用的桌面尺寸；包名、槽位名和图标沿用手机版 COUI 2 的（槽位后面括号里是 COUI 的名字），手机版的规矩不管这里。
//
// 组件只管样子，行为用 mygo 的 base（ButtonBase、SwitchBase、MenuBase、DialogBase……），文字、读屏名字照调用方给的。
package coui

import "github.com/egoist/mygo/ui"

// Colors 是一套配色（浅色或深色），名字按用途起，括号里是 COUI 的槽位名。
type Colors struct {
	Dark bool

	// Window 是聊天区这种不放卡片的页面底色（background），Page 是放卡片的页面底色（backgroundWithCard），
	// Sidebar 是侧栏底色（backgroundElevatedWithCard），Card 是卡片底色（cardBackground，深色叠在黑底上）。
	Window, Page, Sidebar, Card ui.Color
	// Selected 是侧栏里选中的那一行（cardBackground 叠在侧栏上）。
	Selected ui.Color
	// Popup 是菜单的底色（backgroundTop），Dialog 是对话框的底色（surface；手机上是毛玻璃，这里不透明）。
	Popup, Dialog ui.Color
	// Tint 是淡淡的一层（labelPrimary 的 5%）：设备胶囊。Chip 是输入栏下面那排小圆钮（container8），
	// Hover 是悬停、菜单行按下（hover），Press 是按下（press）。
	Tint, Chip, Hover, Press ui.Color
	// Button 是次按钮的底色（secondaryButton），对话框里的普通按钮也用它；DialogBlock 是对话框里命令、代码块的底色。
	Button, DialogBlock ui.Color
	// Field 是输入栏、排队条的面板（container4 叠在 background 上），Edge 是它的细边（container4）；
	// CodeHeader、CodeBody 是代码块的语言栏和正文（fillThin、container4 各叠一层），CodeEdge 是代码块的边（container8）。
	Field, Edge, CodeHeader, CodeBody, CodeEdge ui.Color
	// Bubble 是用户消息的气泡（Zeta 自己定的淡蓝灰）。
	Bubble ui.Color
	// Divider 是分隔线（divider）。
	Divider ui.Color

	// Text、Secondary、Tertiary、Quaternary 是四档文字（labelPrimary、labelSecondary、labelTertiary、labelQuaternary）：
	// 正文；副标题、说明；行尾的值、箭头、提示文字；最淡的。
	Text, Secondary, Tertiary, Quaternary ui.Color
	// Primary 是主题蓝（primary）：主按钮、开着的开关；PrimaryText 是主题色的字和图标（primaryText）；
	// OnPrimary 是蓝底上的字；Halftone 是主题色淡底（containerThemeHalftone）。
	Primary, PrimaryText, OnPrimary, Halftone ui.Color
	// Track 是关着的开关的轨道（dragHandle）。
	Track ui.Color
	// Error 是出错的红（error），Warning 是要注意的黄（Zeta 的 StatusColors），Success 是成功的绿（success）。
	Error, Warning, Success ui.Color
	// 色板：行首图标、权限模式的盾牌。
	Red, Orange, Yellow, Green, Blue, Cyan, Purple ui.Color
	// Mask 是对话框后面的遮罩（mask），Shadow 是浮起元素的阴影色。
	Mask, Shadow ui.Color
	// PopupGlass 是菜单的底色，FieldGlass 是浮在内容上的输入栏底色（都不透明）。
	PopupGlass, FieldGlass ui.Color
}

// light、dark 是照 ChatGPT 客户端的黑白灰配色：主按钮、开关、勾选是黑（深色里是白），蓝色只给链接和主题色的字。
var (
	light = &Colors{
		Window:   ui.Hex("#FFFFFF"),
		Page:     ui.Hex("#FFFFFF"),
		Sidebar:  ui.Hex("#F9F9F9"),
		Card:     ui.Hex("#FFFFFF"),
		Selected: ui.Hex("#EBEBEB"),
		Popup:    ui.Hex("#FFFFFF"),
		Dialog:   ui.Hex("#FFFFFF"),

		Tint:        argb(0x0A000000),
		Chip:        argb(0x0D000000),
		Hover:       argb(0x0D000000),
		Press:       argb(0x1A000000),
		Button:      ui.Hex("#FFFFFF"),
		DialogBlock: ui.Hex("#F4F4F4"),

		Field:      ui.Hex("#FFFFFF"),
		Edge:       argb(0x1A000000),
		CodeHeader: ui.Hex("#F9F9F9"),
		CodeBody:   ui.Hex("#F9F9F9"),
		CodeEdge:   argb(0x0F000000),
		Bubble:     ui.Hex("#F4F4F4"),
		Divider:    argb(0x1A000000),

		Text:       ui.Hex("#0D0D0D"),
		Secondary:  ui.Hex("#5D5D5D"),
		Tertiary:   ui.Hex("#8F8F8F"),
		Quaternary: ui.Hex("#CDCDCD"),

		Primary:     ui.Hex("#0D0D0D"),
		PrimaryText: ui.Hex("#0169CC"),
		OnPrimary:   ui.Hex("#FFFFFF"),
		Halftone:    argb(0x140D0D0D),
		Track:       ui.Hex("#D4D4D4"),

		Error:   ui.Hex("#E02E2A"),
		Warning: ui.Hex("#B25E00"),
		Success: ui.Hex("#00A240"),
		Red:     ui.Hex("#E02E2A"),
		Orange:  ui.Hex("#E25507"),
		Yellow:  ui.Hex("#FFC300"),
		Green:   ui.Hex("#00A240"),
		Blue:    ui.Hex("#0169CC"),
		Cyan:    ui.Hex("#0091A8"),
		Purple:  ui.Hex("#924FF7"),

		Mask:   argb(0x4D000000),
		Shadow: ui.RGBA(0, 0, 0, 0.08),

		PopupGlass: ui.Hex("#FFFFFF"),
		FieldGlass: ui.Hex("#FFFFFF"),
	}
	dark = &Colors{
		Dark:     true,
		Window:   ui.Hex("#212121"),
		Page:     ui.Hex("#212121"),
		Sidebar:  ui.Hex("#181818"),
		Card:     ui.Hex("#212121"),
		Selected: ui.Hex("#2A2A2A"),
		Popup:    ui.Hex("#353535"),
		Dialog:   ui.Hex("#2F2F2F"),

		Tint:        argb(0x0DFFFFFF),
		Chip:        argb(0x14FFFFFF),
		Hover:       argb(0x12FFFFFF),
		Press:       argb(0x1FFFFFFF),
		Button:      ui.Hex("#2F2F2F"),
		DialogBlock: ui.Hex("#171717"),

		Field:      ui.Hex("#303030"),
		Edge:       argb(0x1AFFFFFF),
		CodeHeader: ui.Hex("#171717"),
		CodeBody:   ui.Hex("#171717"),
		CodeEdge:   argb(0x14FFFFFF),
		Bubble:     ui.Hex("#303030"),
		Divider:    argb(0x1AFFFFFF),

		Text:       ui.Hex("#ECECEC"),
		Secondary:  ui.Hex("#B4B4B4"),
		Tertiary:   ui.Hex("#8F8F8F"),
		Quaternary: ui.Hex("#5D5D5D"),

		Primary:     ui.Hex("#FFFFFF"),
		PrimaryText: ui.Hex("#48AAFF"),
		OnPrimary:   ui.Hex("#0D0D0D"),
		Halftone:    argb(0x1AFFFFFF),
		Track:       ui.Hex("#4A4A4A"),

		Error:   ui.Hex("#FF6B66"),
		Warning: ui.Hex("#E5A100"),
		Success: ui.Hex("#40C977"),
		Red:     ui.Hex("#FF6B66"),
		Orange:  ui.Hex("#FF9E6C"),
		Yellow:  ui.Hex("#FFD240"),
		Green:   ui.Hex("#40C977"),
		Blue:    ui.Hex("#48AAFF"),
		Cyan:    ui.Hex("#3CC4D6"),
		Purple:  ui.Hex("#B588FF"),

		Mask:   argb(0x99000000),
		Shadow: ui.RGBA(0, 0, 0, 0.4),

		PopupGlass: ui.Hex("#353535"),
		FieldGlass: ui.Hex("#303030"),
	}
)

// For 返回浅色或深色那套。
func For(darkMode bool) *Colors {
	if darkMode {
		return dark
	}
	return light
}

// Of 返回这一帧用的那套：跟着窗口的深浅色。
func Of(c *ui.Context) *Colors { return For(c.Theme().Dark) }

// argb 是 Android、Compose 写法的颜色 0xAARRGGBB（透明度在前），COUI 的配色表照这样写，抄过来不用换位。
func argb(v uint32) ui.Color {
	return ui.Color{R: uint8(v >> 16), G: uint8(v >> 8), B: uint8(v), A: uint8(v >> 24)}
}
