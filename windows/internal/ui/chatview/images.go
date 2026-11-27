package chatview

import (
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/egoist/mygo/ui"
)

// errNoLoader：调用方没给取这种图的函数。
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

// imageCache 按图片的缓存键记着载入结果（Chat.image）：同一张图只取一次、只解码一次。entries 只在界面线程上读写。
type imageCache struct {
	entries map[string]*imageEntry
	pending sync.WaitGroup // 还在载入的；测试等它们做完
}

// image 返回这个列表里的一张图：还在载入时 done 为 false，第一次见到时在后台开始载入（取法见 fetcher），载完让窗口重画。
func (ch *Chat) image(c *ui.Context, im items.Image) (bmp *ui.Bitmap, done bool, err error) {
	ic, key := &ch.images, im.CacheKey()
	e, ok := ic.entries[key]
	if !ok {
		if ic.entries == nil {
			ic.entries = map[string]*imageEntry{}
		}
		e = &imageEntry{}
		ic.entries[key] = e
		services, fetch := c.Services(), ch.fetcher(im)
		ic.pending.Add(1)
		go func() {
			defer ic.pending.Done()
			data, err := fetch()
			var bmp *ui.Bitmap
			if err == nil {
				bmp, err = ui.DecodeBitmap(data)
			}
			e.mu.Lock()
			e.bmp, e.err, e.done = bmp, err, true
			e.mu.Unlock()
			services.Invalidate()
		}()
	}
	return e.result()
}

// ImageBytes 是一张图的原始数据（阻塞，在后台调用）：实时收到的带着 base64 时就地解开，只带了 key 的经 loadInline 取；
// 会话记录里的经 load 取。
func ImageBytes(im items.Image, load func(file, ref string) ([]byte, error), loadInline func(key string) ([]byte, error)) ([]byte, error) {
	switch im.Kind {
	case items.Inline:
		if im.Data != "" {
			return base64.StdEncoding.DecodeString(im.Data)
		}
		if loadInline == nil {
			return nil, errNoLoader
		}
		return loadInline(im.Key)
	case items.Session:
		if load == nil {
			return nil, errNoLoader
		}
		return load(im.File, im.Ref)
	}
	return nil, fmt.Errorf("不认识的图片种类 %q", im.Kind)
}

// imageBytes 是这个列表里一张图的原始数据：回答里写的图（linked）经 LoadMarkdownImage 取，别的同 ImageBytes。阻塞。
func (ch *Chat) imageBytes(im items.Image) ([]byte, error) {
	if im.Kind == items.Linked {
		if ch.LoadMarkdownImage == nil {
			return nil, errNoLoader
		}
		return ch.LoadMarkdownImage(im.URL)
	}
	return ImageBytes(im, ch.LoadImage, ch.LoadInlineImage)
}

// fetcher 返回取图 im 原始数据的函数（阻塞，在后台调）：读不到时改取 readImage 的那张。条目只在界面线程上读，
// 所以那张先在这里找好。
func (ch *Chat) fetcher(im items.Image) func() ([]byte, error) {
	read, ok := ch.readImage(im)
	return func() ([]byte, error) {
		data, err := ch.imageBytes(im)
		if err != nil && ok {
			return ch.imageBytes(read)
		}
		return data, err
	}
}

// readImage 是回答里写的主机上的图片文件 im 读不到（被删了、挪走了）时用来代替的图：Claude 用 Read 读这个文件时工具结果里的那张，
// 读过几次取最后一次；不是主机上的文件、没读过、结果里没图时 ok 为 false。同手机版 ChatImage.kt 的 readImage。
// Read 记的是绝对路径，~ 开头的先按 Home 展开再比（手机版在拆回答时就展开了）。
func (ch *Chat) readImage(im items.Image) (items.Image, bool) {
	if im.Kind != items.Linked || im.Remote() {
		return items.Image{}, false
	}
	path := im.URL
	if rest, ok := strings.CutPrefix(path, "~/"); ok && ch.Home != "" {
		path = strings.TrimRight(ch.Home, "/") + "/" + rest
	}
	for i := len(ch.items) - 1; i >= 0; i-- {
		steps := ch.items[i].Steps
		for j := len(steps) - 1; j >= 0; j-- {
			s := &steps[j]
			if s.Kind == items.Tool && s.Name == "Read" && len(s.Images) > 0 && s.Str("file_path") == path {
				return s.Images[0], true
			}
		}
	}
	return items.Image{}, false
}

