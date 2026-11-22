package chatview

import (
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo/ui"
)

// 全屏看大图（同手机版 ImageViewer.kt、ImageGallery.kt）：点聊天里的图时列表发出 OpenImages，调用方建一个 Viewer、
// 每帧画在窗口最上层。黑底铺满窗口，不管外面是浅色深色都用 COUI 的深色配色。顶上是返回和「第几张 / 共几张」，
// 多张时两边是上一张、下一张（也可以按左右键），底下是页码圆点和保存。Esc 关上。保存交给调用方给的 Save。

// Viewer 是一次看大图：看的是哪一组图、第几张。图从发出 OpenImages 的那个列表的缓存里取，不再取一遍。
type Viewer struct {
	// Save 存一张图（data 是原始数据，name 是建议的文件名），返回存到哪，取消了是空的。在后台 goroutine 上调；
	// 没给时保存是灰的。
	Save func(data []byte, name string) (saved string, err error)

	from *Chat
	// images 是这一组图，关上后为空。
	images []items.Image
	index  int
	// saving 是正在保存的那次（取图、等用户选位置、写文件），done 了之后 note 是结果。
	saving *saving
}

// NewViewer 从 list 的第 index 张看起；图是列表 from 里的（OpenImages 发出来的那组）。
func NewViewer(from *Chat, list []items.Image, index int) *Viewer {
	return &Viewer{from: from, images: slices.Clone(list), index: index}
}

// saving 是一次保存：在后台 goroutine 上做完，结果给界面线程读。
type saving struct {
	mu   sync.Mutex
	done bool
	note string
}

func (s *saving) result() (bool, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.done, s.note
}

// close 关上看大图。
func (v *Viewer) close() { v.images, v.saving = nil, nil }

// step 翻到上一张（-1）或下一张（1），到头了不动。
func (v *Viewer) step(d int) {
	if n := v.index + d; n >= 0 && n < len(v.images) {
		v.index, v.saving = n, nil
	}
}

// bitmap 是一张图在来源列表缓存里的载入结果。
func (v *Viewer) bitmap(c *ui.Context, im items.Image) (*ui.Bitmap, bool, error) {
	return v.from.images.get(c, im, v.from.LoadImage)
}

// 看大图页上下留给两条栏的高度，左右留给翻页键的宽度。
const (
	viewerBottom = 128
	viewerSide   = 76
)

// View 把看大图画在窗口最上层；返回还开着没有（关上了调用方丢掉它）。
func (v *Viewer) View(c *ui.Context) bool {
	if len(v.images) == 0 {
		return false
	}
	v.index = min(max(v.index, 0), len(v.images)-1)
	ui.Overlay(c, func() {
		page := ui.Themed(c, coui.Theme(true)).Absolute().Left(0).Top(0).Right(0).Bottom(0).Background(coui.For(true).Window).
			Modal().Role(ui.RoleDialog).Label("看大图")
		page.Children(func() {
			v.image(c, v.images[v.index])
			v.top(c)
			if len(v.images) > 1 {
				pageButton(c, coui.IconChevronBack, "上一张", v.index > 0, true).OnClick(func() { v.step(-1) })
				pageButton(c, coui.IconForward, "下一张", v.index < len(v.images)-1, false).OnClick(func() { v.step(1) })
			}
			v.bottom(c)
		})
		switch {
		case page.OverlayShortcut(0, ui.KeyEscape):
			v.close()
		case page.OverlayShortcut(0, ui.KeyLeft):
			v.step(-1)
		case page.OverlayShortcut(0, ui.KeyRight):
			v.step(1)
		}
	})
	return len(v.images) > 0
}

// image 是中间的图：按原比例放进栏和翻页键之间的地方，小图最多放大到两倍；还在取时转圈，取不到时说一声。
func (v *Viewer) image(c *ui.Context, im items.Image) {
	k := coui.Of(c)
	w, h := c.Size()
	room := ui.Rect{W: w - 2*viewerSide, H: h - coui.TitleBarHeight - viewerBottom}
	box := ui.Column(c.Key("image/" + im.CacheKey())).Absolute().Left(viewerSide).Right(viewerSide).Top(coui.TitleBarHeight).
		Bottom(viewerBottom).Center()
	bmp, done, err := v.bitmap(c, im)
	box.Children(func() {
		switch {
		case bmp != nil:
			iw, ih := bmp.Size()
			scale := min(room.W/float32(iw), room.H/float32(ih), 2)
			ui.Image(c, bmp).Size(float32(iw)*scale, float32(ih)*scale).Fit(ui.Contain).Label("图片")
		case done:
			ui.Column(c).Gap(10).AlignItems(ui.Center).Tooltip(imageError(err)).Children(func() {
				ui.Icon(c, iconImage).Size(40, 40).TextColor(k.Secondary)
				ui.Text(c, "图片无法显示").FontSize(coui.SizeBodyM).TextColor(k.Secondary)
			})
		default:
			ui.Spinner(c).Size(24, 24).Label("正在载入图片")
		}
	})
}

