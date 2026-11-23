package conv

import (
	"encoding/base64"
	"errors"
	"net/http"

	"github.com/dzshzx/Zeta/helper/transcript"
)

// 实时收到的图（工具结果里的截图、随消息发的图）在条目里带着 base64 原文，一张就是几百 KB。整份推条目（打开对话、
// 重新打开）和每次重推一组工作过程都会把它们全带上：一个截了几十张图的对话一推就是几十 MB。声明了 options.image_refs
// 的客户端收到的条目里这些图只有 key，画到时再用 conv.image 按 key 取原文；没声明的照旧收到原文。

var errNoImage = errors.New("这个对话里没有这张图")

// itemsFor 是推给 opts 这个客户端的条目：声明了 image_refs 时去掉实时图片的原文（见 withoutImageData）。
func itemsFor(opts Options, list []*transcript.Item) []*transcript.Item {
	if opts.ImageRefs {
		return withoutImageData(list)
	}
	return list
}

// withoutImageData 是去掉了实时图片原文的条目：带原文的那几项换成浅拷贝（图和步骤另拷一份），别的项原样共用。
// 不改原来的条目，核心那份还要留着原文给 conv.image。
func withoutImageData(list []*transcript.Item) []*transcript.Item {
	out := list
	copied := false
	for i, it := range list {
		if !carriesImageData(it) {
			continue
		}
		if !copied {
			out, copied = append([]*transcript.Item(nil), list...), true
		}
		lean := *it
		lean.Images = leanImages(it.Images)
		if it.Steps != nil {
			lean.Steps = make([]*transcript.Step, len(it.Steps))
			for j, s := range it.Steps {
				if len(s.Images) == 0 {
					lean.Steps[j] = s
					continue
				}
				step := *s
				step.Images = leanImages(s.Images)
				lean.Steps[j] = &step
			}
		}
		out[i] = &lean
	}
	return out
}

func carriesImageData(it *transcript.Item) bool {
	for _, im := range it.Images {
		if im.Data != "" {
			return true
		}
	}
	for _, s := range it.Steps {
		for _, im := range s.Images {
			if im.Data != "" {
				return true
			}
		}
	}
	return false
}

func leanImages(list []transcript.Image) []transcript.Image {
	if list == nil {
		return nil
	}
	out := make([]transcript.Image, len(list))
	for i, im := range list {
		im.Data = ""
		out[i] = im
	}
	return out
}

// imageData 是条目里键为 key 的实时图片的原文（base64），没有时为空串。在对话的 goroutine 上调用。
func (c *Conversation) imageData(key string) string {
	find := func(list []transcript.Image) string {
		for _, im := range list {
			if im.Key == key && im.Data != "" {
				return im.Data
			}
		}
		return ""
	}
	for _, it := range c.r.Items {
		if d := find(it.Images); d != "" {
			return d
		}
		for _, s := range it.Steps {
			if d := find(s.Images); d != "" {
				return d
			}
		}
	}
	return ""
}

// image 答 conv.image：核心里标识为 r.Key 的对话（conv.state 的 key）条目里键为 r.Image 的实时图片。
// 不要求这条连接拿着那个对话：客户端从另一条取大块数据的连接上取，免得几百 KB 的应答挡住对话的推送。
func (m *Manager) image(r request) (any, error) {
	m.mu.Lock()
	c := m.byKey(r.Key)
	m.mu.Unlock()
	if c == nil {
		return nil, errGone
	}
	v, err := c.Call(func() (any, error) { return c.imageData(r.Image), nil })
	if err != nil {
		return nil, err
	}
	data := v.(string)
	if data == "" {
		return nil, errNoImage
	}
	// 认类型只看开头 512 字节（DetectContentType 也只看这么多），不解整张
	head, err := base64.StdEncoding.DecodeString(data[:min(len(data), 684)])
	if err != nil {
		return nil, err
	}
	return Image{MediaType: http.DetectContentType(head), Data: data}, nil
}
