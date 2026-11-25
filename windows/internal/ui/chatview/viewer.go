package chatview

import (
	"bytes"
	"image"
	"image/png"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/plugins/glass"
	"github.com/egoist/mygo/transfer"
	"github.com/egoist/mygo/ui"
)

// 全屏看大图（同手机版 ImageViewer.kt、ImageGallery.kt）：点聊天里的图时列表发出 OpenImages，调用方建一个 Viewer、
// 每帧画在窗口最上层。遮罩铺满窗口，是毛玻璃（viewerGlass），不管外面是浅色深色都用 COUI 的深色配色；
// 在遮罩上点一下（图和按钮以外、标题栏以下，按下松开之间没拖动）关上。顶上是返回和「第几张 / 共几张」，
// 多张时两边是上一张、下一张（也可以按左右键），底下是页码圆点、保存和复制（Ctrl+C 也是复制）。Esc 关上。
// 保存交给调用方给的 Save；复制把图转成 PNG 放上剪贴板（Windows 上同时给 PNG 和位图两种格式，聊天软件、画图都能粘贴）。

// Viewer 是一次看大图：看的是哪一组图、第几张。图从发出 OpenImages 的那个列表的缓存里取，不再取一遍。
type Viewer struct {
	// Save 存一张图（data 是原始数据，name 是建议的文件名），返回存到哪，取消了是空的。在后台 goroutine 上调；
	// 没给时保存是灰的。
	Save func(data []byte, name string) (saved string, err error)

	from *Chat
	// images 是这一组图，关上后为空。
	images []items.Image
	index  int
	// task 是正在做的保存或复制（取图、等用户选位置、写文件，或转成 PNG），done 了之后 note 是结果。
	task *task
	// zoom 是这一张的缩放和平移（zoom.go），翻页时回到原大。
	zoom zoom
	// tap 认遮罩上的一次点击：图所在的框里按在图外面的，和框外面的遮罩上的。
	tap tap
}

// tap 认一次点击：按下时在遮罩上，松开前指针没挪出 tapSlop，就算点了遮罩；挪远了是在拖（放大后平移图），不算。
type tap struct {
	armed  bool
	x0, y0 float32
}

// tapSlop：按下到松开之间指针挪动不超过这么多 DIP 还算点击。
const tapSlop = 4

// handle 接一个指针事件，onBackdrop 是这一点在不在遮罩上；返回这次松开是不是点了遮罩。
func (t *tap) handle(ev ui.InputEvent, onBackdrop bool) bool {
	switch ev.Kind {
	case ui.InputPointerDown:
		t.armed, t.x0, t.y0 = ev.Button == 0 && ev.Clicks == 1 && onBackdrop, ev.X, ev.Y
	case ui.InputPointerMove:
		if t.armed && max(abs(ev.X-t.x0), abs(ev.Y-t.y0)) > tapSlop {
			t.armed = false
		}
	case ui.InputPointerUp:
		hit := t.armed && ev.Button == 0
		t.armed = false
		return hit
	}
	return false
}

func abs(f float32) float32 { return max(f, -f) }

// pointer：主键的按下、移动、松开（遮罩自己接，不往下漏）。
func pointer(ev ui.InputEvent) bool {
	switch ev.Kind {
	case ui.InputPointerDown, ui.InputPointerUp:
		return ev.Button == 0
	case ui.InputPointerMove:
		return true
	}
	return false
}

// NewViewer 从 list 的第 index 张看起；图是列表 from 里的（OpenImages 发出来的那组）。
func NewViewer(from *Chat, list []items.Image, index int) *Viewer {
	return &Viewer{from: from, images: slices.Clone(list), index: index}
}

// task 是一次保存或复制：在后台 goroutine 上做完，结果给界面线程读。
type task struct {
	mu   sync.Mutex
	done bool
	note string
	// clip 是复制时转好的 PNG：剪贴板要在界面线程上写，由下一帧的 View 放上去后清掉。
	clip []byte
}

func (s *task) result() (bool, string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.done, s.note
}