// thumbnail 是固定大小、裁切填满的小图（用户消息带的图）。
func (ch *Chat) thumbnail(c *ui.Context, im items.Image, size float32) {
	bmp, done, err := ch.image(c, im)
	box := ui.Box(c).Size(size, size).Radius(12).Clip().Background(c.Theme().Text.Alpha(0.06))
	switch {
	case bmp != nil:
		box.Children(func() { ui.Image(c, bmp).Size(size, size).Fit(ui.Cover).Label(locales.ChatImage()) })
	case done:
		box.Center().Tooltip(imageError(err)).Children(func() {
			ui.Icon(c, iconImage).FontSize(22).TextColor(c.Theme().TextMuted).Label(locales.ChatImageUnavailable())
		})
	default:
		box.Label(locales.ChatImageLoading())
	}
}

// 按原比例显示的图（工具结果里的）：最宽这么多，很高的图限制高度，免得一张图占满几屏。
const (
	imageMaxWidth  = 480
	imageMaxHeight = 340
)

// picture 是按原比例铺开的一张图；网络图先是「加载图片」，点了才取。
func (ch *Chat) picture(c *ui.Context, im items.Image) {
	if ch.waitsForTap(im) {
		ch.remotePrompt(c, im)
		return
	}
	bmp, done, err := ch.image(c, im)
	t := c.Theme()
	switch {
	case bmp != nil:
		w, h := fitSize(bmp)
		ui.Image(c, bmp).Size(w, h).Fit(ui.Contain).Radius(12).Label(locales.ChatImage())
	case done:
		ui.Row(c).Gap(6).AlignItems(ui.Center).Padding(6, 11).Radius(10).Background(t.Text.Alpha(0.05)).
			Tooltip(imageError(err)).Children(func() {
			ui.Icon(c, iconImage).FontSize(14).TextColor(t.TextMuted)
			ui.Text(c, locales.ChatImageUnavailable()).FontSize(12).TextColor(t.TextMuted)
		})
	default:
		ui.Box(c).Size(imageMaxWidth/2, 120).Radius(12).Background(t.Text.Alpha(0.05)).Label(locales.ChatImageLoading())
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
		return locales.ChatImageUnavailable()
	}
	return locales.ChatImageUnavailableWhy(err.Error())
}

// waitsForTap：网络图还没经用户点过（同手机版的 waitsForTap，访问图片地址会把这台电脑的 IP 交给对方的服务器）。
// 设置里开着「自动加载网络图片」（AutoLoadRemote）时不等。
func (ch *Chat) waitsForTap(im items.Image) bool {
	if !im.Remote() || ch.allowed[im.CacheKey()] || ch.AutoLoadRemote != nil && ch.AutoLoadRemote() {
		return false
	}
	_, started := ch.images.entries[im.CacheKey()]
	return !started
}

// remotePrompt 是网络图取之前的一行「加载图片 · 网站」（同手机版的 RemoteImagePrompt），点了取图。
func (ch *Chat) remotePrompt(c *ui.Context, im items.Image) {
	t := c.Theme()
	site := im.URL[strings.Index(im.URL, "://")+3:]
	if i := strings.IndexByte(site, '/'); i >= 0 {
		site = site[:i]
	}
	b := ui.ButtonBase(c).Label(locales.ChatLoadImage(site)).Tooltip(im.URL).Cursor(ui.CursorPointer).Radius(16).Padding(7, 11)
	b.Background(t.Text.Alpha(0.05))
	if b.Hovered() {
		b.Background(t.Text.Alpha(0.09))
	}
	b.Children(func() {
		ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
			ui.Icon(c, iconImage).FontSize(15).TextColor(t.Accent)
			ui.Text(c, locales.ChatLoadImage(site)).FontSize(12.5).TextColor(t.TextMuted).SingleLine().Ellipsis("…").Shrink(1)
		})
	})
	b.OnClick(func() { ch.allowed[im.CacheKey()] = true })
}
