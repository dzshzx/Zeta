package mobile

// 输入栏（docs/ios.md「输入栏」）：草稿、附件、斜杠命令、模型与思考强度与权限模式、排队、用量与额度、常用指令。
// 规矩照桌面版（windows/internal/ui 的 composer.go、attachments.go、drafts.go、slash.go、models.go、savedprompts.go、usageturns.go），
// 这里只把它们调中枢的那一半搬过来；画法在 Swift 的 Views/Composer。
//
// 输入框里的字归 Swift 管：每改一次经 composer.draft 交过来（不算变化），这边记下当草稿存盘、算斜杠命令的提示；
// 这边改了字（撤回的消息放回来、没发出去放回来、换了对话恢复草稿）时 rev 加一，界面见 rev 变了就换上 text。
// 光标都按 UTF-16 算（Swift 的 String.utf16，也是草稿存盘的单位）。

import (
	"cmp"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"log"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf16"

	"github.com/dzshzx/Zeta/client/drafts"
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/helper/tasks"
	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/hub"
	"github.com/dzshzx/Zeta/hub/attach"
	"github.com/dzshzx/Zeta/hub/chat"
	"github.com/dzshzx/Zeta/hub/host"
	"github.com/dzshzx/Zeta/hub/locales"
	"github.com/dzshzx/Zeta/hub/settings"
)

// comp 是聊天页的输入栏，第一次用时建（核心 goroutine 上）。
func (c *Core) comp() *composer {
	if c.composer == nil {
		c.composer = &composer{core: c, models: map[string]*modelSource{}}
	}
	return c.composer
}

// composer 是聊天页输入栏的状态，跟着聊天页里的对话走：换了对话时把这一份收进草稿，换上那个对话的。
type composer struct {
	core    *Core
	conv    *chat.Conversation // 现在跟着的对话
	backend string

	text   string
	rev    int
	files  []*upFile
	images []*inlineImage
	next   int
	// problems 是没收进来的原因，界面弹一次（composer.problems 清掉）。
	problems []string
	tooLarge bool

	typing bool   // 上一次光标前是「/…」（只在从不是变成是时让核心起进程）
	taking bool   // 正在取撤回的那条
	took   string // 取过的那条的 uuid

	book   *draftBook
	models map[string]*modelSource
}

// upFile 是一个待发的文件：正在传、传好了（entry 是消息里带的主机路径）或没传成；图片另有缩小图（shrunk）。
type upFile struct {
	id    int
	name  string
	size  int64 // 不知道是 -1
	local string
	entry string
	// uploaded 是这份草稿自己传到设备上的路径（撤回放回的、存盘恢复的没有）：没发就移除时把那边的删掉。
	uploaded string
	failed   string
	sent     int64
	encoding bool
	shrunk   *inlineImage
	cancel   context.CancelFunc
}

func (f *upFile) uploading() bool { return f.entry == "" && f.failed == "" }

// inlineImage 是随消息内联发的一张图：单独的（撤回的消息、草稿里的），或图片文件的缩小图。
type inlineImage struct {
	id    int
	image coreapi.Image
	hash  string
}

// contentHash 同桌面版：内容的哈希，草稿里存图的文件名。
func (im *inlineImage) contentHash() string {
	if im.hash == "" {
		sum := sha256.Sum256(im.image.Data)
		im.hash = hex.EncodeToString(sum[:16])
	}
	return im.hash
}

// thumbPath 把图写进临时目录，答路径给界面画缩略图（同一张只写一次）。写不成时为空，界面画灰块。
func (im *inlineImage) thumbPath() string {
	dir := filepath.Join(os.TempDir(), "zeta-composer")
	file := filepath.Join(dir, im.contentHash())
	if _, err := os.Stat(file); err == nil {
		return file
	}
	if os.MkdirAll(dir, 0o700) != nil || os.WriteFile(file, im.image.Data, 0o600) != nil {
		return ""
	}
	return file
}

func (m *composer) id() int { m.next++; return m.next }

func (m *composer) report(why string, tooLarge bool) {
	m.problems = append(m.problems, why)
	m.tooLarge = m.tooLarge || tooLarge
}

func (m *composer) uploading() bool {
	return slices.ContainsFunc(m.files, func(f *upFile) bool { return f.uploading() || f.encoding })
}

func (m *composer) entries() []string {
	var out []string
	for _, f := range m.files {
		if f.entry != "" {
			out = append(out, f.entry)
		}
	}
	return out
}

// pictures 同桌面版：单独的图，和传好了的图片文件的缩小图（Original 是它的主机路径）。
func (m *composer) pictures() []coreapi.Image {
	var out []coreapi.Image
	for _, im := range m.images {
		out = append(out, im.image)
	}
	for _, f := range m.files {
		if f.entry != "" && f.shrunk != nil {
			img := f.shrunk.image
			img.Original = f.entry
			out = append(out, img)
		}
	}
	return out
}

// setText 是这边改了输入框里的字：界面见 rev 变了就换上。
func (m *composer) setText(text string) {
	m.text = text
	m.rev++
}

// sync 让输入栏跟上聊天页里的对话：换了对话就收起上一个的草稿、换上这一个的。核心 goroutine 上调。
func (m *composer) sync() *hub.Chat {
	ch := m.core.hub.Chat()
	var conv *chat.Conversation
	if ch != nil {
		conv = ch.Conv
	}
	if conv != m.conv {
		m.leave()
		m.enter(ch)
	}
	if conv != nil {
		m.book.track(m, ch, conv.State())
	}
	return ch
}