func (s *task) finish(note string, clip []byte) {
	s.mu.Lock()
	s.done, s.note, s.clip = true, note, clip
	s.mu.Unlock()
}

// takeClip 取走做完的复制要放上剪贴板的 PNG，没有时为空。
func (s *task) takeClip() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	clip := s.clip
	s.clip = nil
	return clip
}

// close 关上看大图。
func (v *Viewer) close() { v.images, v.task, v.zoom, v.tap = nil, nil, zoom{}, tap{} }

// step 翻到上一张（-1）或下一张（1），到头了不动。
func (v *Viewer) step(d int) {
	if n := v.index + d; n >= 0 && n < len(v.images) {
		v.index, v.task, v.zoom, v.tap = n, nil, zoom{}, tap{}
	}
}

// bitmap 是一张图在来源列表缓存里的载入结果。
func (v *Viewer) bitmap(c *ui.Context, im items.Image) (*ui.Bitmap, bool, error) {
	return v.from.images.get(c, im, v.from.fetcher)
}

// 看大图页上下留给两条栏的高度，左右留给翻页键的宽度。
const (
	viewerBottom = 128
	viewerSide   = 76
)

// viewerGlass 是遮罩的材质：mygo 的毛玻璃（glass.Glass 的 Regular），底下的界面透过来、模糊，深色配色下压暗，
// 图和按钮在它上面看得清；铺满窗口的大块玻璃按 Clarity 0.5 调得通透些。
var viewerGlass = glass.Glass{Clarity: 0.5}

