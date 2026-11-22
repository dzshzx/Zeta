package ui

import (
	"encoding/base64"
	"log"
	"maps"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/dzshzx/Zeta/client/drafts"
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

// 输入草稿（同手机版的 ComposerDrafts）：每个对话输入框里没发的字和附件，切到别的对话再回来、重开 Zeta 都还在。
// 按会话号保管；还没开始的新对话（没有会话号、也没有内容）共用一份，记在 drafts.NewKey 下，它拿到会话号时改记过去。
// 已经开始、还没有会话号的对话不存。存在设置目录里的 zeta_drafts.json（见 settings.Drafts）。
//
// 聊天窗格每换一个对话就是一个新的 chatPane：离开时把它的字和附件收进 live，进来时放回去（还在传的文件传完照样落进去）；
// live 里没有的从存盘的那份恢复：字和附件说明马上就有，图片在后台读回来，传上去的文件在后台核对还在不在。
// 每一帧看一眼正显示的那份变没变；变了以后停下约一秒再存盘，切换对话、关窗口时马上存。写盘在后台，攒着只写最新的。

// draftSaveDelay 是输入栏停下多久才存盘：打字时不必每个字都写一次。
const draftSaveDelay = time.Second

// draftBook 保管各对话的草稿。只在主线程上用（写盘除外）。
type draftBook struct {
	store   *settings.Drafts
	records map[string]drafts.Draft // 存盘的那份，按 key
	// live 是离开了的对话输入栏里的（按 key），liveOn 是它在哪台设备上。
	live   map[string]composerSnapshot
	liveOn map[string]string
	// key 是正显示的那个对话的草稿记在哪（空是不存），sig 是上次记下时它的样子。
	key, sig string
	// changed：这一帧记下了新的样子，due 从现在起重新算；due 是有没存的改动时该存盘的时刻（零值是没有）。
	changed bool
	due     time.Time

	mu      sync.Mutex
	idle    *sync.Cond // 写完了（writing 变假）
	writing bool
	queued  *draftWrite
}

// draftWrite 是一次写盘：整份草稿，和各张图的内容（按内容编号，base64；写盘时只解要补写的）。
type draftWrite struct {
	list   []drafts.Draft
	images map[string]string
}

// draftBook 取窗口的草稿，头一次用时从设置目录读出来。
func (a *App) draftBook() *draftBook {
	if a.drafts == nil {
		store, err := settings.OpenDrafts(a.hub.Settings().DraftsDir())
		if err != nil {
			log.Printf("zeta: 读草稿：%v", err)
		}
		b := &draftBook{store: store, records: map[string]drafts.Draft{}, live: map[string]composerSnapshot{}, liveOn: map[string]string{}}
		b.idle = sync.NewCond(&b.mu)
		for _, r := range store.Load() {
			b.records[r.Key] = r
		}
		a.drafts = b
	}
	return a.drafts
}

// draftKey 是窗格 p 的草稿记在哪：点开的会话号，新对话拿到的会话号；还没开始的新对话是 drafts.NewKey；开始了还没有会话号的为空（不存）。
func draftKey(p *chatPane) string {
	switch {
	case p.src.Session.ID != "":
		return p.src.Session.ID
	case p.state.Session != "":
		return p.state.Session
	case len(p.list.Items()) == 0:
		return drafts.NewKey
	}
	return ""
}

// composerDraft 是输入栏里算作草稿的那份：编辑消息时是开始编辑前的（编辑中的字不当草稿）。
func (p *chatPane) composerDraft() composerSnapshot {
	if e := p.acts.editing; e != nil {
		return e.saved
	}
	return p.snapshot()
}

// leaveDraft 在聊天窗格离开对话 p 之前收起它的草稿（正在编辑的消息不要了，输入栏放回编辑前的样子），有没存的改动马上存盘。
func (a *App) leaveDraft(p *chatPane) {
	if p == nil {
		return
	}
	b := a.draftBook()
	p.cancelEdit()
	a.trackDraft(p)
	if b.key != "" {
		b.live[b.key], b.liveOn[b.key] = p.snapshot(), p.src.Backend
		b.key = ""
	}
	if b.changed || !b.due.IsZero() {
		a.saveDrafts(false)
	}
}

// enterDraft 把对话 p 的草稿放回它的输入栏（窗格刚建好、还是空的时候调）。
func (a *App) enterDraft(p *chatPane) {
	b := a.draftBook()
	b.key = draftKey(p)
	if s, ok := b.live[b.key]; ok {
		if b.liveOn[b.key] != p.src.Backend {
			s.att.files = nil // 新对话换了设备：传到上一台、引用上一台路径的附件不能带过去
		}
		p.restore(s)
	} else if r, ok := b.records[b.key]; ok {
		a.restoreDraft(p, r)
	}
	b.sig = p.composerDraft().signature()
}

// restoreDraft 从存盘的那份恢复：字和附件说明马上放回去，图片在后台读回来，传上去的文件在后台核对还在不在。
func (a *App) restoreDraft(p *chatPane, r drafts.Draft) {
	store := a.drafts.store
	p.draft = r.Text
	var uploaded []string
	for _, f := range r.Files {
		p.att.files = append(p.att.files, p.att.newEntryFile(f.Entry, f.Label))
		if !strings.HasPrefix(f.Entry, "@") {
			uploaded = append(uploaded, f.Entry)
		}
	}
	images := r.Images
	check := a.hub.PathChecker(p.src.Backend)
	p.att.loading += len(images)
	a.async(func() func() {
		var restored []*draftImage
		for _, im := range images {
			data, err := store.Image(im.ID)
			if err != nil {
				continue
			}
			// 编号沿用存着的，不重算
			d := &draftImage{image: chat.Image{MediaType: im.Type, Data: base64.StdEncoding.EncodeToString(data)}, hash: im.ID}
			d.thumb, _ = ui.DecodeBitmap(data)
			restored = append(restored, d)
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
			for _, d := range restored {
				d.id = p.att.id()
				p.att.images = append(p.att.images, d)
			}
			p.att.files = removeWhere(p.att.files, func(f *draftFile) bool { return missing[f.entry] })
		}
	})
}

// trackDraft 每一帧看一眼正显示的对话 p 的草稿：记的地方换了（新对话拿到了会话号）就挪过去，样子变了就记下、等着存盘。
func (a *App) trackDraft(p *chatPane) {
	b := a.draftBook()
	key := draftKey(p)
	if key != b.key {
		if b.key != "" {
			delete(b.records, b.key)
			delete(b.live, b.key)
			delete(b.liveOn, b.key)
		}
		b.key, b.sig = key, "\x00" // 挪到了新地方，照样写一次
	}
	if key == "" {
		return
	}
	s := p.composerDraft()
	sig := s.signature()
	if sig == b.sig {
		return
	}
	b.sig = sig
	if r := s.record(key, a.now()); r.Empty() {
		delete(b.records, key)
	} else {
		b.records[key] = r
	}
	b.changed = true
}

// saveDraftsWhenDue 在每一帧末尾看草稿该不该存盘：样子变了以后停下 draftSaveDelay 才存，还没到时到点再画一帧。
func (a *App) saveDraftsWhenDue(c *ui.Context) {
	b := a.drafts
	if b == nil {
		return
	}
	now := c.Now()
	if b.changed {
		b.changed, b.due = false, now.Add(draftSaveDelay)
	}
	if b.due.IsZero() {
		return
	}
	if wait := b.due.Sub(now); wait > 0 {
		c.After(wait)
		return
	}
	a.saveDrafts(false)
}

// SaveDrafts 马上把正显示的对话的草稿存盘，等写完才返回（关窗口时）。
func (a *App) SaveDrafts() {
	if a.drafts == nil {
		return
	}
	if a.pane != nil {
		a.trackDraft(a.pane)
	}
	a.saveDrafts(true)
}

// saveDrafts 把整份草稿交给后台写盘（裁到上限以内、补上还没写过的图片都在后台做）。前一次还在写时攒着，写完接着写最新的那份。
// wait 时在这里写、等写完。
func (a *App) saveDrafts(wait bool) {
	b := a.drafts
	b.changed, b.due = false, time.Time{}
	images := map[string]string{}
	add := func(list []*draftImage) {
		for _, d := range list {
			images[d.contentHash()] = d.image.Data
		}
	}
	if a.pane != nil {
		add(a.pane.composerDraft().att.images)
	}
	for _, s := range b.live {
		add(s.att.images)
	}
	w := &draftWrite{list: slices.Collect(maps.Values(b.records)), images: images}
	b.mu.Lock()
	b.queued = w
	start := !b.writing
	b.writing = true
	b.mu.Unlock()
	switch {
	case start && wait:
		b.writeQueued()
	case start:
		a.run(b.writeQueued)
	case wait:
		b.mu.Lock()
		for b.writing {
			b.idle.Wait()
		}
		b.mu.Unlock()
	}
}

// writeQueued 把攒着的写盘，直到没有新的。
func (b *draftBook) writeQueued() {
	b.mu.Lock()
	for b.queued != nil {
		w := b.queued
		b.queued = nil
		b.mu.Unlock()
		if err := b.store.Save(w.list, w.image); err != nil {
			log.Printf("zeta: 存草稿：%v", err)
		}
		b.mu.Lock()
	}
	b.writing = false
	b.idle.Broadcast()
	b.mu.Unlock()
}

// image 是要补写的图片 id 的内容；不在这次的输入栏里的（早就写过了）是 nil。
func (w *draftWrite) image(id string) []byte {
	text, ok := w.images[id]
	if !ok {
		return nil
	}
	data, err := base64.StdEncoding.DecodeString(text)
	if err != nil {
		return nil
	}
	return data
}

// signature 是输入栏的这个样子（字、图、传好了的文件），变了才存。
func (s composerSnapshot) signature() string {
	var b strings.Builder
	b.WriteString(s.text)
	for _, d := range s.att.images {
		b.WriteString("\x00i" + d.contentHash())
	}
	for _, f := range s.att.files {
		if f.entry != "" {
			b.WriteString("\x00f" + f.entry)
		}
	}
	return b.String()
}

// record 是这个样子要存盘的那份：还在传、没传成的文件不存。
func (s composerSnapshot) record(key string, at time.Time) drafts.Draft {
	r := drafts.Draft{Key: key, Text: s.text, At: at.UnixMilli()}
	n := drafts.UTF16Len(s.text)
	r.Selection = [2]int{n, n}
	for _, d := range s.att.images {
		r.Images = append(r.Images, drafts.Image{ID: d.contentHash(), Type: d.image.MediaType, Bytes: int64(len(d.image.Data)) * 3 / 4})
	}
	for _, f := range s.att.files {
		if f.entry != "" {
			r.Files = append(r.Files, drafts.File{Entry: f.entry, Label: f.name})
		}
	}
	return r
}