// remove 从输入栏拿掉一个待发的：在传的取消，这份草稿自己传好的那份在后台从设备上删掉。
func (m *composer) remove(id int) {
	m.images = slices.DeleteFunc(m.images, func(im *inlineImage) bool { return im.id == id })
	m.files = slices.DeleteFunc(m.files, func(f *upFile) bool {
		if f.id != id {
			return false
		}
		if f.cancel != nil {
			f.cancel()
		}
		if f.uploaded != "" {
			go m.core.hub.UploadRemover(m.backend)(f.uploaded)
		}
		return true
	})
}

// ---- 附件（同桌面版 attachments.go）----

// pickedFile 是界面选好、写进 App 临时目录的一个文件：Path 的最后一段就是传上去的名字。
type pickedFile struct {
	Path string `json:"path"`
}

func (m *composer) addPaths(list []pickedFile) {
	if len(list) > attach.MaxFiles {
		m.report(locales.MobileAttachTooMany(attach.MaxFiles, len(list)-attach.MaxFiles), false)
		list = list[:attach.MaxFiles]
	}
	for _, p := range list {
		name := filepath.Base(p.Path)
		st, err := os.Stat(p.Path)
		if err != nil || st.IsDir() {
			m.report(locales.MobileAttachUnreadable(name), false)
			continue
		}
		m.uploadFile(p.Path, name, st.Size())
	}
}

func (m *composer) uploadFile(local, name string, size int64) {
	if attach.TooLarge(size) {
		m.report(attach.TooLargeReason(name, size), true)
		return
	}
	f := &upFile{name: name, size: size, local: local, encoding: attach.IsImage(name)}
	m.startUpload(f)
	if !f.encoding {
		return
	}
	c := m.core
	go func() {
		im, err := shrink(local)
		c.post(func() {
			f.encoding = false
			if err == nil {
				f.shrunk = im
			}
		})
	}()
}

// encodeSlots 同桌面版：同时最多做两张缩小图，免得一次选很多大图时整张解码占满内存。
var encodeSlots = make(chan struct{}, 2)

func shrink(local string) (*inlineImage, error) {
	encodeSlots <- struct{}{}
	defer func() { <-encodeSlots }()
	data, err := os.ReadFile(local)
	if err != nil {
		return nil, err
	}
	img, err := attach.EncodeImage(data)
	if err != nil {
		return nil, err
	}
	return &inlineImage{image: img}, nil
}

func (m *composer) startUpload(f *upFile) {
	c := m.core
	ctx, cancel := context.WithCancel(context.Background())
	f.id, f.cancel = m.id(), cancel
	m.files = append(m.files, f)
	upload := c.hub.Uploader(m.backend)
	u := host.Upload{Local: f.local, Limit: attach.MaxBytes}
	go func() {
		shown := int64(-1)
		path, err := upload(ctx, u, func(n int64) {
			// 按百分之一报回来，大文件不每一块都叫界面重画
			if f.size > 0 && n*100/f.size != shown {
				shown = n * 100 / f.size
				c.post(func() { f.sent = n })
			}
		})
		c.post(func() { m.uploaded(f, path, err) })
	}()
}

func (m *composer) uploaded(f *upFile, path string, err error) {
	f.cancel()
	switch {
	case errors.Is(err, context.Canceled):
	case errors.Is(err, hostlink.ErrTooLarge):
		m.remove(f.id)
		m.report(attach.TooLargeReason(f.name, -1), true)
	case err != nil:
		f.failed = locales.ErrorText(err)
	default:
		f.entry, f.uploaded = path, path
	}
}

// putBack 同桌面版：撤回的、没发出去的一条放回输入栏，字接在已有的前面。
func (m *composer) putBack(msg coreapi.Message) {
	if msg.Text != "" {
		text := msg.Text
		if strings.TrimSpace(m.text) != "" {
			text += "\n" + m.text
		}
		m.setText(text)
	}
	for _, entry := range msg.Files {
		m.entryFile(entry, "")
	}
	for _, img := range msg.Images {
		if img.Original != "" {
			if f := m.entryFile(img.Original, ""); f.shrunk == nil {
				f.shrunk = &inlineImage{image: img}
			}
			continue
		}
		if len(m.images) < attach.MaxImages {
			m.images = append(m.images, &inlineImage{id: m.id(), image: img})
		}
	}
}

func (m *composer) entryFile(entry, name string) *upFile {
	if i := slices.IndexFunc(m.files, func(f *upFile) bool { return f.entry == entry }); i >= 0 {
		return m.files[i]
	}
	f := &upFile{id: m.id(), name: cmp.Or(name, attach.EntryName(entry)), size: -1, entry: entry}
	m.files = append(m.files, f)
	return f
}

// takeRecalled 同桌面版：核心说有一条撤回放回输入框的，取走放回来（同一条只取一次）。
func (m *composer) takeRecalled(s chat.State) {
	r := s.Recalled
	if r == nil || m.taking || r.UUID == m.took {
		return
	}
	m.taking, m.took = true, r.UUID
	c := m.core
	c.hub.RunChatThen(func(ctx context.Context, conv *chat.Conversation) func() {
		msg, err := conv.TakeRecalled(ctx)
		return func() {
			m.taking = false
			if msg != nil {
				m.putBack(*msg)
			}
			if err != nil {
				c.problem = locales.ErrorText(err)
			}
		}
	})
}