// View 把看大图画在窗口最上层；返回还开着没有（关上了调用方丢掉它）。
func (v *Viewer) View(c *ui.Context) bool {
	if len(v.images) == 0 {
		return false
	}
	v.index = min(max(v.index, 0), len(v.images)-1)
	if s := v.task; s != nil {
		if clip := s.takeClip(); clip != nil {
			c.Services().WriteClipboardData(transfer.New(transfer.NewItem(transfer.Bytes(transfer.PNG, clip))))
		}
	}
	ui.Overlay(c, func() {
		// 毛玻璃代替深色主题的底色铺满这一层：材质按这一层的主题压暗，浅色窗口下也压暗
		page := ui.Themed(c, widget.Theme(true)).Material(viewerGlass).Absolute().Left(0).Top(0).Right(0).Bottom(0)
		page = page.Modal().Role(ui.RoleDialog).Label(locales.ChatViewer())
		page.Children(func() {
			// 标题栏以下的遮罩，在最底层：点了关上。图所在的框、按钮、两条栏画在它上面，各自接各自的点击
			ui.Box(c.Key("backdrop")).Absolute().Left(0).Right(0).Top(widget.TitleBarHeight).Bottom(0).HandleInput(v.backdropInput)
			v.image(c, v.images[v.index])
			v.top(c)
			if len(v.images) > 1 {
				pageButton(c, widget.IconChevronBack, locales.ChatPreviousImage(), v.index > 0, true).OnClick(func() { v.step(-1) })
				pageButton(c, widget.IconForward, locales.ChatNextImage(), v.index < len(v.images)-1, false).OnClick(func() { v.step(1) })
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
		case page.OverlayShortcut(ui.Cmd, ui.KeyC):
			if v.canAct(c) {
				v.copy(c, v.images[v.index])
			}
		}
	})
	return len(v.images) > 0
}

// backdropInput 接遮罩上的指针：点了关上。底栏让指针穿过它的空白处落到遮罩上（PassThrough）。
func (v *Viewer) backdropInput(ev ui.InputEvent) bool {
	if v.tap.handle(ev, true) {
		v.close()
	}
	return pointer(ev)
}

// image 是中间的图：按原比例放进栏和翻页键之间的地方，小图最多放大到两倍，可以缩放、拖动（zoom.go）；
// 还在取时转圈，取不到时说一声；没点过的网络图先是「加载图片」。
func (v *Viewer) image(c *ui.Context, im items.Image) {
	k := widget.Of(c)
	w, h := c.Size()
	room := ui.Rect{W: w - 2*viewerSide, H: h - widget.TitleBarHeight - viewerBottom}
	box := ui.Column(c.Key("image/" + im.CacheKey())).Absolute().Left(viewerSide).Right(viewerSide).Top(widget.TitleBarHeight).
		Bottom(viewerBottom).Center()
	if v.from.waitsForTap(im) {
		box.Children(func() { v.from.remotePrompt(c, im) })
		return
	}
	bmp, done, err := v.bitmap(c, im)
	var shown ui.Rect // 图画在框里的哪（缩放、平移之后），没有图时是空的
	if bmp != nil {
		iw, ih := bmp.Size()
		scale := min(room.W/float32(iw), room.H/float32(ih), 2)
		shown.W, shown.H = float32(iw)*scale, float32(ih)*scale
	}
	// 滚轮、捏合缩放，放大后拖动，双击切换（zoom.go）；按在图外面点一下是点了遮罩，关上。放大的图超出这块地方的部分不画，免得盖住两条栏
	box.Clip().HandleInput(func(ev ui.InputEvent) bool {
		took := bmp != nil && v.zoom.handle(ev, room.W, room.H)
		z := v.zoom.factor()
		w, h := shown.W*z, shown.H*z
		x, y := room.W/2+v.zoom.x-w/2, room.H/2+v.zoom.y-h/2
		onImage := ev.X >= x && ev.X < x+w && ev.Y >= y && ev.Y < y+h
		if v.tap.handle(ev, !onImage) {
			v.close()
			return true
		}
		return took || pointer(ev)
	})
	if bmp != nil && v.zoom.zoomed() {
		box.Cursor(ui.CursorGrab)
	}
	box.Children(func() {
		switch {
		case bmp != nil:
			z := v.zoom.factor()
			ui.Image(c, bmp).Size(shown.W, shown.H).Fit(ui.Contain).Scale(z, z).Translate(v.zoom.x, v.zoom.y).
				Label(locales.ChatImage()).Description(zoomText(z))
		case done:
			ui.Column(c).Gap(10).AlignItems(ui.Center).Tooltip(imageError(err)).Children(func() {
				ui.Icon(c, iconImage).Size(40, 40).TextColor(k.Secondary)
				ui.Text(c, locales.ChatImageUnavailable()).FontSize(widget.SizeBodyM).TextColor(k.Secondary)
			})
		default:
			ui.Spinner(c).Size(24, 24).Label(locales.ChatImageLoading())
		}
	})
}

// top 是顶栏（同时是标题栏，空白处拖动窗口）：左边返回，多张时按整个窗口居中写「第几张 / 共几张」。
func (v *Viewer) top(c *ui.Context) {
	k := widget.Of(c)
	ui.Row(c).Absolute().Left(0).Right(0).Top(0).Height(widget.TitleBarHeight).Justify(ui.Center).AlignItems(ui.Center).
		DragWindow().Children(func() {
		if len(v.images) > 1 {
			ui.Text(c, strconv.Itoa(v.index+1)+" / "+strconv.Itoa(len(v.images))).FontSize(widget.SizeHeadlineS).FontWeight(600).
				TextColor(k.Text)
		}
	})
	widget.IconButton(c, widget.IconBack, locales.WidgetBack()).Absolute().Left(16).Top((widget.TitleBarHeight - 40) / 2).
		OnClick(v.close)
}

// pageButton 是两边竖直居中的翻页键（浮起的圆钮）；到头的那边变灰。
func pageButton(c *ui.Context, icon *ui.SVG, label string, enabled, left bool) ui.Element {
	_, h := c.Size()
	b := widget.IconButton(c, icon, label).Absolute().Top((widget.TitleBarHeight + h - viewerBottom - 40) / 2)
	if left {
		b.Left(18)
	} else {
		b.Right(18)
	}
	return b.Disabled(!enabled)
}

// bottom 是底栏：多张时上面一排页码圆点，下面是保存和复制（图标在上、字在下，同手机版的 BarAction），
// 做过后底下一句结果。
func (v *Viewer) bottom(c *ui.Context) {
	k := widget.Of(c)
	ui.Column(c).Absolute().Left(0).Right(0).Bottom(0).Height(viewerBottom).AlignItems(ui.Center).Justify(ui.End).
		Padding(0, 0, 12, 0).Gap(6).PassThrough().Children(func() {
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
		note := ""
		if s := v.task; s != nil {
			_, note = s.result()
		}
		ready := v.canAct(c)
		ui.Row(c).Gap(8).Children(func() {
			barAction(c, widget.IconDownload, locales.CommonSave(), v.Save == nil || !ready).OnClick(func() { v.save(c, v.images[v.index]) })
			barAction(c, widget.IconCopy, locales.CommonCopy(), !ready).OnClick(func() { v.copy(c, v.images[v.index]) })
		})
		ui.Text(c, note).FontSize(widget.SizeBodyXS).TextColor(k.Secondary).Height(16).SingleLine().Ellipsis("…").MaxWidth(560)
	})
}

// canAct：这一张已经载入、没有在做的保存或复制，保存、复制能点。
func (v *Viewer) canAct(c *ui.Context) bool {
	if bmp, _, _ := v.bitmap(c, v.images[v.index]); bmp == nil {
		return false
	}
	if s := v.task; s != nil {
		if done, _ := s.result(); !done {
			return false
		}
	}
	return true
}

// barAction 是底栏的一个操作：图标在上、字在下。
func barAction(c *ui.Context, icon *ui.SVG, label string, disabled bool) ui.Element {
	k := widget.Of(c)
	b := ui.ButtonBase(c.Key(label)).Label(label).MinWidth(88).Radius(16).Padding(8, 12).Cursor(ui.CursorPointer).Disabled(disabled)
	if b.Hovered() {
		b.Background(k.Hover)
	}
	b.Children(func() {
		ui.Column(c).Gap(4).AlignItems(ui.Center).Children(func() {
			ui.Icon(c, icon).Size(24, 24).TextColor(k.Text)
			ui.Text(c, label).FontSize(widget.SizeBodyXS).TextColor(k.Text)
		})
	})
	return b
}

// run 在后台做一次保存或复制：取这张图的原始数据交给 work，结果记在看大图上，做完让窗口重画。
func (v *Viewer) run(c *ui.Context, im items.Image, failed func(error string) string, work func(data []byte) (note string, clip []byte, err error)) {
	s := &task{}
	v.task = s
	fetch, services := v.from.fetcher(im), c.Services()
	go func() {
		var note string
		var clip []byte
		data, err := fetch()
		if err == nil {
			note, clip, err = work(data)
		}
		if err != nil {
			note = failed(err.Error())
		}
		s.finish(note, clip)
		services.Invalidate()
	}()
}

// save 交给 Save 存这张图。
func (v *Viewer) save(c *ui.Context, im items.Image) {
	save := v.Save
	v.run(c, im, locales.ChatImageSaveFailed, func(data []byte) (string, []byte, error) {
		saved, err := save(data, imageFileName(data, time.Now()))
		if saved == "" || err != nil {
			return "", nil, err
		}
		return locales.ChatImageSaved(saved), nil, nil
	})
}

// copy 把这张图转成 PNG（本来是 PNG 的原样用）放上剪贴板。
func (v *Viewer) copy(c *ui.Context, im items.Image) {
	v.run(c, im, locales.ChatImageCopyFailed, func(data []byte) (string, []byte, error) {
		clip, err := pngOf(data)
		if err != nil {
			return "", nil, err
		}
		return locales.ChatImageCopied(), clip, nil
	})
}

// pngOf 是图的 PNG 数据：已经是 PNG 的原样返回，别的（JPEG、GIF 的第一帧、WebP、BMP）解码后重编。
func pngOf(data []byte) ([]byte, error) {
	if http.DetectContentType(data) == "image/png" {
		return data, nil
	}
	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
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

// zoomText 是给读屏的缩放倍数（同手机版的 zoomText）：原大时是「原大」，别的是百分比。
func zoomText(z float32) string {
	if z == 1 {
		return locales.ChatZoomActual()
	}
	return locales.ChatZoomPercent(int(z*100 + 0.5))
}
