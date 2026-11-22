package ui

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"log"
	"maps"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// 输入草稿（同 Compose 版的 ComposerDrafts）：每个对话输入框里没发的字和附件，切到别的对话再回来、重开 Zeta 都还在。
// 按会话号保管；还没开始的新对话（没有会话号、也没有内容）共用一份，记在 settings.DraftNew 下，它拿到会话号时改记过去。
// 已经开始、还没有会话号的对话不存。存盘和 Compose 版共用设置目录里的 zeta_drafts.json（见 settings.Drafts）。
//
// 聊天窗格每换一个对话就是一个新的 chatPane：离开时把它的字和附件收进 live，进来时放回去（还在传的文件传完照样落进去）；
// live 里没有的从存盘的那份恢复：字和附件说明马上就有，图片在后台读回来，传上去的文件在后台核对还在不在。
// 每一帧看一眼正显示的那份变没变，变了就存盘（写盘在后台，攒着只写最新的）。

// heldDraft 是离开了的对话输入栏里的东西。
type heldDraft struct {
	text    string
	att     attachments
	backend string
}

// draftBook 保管各对话的草稿。只在主线程上用（写盘除外）。
type draftBook struct {
	store   *settings.Drafts
	records map[string]settings.Draft // 存盘的那份，按 key
	live    map[string]*heldDraft
	// key 是正显示的那个对话的草稿记在哪（空是不存），sig 是上次存时它的样子。
	key, sig string
	// ids 是各张图的内容编号（图片文件名），written 是已经写进图片目录的。
	ids     map[*draftImage]string
	written map[string]bool

	mu      sync.Mutex
	writing bool
	queued  *draftWrite
}

// draftWrite 是一次写盘：整份草稿和要补写的图片。
type draftWrite struct {
	list   []settings.Draft
	images map[string][]byte
}

// draftBook 取窗口的草稿，头一次用时从设置目录读出来。
func (a *App) draftBook() *draftBook {
	if a.drafts == nil {
		store, err := settings.OpenDrafts(a.hub.Settings().DraftsDir())
		if err != nil {
			log.Printf("zeta: 读草稿：%v", err)
		}
		b := &draftBook{store: store, records: map[string]settings.Draft{}, live: map[string]*heldDraft{},
			ids: map[*draftImage]string{}, written: map[string]bool{}}
		for _, r := range store.Load() {
			b.records[r.Key] = r
			for _, im := range r.Images {
				b.written[im.ID] = true
			}
		}
		a.drafts = b
	}
	return a.drafts
}

// draftKey 是窗格 p 的草稿记在哪：点开的会话号，新对话拿到的会话号；还没开始的新对话是 settings.DraftNew；开始了还没有会话号的为空（不存）。
func draftKey(p *chatPane) string {
	switch {
	case p.src.Session.ID != "":
		return p.src.Session.ID
	case p.state.Session != "":
		return p.state.Session
	case len(p.list.Items()) == 0:
		return settings.DraftNew
	}
	return ""
}

// leaveDraft 在聊天窗格离开对话 p 之前收起它的草稿。
func (a *App) leaveDraft(p *chatPane) {
	if p == nil {
		return
	}
	b := a.draftBook()
	a.trackDraft(p)
	if b.key == "" {
		return
	}
	att := p.att
	att.loading, att.problems, att.tooLarge = 0, nil, false // 还在读的图落不回来了，说明已经说过
	b.live[b.key] = &heldDraft{text: p.draft, att: att, backend: p.src.Backend}
	b.key = ""
}

// enterDraft 把对话 p 的草稿放回它的输入栏（窗格刚建好、还是空的时候调）。
func (a *App) enterDraft(p *chatPane) {
	b := a.draftBook()
	b.key = draftKey(p)
	if h := b.live[b.key]; h != nil {
		p.draft, p.att = h.text, h.att
		if h.backend != p.src.Backend {
			p.att.files = nil // 新对话换了设备：传到上一台、引用上一台路径的附件不能带过去
		}
	} else if r, ok := b.records[b.key]; ok {
		a.restoreDraft(p, r)
	}
	b.sig = draftSignature(p, b.ids)
}

// restoreDraft 从存盘的那份恢复：字和附件说明马上放回去，图片在后台读回来，传上去的文件在后台核对还在不在。
func (a *App) restoreDraft(p *chatPane, r settings.Draft) {
	b := a.drafts
	p.draft = r.Text
	var uploaded []string
	for _, f := range r.Files {
		p.att.files = append(p.att.files, &draftFile{id: p.att.id(), name: f.Label, size: -1,
			folder: strings.HasPrefix(f.Entry, "@") && strings.HasSuffix(f.Entry, "/"), entry: f.Entry})
		if !strings.HasPrefix(f.Entry, "@") {
			uploaded = append(uploaded, f.Entry)
		}
	}
	images := r.Images
	check := a.hub.PathChecker(p.src.Backend)
	p.att.loading += len(images)
	a.async(func() func() {
		var restored []*draftImage
		var ids []string // 各张存着的编号
		for _, im := range images {
			data, err := b.store.Image(im.ID)
			if err != nil {
				continue
			}
			d := &draftImage{image: chat.Image{MediaType: im.Type, Data: base64.StdEncoding.EncodeToString(data)}}
			d.thumb, _ = ui.DecodeBitmap(data)
			restored, ids = append(restored, d), append(ids, im.ID)
		}
		// 查不成（连不上）时当作都还在
		missing := map[string]bool{}
		for _, entry := range uploaded {
			if kind, err := check(entry); err == nil && kind == hostlink.PathMissing {
				missing[entry] = true
			}
		}
		return func() {
			p.att.loading -= len(images)
			for i, d := range restored {
				b.ids[d] = ids[i] // 编号沿用存着的（Compose 版存的也是），不重算
				d.id = p.att.id()
				p.att.images = append(p.att.images, d)
			}
			p.att.files = removeWhere(p.att.files, func(f *draftFile) bool { return missing[f.entry] })
		}
	})
}