// send 发出输入框里的 text 和传好了的附件；没东西可发、还有在传的时不发。没发出去时字和附件放回输入栏，原因进 chat.failed。
func (m *composer) send(text string) bool {
	ch := m.core.hub.Chat()
	if ch == nil || ch.Conv == nil || ch.CwdProblem != "" || m.uploading() {
		return false
	}
	msg := coreapi.Message{Text: text, Images: m.pictures(), Files: m.entries()}
	if strings.TrimSpace(text) == "" && len(msg.Images) == 0 && len(msg.Files) == 0 {
		return false
	}
	m.images, m.files, m.text = nil, nil, ""
	c := m.core
	c.problem = ""
	c.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error {
		return conv.Send(ctx, coreapi.Outgoing{Text: msg.Text, Images: msg.Images, Files: msg.Files})
	}, func(err error) {
		c.problem = locales.ErrorText(err)
		m.putBack(msg)
	})
	return true
}

// ---- 草稿（同桌面版 drafts.go）----

// draftSaveDelay 同桌面版：停下这么久才存盘。
const draftSaveDelay = time.Second

type snapshot struct {
	text   string
	files  []*upFile
	images []*inlineImage
}

func (m *composer) snapshot() snapshot { return snapshot{m.text, m.files, m.images} }

type draftBook struct {
	store   *settings.Drafts
	records map[string]drafts.Draft
	live    map[string]snapshot
	liveOn  map[string]string
	key     string
	sig     string
	due     bool // 定了一次存盘，还没存

	mu      sync.Mutex // 写盘：gen 是最后一次交出去的，written 是已经写下的（晚到的旧的不写）
	gen     int
	written int
}

func (m *composer) drafts() *draftBook {
	if m.book == nil {
		store, err := settings.OpenDrafts(m.core.hub.Settings().DraftsDir())
		if err != nil {
			log.Printf("zeta: 读草稿：%v", err)
		}
		b := &draftBook{store: store, records: map[string]drafts.Draft{}, live: map[string]snapshot{}, liveOn: map[string]string{}}
		for _, r := range store.Load() {
			b.records[r.Key] = r
		}
		m.book = b
	}
	return m.book
}

func draftKey(ch *hub.Chat, s chat.State) string {
	switch {
	case ch.Session.ID != "":
		return ch.Session.ID
	case s.Session != "":
		return s.Session
	case len(ch.Conv.Items()) == 0:
		return drafts.NewKey
	}
	return ""
}

// leave 收起正跟着的对话的草稿，马上存盘。
func (m *composer) leave() {
	b := m.drafts()
	if m.conv != nil && b.key != "" {
		b.live[b.key], b.liveOn[b.key] = m.snapshot(), m.backend
	}
	b.key = ""
	if b.due {
		m.save()
	}
	m.conv, m.files, m.images, m.problems, m.tooLarge, m.typing = nil, nil, nil, nil, false, false
	m.setText("")
}

// enter 换上对话 ch 的草稿：离开时收着的，没有时从存盘的那份恢复（传上去的文件在后台核对还在不在）。
func (m *composer) enter(ch *hub.Chat) {
	if ch == nil || ch.Conv == nil {
		return
	}
	b := m.drafts()
	m.conv, m.backend = ch.Conv, ch.Backend
	b.key = draftKey(ch, ch.Conv.State())
	if s, ok := b.live[b.key]; ok {
		if b.liveOn[b.key] != ch.Backend {
			s.files = nil // 新对话换了设备：传到上一台的附件带不过去
		}
		m.files, m.images = s.files, s.images
		m.setText(s.text)
	} else if r, ok := b.records[b.key]; ok {
		m.restore(r)
	}
	b.sig = m.signature()
}

func (m *composer) restore(r drafts.Draft) {
	m.setText(r.Text)
	var uploaded []string
	for _, f := range r.Files {
		m.files = append(m.files, &upFile{id: m.id(), name: cmp.Or(f.Label, attach.EntryName(f.Entry)), size: -1, entry: f.Entry})
		if !strings.HasPrefix(f.Entry, "@") {
			uploaded = append(uploaded, f.Entry)
		}
	}
	for _, im := range r.Images {
		if data, err := m.book.store.Image(im.ID); err == nil {
			m.images = append(m.images, &inlineImage{id: m.id(), image: coreapi.Image{MediaType: im.Type, Data: data}, hash: im.ID})
		}
	}
	if len(uploaded) == 0 {
		return
	}
	check, conv, c := m.core.hub.PathChecker(m.backend), m.conv, m.core
	go func() {
		missing := map[string]bool{}
		for _, entry := range uploaded {
			if kind, err := check(entry); err == nil && kind == hostlink.PathMissing { // 查不成时当作还在
				missing[entry] = true
			}
		}
		c.post(func() {
			if m.conv == conv {
				m.files = slices.DeleteFunc(m.files, func(f *upFile) bool { return missing[f.entry] })
			}
		})
	}()
}

func (m *composer) signature() string {
	var b strings.Builder
	b.WriteString(m.text)
	for _, im := range m.images {
		b.WriteString("\x00i" + im.contentHash())
	}
	for _, f := range m.files {
		if f.entry != "" {
			b.WriteString("\x00f" + f.entry)
		}
	}
	return b.String()
}

