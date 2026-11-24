package chatview

import (
	"encoding/base64"
	"hash/fnv"
	"regexp"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// 回答里用 Markdown 引用的图（同手机版 ChatImage.kt 的 answerImages）：`![说明](/路径)`、网址、内嵌的 data: 从正文里取出来，
// 画在回答下面，点了看大图；正文里不再留一行替代文字。主机上的文件经调用方给的 LoadMarkdownImage 取（核心的 images.file），
// 网址等用户点一下再取。

var (
	markdownImage = regexp.MustCompile(`!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+["'][^"']*["'])?\s*\)`)
	codeFence     = regexp.MustCompile("(?s)```.*?(```|$)")
)

// maxAnswerImages：一段回答里最多直接显示几张图，多的原样留在正文里。
const maxAnswerImages = 12

// answerParts 是一段回答拆成的两部分：照常排版的正文，和从正文里取出来直接显示的图。图按原文里的先后分组：
// 只隔着空白的几张连成一组（排成一行），被正文隔开的各成一组。
type answerParts struct {
	src, cwd string // 拆的是哪段原文、按哪个工作目录解析的（缓存用）
	text     string
	groups   [][]items.Image
}

// images 是各组的图连成的一串（看大图时左右翻的就是它）。
func (p *answerParts) images() []items.Image {
	var all []items.Image
	for _, g := range p.groups {
		all = append(all, g...)
	}
	return all
}

// splitAnswerImages 找出回答 text 里的 Markdown 图片并从正文里拿掉：相对路径按工作目录 cwd 解析（cwd 为空时不认相对路径），
// 代码块里的不算，同一张图只显示一次，认不出的写法原样留着。
func splitAnswerImages(text, cwd string) answerParts {
	parts := answerParts{src: text, cwd: cwd, text: text}
	if !strings.Contains(text, "![") {
		return parts
	}
	fences := codeFence.FindAllStringIndex(text, -1)
	inFence := func(at int) bool {
		for _, f := range fences {
			if at >= f[0] && at < f[1] {
				return true
			}
		}
		return false
	}
	seen := map[string]bool{}
	lastEnd, open := -1, false
	var shown strings.Builder
	at := 0
	for _, m := range markdownImage.FindAllStringSubmatchIndex(text, -1) {
		start, end := m[0], m[1]
		im, ok := linkedImage(text[m[2]:m[3]], cwd)
		if !ok || inFence(start) {
			continue
		}
		key := im.CacheKey()
		fresh := !seen[key]
		if fresh && len(seen) >= maxAnswerImages {
			continue
		}
		// 重复的图不再显示，也不打断连着的一组；它前面隔着正文时，后面的图另起一组
		if lastEnd < 0 || strings.TrimSpace(text[lastEnd:start]) != "" {
			open = false
		}
		if fresh {
			if open {
				parts.groups[len(parts.groups)-1] = append(parts.groups[len(parts.groups)-1], im)
			} else {
				parts.groups = append(parts.groups, []items.Image{im})
			}
			seen[key] = true
			open = true
		}
		lastEnd = end
		shown.WriteString(text[at:start])
		at = end
	}
	if len(parts.groups) > 0 {
		shown.WriteString(text[at:])
		parts.text = strings.TrimRight(shown.String(), " \t\r\n")
	}
	return parts
}

// linkedImage 认一个图片地址：内嵌的 data:image 是 inline；http、https 是网络图；file:// 和不带协议的路径是主机上的文件
// （扩展名得像图片，相对路径按 cwd 解析，~ 开头的留给核心按家目录解析）；别的协议不认。
func linkedImage(target, cwd string) (items.Image, bool) {
	switch {
	case strings.HasPrefix(target, "data:image/") && strings.Contains(target, ";base64,"):
		data := target[strings.Index(target, ";base64,")+len(";base64,"):]
		if _, err := base64.StdEncoding.DecodeString(data); err != nil {
			return items.Image{}, false
		}
		h := fnv.New64a()
		h.Write([]byte(target))
		return items.Image{Kind: items.Inline, Key: "d:" + strconv.FormatUint(h.Sum64(), 16) + ":" + strconv.Itoa(len(target)), Data: data}, true
	case strings.HasPrefix(target, "https://") || strings.HasPrefix(target, "http://"):
		return items.Image{Kind: items.Linked, URL: target}, true
	case strings.Contains(target, "://") && !strings.HasPrefix(target, "file://"):
		return items.Image{}, false
	}
	raw := strings.ReplaceAll(strings.TrimPrefix(target, "file://"), "%20", " ")
	p := raw
	switch {
	case strings.HasPrefix(raw, "/"), strings.HasPrefix(raw, "~/"):
	case cwd == "":
		return items.Image{}, false
	default:
		p = strings.TrimRight(cwd, "/") + "/" + strings.TrimPrefix(raw, "./")
	}
	if !attach.IsImage(p) {
		return items.Image{}, false
	}
	return items.Image{Kind: items.Linked, URL: p}, true
}
