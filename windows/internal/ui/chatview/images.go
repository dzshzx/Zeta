package chatview

import (
	"encoding/base64"
	"errors"
	"fmt"
	"sync"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// errNoLoader：调用方没给取会话记录里图片的函数。
var errNoLoader = errors.New("没有取图片的办法")

// imageEntry 是一张图的载入结果。载入在单独的 goroutine 里做，做完置 done。
type imageEntry struct {
	mu   sync.Mutex
	bmp  *ui.Bitmap
	err  error
	done bool
}

func (e *imageEntry) result() (*ui.Bitmap, bool, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.bmp, e.done, e.err
}

// imageCache 按图片的缓存键记着载入结果：同一张图只取一次、只解码一次。entries 只在界面线程上读写。
type imageCache struct {
	entries map[string]*imageEntry
	pending sync.WaitGroup // 还在载入的；测试等它们做完
}

// get 返回一张图：还在载入时 done 为 false，第一次见到时开始载入，载完让窗口重画。
func (ic *imageCache) get(c *ui.Context, im items.Image, load func(file, ref string) ([]byte, error)) (bmp *ui.Bitmap, done bool, err error) {
	key := im.CacheKey()
	e, ok := ic.entries[key]
	if !ok {
		if ic.entries == nil {
			ic.entries = map[string]*imageEntry{}
		}
		e = &imageEntry{}
		ic.entries[key] = e
		services := c.Services()
		ic.pending.Add(1)
		go func() {
			defer ic.pending.Done()
			bmp, err := loadImage(im, load)
			e.mu.Lock()
			e.bmp, e.err, e.done = bmp, err, true
			e.mu.Unlock()
			services.Invalidate()
		}()
	}
	return e.result()
}

// loadImage 取一张图的数据并解码：实时收到的带着 base64，会话记录里的经调用方给的 load 取。
func loadImage(im items.Image, load func(file, ref string) ([]byte, error)) (*ui.Bitmap, error) {
	var data []byte
	var err error
	switch im.Kind {
	case items.Inline:
		data, err = base64.StdEncoding.DecodeString(im.Data)
	case items.Session:
		if load == nil {
			return nil, errNoLoader
		}
		data, err = load(im.File, im.Ref)
	default:
		return nil, fmt.Errorf("不认识的图片种类 %q", im.Kind)
	}
	if err != nil {
		return nil, err
	}
	return ui.DecodeBitmap(data)
}

// thumbnail 是固定大小、裁切填满的小图（用户消息带的图）。
func (ch *Chat) thumbnail(c *ui.Context, im items.Image, size float32) {
	bmp, done, err := ch.images.get(c, im, ch.LoadImage)
	box := ui.Box(c).Size(size, size).Radius(12).Clip().Background(c.Theme().Text.Alpha(0.06))
	switch {
	case bmp != nil:
		box.Children(func() { ui.Image(c, bmp).Size(size, size).Fit(ui.Cover).Label("图片") })
	case done:
		box.Center().Tooltip(imageError(err)).Children(func() {
			ui.Icon(c, iconImage).FontSize(22).TextColor(c.Theme().TextMuted).Label("图片无法显示")
		})
	default:
		box.Label("正在载入图片")
	}
}

// 按原比例显示的图（工具结果里的）：最宽这么多，很高的图限制高度，免得一张图占满几屏。
const (
	imageMaxWidth  = 480
	imageMaxHeight = 340
)

// picture 是按原比例铺开的一张图。
func (ch *Chat) picture(c *ui.Context, im items.Image) {
	bmp, done, err := ch.images.get(c, im, ch.LoadImage)
	t := c.Theme()
	switch {
	case bmp != nil:
		w, h := fitSize(bmp)
		ui.Image(c, bmp).Size(w, h).Fit(ui.Contain).Radius(12).Label("图片")
	case done:
		ui.Row(c).Gap(6).AlignItems(ui.Center).Padding(6, 11).Radius(10).Background(t.Text.Alpha(0.05)).
			Tooltip(imageError(err)).Children(func() {
			ui.Icon(c, iconImage).FontSize(14).TextColor(t.TextMuted)
			ui.Text(c, "图片无法显示").FontSize(12).TextColor(t.TextMuted)
		})
	default:
		ui.Box(c).Size(imageMaxWidth/2, 120).Radius(12).Background(t.Text.Alpha(0.05)).Label("正在载入图片")
	}
}

// fitSize 是图按原比例放进最大宽高后的大小（像素当作 DIP）。
func fitSize(bmp *ui.Bitmap) (w, h float32) {
	iw, ih := bmp.Size()
	if iw <= 0 || ih <= 0 {
		return 0, 0
	}
	w, h = float32(iw), float32(ih)
	if w > imageMaxWidth {
		w, h = imageMaxWidth, h*imageMaxWidth/w
	}
	if h > imageMaxHeight {
		w, h = w*imageMaxHeight/h, imageMaxHeight
	}
	return w, h
}

func imageError(err error) string {
	if err == nil {
		return "图片无法显示"
	}
	return "图片无法显示：" + err.Error()
}
