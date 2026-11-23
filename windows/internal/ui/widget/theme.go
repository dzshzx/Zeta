package widget

import "github.com/egoist/mygo/ui"

// 字号（COUI 的 TextStyles，sp 照搬成 DIP）。字重：Medium 是 500。
const (
	SizeHeadlineM  = 20 // 侧栏的 Zeta
	SizeHeadlineS  = 18 // 对话框标题、小顶栏标题
	SizeHeadlineXS = 16 // 列表行标题、对话标题
	SizeBodyXL     = 16 // 输入栏、气泡、回答
	SizeBodyL      = 15 // 会话行标题、菜单行
	SizeBodyM      = 14 // 行尾的值、按钮
	SizeBodyS      = 13 // 副标题、说明
	SizeBodyXS     = 12 // 分组标题、小字
	Medium         = 500
)

// Mono 是等宽字：Windows 上的 Cascadia Mono、Consolas，别处是系统的等宽字。
const Mono = "Cascadia Mono, Consolas, monospace"

// Theme 返回 COUI 配色的 mygo 主题：mygo 自带的控件（滚动条、输入框、提示、选中的文字）和没换样子的地方跟着它。
// 字用系统的界面字体（Windows 上中文是微软雅黑），正文 14。
func Theme(darkMode bool) *ui.Theme {
	k := For(darkMode)
	t := ui.LightTheme()
	if darkMode {
		t = ui.DarkTheme()
	}
	t.Background = k.Window
	t.Surface = k.Field
	t.SurfaceHover = k.Hover
	t.SurfacePressed = k.Press
	t.Border = k.Divider
	t.Text = k.Text
	t.TextMuted = k.Secondary
	t.Accent = k.Primary
	t.AccentHover = k.Primary.Mix(k.OnPrimary, 0.12)
	t.AccentPressed = k.Primary.Mix(ui.RGB(0, 0, 0), 0.12)
	t.AccentText = k.OnPrimary
	t.InactiveAccent = k.Primary
	t.Danger = k.Error
	t.Warning = k.Warning
	t.Success = k.Success
	t.Selection = k.PrimaryText.Alpha(0.25)
	t.Focus = k.Primary.Alpha(0.55)
	t.Scrollbar = k.Tertiary
	t.Radius = 10
	t.FontSize = SizeBodyM
	return t
}