// top 是顶栏（同时是标题栏，空白处拖动窗口）：左边返回，多张时按整个窗口居中写「第几张 / 共几张」。
func (v *Viewer) top(c *ui.Context) {
	k := coui.Of(c)
	ui.Row(c).Absolute().Left(0).Right(0).Top(0).Height(coui.TitleBarHeight).Justify(ui.Center).AlignItems(ui.Center).
		DragWindow().Children(func() {
		if len(v.images) > 1 {
			ui.Text(c, strconv.Itoa(v.index+1)+" / "+strconv.Itoa(len(v.images))).FontSize(coui.SizeHeadlineS).FontWeight(600).
				TextColor(k.Text)
		}
	})
	coui.CircleButton(c, coui.IconBack, "返回").Absolute().Left(16).Top((coui.TitleBarHeight - 40) / 2).
		OnClick(v.close)
}

// pageButton 是两边竖直居中的翻页键（浮起的圆钮）；到头的那边变灰。
func pageButton(c *ui.Context, icon *ui.SVG, label string, enabled, left bool) ui.Element {
	_, h := c.Size()
	b := coui.CircleButton(c, icon, label).Absolute().Top((coui.TitleBarHeight + h - viewerBottom - 40) / 2)
	if left {
		b.Left(18)
	} else {
		b.Right(18)
	}
	return b.Disabled(!enabled)
}

// bottom 是底栏：多张时上面一排页码圆点，下面是保存（图标在上、字在下，同手机版的 BarAction），
// 保存过后旁边一句结果。
func (v *Viewer) bottom(c *ui.Context) {
	k := coui.Of(c)
	ui.Column(c).Absolute().Left(0).Right(0).Bottom(0).Height(viewerBottom).AlignItems(ui.Center).Justify(ui.End).
		Padding(0, 0, 12, 0).Gap(6).Children(func() {
		if len(v.images) > 1 {
			ui.Row(c).Gap(6).Children(func() {
				for i := range v.images {
					dot := k.Text.Alpha(0.3)
					if i == v.index {
						dot = k.Text
					}
					ui.Box(c.Key(i)).Size(6, 6).Radius(3).Background(dot)
				}
			})
		}
		bmp, _, _ := v.bitmap(c, v.images[v.index])
		busy, note := false, ""
		if s := v.saving; s != nil {
			done, n := s.result()
			busy, note = !done, n
		}
		save := ui.ButtonBase(c).Label("保存").MinWidth(88).Radius(16).Padding(8, 12).
			Cursor(ui.CursorPointer).Disabled(v.Save == nil || bmp == nil || busy)
		if save.Hovered() {
			save.Background(k.Hover)
		}
		save.Children(func() {
			ui.Column(c).Gap(4).AlignItems(ui.Center).Children(func() {
				ui.Icon(c, coui.IconDownload).Size(24, 24).TextColor(k.Text)
				ui.Text(c, "保存").FontSize(coui.SizeBodyXS).TextColor(k.Text)
			})
		})
		save.OnClick(func() { v.save(c, v.images[v.index]) })
		ui.Text(c, note).FontSize(coui.SizeBodyXS).TextColor(k.Secondary).Height(16).SingleLine().Ellipsis("…").MaxWidth(560)
	})
}

// save 在后台取这张图的原始数据，交给 Save 存，结果记在看大图上。
func (v *Viewer) save(c *ui.Context, im items.Image) {
	s := &saving{}
	v.saving = s
	save, load, services := v.Save, v.from.LoadImage, c.Services()
	go func() {
		note := ""
		data, err := ImageBytes(im, load)
		if err == nil {
			var saved string
			saved, err = save(data, imageFileName(data, time.Now()))
			if saved != "" {
				note = "已保存到 " + saved
			}
		}
		if err != nil {
			note = "保存失败：" + err.Error()
		}
		s.mu.Lock()
		s.done, s.note = true, note
		s.mu.Unlock()
		services.Invalidate()
	}()
}

// imageFileName 是保存时建议的文件名（同手机版 ImageExport.fileName 取不出名字时）：Zeta_年月日_时分秒，
// 扩展名按内容认出的类型。
func imageFileName(data []byte, now time.Time) string {
	ext := "png"
	if kind := http.DetectContentType(data); strings.HasPrefix(kind, "image/") {
		ext = strings.TrimPrefix(kind, "image/")
		switch ext {
		case "jpeg":
			ext = "jpg"
		case "x-icon", "vnd.microsoft.icon":
			ext = "ico"
		}
	}
	return "Zeta_" + now.Format("20060102_150405") + "." + ext
}
