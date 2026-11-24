package widget

import (
	"embed"
	"fmt"
	"math"
	"strings"
	"sync"

	"github.com/egoist/mygo/ui"
)

// icons/ 里是手机版用的图标，照搬路径数据：COUI 2 自带的（coui-icons，Apache-2.0，https://github.com/suqi8/coui）
// 和 Zeta 自己画的（shared 的 ui/components/coui2/ZetaIcons.kt，24×24、线宽 1.7、圆头圆角）。颜色跟着文字。
//
//go:embed icons/*.svg
var iconFiles embed.FS

func icon(name string) *ui.SVG {
	data, err := iconFiles.ReadFile("icons/" + name + ".svg")
	if err != nil {
		panic(err)
	}
	return ui.MustParseSVG(data)
}

// 图标。COUI 2 自带的：
var (
	IconBack      = icon("back")
	IconMore      = icon("more")
	IconCopy      = icon("copy")
	IconSearch    = icon("search")
	IconSettings  = icon("settings")
	IconAdd       = icon("add")
	IconForward   = icon("chevron-forward") // 行尾的箭头
	IconExpand    = icon("expand-more")     // 下拉
	IconOK        = icon("ok")
	IconDelete    = icon("delete")
	IconRefresh   = icon("refresh")
	IconEdit      = icon("edit")
	IconClose     = icon("close")
	IconFile      = icon("file")
	IconFolder    = icon("folder")
	IconTodo      = icon("select-all")
	IconAsk       = icon("reply-all")
	IconTimer     = icon("timer")
	IconImage     = icon("image")
	IconFavorites = icon("favorites")
	IconLink      = icon("link")
	IconPin       = icon("pin")
	IconUnfold    = icon("unfold")        // 下拉值旁边的上下箭头（照 COUI 下拉行的样子画的）
	IconContacts  = icon("contacts-book") // 设置里的账号与额度
	IconTheme     = icon("theme")         // 设置里的外观
	IconDownload  = icon("download")      // 看大图的保存
)

// Zeta 自己画的：
var (
	IconBrand       = icon("brand")
	IconArrowUp     = icon("arrow-up")
	IconArrowDown   = icon("arrow-down")
	IconChevronDown = icon("chevron-down")
	IconChevronUp   = icon("chevron-up")   // IconChevronDown 倒过来：查找栏的「上一个」、收起
	IconChevronBack = icon("chevron-back") // 行尾箭头左右翻过来：看大图的上一张
	IconStop        = icon("stop")
	IconPaperclip   = icon("paperclip")
	IconSparkle     = icon("sparkle")
	IconBolt        = icon("bolt")
	IconWrench      = icon("wrench")
	IconBranch      = icon("branch")
	IconPuzzle      = icon("puzzle")
	IconFileAdd     = icon("file-add")
	IconGlobe       = icon("globe")
	IconLightbulb   = icon("lightbulb")
	IconSearchText  = icon("search-text")
	IconNewChat     = icon("new-chat")
	IconPhone       = icon("phone")
	IconSwipe       = icon("swipe")
	IconTap         = icon("tap")
	IconTerminal    = icon("terminal")
	IconServer      = icon("server")
	IconWarning     = icon("warning")
	IconClock       = icon("clock")
	IconCheckbox    = icon("checkbox-blank")
	IconInfo        = icon("info")    // 设置里的版本
	IconArchive     = icon("archive") // 归档：侧栏底部看归档的入口（同 ZetaIcons.kt 的 Archive）
	IconSidebar     = icon("sidebar") // 收起、展开侧栏（桌面版自己的）
)

// 权限模式的盾牌：里面的字母是模式的头一个字母（同手机版 Composer 的 modeIcon）。
var (
	IconShieldDefault = icon("shield-m") // 逐项确认（manual）
	IconShieldEdits   = icon("shield-e") // 自动接受编辑
	IconShieldPlan    = icon("shield-p") // 计划模式
	IconShieldAuto    = icon("shield-a") // 自动判断
	IconShieldBypass  = icon("shield-alert")
	IconShieldCheck   = icon("shield-check")
)

// gauges 是思考强度的表盘，按 steps、level 记着，画过的不再画。
var gauges struct {
	sync.Mutex
	m map[[2]int]*ui.SVG
}

// Gauge 是思考强度的表盘：半圈 steps 格，亮 level 格，指针指着最后亮的那格（同 ZetaIcons.gauge）；level 为 0 时指针朝上、格子全暗。
func Gauge(level, steps int) *ui.SVG {
	key := [2]int{level, steps}
	gauges.Lock()
	defer gauges.Unlock()
	if g, ok := gauges.m[key]; ok {
		return g
	}
	const cx, cy, r = 12.0, 16.2, 8.6
	polar := func(radius, deg float64) (float64, float64) {
		rad := deg * math.Pi / 180
		return cx + radius*math.Cos(rad), cy + radius*math.Sin(rad)
	}
	step := 180.0 / float64(steps)
	gap := 4.5 * 5 / float64(steps)
	var b strings.Builder
	b.WriteString(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">`)
	for i := range steps {
		x0, y0 := polar(r, 180+float64(i)*step+gap)
		x1, y1 := polar(r, 180+float64(i+1)*step-gap)
		opacity := 0.4
		if i < level {
			opacity = 1
		}
		fmt.Fprintf(&b, `<path d="M%.3f %.3fA%.1f %.1f 0 0 1 %.3f %.3f" stroke-opacity="%.1f"/>`, x0, y0, r, r, x1, y1, opacity)
	}
	angle := 270.0
	if level > 0 {
		angle = 180 + (float64(min(level, steps))-0.5)*step
	}
	nx, ny := polar(5, angle)
	fmt.Fprintf(&b, `<path d="M%.1f %.1fL%.3f %.3f"/>`, cx, cy, nx, ny)
	fmt.Fprintf(&b, `<circle cx="%.1f" cy="%.1f" r="1.7" fill="currentColor" stroke="none"/></svg>`, cx, cy)
	g := ui.MustParseSVG([]byte(b.String()))
	if gauges.m == nil {
		gauges.m = map[[2]int]*ui.SVG{}
	}
	gauges.m[key] = g
	return g
}
