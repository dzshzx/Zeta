// Package drafts 是输入草稿的规矩（同 shared 的 core/ComposerDrafts.kt、DraftRecords）：每个对话没发出的字和附件一份，
// 存盘的上限、超出时丢哪些，和存盘的 JSON 格式。和平台无关，只管数据；放在哪、图片文件怎么存由各端自己做
// （桌面版在 windows/internal/settings）。格式和 Kotlin 版一样，两边存的互相读得出来。
package drafts

import (
	"bytes"
	"cmp"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"unicode/utf16"
)

// NewKey 是还没开始的新对话那一份草稿的 key（别的都是会话号）。
const NewKey = "new"

// 存盘的上限，同 DraftRecords。
const (
	MaxCount      = 30
	MaxText       = 20_000 // 一份最多存这么多字（UTF-16 单位，同 Kotlin），再长的只存开头
	MaxTotalText  = 60_000 // 所有草稿的字加起来
	MaxImageBytes = 16 << 20
)

// Draft 是存盘的一份草稿。
type Draft struct {
	// Key 是会话号；还没开始的新对话是 NewKey。
	Key  string `json:"key"`
	Text string `json:"text"`
	// Selection 是光标（UTF-16 单位，同 Kotlin 的 TextRange）。
	Selection [2]int  `json:"selection"`
	Images    []Image `json:"images"`
	Files     []File  `json:"files"`
	// At 是最后存的时刻（Unix 毫秒）：超出上限时先丢最久没动的。
	At int64 `json:"at"`
}

// Image 是草稿里的一张图：内容另存（桌面版是 drafts/<ID>），这里只记类型和大约多少字节。
type Image struct {
	ID    string `json:"id"`
	Type  string `json:"type"`
	Bytes int64  `json:"bytes"`
}

// File 是草稿里附带的一个设备上的路径：Entry 是附件说明（/路径，或 @路径、@文件夹/ 的引用），Label 是小标签上的名字。
// 存盘的没写 Label 时读出来是空的，由界面按 Entry 取名。
type File struct {
	Entry string `json:"entry"`
	Label string `json:"label"`
}

// Empty：没东西可存。
func (d Draft) Empty() bool {
	return strings.TrimSpace(d.Text) == "" && len(d.Images) == 0 && len(d.Files) == 0
}

// Fit 去掉空的，按最近改动排，裁到上限以内：新的先占额度（同 DraftRecords.fit）。
func Fit(list []Draft) []Draft {
	sorted := slices.Clone(list)
	slices.SortStableFunc(sorted, func(a, b Draft) int { return cmp.Compare(b.At, a.At) })
	textLeft, imagesLeft := MaxTotalText, int64(MaxImageBytes)
	var kept []Draft
	for _, r := range sorted {
		if len(kept) >= MaxCount {
			break
		}
		if r.Empty() {
			continue
		}
		r.Text = clipUTF16(r.Text, min(MaxText, textLeft))
		n := UTF16Len(r.Text)
		textLeft -= n
		var images []Image
		for _, im := range r.Images {
			if im.Bytes <= imagesLeft {
				imagesLeft -= im.Bytes
				images = append(images, im)
			}
		}
		r.Images = images
		r.Selection = [2]int{min(max(r.Selection[0], 0), n), min(max(r.Selection[1], 0), n)}
		if !r.Empty() {
			kept = append(kept, r)
		}
	}
	return kept
}

// previewChars 是侧栏预览最多几个字（UTF-16 单位，同 DraftRecords 的 PREVIEW_CHARS）。
const previewChars = 60

// Preview 是侧栏里这份草稿的一行预览（同 DraftRecords.preview）：第一行不空的字，连续的空白并成一个空格，最多 60 个字；
// 没有字时是「N 张图片」，再没有是附件的名字（Label，多个时「a.txt 等 3 个文件」）。
func Preview(d Draft) string {
	for line := range strings.Lines(d.Text) {
		if words := strings.Fields(line); len(words) > 0 {
			return clipUTF16(strings.Join(words, " "), previewChars)
		}
	}
	switch {
	case len(d.Images) > 0:
		return fmt.Sprintf("%d 张图片", len(d.Images))
	case len(d.Files) > 1:
		return fmt.Sprintf("%s 等 %d 个文件", d.Files[0].Label, len(d.Files))
	case len(d.Files) == 1:
		return d.Files[0].Label
	}
	return ""
}

// file 是存盘的整份。
type file struct {
	Version int               `json:"version"`
	Drafts  []json.RawMessage `json:"drafts"`
}

// Encode 写成存盘的 JSON（同 DraftRecords.encode）：空的图片、附件写成空数组。
func Encode(list []Draft) string {
	out := file{Version: 1, Drafts: []json.RawMessage{}}
	for _, r := range list {
		if r.Images == nil {
			r.Images = []Image{}
		}
		if r.Files == nil {
			r.Files = []File{}
		}
		if b, err := marshal(r); err == nil {
			out.Drafts = append(out.Drafts, b)
		}
	}
	b, _ := marshal(out)
	return string(b)
}

// marshal 同 json.Marshal，但不转义 <、>、&（同 Kotlin 写出来的）。
func marshal(v any) ([]byte, error) {
	var b bytes.Buffer
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(b.Bytes(), "\n"), nil
}

// Decode 读存盘的 JSON：坏的条目跳过，不让一条坏数据弄丢全部；同一个 key 只留第一份，空的不要（同 DraftRecords.decode）。
func Decode(raw string) []Draft {
	var root file
	if json.Unmarshal([]byte(raw), &root) != nil {
		return nil
	}
	seen := map[string]bool{}
	var out []Draft
	for _, item := range root.Drafts {
		r, ok := decodeDraft(item)
		if ok && !seen[r.Key] {
			seen[r.Key] = true
			out = append(out, r)
		}
	}
	return out
}

func decodeDraft(item json.RawMessage) (Draft, bool) {
	var o struct {
		Key       string            `json:"key"`
		Text      string            `json:"text"`
		Selection []int             `json:"selection"`
		Images    []json.RawMessage `json:"images"`
		Files     []json.RawMessage `json:"files"`
		At        int64             `json:"at"`
	}
	if json.Unmarshal(item, &o) != nil || o.Key == "" {
		return Draft{}, false
	}
	r := Draft{Key: o.Key, Text: o.Text, At: o.At}
	for _, raw := range o.Images {
		var im Image
		if json.Unmarshal(raw, &im) == nil && im.ID != "" {
			if im.Type == "" {
				im.Type = "image/jpeg"
			}
			r.Images = append(r.Images, im)
		}
	}
	for _, raw := range o.Files {
		var f File
		if json.Unmarshal(raw, &f) == nil && (strings.HasPrefix(f.Entry, "/") || strings.HasPrefix(f.Entry, "@")) {
			r.Files = append(r.Files, f)
		}
	}
	n := UTF16Len(r.Text)
	r.Selection = [2]int{n, n}
	for i := range min(len(o.Selection), 2) {
		r.Selection[i] = min(max(o.Selection[i], 0), n)
	}
	return r, !r.Empty()
}

// UTF16Len 是 s 有多少个 UTF-16 单位（Kotlin 字符串的长度，光标按它算）。
func UTF16Len(s string) int {
	n := 0
	for _, r := range s {
		n += utf16.RuneLen(r)
	}
	return n
}

// clipUTF16 是 s 最多 limit 个 UTF-16 单位的开头，不切开一个字。
func clipUTF16(s string, limit int) string {
	n := 0
	for i, r := range s {
		n += utf16.RuneLen(r)
		if n > limit {
			return s[:i]
		}
	}
	return s
}
