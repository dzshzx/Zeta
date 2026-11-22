package settings

import (
	"cmp"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"unicode/utf16"
)

// 各对话没发出的输入草稿，存法同 shared 的 core/ComposerDrafts.kt：设置目录里单独的 zeta_drafts.json 的 drafts 项
// （JSON 字符串），图片另存在 drafts/<id>，Compose 版存的这一版读得出来，反过来也一样。

// 草稿存在哪。
const (
	draftsFile = "zeta_drafts.json"
	keyDrafts  = "drafts"
	draftsDir  = "drafts"
)

// DraftNew 是还没开始的新对话那一份草稿的 key（别的都是会话号）。
const DraftNew = "new"

// 存盘的上限，同 DraftRecords。
const (
	MaxDrafts       = 30
	MaxDraftText    = 20_000 // 一份最多存这么多字（UTF-16 单位，同 Kotlin），再长的只存开头
	MaxDraftsText   = 60_000 // 所有草稿的字加起来
	MaxDraftsImages = 16 << 20
)

// Draft 是存盘的一份草稿。
type Draft struct {
	// Key 是会话号；还没开始的新对话是 DraftNew。
	Key  string `json:"key"`
	Text string `json:"text"`
	// Selection 是光标（UTF-16 单位，同 Kotlin 的 TextRange）。
	Selection [2]int       `json:"selection"`
	Images    []DraftImage `json:"images"`
	Files     []DraftFile  `json:"files"`
	// At 是最后存的时刻（Unix 毫秒）：超出上限时先丢最久没动的。
	At int64 `json:"at"`
}

// DraftImage 是草稿里的一张图：内容另存成文件 drafts/<ID>，这里只记类型和大约多少字节。
type DraftImage struct {
	ID    string `json:"id"`
	Type  string `json:"type"`
	Bytes int64  `json:"bytes"`
}

// DraftFile 是草稿里附带的一个设备上的路径：Entry 是附件说明（/路径，或 @路径、@文件夹/ 的引用），Label 是小标签上的名字。
type DraftFile struct {
	Entry string `json:"entry"`
	Label string `json:"label"`
}

// Empty：没东西可存。
func (d Draft) Empty() bool {
	return strings.TrimSpace(d.Text) == "" && len(d.Images) == 0 && len(d.Files) == 0
}

// Drafts 是存草稿的文件和图片目录。哪个 goroutine 上用都行，但同一时间只该有一个在写。
type Drafts struct {
	store *Store
	dir   string
}

// OpenDrafts 打开设置目录 dir 里的草稿。读不成时给一份空的能用的，同时返回原因。
func OpenDrafts(dir string) (*Drafts, error) {
	store, err := Open(filepath.Join(dir, draftsFile))
	return &Drafts{store: store, dir: filepath.Join(dir, draftsDir)}, err
}

// DraftsDir 是设置文件所在的目录（草稿和它放在一起）。
func (s *Settings) DraftsDir() string { return filepath.Dir(s.store.path) }

// Load 读出存着的草稿。
func (d *Drafts) Load() []Draft {
	text, _ := d.store.String(keyDrafts)
	return DecodeDrafts(text)
}

// Save 裁到上限以内存下 list，返回存下的那些；images 是要补写的图片内容（按 ID，已经写过的不再写），
// 不再用到的图片文件删掉。
func (d *Drafts) Save(list []Draft, images map[string][]byte) ([]Draft, error) {
	kept := FitDrafts(list)
	var err error
	if len(kept) == 0 {
		err = d.store.Delete(keyDrafts)
	} else {
		err = d.store.SetString(keyDrafts, EncodeDrafts(kept))
	}
	wanted := map[string]bool{}
	for _, r := range kept {
		for _, im := range r.Images {
			if imageName.MatchString(im.ID) {
				wanted[im.ID] = true
			}
		}
	}
	return kept, errors.Join(err, d.syncImages(images, wanted))
}

// Image 读草稿里图片 id 的内容。
func (d *Drafts) Image(id string) ([]byte, error) {
	if !imageName.MatchString(id) {
		return nil, fs.ErrNotExist
	}
	return os.ReadFile(filepath.Join(d.dir, id))
}

// imageName 是能当文件名的图片 ID，别的不存（同 Kotlin）。
var imageName = regexp.MustCompile(`^[A-Za-z0-9-]{1,64}$`)