// track 看正跟着的对话的草稿：记的地方换了（新对话拿到了会话号）就挪过去，样子变了就记下、约一秒后存盘。
func (b *draftBook) track(m *composer, ch *hub.Chat, s chat.State) {
	key := draftKey(ch, s)
	if key != b.key {
		if b.key != "" {
			delete(b.records, b.key)
			delete(b.live, b.key)
			delete(b.liveOn, b.key)
		}
		b.key, b.sig = key, "\x00"
	}
	if key == "" {
		return
	}
	sig := m.signature()
	if sig == b.sig {
		return
	}
	b.sig = sig
	r := drafts.Draft{Key: key, Text: m.text, At: time.Now().UnixMilli()}
	n := drafts.UTF16Len(m.text)
	r.Selection = [2]int{n, n}
	for _, im := range m.images {
		r.Images = append(r.Images, drafts.Image{ID: im.contentHash(), Type: im.image.MediaType, Bytes: int64(len(im.image.Data))})
	}
	for _, f := range m.files {
		if f.entry != "" {
			r.Files = append(r.Files, drafts.File{Entry: f.entry, Label: f.name})
		}
	}
	if r.Empty() {
		delete(b.records, key)
	} else {
		b.records[key] = r
	}
	if !b.due {
		b.due = true
		time.AfterFunc(draftSaveDelay, func() { m.core.post(m.saveIfDue) })
	}
}

func (m *composer) saveIfDue() {
	if m.book != nil && m.book.due {
		m.save()
	}
}

// save 把整份草稿交给后台写盘（核心 goroutine 上调），返回等它写完的办法。
func (m *composer) save() (wait func()) {
	b := m.drafts()
	b.due = false
	images := map[string][]byte{}
	add := func(list []*inlineImage) {
		for _, im := range list {
			images[im.contentHash()] = im.image.Data
		}
	}
	add(m.images)
	for _, s := range b.live {
		add(s.images)
	}
	list := slices.Collect(maps.Values(b.records))
	b.mu.Lock()
	b.gen++
	gen := b.gen
	b.mu.Unlock()
	done := make(chan struct{})
	go func() {
		defer close(done)
		b.mu.Lock()
		defer b.mu.Unlock()
		if gen < b.written {
			return
		}
		b.written = gen
		if err := b.store.Save(list, func(id string) []byte { return images[id] }); err != nil {
			log.Printf("zeta: 存草稿：%v", err)
		}
	}()
	return func() { <-done }
}

// ---- 斜杠命令（同桌面版 slash.go，没有桌面版那两条设置命令：iOS 的设置页另有入口）----

// u16ToRunes、runesToU16 换算光标：界面按 UTF-16，chat.SlashAt 按字符。
func u16ToRunes(text string, at int) int {
	n, units := 0, 0
	for _, r := range text {
		if units >= at {
			break
		}
		units += utf16.RuneLen(r)
		n++
	}
	return n
}

func runesToU16(text string, at int) int {
	units := 0
	for i, r := range []rune(text) {
		if i >= at {
			break
		}
		units += utf16.RuneLen(r)
	}
	return units
}

// Sent 是 composer.send 的结果：发出去了（字和附件从输入栏拿掉）；Open 不为空时没发，是要打开设置页 config 或 permissions。
type Sent struct {
	Sent bool   `json:"sent"`
	Open string `json:"open,omitempty"`
}

// SlashView 是输入框上方要提示的斜杠命令：没在打时 commands 为空。
type SlashView struct {
	Commands []chat.Command `json:"commands"`
}

// typed 记下输入框里的字、按光标算斜杠命令的提示；开始打 / 而对话没接着进程时让核心先起好（同桌面版 prepareForSlash）。
func (m *composer) typed(text string, caret int) SlashView {
	ch := m.sync()
	m.text = text
	out := SlashView{Commands: []chat.Command{}}
	if ch == nil || ch.Conv == nil {
		return out
	}
	s := ch.Conv.State()
	m.book.track(m, ch, s)
	slash, typing := chat.SlashAt(text, u16ToRunes(text, caret))
	start := typing && !m.typing
	m.typing = typing
	if start && ch.CwdProblem == "" && !s.Process && !s.Blocked {
		m.core.hub.RunChat(func(ctx context.Context, conv *chat.Conversation) error { return conv.Prepare(ctx) }, nil)
	}
	if typing {
		out.Commands = append(out.Commands, chat.Suggest(s.Commands, slash.Prefix, chat.SettingsCommandsFor(s.Harness)...)...)
	}
	return out
}

// ---- 模型（同桌面版 models.go 输入栏那一半）----

const (
	modelsTimeout = time.Minute
	modelsTTL     = 5 * time.Minute
	modelsRetry   = 30 * time.Second
)

// modelSource 是一台设备一个工具（claude 或 agy）的模型来源：生效中转的目录（config.models.read）和工具报的清单。账号设置换了版本就换新的。
type modelSource struct {
	tool     string
	revision uint64
	ready    bool
	pending  bool
	readAt   time.Time
	catalog  coreapi.ModelCatalog
	harness  []chat.ModelOption
}

func (m *composer) source(backend, tool string, r *hub.Remote) *modelSource {
	h := sessionproto.NormalizeHarness(tool)
	key := backend + ":" + h
	acct := r.AccountsFor(h)
	s := m.models[key]
	if s == nil || s.revision != acct.ModelRevision() {
		s = &modelSource{tool: h, revision: acct.ModelRevision()}
		m.models[key] = s
	}
	ttl := modelsTTL
	if !s.ready || s.catalog.Relay != "" && len(s.catalog.Models) == 0 {
		ttl = modelsRetry
	}
	if s.pending || !s.readAt.IsZero() && time.Since(s.readAt) < ttl {
		return s
	}
	s.pending = true
	core, c := r.CoreFor(h), m.core
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), modelsTimeout)
		defer cancel()
		catalog, err := core.ModelCatalog(ctx)
		c.post(func() {
			s.catalog, s.pending, s.readAt = catalog, false, time.Now()
			s.ready = err == nil || catalog.Relay != ""
		})
	}()
	return s
}

