package ui

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/locales"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/transfer"
	"github.com/egoist/mygo/ui"
)

// 输入栏的附件（同手机版的 IntakeSink、ComposerAttachments）：文件、文件夹和图片都把原文件传到对话所在的设备上
// （~/.cache/zeta/files/…），消息里带那边的路径；图片另做一张缩小图（attach.EncodeImage）随消息内联发给模型看，
// 输入栏里一张图只显示一个缩略图。「输入路径」引用设备上本来就有的。images 是旧消息、草稿里存着的内联图放回来时用的。
// 收不了的（太大、读不了、太多）攒在 problems 里，弹框说一次。

// draftImage 是输入栏里一张只有内联图的待发图：撤回、编辑的旧消息和草稿里存着的；新加的图片是带缩小图的 draftFile。
type draftImage struct {
	id    int
	image coreapi.Image
	thumb *ui.Bitmap // 解不开时是 nil，画一块灰
	// hash 是内容的编号（草稿里存图的文件名），见 contentHash。
	hash string
}

// contentHash 是图的内容编号：内容的哈希，同一张图在草稿里只存一份。第一次用时算好记着；从草稿恢复的沿用存着的编号。
func (d *draftImage) contentHash() string {
	if d.hash == "" {
		sum := sha256.Sum256(d.image.Data)
		d.hash = hex.EncodeToString(sum[:16])
	}
	return d.hash
}

// draftFile 是输入栏里一个待发的文件或文件夹：正在传、传好了（entry 是消息里带的附件说明）或没传成。
type draftFile struct {
	id     int
	name   string
	size   int64 // 不知道是 -1
	folder bool
	entry  string // 传好了的主机路径（/…）或引用（@…）
	failed string // 没传成的原因
	sent   int64  // 已传的字节数
	// picture：是张图片，输入栏里画成缩略图；encoding 时缩小图还在做（做好才能发），inline 是做好的缩小图
	// （随消息内联发），thumb 从它解；做不成时 picture 变回假，只当文件。
	picture  bool
	encoding bool
	inline   *coreapi.Image
	thumb    *ui.Bitmap
	cancel   context.CancelFunc
}

func (f *draftFile) uploading() bool { return f.entry == "" && f.failed == "" }

// label 是附件上显示的字：正在传的带进度，没传成的注明。
func (f *draftFile) label() string {
	name := attach.Label(f.name, f.size)
	switch {
	case f.failed != "":
		return locales.ComposerUploadFailed(name)
	case !f.uploading():
		return name
	case f.size > 0:
		return locales.ComposerUploadingPercent(int(min(f.sent*100/f.size, 99)), name)
	}
	return locales.ComposerUploading(name)
}

// attachments 是一个对话输入栏里的图和文件，加上没收进来的原因。
type attachments struct {
	images   []*draftImage
	files    []*draftFile
	loading  int // 正在读、正在编码的图（占着名额）
	next     int
	problems []string
	tooLarge bool // problems 里有因为太大没收的
}

func (at *attachments) id() int { at.next++; return at.next }

func (at *attachments) report(why string, tooLarge bool) {
	at.problems = append(at.problems, why)
	at.tooLarge = at.tooLarge || tooLarge
}

// roomForImages：还能再放几张图。
func (at *attachments) roomForImages() int { return attach.MaxImages - len(at.images) - at.loading }

// uploading：还有在传的文件、在做的缩小图（都好了才能发）。
func (at *attachments) uploading() bool {
	for _, f := range at.files {
		if f.uploading() || f.encoding {
			return true
		}
	}
	return false
}

// entries 是要随消息带上的附件说明（传好了的）。
func (at *attachments) entries() []string {
	var out []string
	for _, f := range at.files {
		if f.entry != "" {
			out = append(out, f.entry)
		}
	}
	return out
}

// pictures 是要随消息内联发的图：放回来的内联图，再是传好了的图片文件的缩小图，后者的顺序同 entries() 里
// 这些文件的顺序（Claude 靠顺序对上原图）。
func (at *attachments) pictures() []coreapi.Image {
	var out []coreapi.Image
	for _, im := range at.images {
		out = append(out, im.image)
	}
	for _, f := range at.files {
		if f.entry != "" && f.inline != nil {
			out = append(out, *f.inline)
		}
	}
	return out
}

