package settings

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"

	"github.com/dzshzx/Zeta/client/drafts"
)

// 各对话没发出的输入草稿的存盘，存法同 shared 的 core/ComposerDrafts.kt：设置目录里单独的 zeta_drafts.json 的 drafts 项
// （JSON 字符串，格式和上限见 client/drafts），图片另存在 drafts/<id>。

// 草稿存在哪。
const (
	draftsFile = "zeta_drafts.json"
	keyDrafts  = "drafts"
	draftsDir  = "drafts"
)

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
func (d *Drafts) Load() []drafts.Draft {
	text, _ := d.store.String(keyDrafts)
	return drafts.Decode(text)
}

// Save 裁到上限以内存下 list。图片文件只补写还没有的，内容向 image 要（按 ID；给 nil 的不写）；不再用到的图片文件删掉。
func (d *Drafts) Save(list []drafts.Draft, image func(id string) []byte) error {
	kept := drafts.Fit(list)
	var err error
	if len(kept) == 0 {
		err = d.store.Delete(keyDrafts)
	} else {
		err = d.store.SetString(keyDrafts, drafts.Encode(kept))
	}
	wanted := map[string]bool{}
	for _, r := range kept {
		for _, im := range r.Images {
			if imageName.MatchString(im.ID) {
				wanted[im.ID] = true
			}
		}
	}
	return errors.Join(err, d.syncImages(image, wanted))
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

// syncImages 补写 wanted 里还没有的、删掉不在 wanted 里的图片文件。
func (d *Drafts) syncImages(image func(id string) []byte, wanted map[string]bool) error {
	var errs []error
	if len(wanted) > 0 {
		errs = append(errs, os.MkdirAll(d.dir, 0o700))
	}
	for id := range wanted {
		file := filepath.Join(d.dir, id)
		if _, err := os.Stat(file); err == nil {
			continue
		}
		if data := image(id); data != nil {
			errs = append(errs, os.WriteFile(file, data, 0o600))
		}
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