// trackDraft 每一帧看一眼正显示的对话 p 的草稿：记的地方换了（新对话拿到了会话号）就挪过去，样子变了就存盘。
func (a *App) trackDraft(p *chatPane) {
	b := a.draftBook()
	key := draftKey(p)
	if key != b.key {
		if b.key != "" {
			delete(b.records, b.key)
			delete(b.live, b.key)
		}
		b.key, b.sig = key, "\x00" // 挪到了新地方，照样写一次
	}
	if key == "" {
		return
	}
	sig := draftSignature(p, b.ids)
	if sig == b.sig {
		return
	}
	b.sig = sig
	r := draftRecord(p, key, b.ids, a.now())
	if r.Empty() {
		delete(b.records, key)
	} else {
		b.records[key] = r
	}
	a.saveDrafts(p)
}

// saveDrafts 把整份草稿交给后台写盘：裁到上限以内，补上还没写过的图片。前一次还在写时攒着，写完接着写最新的那份。
func (a *App) saveDrafts(p *chatPane) {
	b := a.drafts
	b.forgetImages(p)
	kept := settings.FitDrafts(slices.Collect(maps.Values(b.records)))
	clear(b.records)
	images := map[string][]byte{}
	for _, r := range kept {
		b.records[r.Key] = r
		for _, im := range r.Images {
			if !b.written[im.ID] {
				images[im.ID] = b.imageData(im.ID)
				b.written[im.ID] = true
			}
		}
	}
	b.mu.Lock()
	if q := b.queued; q != nil {
		maps.Copy(images, q.images) // 上一份还没写就被这份盖掉：它要补写的图片并过来
	}
	b.queued = &draftWrite{list: kept, images: images}
	if b.writing {
		b.mu.Unlock()
		return
	}
	b.writing = true
	b.mu.Unlock()
	a.run(b.writeQueued)
}

// writeQueued 在后台把攒着的写盘，直到没有新的。
func (b *draftBook) writeQueued() {
	for {
		b.mu.Lock()
		w := b.queued
		b.queued = nil
		if w == nil {
			b.writing = false
			b.mu.Unlock()
			return
		}
		b.mu.Unlock()
		if _, err := b.store.Save(w.list, w.images); err != nil {
			log.Printf("zeta: 存草稿：%v", err)
		}
	}
}

// imageData 是编号 id 的那张图的内容（正显示的、或收着的输入栏里的）。
func (b *draftBook) imageData(id string) []byte {
	for d, known := range b.ids {
		if known == id {
			data, _ := base64.StdEncoding.DecodeString(d.image.Data)
			return data
		}
	}
	return nil
}

// imageID 是一张图的编号：内容的哈希（同一张图存一份），算过的记着。
func imageID(d *draftImage, ids map[*draftImage]string) string {
	if id, ok := ids[d]; ok {
		return id
	}
	sum := sha256.Sum256([]byte(d.image.Data))
	id := hex.EncodeToString(sum[:16])
	ids[d] = id
	return id
}

// draftSignature 是输入栏此刻的样子（字、图、传好了的文件），变了才存。
func draftSignature(p *chatPane, ids map[*draftImage]string) string {
	var s strings.Builder
	s.WriteString(p.draft)
	for _, d := range p.att.images {
		s.WriteString("\x00i" + imageID(d, ids))
	}
	for _, f := range p.att.files {
		if f.entry != "" {
			s.WriteString("\x00f" + f.entry)
		}
	}
	return s.String()
}

// draftRecord 是输入栏要存盘的那份：还在传、没传成的文件不存。
func draftRecord(p *chatPane, key string, ids map[*draftImage]string, at time.Time) settings.Draft {
	r := settings.Draft{Key: key, Text: p.draft, At: at.UnixMilli()}
	n := utf16Count(p.draft)
	r.Selection = [2]int{n, n}
	for _, d := range p.att.images {
		r.Images = append(r.Images, settings.DraftImage{ID: imageID(d, ids), Type: d.image.MediaType,
			Bytes: int64(len(d.image.Data)) * 3 / 4})
	}
	for _, f := range p.att.files {
		if f.entry != "" {
			r.Files = append(r.Files, settings.DraftFile{Entry: f.entry, Label: f.name})
		}
	}
	return r
}

func utf16Count(s string) int {
	n := 0
	for _, r := range s {
		n++
		if r >= 0x10000 {
			n++
		}
	}
	return n
}

// forgetImages 丢掉不在正显示的输入栏 p 和收着的草稿里的图的编号。
func (b *draftBook) forgetImages(p *chatPane) {
	inUse := map[*draftImage]bool{}
	for _, d := range p.att.images {
		inUse[d] = true
	}
	for _, h := range b.live {
		for _, d := range h.att.images {
			inUse[d] = true
		}
	}
	maps.DeleteFunc(b.ids, func(d *draftImage, _ string) bool { return !inUse[d] })
}