// composerSnapshot 是输入栏里的字和附件（图、文件），收起来以后能原样放回：离开对话时收着的草稿，开始编辑消息前输入栏里的。
type composerSnapshot struct {
	text string
	att  attachments
}

// snapshot 收起输入栏现在的字和附件。正在读的图和还没说的问题不算：它们落回的是当时的输入栏。
func (p *chatPane) snapshot() composerSnapshot {
	return composerSnapshot{text: p.draft, att: attachments{images: p.att.images, files: p.att.files, next: p.att.next}}
}

// restore 把输入栏的字和附件换成 s（正在读的图、还没说的问题照旧）。附件的编号接着 s 的往后排，不和放回来的重号。
func (p *chatPane) restore(s composerSnapshot) {
	p.draft = s.text
	p.att.images, p.att.files = s.att.images, s.att.files
	p.att.next = max(p.att.next, s.att.next)
}

// clear 发出去以后清掉图和文件（没收进来的说明留着）。
func (at *attachments) clear() { at.images, at.files = nil, nil }

func (at *attachments) removeImage(id int) {
	at.images = removeWhere(at.images, func(im *draftImage) bool { return im.id == id })
}

func (at *attachments) removeFile(id int) {
	at.files = removeWhere(at.files, func(f *draftFile) bool {
		if f.id == id && f.cancel != nil {
			f.cancel()
		}
		return f.id == id
	})
}

func removeWhere[T any](list []T, gone func(T) bool) []T {
	var out []T
	for _, x := range list {
		if !gone(x) {
			out = append(out, x)
		}
	}
	return out
}

// picker 用系统的文件对话框选东西：images 时只列图片，folder 时选文件夹，都可以多选。取消了是空的。阻塞，在后台调用。
// 测试里换掉。
type picker func(title string, images, folder bool) []string

func systemPicker(title string, images, folder bool) []string {
	opts := mygo.OpenDialogOptions{Title: title, Multiple: true, Directory: folder}
	if images {
		opts.Filters = []mygo.FileFilter{{Name: locales.ComposerImagesFilter(), Extensions: attach.ImageExtensions()}}
	}
	paths, _ := mygo.Dialog.Open(opts)
	return paths
}

// systemSaveFile 是 App.saveFile 的系统实现：弹系统的保存对话框，把 data 写到选好的地方。
func systemSaveFile(title, name string, filters []mygo.FileFilter, data []byte) (string, error) {
	path, err := mygo.Dialog.Save(mygo.SaveDialogOptions{Title: title, DefaultPath: name, Filters: filters})
	if path == "" || err != nil {
		return "", err
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		return "", err
	}
	return path, nil
}

// pickAttachments 是附件菜单的「图片」「文件」「文件夹」：弹系统的对话框，选好的收进输入栏。「图片」只是对话框里只列图片。
func (a *App) pickAttachments(images, folder bool) {
	p := a.pane
	if p == nil {
		return
	}
	title := locales.ComposerPickFiles()
	switch {
	case folder:
		title = locales.ComposerPickFolder()
	case images:
		title = locales.ComposerPickImages()
	}
	a.async(func() func() {
		paths := a.pick(title, images, folder)
		return func() { a.addPaths(p, paths) }
	})
}

// addPaths 收本机的这些路径（选的、拖进来的、粘贴的）：文件夹整个传上去，文件（图片也是）原样传上去。在主线程调用。
func (a *App) addPaths(p *chatPane, paths []string) {
	at := &p.att
	if len(paths) > attach.MaxFiles {
		at.report(locales.ComposerTooManyFiles(attach.MaxFiles, len(paths)-attach.MaxFiles), false)
		paths = paths[:attach.MaxFiles]
	}
	for _, local := range paths {
		st, err := os.Stat(local)
		switch {
		case err != nil:
			at.report(locales.ComposerUnreadable(filepath.Base(local)), false)
		case st.IsDir():
			a.uploadFolder(p, local)
		default:
			a.uploadFile(p, host.Upload{Local: local}, filepath.Base(local), st.Size())
		}
	}
}