// choices 同桌面版：中转只有它报的具体 ID；否则是工具报的清单分好的两层，还没报时是后备清单。
func (s *modelSource) choices() (latest, older []chat.ModelOption) {
	if !s.ready {
		return nil, nil
	}
	return modelOptions(s.catalog, s.harness, s.tool)
}

// modelOptions 是可选的模型（输入栏和设置的默认模型共用，同桌面版 modelSource.choices）。
func modelOptions(catalog coreapi.ModelCatalog, harness []chat.ModelOption, tool string) (latest, older []chat.ModelOption) {
	switch {
	case catalog.Relay != "":
		for _, x := range catalog.Models {
			latest = append(latest, chat.ModelOption{Value: x.ID, DisplayName: x.DisplayName, ResolvedModel: x.ID})
		}
		return latest, nil
	case len(harness) == 0:
		return chat.FallbackModelsFor(tool), nil
	}
	return chat.SplitModels(harness)
}

// Choice 是菜单里的一项。
type Choice struct {
	Value string `json:"value"`
	Name  string `json:"name"`
}

// ModelMenu 是模型按钮：Chosen 是这个对话选的（空是默认），InUse 是 Claude Code（或中转）报的在用的那个的名字；
// Harness 时菜单最上面有「默认」；Latest 直接列，Older 收进「更多模型」。Available 为假时按钮不能点。
type ModelMenu struct {
	Available  bool     `json:"available"`
	Harness    bool     `json:"harness"`
	Chosen     string   `json:"chosen"`
	ChosenName string   `json:"chosen_name"`
	InUse      string   `json:"in_use"`
	Latest     []Choice `json:"latest"`
	Older      []Choice `json:"older"`
}

func (m *composer) modelMenu(backend string, s chat.State) ModelMenu {
	out := ModelMenu{Latest: []Choice{}, Older: []Choice{}}
	r := m.core.hub.Remote(backend)
	if r == nil {
		return out
	}
	h := sessionproto.NormalizeHarness(s.Harness)
	src := m.source(backend, h, r)
	if src.ready && src.catalog.Relay == "" && len(s.Models) > 0 {
		src.harness = s.Models
	}
	latest, older := src.choices()
	out.Available = src.ready && r.AccountsFor(h).Working() == ""
	out.Harness = out.Available && src.catalog.Relay == ""
	reported := s.ReportedModel
	if src.catalog.Relay != "" && (!s.Process || reported == "") {
		reported = src.catalog.Model
	}
	if reported != "" {
		out.InUse = chat.ModelName(reported, latest, older)
	}
	if s.Model != nil && *s.Model != chat.DefaultModel {
		out.Chosen = *s.Model
		if out.Chosen != "" {
			out.ChosenName = chat.ModelName(out.Chosen, latest, older)
		}
	}
	out.Latest, out.Older = nonDefaultChoices(latest), nonDefaultChoices(older)
	return out
}

func nonDefaultChoices(list []chat.ModelOption) []Choice {
	out := []Choice{}
	for _, o := range list {
		if o.Value != chat.DefaultModel {
			out = append(out, Choice{Value: o.Value, Name: o.Name()})
		}
	}
	return out
}

// ---- 用量与额度（同桌面版 composer.go 的 usageDetails、usageturns.go 和 accounts 的额度一节）----

// usageTurnsShown 同桌面版：各轮明细最多列最近这么多轮。
const usageTurnsShown = 50

// UsageView 是用量：数都按给人看的写好了，说明的句子由界面按这几个标记出字（同桌面版 contextNotes、totalsNote）。
type UsageView struct {
	Fraction *float32 `json:"fraction"`
	Level    string   `json:"level"`
	// Context 是「占用 / 窗口 · 百分比」，还没有时为空。
	Context          string `json:"context"`
	ContextKnown     bool   `json:"context_known"`
	ContextEstimated bool   `json:"context_estimated"`
	AutoCompactOff   bool   `json:"auto_compact_off"`
	CompactAt        string `json:"compact_at"`
	CompactPercent   *int   `json:"compact_percent"`
	Compactions      int    `json:"compactions"`
	Model            string `json:"model"`
	AppliedEffort    string `json:"applied_effort"`
	Input            string `json:"input"`
	CacheRead        string `json:"cache_read"`
	CacheWrite       string `json:"cache_write"`
	Output           string `json:"output"`
	Cost             string `json:"cost"`
	CostEstimated    bool   `json:"cost_estimated"`
	CostPartial      bool   `json:"cost_partial"`
	// Earlier：更早的记录没载入，累计没算上。
	Earlier   bool       `json:"earlier"`
	TurnCount int        `json:"turn_count"`
	Turns     []TurnView `json:"turns"`
}

// TurnView 是一轮：新的在前。
type TurnView struct {
	N          int    `json:"n"`
	Duration   string `json:"duration"`
	Cost       string `json:"cost"`
	Input      string `json:"input"`
	CacheRead  string `json:"cache_read"`
	CacheWrite string `json:"cache_write"`
	Output     string `json:"output"`
}

func cost(v *float64, estimated bool) string {
	if v == nil {
		return "—"
	}
	if estimated {
		return "≈" + usage.FormatCost(*v)
	}
	return usage.FormatCost(*v)
}

