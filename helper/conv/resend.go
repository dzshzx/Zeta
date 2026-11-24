package conv

import (
	"encoding/base64"
	"net/http"
	"slices"
	"strings"

	"github.com/dzshzx/Zeta/helper/locales"
	"github.com/dzshzx/Zeta/helper/transcript"
)

// 重新生成：conv.rewrite、conv.restore_code 带 resend 时照 target 那条用户消息原样重发。文字、附件路径和图片都由核心从那一项取，
// 会话记录里的图片经 Deps.SessionImage 取回，客户端不用把图取回本机再传回来。

// resend 分三步：在对话的 goroutine 里核对能不能改、抄下那一项；在外面取回图片（要读会话记录，不挡着对话）；
// 再回到对话的 goroutine 照不带 resend 的同一个方法做。所以返回 Pending。
func (m *Manager) resend(c *Conversation, client int, method string, r request) (any, error) {
	v, err := c.Call(func() (any, error) {
		i, _, err := c.rewindTarget(r.Target)
		if err != nil {
			return nil, err
		}
		it := *c.r.Items[i]
		it.Images, it.Files = slices.Clone(it.Images), slices.Clone(it.Files)
		return it, nil
	})
	if err != nil {
		return nil, err
	}
	original := v.(transcript.Item)
	return Pending(func() (any, error) {
		msg, err := c.deps.originalMessage(original)
		if err != nil {
			return nil, err
		}
		r.Message, r.Resend = msg, false
		return c.Call(func() (any, error) { return handlers[method](c, client, r) })
	}), nil
}

// originalMessage 是用户消息 it 原样的内容：文字、附件路径和图片的数据。
func (d Deps) originalMessage(it transcript.Item) (Message, error) {
	m := Message{Text: it.Text, Files: it.Files}
	for _, im := range it.Images {
		img, err := d.imageOf(im)
		if err != nil {
			return Message{}, locales.Err("errResendImage", "reason", locales.ErrArg(err))
		}
		img.Original = im.Original
		m.Images = append(m.Images, img)
	}
	if m.empty() {
		return Message{}, locales.Err("errResendEmpty")
	}
	return m, nil
}

// imageOf 是聊天条目里的一张图的数据：会话记录里的去记录里取，实时发出的带着数据（格式从数据认）。
func (d Deps) imageOf(im transcript.Image) (Image, error) {
	switch {
	case im.Kind == "session" && d.SessionImage != nil:
		return d.SessionImage(im.File, im.Ref)
	case im.Kind == "session":
		return Image{}, locales.Err("errSessionImageUnsupported")
	case im.Data == "":
		return Image{}, locales.Err("errImageNoData")
	}
	mediaType, err := sniffImage(im.Data)
	return Image{MediaType: mediaType, Data: im.Data}, err
}

// sniffImage 从 base64 的开头认出图片的格式（image/png 等）。
func sniffImage(data string) (string, error) {
	head := data[:min(len(data), 684)] // 解出约 512 字节就够认
	head = head[:len(head)/4*4]
	b, err := base64.StdEncoding.DecodeString(head)
	if err != nil {
		return "", locales.Err("errImageNotBase64")
	}
	mediaType := http.DetectContentType(b)
	if !strings.HasPrefix(mediaType, "image/") {
		return "", locales.Err("errImageFormat")
	}
	return mediaType, nil
}