// uploadFile 把一个文件传到对话所在的设备上；太大的不传。图片另在后台做一张缩小图（attach.EncodeImage）随消息内联发给模型看，
// 输入栏的缩略图也从它解；做不成（不是认得的图）就只当文件。
func (a *App) uploadFile(p *chatPane, u host.Upload, name string, size int64) {
	if attach.TooLarge(size) {
		p.att.report(attach.TooLargeReason(name, size), true)
		return
	}
	f := &draftFile{name: name, size: size, picture: attach.IsImage(name)}
	f.encoding = f.picture
	a.startUpload(p, u, f)
	if !f.picture {
		return
	}
	a.async(func() func() {
		im, err := shrunkImage(u)
		return func() {
			f.encoding = false
			if err != nil {
				f.picture = false
				return
			}
			f.inline, f.thumb = &im.image, im.thumb
		}
	})
}

// shrunkImage 是要传的图片的缩小图：读本机的原文件或粘贴的数据（编码和解缩略图都耗时，在后台调用）。
func shrunkImage(u host.Upload) (*draftImage, error) {
	data := u.Data
	if u.Local != "" {
		var err error
		if data, err = os.ReadFile(u.Local); err != nil {
			return nil, err
		}
	}
	img, err := attach.EncodeImage(data)
	if err != nil {
		return nil, err
	}
	thumb, _ := ui.DecodeBitmap(img.Data)
	return &draftImage{image: img, thumb: thumb}, nil
}

// uploadFolder 把本机的文件夹整个传上去，传好后当文件夹引用（@路径/）。
func (a *App) uploadFolder(p *chatPane, local string) {
	name := filepath.Base(local)
	size, err := hostlink.FolderSize(local)
	switch {
	case err != nil:
		p.att.report(locales.ComposerFolderUnreadable(name), false)
	case attach.TooLarge(size):
		p.att.report(attach.TooLargeReason(name, size), true)
	default:
		a.startUpload(p, host.Upload{Local: local, Folder: true}, &draftFile{name: name, size: size, folder: true})
	}
}

// startUpload 在后台传 u，进度和结果记在 f 上（在主线程改）；点了附件上的移除就取消。
func (a *App) startUpload(p *chatPane, u host.Upload, f *draftFile) {
	u.Limit = attach.MaxBytes
	ctx, cancel := context.WithCancel(context.Background())
	f.id, f.cancel = p.att.id(), cancel
	p.att.files = append(p.att.files, f)
	upload := a.hub.Uploader(p.src.Backend)
	a.run(func() {
		shown := int64(-1)
		path, err := upload(ctx, u, func(n int64) {
			// 进度按百分之一报回主线程，免得大文件每一块都重画一次
			if f.size > 0 && n*100/f.size != shown {
				shown = n * 100 / f.size
				a.post(func() { f.sent = n })
			}
		})
		a.post(func() { a.uploaded(p, f, path, err) })
	})
}

// uploaded 记下传的结果。被移除（取消）的已经不在输入栏里，什么也不做。
func (a *App) uploaded(p *chatPane, f *draftFile, path string, err error) {
	f.cancel()
	switch {
	case errors.Is(err, context.Canceled):
	case errors.Is(err, hostlink.ErrTooLarge):
		p.att.removeFile(f.id)
		p.att.report(attach.TooLargeReason(f.name, -1), true)
	case err != nil:
		f.failed = err.Error()
	case f.folder:
		f.entry = hostlink.Reference(path, true)
	default:
		f.entry = path
	}
}