func usageView(u usage.Usage, earlier int, locale string) UsageView {
	f := usage.FormatTokens
	out := UsageView{Fraction: u.Fraction, Level: string(u.Level), ContextKnown: u.ContextTokens != nil, ContextEstimated: u.ContextEstimated,
		AutoCompactOff: u.AutoCompact != nil && !*u.AutoCompact, Compactions: u.Compactions, AppliedEffort: u.AppliedEffort,
		Input: f(u.Total.Input), CacheRead: f(u.Total.CacheRead), CacheWrite: f(u.Total.CacheWrite), Output: f(u.Total.Output),
		Cost: cost(u.TotalCost, u.CostEstimated), CostEstimated: u.CostEstimated, CostPartial: u.CostPartial, Earlier: earlier > 0,
		TurnCount: len(u.Turns), Turns: []TurnView{}}
	if u.Model != "" {
		out.Model = chat.ReadableModel(u.Model)
	}
	if u.ContextTokens != nil {
		out.Context = f(*u.ContextTokens)
		if u.ContextWindow != nil {
			out.Context += " / " + f(*u.ContextWindow)
		}
		if u.Fraction != nil {
			out.Context += " · " + strconv.FormatFloat(float64(*u.Fraction)*100, 'f', 1, 32) + "%"
		}
	}
	if u.CompactAt != nil && !out.AutoCompactOff {
		out.CompactAt = f(*u.CompactAt)
		if u.CompactFraction != nil {
			p := int(*u.CompactFraction * 100)
			out.CompactPercent = &p
		}
	}
	for i := len(u.Turns) - 1; i >= max(0, len(u.Turns)-usageTurnsShown); i-- {
		t := u.Turns[i]
		v := TurnView{N: i + 1, Cost: cost(t.CostUSD, t.CostEstimated), Input: f(t.Tokens.Input), CacheRead: f(t.Tokens.CacheRead),
			CacheWrite: f(t.Tokens.CacheWrite), Output: f(t.Tokens.Output)}
		if t.DurationMs != nil {
			v.Duration = tasks.FormatElapsedIn(locale, *t.DurationMs)
		}
		out.Turns = append(out.Turns, v)
	}
	return out
}

// ---- 交给界面的整份 ----

// Composer 是 state 里 chat.composer：输入栏要画的。
type Composer struct {
	// Text 是这边改过的输入框里的字，Rev 变了界面才换上（见文件开头）。
	Text string `json:"text"`
	Rev  int    `json:"rev"`
	// Ready：能发（对话打开了、工作目录进得去）。Uploading：还有在传的文件、在做的缩小图，传完才能发。
	Ready       bool         `json:"ready"`
	Uploading   bool         `json:"uploading"`
	Attachments []Attachment `json:"attachments"`
	Problems    []string     `json:"problems"`
	TooLarge    bool         `json:"too_large"`
	Process     bool         `json:"process"`
	Model       ModelMenu    `json:"model"`
	// Effort 是这个对话选的思考强度（空是默认），AppliedEffort 是工具报的实际生效的，EffortLevels 是当前模型支持的档位。
	Effort        string   `json:"effort"`
	AppliedEffort string   `json:"applied_effort"`
	EffortLevels  []string `json:"effort_levels,omitempty"`
	// Mode 是权限模式：选过的，没选过时是进程此刻用的；Modes 是可选的几种（字已按界面语言出好）。
	Mode          string     `json:"mode"`
	Modes         []Choice   `json:"modes"`
	Agent         string     `json:"agent,omitempty"`
	ExtraDirs     []string   `json:"extra_dirs,omitempty"`
	Sandbox       bool       `json:"sandbox,omitempty"`
	CanEditLaunch bool       `json:"can_edit_launch,omitempty"`
	Queued        []Queued   `json:"queued"`
	Steering      bool       `json:"steering"`
	Usage         UsageView  `json:"usage"`
	Quota         *QuotaView `json:"quota"`
}

// Attachment 是输入栏上方一个待发的：Picture 的画成缩略图（Thumb 是本机的图片文件，空是灰块），别的画成文件标签。
// Thumb 是这次选的原文件，撤回放回的、存盘恢复的是缩小图；点开看大图读的也是它。
type Attachment struct {
	ID        int    `json:"id"`
	Name      string `json:"name"`
	Picture   bool   `json:"picture"`
	Thumb     string `json:"thumb"`
	Uploading bool   `json:"uploading"`
	// Percent 是传了多少（0～99），不知道是 -1。
	Percent int    `json:"percent"`
	Failed  string `json:"failed"`
}

// Queued 是排队等发的一条：Preview 是正文的一行（空白并成一个空格），Extra 是另带的图和文件数。
type Queued struct {
	UUID    string `json:"uuid"`
	Preview string `json:"preview"`
	Images  int    `json:"images"`
	Extra   int    `json:"extra"`
}

func (m *composer) attachments() []Attachment {
	out := []Attachment{}
	for _, f := range m.files {
		a := Attachment{ID: f.id, Name: attach.Label(f.name, f.size), Picture: f.encoding || f.shrunk != nil, Uploading: f.uploading(),
			Percent: -1, Failed: f.failed}
		if a.Picture {
			a.Thumb = f.local
			if a.Thumb == "" && f.shrunk != nil {
				a.Thumb = f.shrunk.thumbPath()
			}
		}
		if a.Uploading && f.size > 0 {
			a.Percent = int(min(f.sent*100/f.size, 99))
		}
		out = append(out, a)
	}
	for _, im := range m.images {
		out = append(out, Attachment{ID: im.id, Picture: true, Thumb: im.thumbPath(), Percent: -1})
	}
	return out
}

func queued(list []coreapi.Message) []Queued {
	out := []Queued{}
	for _, x := range list {
		extra := len(x.Images)
		for _, f := range x.Files {
			if !slices.ContainsFunc(x.Images, func(im coreapi.Image) bool { return im.Original == f }) {
				extra++
			}
		}
		out = append(out, Queued{UUID: x.UUID, Preview: strings.Join(strings.Fields(x.Text), " "), Images: len(x.Images), Extra: extra})
	}
	return out
}

