// Package coui 是桌面版的 COUI 2（ColorOS 17）外观：浅色、深色两套配色，字号，和界面常用的几样组件
// （页面与卡片分组、列表行、开关、按钮、输入框、菜单、对话框、提示条）。数值照手机版：
// COUI 2 库（io.github.suqi8.coui.kmp，https://github.com/suqi8/coui）的 lightColorScheme、darkColorScheme、
// TextStyles 和各组件的默认值，Zeta 在 shared 的 ui/components/coui2、ui/chat、ui/pane 里补的，各处注释写着出处。
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
	// 毛玻璃上叠的那层（手机上是 COUI 17 的材质模糊，MaterialBlur.kt，这里是高斯模糊加半透明底色，
	// 落在白底、黑底上时等于不开毛玻璃时的不透明底色）：菜单、输入栏。
	PopupGlass, FieldGlass ui.Color
}

// light、dark 是 COUI 2 的两套默认配色，没有改过任何槽位。
var (
	light = &Colors{
		Window:   ui.Hex("#FFFFFF"),
		Page:     ui.Hex("#F0F1F2"),
		Sidebar:  ui.Hex("#F0F1F2"),
		Card:     ui.Hex("#FFFFFF"),
		Selected: ui.Hex("#FFFFFF"),
		Popup:    ui.Hex("#FFFFFF"),
		Dialog:   ui.Hex("#FFFFFF"),

		Tint:        argb(0x0D000000),
		Chip:        argb(0x14000000),
		Hover:       argb(0x14000000),
		Press:       argb(0x1F000000),
		Button:      ui.Hex("#EBEBEB"),
		DialogBlock: ui.Hex("#E7E7E7"),

		Field:      ui.Hex("#F5F5F5"),
		Edge:       argb(0x0A000000),
		CodeHeader: ui.Hex("#ECECEC"),
		CodeBody:   ui.Hex("#F5F5F5"),
		CodeEdge:   argb(0x14000000),
		Bubble:     ui.Hex("#EEF2FA"),
		Divider:    argb(0x1F000000),

		Text:       argb(0xE6000000),
		Secondary:  argb(0x8A000000),
		Tertiary:   argb(0x42000000),
		Quaternary: argb(0x14000000),

		Primary:     ui.Hex("#0080FF"),
		PrimaryText: ui.Hex("#0080FF"),
		OnPrimary:   ui.Hex("#FFFFFF"),
		Halftone:    argb(0x260080FF),
		Track:       argb(0x29000000),

		Error:   ui.Hex("#DB382C"),
		Warning: ui.Hex("#A36200"),
		Success: ui.Hex("#00BD13"),
		Red:     ui.Hex("#EB3B2F"),
		Orange:  ui.Hex("#FF7700"),
		Yellow:  ui.Hex("#FFB200"),
		Green:   ui.Hex("#00BD13"),
		Blue:    ui.Hex("#0080FF"),
		Cyan:    ui.Hex("#00A3B8"),
		Purple:  ui.Hex("#8D5BE8"),

		Mask:   argb(0x33000000),
		Shadow: ui.RGBA(0, 0, 0, 30.0/255),

		PopupGlass: ui.RGBA(246, 246, 246, 0.86),
		FieldGlass: ui.RGBA(245, 245, 245, 0.9),
	}
	dark = &Colors{
		Dark:     true,
		Window:   ui.Hex("#000000"),
		Page:     ui.Hex("#000000"),
		Sidebar:  ui.Hex("#1E1E1E"),
		Card:     ui.Hex("#1A1A1A"),
		Selected: ui.Hex("#353535"),
		Popup:    ui.Hex("#333333"),
		Dialog:   ui.Hex("#262626"),

		Tint:        argb(0x0DFFFFFF),
		Chip:        argb(0x1AFFFFFF),
		Hover:       argb(0x26FFFFFF),
		Press:       argb(0x33FFFFFF),
		Button:      ui.Hex("#353535"),
		DialogBlock: ui.Hex("#3B3B3B"),

		Field:      ui.Hex("#1A1A1A"),
		Edge:       argb(0x1AFFFFFF),
		CodeHeader: ui.Hex("#2B2B2B"),
		CodeBody:   ui.Hex("#1A1A1A"),
		CodeEdge:   argb(0x1AFFFFFF),
		Bubble:     ui.Hex("#202634"),
		Divider:    argb(0x33FFFFFF),

		Text:       argb(0xE6FFFFFF),
		Secondary:  argb(0x8AFFFFFF),
		Tertiary:   argb(0x4DFFFFFF),
		Quaternary: argb(0x26FFFFFF),

		Primary:     ui.Hex("#1A8CFF"),
		PrimaryText: ui.Hex("#5C9DFF"),
		OnPrimary:   ui.Hex("#FFFFFF"),
		Halftone:    argb(0x401A8CFF),
		Track:       argb(0x40FFFFFF),

		Error:   ui.Hex("#FF6C61"),
		Warning: ui.Hex("#E5A100"),
		Success: ui.Hex("#24B232"),
		Red:     ui.Hex("#EB493D"),
		Orange:  ui.Hex("#F08222"),
		Yellow:  ui.Hex("#E5A100"),
		Green:   ui.Hex("#24B232"),
		Blue:    ui.Hex("#1A8CFF"),
		Cyan:    ui.Hex("#26C0D3"),
		Purple:  ui.Hex("#A57BF5"),

		Mask:   argb(0x99000000),
		Shadow: ui.RGBA(0, 0, 0, 0.5),

		PopupGlass: ui.RGBA(51, 51, 51, 0.88),
		FieldGlass: ui.RGBA(26, 26, 26, 0.9),
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