// syncImages 补写 images 里还没有的、删掉不在 wanted 里的图片文件。
func (d *Drafts) syncImages(images map[string][]byte, wanted map[string]bool) error {
	var errs []error
	if len(wanted) > 0 {
		errs = append(errs, os.MkdirAll(d.dir, 0o700))
	}
	for id := range wanted {
		data, ok := images[id]
		file := filepath.Join(d.dir, id)
		if _, err := os.Stat(file); !ok || err == nil {
			continue
		}
		errs = append(errs, os.WriteFile(file, data, 0o600))
	}
	entries, err := os.ReadDir(d.dir)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		errs = append(errs, err)
	}
	for _, e := range entries {
		if !wanted[e.Name()] {
			errs = append(errs, os.Remove(filepath.Join(d.dir, e.Name())))
		}
	}
	return errors.Join(errs...)
}

// FitDrafts 去掉空的，按最近改动排，裁到上限以内：新的先占额度（同 DraftRecords.fit）。
func FitDrafts(list []Draft) []Draft {
	sorted := slices.Clone(list)
	slices.SortStableFunc(sorted, func(a, b Draft) int { return cmp.Compare(b.At, a.At) })
	textLeft, imagesLeft := MaxDraftsText, int64(MaxDraftsImages)
	var kept []Draft
	for _, r := range sorted {
		if len(kept) >= MaxDrafts {
			break
		}
		if r.Empty() {
			continue
		}
		r.Text = clipUTF16(r.Text, min(MaxDraftText, textLeft))
		textLeft -= utf16Len(r.Text)
		var images []DraftImage
		for _, im := range r.Images {
			if im.Bytes <= imagesLeft {
				imagesLeft -= im.Bytes
				images = append(images, im)
			}
		}
		r.Images = images
		n := utf16Len(r.Text)
		r.Selection = [2]int{min(max(r.Selection[0], 0), n), min(max(r.Selection[1], 0), n)}
		if !r.Empty() {
			kept = append(kept, r)
		}
	}
	return kept
}

// draftsJSON 是存盘的整份。
type draftsJSON struct {
	Version int               `json:"version"`
	Drafts  []json.RawMessage `json:"drafts"`
}

// EncodeDrafts 写成存盘的 JSON（同 DraftRecords.encode）。
func EncodeDrafts(list []Draft) string {
	out := draftsJSON{Version: 1, Drafts: []json.RawMessage{}}
	for _, r := range list {
		if r.Images == nil {
			r.Images = []DraftImage{}
		}
		if r.Files == nil {
			r.Files = []DraftFile{}
		}
		b, err := marshal(r)
		if err == nil {
			out.Drafts = append(out.Drafts, b)
		}
	}
	b, _ := marshal(out)
	return string(b)
}

// DecodeDrafts 读存盘的 JSON：坏的条目跳过，不让一条坏数据弄丢全部；同一个 key 只留第一份，空的不要（同 DraftRecords.decode）。
func DecodeDrafts(raw string) []Draft {
	var root draftsJSON
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
		var im DraftImage
		if json.Unmarshal(raw, &im) == nil && im.ID != "" {
			if im.Type == "" {
				im.Type = "image/jpeg"
			}
			r.Images = append(r.Images, im)
		}
	}
	for _, raw := range o.Files {
		var f DraftFile
		if json.Unmarshal(raw, &f) != nil || !(strings.HasPrefix(f.Entry, "/") || strings.HasPrefix(f.Entry, "@")) {
			continue
		}
		if f.Label == "" {
			f.Label = EntryLabel(f.Entry)
		}
		r.Files = append(r.Files, f)
	}
	n := utf16Len(r.Text)
	r.Selection = [2]int{n, n}
	for i := range min(len(o.Selection), 2) {
		r.Selection[i] = min(max(o.Selection[i], 0), n)
	}
	return r, !r.Empty()
}

// EntryLabel 是附件说明的显示名：路径的最后一段（同 AttachedPaths.label）。
func EntryLabel(entry string) string {
	p := strings.TrimPrefix(entry, "@")
	if name := path.Base(strings.TrimRight(p, "/")); name != "." && name != "/" {
		return name
	}
	return "/"
}

func utf16Len(s string) int {
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
