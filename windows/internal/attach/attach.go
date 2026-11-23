// Package attach 是输入栏收附件的规矩，同 shared 的 Attachments、FileLimits：图片缩到长边 1568、
// 编成 JPEG 随消息发给模型看；别的文件传到主机上（client/hostlink 的 Upload），太大的不收。
package attach

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	_ "image/gif" // 解码时认得这几种
	"image/jpeg"
	_ "image/png"
	"path"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/helper/coreapi"
	xdraw "golang.org/x/image/draw"

	_ "golang.org/x/image/bmp"
	_ "golang.org/x/image/webp"
)

const (
	// MaxImages 是一条消息最多带几张图（ChatScreen.kt 的 MaxAttachments）。
	MaxImages = 4
	// MaxEdge 是图片长边的上限：再大模型端也会缩，白占传输和上下文。
	MaxEdge = 1568
	// MaxFiles 是一次最多收几个文件。
	MaxFiles = 20
	// MaxBytes 是一个文件（文件夹是打包后的总量）最大多少：再大传上去要多占一份同样大的空间，Claude 也读不完。
	MaxBytes = 200_000_000
	// NoteBytes：超过它，附件上注明大小。
	NoteBytes = 10_000_000

	jpegQuality = 85
)

// imageExtensions 是选图时列的扩展名（同桌面版的 IMAGE_EXTENSIONS），也是拖进来、选的文件里当图片收的。
var imageExtensions = []string{"png", "jpg", "jpeg", "gif", "webp", "bmp"}

// ImageExtensions 是系统选图对话框里列的扩展名。
func ImageExtensions() []string { return append([]string(nil), imageExtensions...) }

// IsImage：文件名看着是能当图片附件的图。
func IsImage(name string) bool {
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))
	for _, e := range imageExtensions {
		if ext == e {
			return true
		}
	}
	return false
}

// ErrNotImage：解不开，不是认得的图片。
var ErrNotImage = errors.New("不是能读的图片")

// EncodeImage 把图片文件的内容做成随消息发的一张图：长边缩到 MaxEdge 以内，透明的地方铺白，编成 JPEG（质量 85）。
// Data 是 JPEG 的字节，输入栏的缩略图也从它解。解不开时返回 ErrNotImage。
func EncodeImage(data []byte) (coreapi.Image, error) {
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return coreapi.Image{}, ErrNotImage
	}
	b := src.Bounds()
	w, h := FitWithin(b.Dx(), b.Dy(), MaxEdge)
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	draw.Draw(dst, dst.Bounds(), image.NewUniform(color.White), image.Point{}, draw.Src)
	if w == b.Dx() && h == b.Dy() {
		draw.Draw(dst, dst.Bounds(), src, b.Min, draw.Over)
	} else {
		xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Over, nil)
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, dst, &jpeg.Options{Quality: jpegQuality}); err != nil {
		return coreapi.Image{}, err
	}
	return coreapi.Image{MediaType: "image/jpeg", Data: out.Bytes()}, nil
}

// FitWithin 把 w×h 等比缩到最长边不超过 edge，不放大；四舍五入，至少 1（同 ImageCodec.fitWithin）。
func FitWithin(w, h, edge int) (int, int) {
	longest := max(w, h)
	if longest <= edge || longest <= 0 {
		return w, h
	}
	scale := float64(edge) / float64(longest)
	return max(1, int(float64(w)*scale+0.5)), max(1, int(float64(h)*scale+0.5))
}

// Label 是附件上显示的名字：大文件后面注明大小。size 不知道时给 -1。
func Label(name string, size int64) string {
	if size >= NoteBytes {
		return name + " · " + FormatBytes(size)
	}
	return name
}

// EntryName 是附件说明（/路径，或 @路径、@文件夹/ 的引用）的显示名：路径的最后一段，根目录是 /（同 AttachedPaths.label）。
func EntryName(entry string) string {
	name := path.Base(strings.TrimRight(strings.TrimPrefix(entry, "@"), "/"))
	if name == "." || name == "/" {
		return "/"
	}
	return name
}

// TooLarge：size 超过了上限（不知道时给 -1，不算）。
func TooLarge(size int64) bool { return size > MaxBytes }

// TooLargeReason 是没收某个文件的原因；size 是 -1 时是传到一半才发现超过上限。
func TooLargeReason(name string, size int64) string {
	text := "「" + name + "」"
	if size >= 0 {
		text += "有 " + FormatBytes(size) + "，"
	}
	return text + "超过 " + FormatBytes(MaxBytes) + " 的上限，没有传上去"
}

// FormatBytes 是给人看的大小，按 1000 进位，三位有效数字（同 shared 的 formatBytes）：999 B、1.5 KB、12.3 MB、200 MB。
func FormatBytes(n int64) string {
	if n < 1000 {
		return strconv.FormatInt(n, 10) + " B"
	}
	units := []string{"KB", "MB", "GB", "TB"}
	value, unit := float64(n)/1000, 0
	for value >= 999.5 && unit < len(units)-1 {
		value /= 1000
		unit++
	}
	var text string
	switch {
	case value >= 100:
		text = fmt.Sprintf("%.0f", value)
	case value >= 10:
		text = fmt.Sprintf("%.1f", value)
	default:
		text = fmt.Sprintf("%.2f", value)
	}
	if strings.Contains(text, ".") {
		text = strings.TrimRight(strings.TrimRight(text, "0"), ".")
	}
	return text + " " + units[unit]
}