// fillComposer 给 state 的聊天页填上输入栏（state 里调）。
func (c *Core) fillComposer(out *Chat, locale string) {
	m := c.comp()
	ch := m.sync()
	if out == nil || ch == nil || ch.Conv == nil {
		return
	}
	s := ch.Conv.State()
	m.takeRecalled(s)
	v := &Composer{Text: m.text, Rev: m.rev, Ready: ch.CwdProblem == "", Uploading: m.uploading(), Attachments: m.attachments(),
		Problems: slices.Clone(m.problems), TooLarge: m.tooLarge, Process: s.Process, Model: m.modelMenu(ch.Backend, s),
		AppliedEffort: s.Usage.AppliedEffort, Mode: cmp.Or(s.Mode.User, s.Mode.Process), Queued: queued(s.Queued), Steering: s.Steering,
		Usage: usageView(s.Usage, s.Earlier, locale)}
	if v.Problems == nil {
		v.Problems = []string{}
	}
	if s.Effort != nil {
		v.Effort = *s.Effort
	}
	if s.Agent != nil {
		v.Agent = *s.Agent
	}
	v.ExtraDirs = slices.Clone(s.ExtraDirs)
	v.Sandbox = s.Sandbox
	v.CanEditLaunch = sessionproto.NormalizeHarness(s.Harness) == sessionproto.HarnessAgy && out.CanSwitchHarness
	activeModel := s.ReportedModel
	if s.Model != nil && *s.Model != "" && *s.Model != chat.DefaultModel {
		activeModel = *s.Model
	}
	v.EffortLevels = chat.EffortLevelsFor(s.Models, activeModel)
	for _, x := range chat.PermissionModes {
		v.Modes = append(v.Modes, Choice{Value: x.Value, Name: x.Label()})
	}
	// 在用中转、还不知道时没有额度一节（accounts.Quota.Shown）
	if r := c.hub.Remote(ch.Backend); r != nil {
		if q := r.QuotaFor(s.Harness); q.Shown() {
			v.Quota = quotaView(q, time.Now())
		}
	}
	out.Composer = v
}

// ---- 常用指令（同桌面版 savedprompts.go，存在设置里，见 settings.SavedPrompt）----

// Prompt 是常用指令的一条：Title 是显示的名字（没起名时取内容开头）。
type Prompt struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Text  string `json:"text"`
	Title string `json:"title"`
}

// Prompts 是方法 prompts 的结果：Full 时不能再加。
type Prompts struct {
	List []Prompt `json:"list"`
	Full bool     `json:"full"`
}

func (c *Core) prompts() Prompts {
	list := c.hub.Prompts()
	out := Prompts{List: []Prompt{}, Full: len(list) >= settings.MaxPrompts}
	for _, p := range list {
		out.List = append(out.List, Prompt{ID: p.ID, Name: p.Name, Text: p.Text, Title: p.DisplayName()})
	}
	return out
}

func (c *Core) setPrompts(list []settings.SavedPrompt) (any, error) {
	if err := c.hub.SetPrompts(list); err != nil {
		return nil, err
	}
	return c.prompts(), nil
}

// ---- 方法 ----

type (
	draftParams struct {
		Text  string `json:"text"`
		Caret int    `json:"caret"`
	}
	completeParams struct {
		Text  string `json:"text"`
		Caret int    `json:"caret"`
		Name  string `json:"name"`
	}
	attachParams struct {
		Files []pickedFile `json:"files"`
	}
	removeParams struct {
		ID int `json:"id"`
	}
	choiceParams struct {
		// Value 是选的；null 回到默认。
		Value *string `json:"value"`
	}
	harnessValueParams struct {
		Value string `json:"value"`
	}
	cancelParams struct {
		UUID   string `json:"uuid"`
		Recall bool   `json:"recall"`
	}
	forceParams struct {
		Force bool `json:"force"`
	}
	promptParams struct {
		ID    string `json:"id"`
		Name  string `json:"name"`
		Text  string `json:"text"`
		Delta int    `json:"delta"`
		// Dedupe：已经有同样内容的一条时不加，答出错（消息上的「收藏为常用指令」）。
		Dedupe bool `json:"dedupe"`
	}
	// Completed 是选了斜杠命令以后输入框里的字和光标（UTF-16）。
	Completed struct {
		Text  string `json:"text"`
		Caret int    `json:"caret"`
	}
)

type convDo = func(ctx context.Context, conv *chat.Conversation) error