// paste 是输入框里的粘贴：剪贴板里有图或文件时收进输入栏，返回 true（不当文字贴）。
func (a *App) paste(c *ui.Context, p *chatPane) bool {
	data := c.Services().ReadClipboardData(transfer.PNG, transfer.FileList)
	if paths, err := data.Files(); err == nil && len(paths) > 0 {
		a.addPaths(p, paths)
		return true
	}
	if png, err := data.Read(transfer.PNG); err == nil && len(png) > 0 {
		name := locales.ComposerPastedImageName(a.now().Format("20060102-150405"))
		a.uploadFile(p, host.Upload{Name: name, Data: png}, name, int64(len(png)))
		return true
	}
	return false
}

// putBack 把撤回的、没发出去的一条放回输入栏：文字接在已有的前面，图补上放得下的，附件说明补上没有的。
// 传上去的图片文件带着的缩小图在 m.Images 的末尾、和这些文件同样的顺序（见 pictures），配回到文件上，不另放一份。
func (p *chatPane) putBack(m coreapi.Message) {
	if m.Text != "" {
		p.recall(m.Text)
	}
	for _, entry := range m.Files {
		if !containsEntry(p.att.files, entry) {
			p.att.files = append(p.att.files, p.att.newEntryFile(entry, ""))
		}
	}
	for _, img := range p.att.pairShrunk(m) {
		if p.att.roomForImages() <= 0 {
			break
		}
		im := &draftImage{id: p.att.id(), image: img}
		im.thumb, _ = ui.DecodeBitmap(img.Data)
		p.att.images = append(p.att.images, im)
	}
}

// splitShrunk 把 m 的图分成单独的内联图和传上去的图片文件的缩小图（按文件的附件说明）。图片文件比末尾的图多时
// （有的没做成缩小图）对不上，都当单独的图。
func splitShrunk(m coreapi.Message) ([]coreapi.Image, map[string]coreapi.Image) {
	var pictures []string
	for _, entry := range m.Files {
		if !strings.HasPrefix(entry, "@") && attach.IsImage(entry) {
			pictures = append(pictures, entry)
		}
	}
	if len(pictures) == 0 || len(pictures) > len(m.Images) {
		return m.Images, nil
	}
	own := len(m.Images) - len(pictures)
	shrunk := map[string]coreapi.Image{}
	for i, entry := range pictures {
		shrunk[entry] = m.Images[own+i]
	}
	return m.Images[:own], shrunk
}

// newEntryFile 是一个已经在设备上的附件（传好了的路径或引用）：name 是小标签上的名字，空着时按路径取。
func (at *attachments) newEntryFile(entry, name string) *draftFile {
	if name == "" {
		name = attach.EntryName(entry)
	}
	return &draftFile{id: at.id(), name: name, size: -1, folder: strings.HasPrefix(entry, "@") && strings.HasSuffix(entry, "/"), entry: entry}
}

func containsEntry(files []*draftFile, entry string) bool {
	for _, f := range files {
		if f.entry == entry {
			return true
		}
	}
	return false
}

// attachProblems 告诉用户哪些没有加进来、为什么（同手机版的 IntakeProblemsDialog）。
func (a *App) attachProblems(c *ui.Context, p *chatPane) {
	at := &p.att
	open := len(at.problems) > 0
	if !open {
		return
	}
	lines := make([]string, len(at.problems))
	for i, why := range at.problems {
		lines[i] = locales.ComposerProblem(why)
	}
	message := strings.Join(lines, "\n")
	if at.tooLarge {
		message += "\n\n" + locales.ComposerTooLargeAdvice() // 跟在「太大没收」的说明后面
	}
	widget.Alert(c, &open, locales.ComposerProblemsTitle(), message, widget.Action{Label: locales.CommonGotIt(), Style: widget.Recommend})
	if !open {
		at.problems, at.tooLarge = nil, false
	}
}

// pairShrunk 把 m 里传上去的图片文件的缩小图配回输入栏里已有的这些文件（见 splitShrunk），返回剩下的单独的图。
func (at *attachments) pairShrunk(m coreapi.Message) []coreapi.Image {
	images, shrunk := splitShrunk(m)
	for _, f := range at.files {
		if img, ok := shrunk[f.entry]; ok && f.inline == nil {
			f.picture, f.inline = true, &img
			f.thumb, _ = ui.DecodeBitmap(img.Data)
		}
	}
	return images
}
