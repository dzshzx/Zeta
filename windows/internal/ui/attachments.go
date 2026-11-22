package ui

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/host"
	"github.com/dzshzx/Zeta/windows/internal/ui/coui"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/transfer"
	"github.com/egoist/mygo/ui"
)

// 输入栏的附件（同手机版的 IntakeSink、ComposerAttachments）：图片缩好、编成 JPEG 随消息发给模型看（attach.EncodeImage）；
// 别的文件、文件夹传到对话所在的设备上（~/.cache/zeta/files/…），消息里带那边的路径；「输入路径」引用设备上本来就有的。
// 收不了的（太大、读不了、太多）攒在 problems 里，弹框说一次。

// draftImage 是输入栏里一张待发的图。
type draftImage struct {
	id    int
	image chat.Image
	thumb *ui.Bitmap // 解不开时是 nil，画一块灰
	// hash 是内容的编号（草稿里存图的文件名），见 contentHash。
	hash string
}

// contentHash 是图的内容编号：内容的哈希，同一张图在草稿里只存一份。第一次用时算好记着；从草稿恢复的沿用存着的编号。
func (d *draftImage) contentHash() string {
	if d.hash == "" {
		sum := sha256.Sum256([]byte(d.image.Data))
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
	cancel context.CancelFunc
}

func (f *draftFile) uploading() bool { return f.entry == "" && f.failed == "" }

// label 是附件上显示的字：正在传的带进度，没传成的注明。
func (f *draftFile) label() string {
	name := attach.Label(f.name, f.size)
	switch {
	case f.failed != "":
		return "上传失败 · " + name
	case !f.uploading():
		return name
	case f.size > 0:
		return "正在上传 " + strconv.Itoa(int(min(f.sent*100/f.size, 99))) + "% · " + name
	}
	return "正在上传 · " + name
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

// uploading：还有在传的文件（传完才能发）。
func (at *attachments) uploading() bool {
	for _, f := range at.files {
		if f.uploading() {
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

// pictures 是要随消息发的图。
func (at *attachments) pictures() []chat.Image {
	var out []chat.Image
	for _, im := range at.images {
		out = append(out, im.image)
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
		opts.Filters = []mygo.FileFilter{{Name: "图片", Extensions: attach.ImageExtensions()}}
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

// pickAttachments 是附件菜单的「图片」「文件」「文件夹」：弹系统的对话框，选好的收进输入栏。
func (a *App) pickAttachments(images, folder bool) {
	p := a.pane
	if p == nil {
		return
	}
	title := map[bool]string{true: "选择图片", false: "选择文件"}[images]
	if folder {
		title = "选择文件夹"
	}
	a.async(func() func() {
		paths := a.pick(title, images, folder)
		return func() { a.addPaths(p, paths, images) }
	})
}

// addPaths 收本机的这些路径（选的、拖进来的、粘贴的）：文件夹整个传上去；图片在输入栏还放得下时当图片附件，
// 读不了的和别的文件传上去。onlyImages（附件菜单的「图片」）时读不了的图不传，说一声。在主线程调用。
func (a *App) addPaths(p *chatPane, paths []string, onlyImages bool) {
	at := &p.att
	if len(paths) > attach.MaxFiles {
		at.report("一次最多收 "+strconv.Itoa(attach.MaxFiles)+" 个文件，另外 "+strconv.Itoa(len(paths)-attach.MaxFiles)+" 个没有加进来", false)
		paths = paths[:attach.MaxFiles]
	}
	for _, local := range paths {
		st, err := os.Stat(local)
		switch {
		case err != nil:
			at.report("「"+filepath.Base(local)+"」读不了，没有加进来", false)
		case st.IsDir():
			a.uploadFolder(p, local)
		case (onlyImages || attach.IsImage(local)) && at.roomForImages() > 0:
			a.loadImage(p, local, st.Size(), onlyImages)
		case onlyImages:
			at.report("一条消息最多带 "+strconv.Itoa(attach.MaxImages)+" 张图片，「"+filepath.Base(local)+"」没有加进来", false)
		default:
			a.uploadFile(p, host.Upload{Local: local}, filepath.Base(local), st.Size())
		}
	}
}

// loadImage 在后台读、缩、编码一张图，好了放进输入栏；读不了的当文件传（onlyImages 时只说一声）。
func (a *App) loadImage(p *chatPane, local string, size int64, onlyImages bool) {
	p.att.loading++
	a.async(func() func() {
		data, err := os.ReadFile(local)
		var im *draftImage
		if err == nil {
			im, err = encodeImage(data)
		}
		return func() {
			p.att.loading--
			switch {
			case err == nil:
				im.id = p.att.id()
				p.att.images = append(p.att.images, im)
			case onlyImages:
				p.att.report("有一张图片读不了，没有加进来", false)
			default:
				a.uploadFile(p, host.Upload{Local: local}, filepath.Base(local), size)
			}
		}
	})
}

// encodeImage 把图片文件的内容做成待发的图（编码和解缩略图都耗时，在后台调用）。
func encodeImage(data []byte) (*draftImage, error) {
	img, jpeg, err := attach.EncodeImage(data)
	if err != nil {
		return nil, err
	}
	thumb, _ := ui.DecodeBitmap(jpeg)
	return &draftImage{image: img, thumb: thumb}, nil
}

// addImageData 收一张内存里的图（粘贴的）：输入栏放得下时当图片附件，放不下的当文件传。
func (a *App) addImageData(p *chatPane, data []byte, name string) {
	if p.att.roomForImages() <= 0 {
		a.uploadFile(p, host.Upload{Name: name, Data: data}, name, int64(len(data)))
		return
	}
	p.att.loading++
	a.async(func() func() {
		im, err := encodeImage(data)
		return func() {
			p.att.loading--
			if err != nil {
				p.att.report("剪贴板里的图片读不了，没有加进来", false)
				return
			}
			im.id = p.att.id()
			p.att.images = append(p.att.images, im)
		}
	})
}

// uploadFile 把一个文件传到对话所在的设备上；太大的不传。
func (a *App) uploadFile(p *chatPane, u host.Upload, name string, size int64) {
	if attach.TooLarge(size) {
		p.att.report(attach.TooLargeReason(name, size), true)
		return
	}
	a.startUpload(p, u, &draftFile{name: name, size: size})
}

// uploadFolder 把本机的文件夹整个传上去，传好后当文件夹引用（@路径/）。
func (a *App) uploadFolder(p *chatPane, local string) {
	name := filepath.Base(local)
	size, err := hostlink.FolderSize(local)
	switch {
	case err != nil:
		p.att.report("文件夹「"+name+"」读不了，没有加进来", false)
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
		a.addPaths(p, paths, false)
		return true
	}
	if png, err := data.Read(transfer.PNG); err == nil && len(png) > 0 {
		a.addImageData(p, png, "粘贴的图片-"+a.now().Format("20060102-150405")+".png")
		return true
	}
	return false
}

// putBack 把撤回的、没发出去的一条放回输入栏：文字接在已有的前面，图补上放得下的，附件说明补上没有的。
func (p *chatPane) putBack(m chat.Message) {
	if m.Text != "" {
		p.recall(m.Text)
	}
	for _, img := range m.Images {
		if p.att.roomForImages() <= 0 {
			break
		}
		im := &draftImage{id: p.att.id(), image: img}
		if data, err := base64.StdEncoding.DecodeString(img.Data); err == nil {
			im.thumb, _ = ui.DecodeBitmap(data)
		}
		p.att.images = append(p.att.images, im)
	}
	for _, entry := range m.Files {
		if !containsEntry(p.att.files, entry) {
			p.att.files = append(p.att.files, p.att.newEntryFile(entry, ""))
		}
	}
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

// tooLargeAdvice 跟在「太大没收」的说明后面。
const tooLargeAdvice = "太大的文件传到设备上要多占一份同样大的空间，Claude 也读不完。要让 Claude 处理它，可以先把它放到设备上，" +
	"再从附件菜单的「输入路径」引用。"

// attachProblems 告诉用户哪些没有加进来、为什么（同手机版的 IntakeProblemsDialog）。
func (a *App) attachProblems(c *ui.Context, p *chatPane) {
	at := &p.att
	open := len(at.problems) > 0
	if !open {
		return
	}
	message := strings.Join(at.problems, "。\n") + "。"
	if at.tooLarge {
		message += "\n\n" + tooLargeAdvice
	}
	coui.Alert(c, &open, "有些内容没有加进来", message, coui.Action{Label: "好", Style: coui.Recommend})
	if !open {
		at.problems, at.tooLarge = nil, false
	}
}