func init() {
	reading("composer.draft", func(c *Core, p draftParams) (any, error) { return c.comp().typed(p.Text, p.Caret), nil })
	reading("composer.complete", func(_ *Core, p completeParams) (any, error) {
		s, ok := chat.SlashAt(p.Text, u16ToRunes(p.Text, p.Caret))
		if !ok {
			return Completed{Text: p.Text, Caret: p.Caret}, nil
		}
		text, caret := s.Complete(p.Text, p.Name)
		return Completed{Text: text, Caret: runesToU16(text, caret)}, nil
	})
	onLoop("composer.send", func(c *Core, p textParams) (any, error) {
		m := c.comp()
		ch := m.sync()
		if cmd := chat.SettingsCommand(p.Text); cmd != "" && ch != nil && ch.Conv != nil {
			m.text = "" // 附件留着
			return Sent{Sent: true, Open: cmd}, nil
		}
		return Sent{Sent: m.send(p.Text)}, nil
	})
	onLoop("composer.attach", func(c *Core, p attachParams) (any, error) {
		m := c.comp()
		if ch := m.sync(); ch == nil || ch.Conv == nil {
			return nil, locales.Err(locales.MobileChatNotOpen)
		}
		m.addPaths(p.Files)
		return ok, nil
	})
	onLoop("composer.remove", func(c *Core, p removeParams) (any, error) {
		c.comp().remove(p.ID)
		return ok, nil
	})
	onLoop("composer.problems", func(c *Core, _ none) (any, error) {
		m := c.comp()
		m.problems, m.tooLarge = nil, false
		return ok, nil
	})
	blocking("composer.flush", func(c *Core, _ none) (any, error) {
		var wait func()
		c.Do(func() {
			m := c.comp()
			m.sync()
			wait = m.save()
		})
		wait()
		return ok, nil
	})
	onLoop("composer.quota", func(c *Core, p forceParams) (any, error) {
		if ch := c.hub.Chat(); ch != nil {
			if r := c.hub.Remote(ch.Backend); r != nil {
				r.QuotaFor(ch.Harness()).Refresh(p.Force)
			}
		}
		return ok, nil
	})
	inChat("composer.harness", func(p harnessValueParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetHarness(ctx, p.Value) }, nil
	})
	inChat("composer.set_agent", func(p harnessValueParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetAgent(ctx, p.Value) }, nil
	})
	inChat("composer.set_extra_dirs", func(p struct {
		Dirs []string `json:"dirs"`
	}) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetExtraDirs(ctx, p.Dirs) }, nil
	})
	inChat("composer.set_sandbox", func(p struct {
		Sandbox bool `json:"sandbox"`
	}) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetSandbox(ctx, p.Sandbox) }, nil
	})
	blocking("chat.artifacts", func(c *Core, _ none) (any, error) {
		var conv *chat.Conversation
		c.Do(func() {
			if ch := c.hub.Chat(); ch != nil {
				conv = ch.Conv
			}
		})
		if conv == nil {
			return nil, locales.Err(locales.MobileChatNotOpen)
		}
		ctx, cancel := context.WithTimeout(context.Background(), coreTimeout)
		defer cancel()
		return conv.Artifacts(ctx)
	})
	blocking("chat.artifact_read", func(c *Core, p struct {
		Name string `json:"name"`
	}) (any, error) {
		var conv *chat.Conversation
		c.Do(func() {
			if ch := c.hub.Chat(); ch != nil {
				conv = ch.Conv
			}
		})
		if conv == nil {
			return nil, locales.Err(locales.MobileChatNotOpen)
		}
		ctx, cancel := context.WithTimeout(context.Background(), coreTimeout)
		defer cancel()
		return conv.ReadArtifact(ctx, p.Name)
	})
	inChat("composer.model", func(p choiceParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetModel(ctx, p.Value) }, nil
	})
	inChat("composer.effort", func(p choiceParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetEffort(ctx, p.Value) }, nil
	})
	inChat("composer.mode", func(p choiceParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.SetMode(ctx, p.Value) }, nil
	})
	inChat("composer.steer", func(none) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Steer(ctx) }, nil
	})
	inChat("composer.cancel", func(p cancelParams) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Cancel(ctx, p.UUID, p.Recall) }, nil
	})
	inChat("composer.compact", func(none) (convDo, error) {
		return func(ctx context.Context, conv *chat.Conversation) error { return conv.Compact(ctx) }, nil
	})

	reading("prompts", func(c *Core, _ none) (any, error) { return c.prompts(), nil })
	onLoop("prompts.add", func(c *Core, p promptParams) (any, error) {
		list := c.hub.Prompts()
		if problem := settings.PromptProblem(list, p.Name, p.Text, ""); problem != "" {
			return nil, errors.New(problem)
		}
		if p.Dedupe && settings.HasSamePrompt(list, p.Text) {
			return nil, locales.Err(locales.MobilePromptExists)
		}
		return c.setPrompts(settings.AddPrompt(list, newPromptID(), p.Name, p.Text))
	})
	onLoop("prompts.update", func(c *Core, p promptParams) (any, error) {
		list := c.hub.Prompts()
		if problem := settings.PromptProblem(list, p.Name, p.Text, p.ID); problem != "" {
			return nil, errors.New(problem)
		}
		return c.setPrompts(settings.UpdatePrompt(list, p.ID, p.Name, p.Text))
	})
	onLoop("prompts.remove", func(c *Core, p promptParams) (any, error) {
		return c.setPrompts(settings.RemovePrompt(c.hub.Prompts(), p.ID))
	})
	onLoop("prompts.move", func(c *Core, p promptParams) (any, error) {
		return c.setPrompts(settings.MovePrompt(c.hub.Prompts(), p.ID, p.Delta))
	})
}

// newPromptID 是新常用指令的编号（同桌面版）。
func newPromptID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b) // crypto/rand 的 Read 不会失败
	return hex.EncodeToString(b)
}

// rowDraft 是会话列表一行要标的草稿（同桌面版 rowDraft）：会话 id 存着没发出的草稿、又不是输入栏正跟着的那个时，答侧栏的一行预览。
func (m *composer) rowDraft(id string) (string, bool) {
	b := m.drafts()
	if id == "" || id == b.key {
		return "", false
	}
	r, ok := b.records[id]
	if !ok {
		return "", false
	}
	// 存盘的没写 Label 时按 Entry 取名（同恢复草稿时）；复制一份，不改存着的那份
	r.Files = slices.Clone(r.Files)
	for i, f := range r.Files {
		if f.Label == "" {
			r.Files[i].Label = attach.EntryName(f.Entry)
		}
	}
	return drafts.Preview(r), true
}
