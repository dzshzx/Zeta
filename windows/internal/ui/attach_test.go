package ui

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"maps"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/coreapi"
	"github.com/dzshzx/Zeta/hub/attach"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/transfer"
	"github.com/egoist/mygo/ui"
)

func init() { pathCheckDelay = 0 } // 测试里输入路径不用等

// picture 是一张 w×h 的渐变图（PNG），缩略图里看得出是张图。
func picture(w, h int, from, to color.NRGBA) []byte {
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			t := float64(x+y) / float64(w+h)
			mix := func(a, b uint8) uint8 { return uint8(float64(a)*(1-t) + float64(b)*t) }
			img.Set(x, y, color.NRGBA{mix(from.R, to.R), mix(from.G, to.G), mix(from.B, to.B), 255})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

// localFiles 在本机的临时目录里放几样东西：两张图、一份文档、一个带文件的文件夹。
func localFiles(t *testing.T) map[string]string {
	t.Helper()
	dir := t.TempDir()
	files := map[string][]byte{
		"海边.png":       picture(320, 200, color.NRGBA{40, 120, 220, 255}, color.NRGBA{250, 210, 120, 255}),
		"截图.png":       picture(200, 300, color.NRGBA{60, 180, 120, 255}, color.NRGBA{20, 40, 80, 255}),
		"需求说明.pdf":     []byte("%PDF-1.7 假的"),
		"资料/README.md": []byte("# 资料"),
	}
	paths := map[string]string{}
	for name, data := range files {
		path := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, data, 0o644); err != nil {
			t.Fatal(err)
		}
		paths[name] = path
	}
	paths["资料"] = filepath.Join(dir, "资料")
	return paths
}

// pickReturns 让附件菜单的系统对话框选中 paths（记下问的是什么）。
func (f *chatFixture) pickReturns(paths ...string) *[]string {
	var asked []string
	f.app.pick = func(title string, images, folder bool) []string {
		asked = append(asked, title)
		return paths
	}
	return &asked
}

// clickNamed 点读屏名字是 name 的第 n 个控件（从 0 数），不管是什么角色（菜单的触发器不是按钮）。
func (f *chatFixture) clickNamed(t *testing.T, name string, n int) {
	t.Helper()
	for _, a := range f.tt.AccessAll() {
		if a.Name != name {
			continue
		}
		if n == 0 {
			f.tt.ClickAt(a.Bounds.X+a.Bounds.W/2, a.Bounds.Y+a.Bounds.H/2)
			return
		}
		n--
	}
	t.Fatalf("no %q", name)
}

// attach 点「+」里的一项。
func (f *chatFixture) attach(t *testing.T, item string) {
	t.Helper()
	f.click(t, "添加附件")
	f.click(t, item)
}

func TestAttachingImagesFilesAndFolders(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	local := localFiles(t)

	asked := f.pickReturns(local["海边.png"], local["截图.png"])
	f.attach(t, "图片")
	if at := f.app.pane.att; len(at.images) != 0 || len(at.files) != 2 || (*asked)[0] != "选择图片" {
		t.Fatalf("选了两张图：内联图 %d 张、文件 %d 个（问的是 %v）", len(at.images), len(at.files), *asked)
	}
	for _, pic := range f.app.pane.att.files {
		if !pic.picture() || pic.shrunk == nil || pic.shrunk.thumb == nil {
			t.Errorf("「%s」应是带缩小图和缩略图的图片：%+v", pic.name, pic)
		}
	}
	f.pickReturns(local["需求说明.pdf"])
	f.attach(t, "文件")
	f.pickReturns(local["资料"])
	f.attach(t, "文件夹")
	f.see(t, "需求说明.pdf", "资料")
	if a, ok := f.tt.AccessByRole(ui.RoleButton, "发送"); !ok || a.Disabled {
		t.Fatal("有附件就能发")
	}

	f.click(t, "消息")
	f.tt.Type("看看这些")
	f.tt.Key(0, ui.KeyEnter)
	sent := f.sent("conv.send")
	if len(sent) != 1 {
		t.Fatalf("发出：%v", sent)
	}
	var m struct {
		Text   string
		Images []struct {
			MediaType string `json:"media_type"`
			Original  string
		}
		Files []string
	}
	if err := json.Unmarshal([]byte(sent[0]), &m); err != nil {
		t.Fatal(err)
	}
	if m.Text != "看看这些" || len(m.Images) != 2 || m.Images[0].MediaType != "image/jpeg" || m.Images[1].MediaType != "image/jpeg" {
		t.Errorf("文字和图：%+v", m)
	}
	// 图片的原文件也传上去，缩小图注明它是哪个文件的
	want := []string{"/home/box/.cache/zeta/files/00000001/海边.png", "/home/box/.cache/zeta/files/00000002/截图.png",
		"/home/box/.cache/zeta/files/00000003/需求说明.pdf", "@/home/box/.cache/zeta/files/00000004/资料/"}
	if strings.Join(m.Files, "|") != strings.Join(want, "|") {
		t.Errorf("files = %v, want %v", m.Files, want)
	}
	if len(m.Images) == 2 && (m.Images[0].Original != want[0] || m.Images[1].Original != want[1]) {
		t.Errorf("缩小图的 original：%q、%q", m.Images[0].Original, m.Images[1].Original)
	}
	if up := f.devices.Uploaded(); len(up) != 4 || up[0].Local != local["海边.png"] || up[0].Limit != attach.MaxBytes || !up[3].Folder {
		t.Errorf("传上去的：%+v", up)
	}
	if at := f.app.pane.att; len(at.images)+len(at.files) != 0 {
		t.Errorf("发完输入栏里还有附件：%+v", at)
	}
	// 发出去的附件消息里要用，设备上那份不删
	if removed := f.devices.Removed(); len(removed) != 0 {
		t.Errorf("发出去的附件被删了：%v", removed)
	}
}

// 输入栏里待发的图片点一下看大图，看的是本机的原文件（保存下来的就是它）；传好了又移除的，设备上那份删掉。
func TestDraftPictureOpensOriginalAndRemovalDeletesUpload(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	local := localFiles(t)
	f.pickReturns(local["海边.png"])
	f.attach(t, "图片")
	pic := f.app.pane.att.files[0]
	original, err := os.ReadFile(local["海边.png"])
	if err != nil {
		t.Fatal(err)
	}
	var got []byte
	f.app.saveFile = func(_, name string, _ []mygo.FileFilter, data []byte) (string, error) {
		got = data
		return name, nil
	}
	f.click(t, pic.label())
	waitFor(t, f, func() bool { return f.app.viewer != nil })
	f.click(t, "保存")
	waitFor(t, f, func() bool { return got != nil })
	if !bytes.Equal(got, original) {
		t.Fatalf("看的不是本机原文件：%d 字节，原文件 %d 字节", len(got), len(original))
	}
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	f.clickButton(t, "移除", 0)
	if removed := f.devices.WaitRemoved(1); removed[0] != "/home/box/.cache/zeta/files/00000001/海边.png" {
		t.Fatalf("删掉的 %v", removed)
	}
	if n := len(f.app.pane.att.files); n != 0 {
		t.Fatalf("输入栏里还有 %d 个", n)
	}
}

func TestUploadingFileHoldsSendAndCanBeRemoved(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	canceled := false
	f.app.pane.att.files = []*draftFile{{id: 1, name: "录像.zip", size: 50_000_000, sent: 20_000_000, cancel: func() { canceled = true }}}
	f.click(t, "消息")
	f.tt.Type("先别发")
	f.see(t, "正在上传 40% · 录像.zip · 50 MB")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "发送"); !a.Disabled {
		t.Error("还在传的时候不能发")
	}
	f.clickButton(t, "移除", 0)
	if !canceled || len(f.app.pane.att.files) != 0 {
		t.Errorf("移除：取消了 %v，还剩 %d 个", canceled, len(f.app.pane.att.files))
	}
}

func TestTooLargeOrUnreadableIsReported(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	big := filepath.Join(t.TempDir(), "整盘备份.img")
	if err := os.WriteFile(big, nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Truncate(big, attach.MaxBytes+1); err != nil { // 稀疏文件，不真占地方
		t.Fatal(err)
	}
	bad := filepath.Join(t.TempDir(), "坏的.png")
	if err := os.WriteFile(bad, []byte("not a png"), 0o644); err != nil {
		t.Fatal(err)
	}
	f.pickReturns(big, bad)
	f.attach(t, "文件")
	f.see(t, "部分内容未能添加", "「整盘备份.img」大小为 200 MB，超过 200 MB 上限，未上传。")
	if up := f.devices.Uploaded(); len(up) != 1 || filepath.Base(up[0].Local) != "坏的.png" {
		t.Errorf("读不了的图当文件传，太大的不传：%+v", up)
	}
	if at := f.app.pane.att; len(at.files) != 1 || at.files[0].picture() || at.files[0].shrunk != nil {
		t.Errorf("解不开的图只当文件，画成文件小标签：%+v", at.files)
	}
	f.click(t, "好")
	f.notSee(t, "部分内容未能添加")
}

func TestPastingAnImageOrDroppingFiles(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.tt.SetClipboardData(transfer.New(transfer.NewItem(transfer.Bytes(transfer.PNG,
		picture(64, 64, color.NRGBA{200, 60, 60, 255}, color.NRGBA{255, 255, 255, 255})))))
	f.click(t, "消息")
	f.tt.Key(ui.Ctrl, ui.KeyV)
	if at := f.app.pane.att; len(at.files) != 1 || at.files[0].shrunk == nil || len(at.images) != 0 || f.app.pane.draft != "" {
		t.Fatalf("粘贴图片：文件 %d 个、内联图 %d 张，输入框 %q", len(at.files), len(at.images), f.app.pane.draft)
	}
	if up := f.devices.Uploaded(); len(up) != 1 || len(up[0].Data) == 0 || !strings.HasSuffix(up[0].Name, ".png") {
		t.Errorf("粘贴的图原样传上去：%+v", up)
	}
	f.tt.SetClipboard("一段字")
	f.tt.Key(ui.Ctrl, ui.KeyV)
	if f.app.pane.draft != "一段字" {
		t.Errorf("粘贴文字照常贴进输入框：%q", f.app.pane.draft)
	}
	local := localFiles(t)
	if !f.tt.DropFiles(800, 400, local["需求说明.pdf"], local["海边.png"]) {
		t.Fatal("聊天页不收拖进来的文件")
	}
	f.tt.Frame()
	if at := f.app.pane.att; len(at.files) != 3 || !at.files[2].picture() || at.files[1].picture() || len(f.devices.Uploaded()) != 3 {
		t.Errorf("拖进来：文件 %d 个，传了 %d 个", len(at.files), len(f.devices.Uploaded()))
	}
}

func TestEnteringAPathOnTheDevice(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.devices.Paths = map[string]hostlink.PathKind{"/home/box/notes": hostlink.PathFolder, "/srv/a.md": hostlink.PathFile}
	f.attach(t, "输入路径")
	f.see(t, "引用「box」上的路径")
	for input, want := range map[string]string{
		"notes":    "请输入以 / 开头的绝对路径",
		"/w/\"x\"": "路径不能包含双引号或控制字符",
		"/nope":    "路径不存在：/nope",
	} {
		f.app.path.input = input
		f.tt.Frame()
		f.see(t, want, "不可用")
		if a, _ := f.tt.AccessByRole(ui.RoleButton, "引用"); !a.Disabled {
			t.Errorf("%q 也能引用", input)
		}
	}
	f.app.path.input = "~/notes/"
	f.tt.Frame()
	f.see(t, "文件夹")
	f.click(t, "引用")
	f.attach(t, "输入路径")
	f.app.path.input = "/srv/a.md"
	f.tt.Frame()
	f.see(t, "文件")
	f.click(t, "引用")
	if got := f.app.pane.att.entries(); strings.Join(got, "|") != "@/home/box/notes/|@/srv/a.md" {
		t.Errorf("引用了 %v", got)
	}
	f.see(t, "notes", "a.md")
}

func TestSavedPrompts(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.attach(t, "常用指令")
	f.see(t, "暂无常用指令。点击「新建」添加，或在已发送的消息上右键选择「收藏为常用指令」。点击条目即可填入输入框，修改后再发送。")
	f.click(t, "新建")
	f.see(t, "新建常用指令")
	f.click(t, "内容")
	f.tt.Type("审查这次改动，列出风险")
	f.click(t, "保存")
	f.click(t, "新建")
	f.click(t, "名称")
	f.tt.Type("周报")
	f.click(t, "内容")
	f.tt.Type("把这周的提交整理成周报")
	f.click(t, "保存")
	if got := f.hub.Prompts(); len(got) != 2 || got[0].Name != "周报" || got[1].Name != "" {
		t.Fatalf("存下的：%+v", got)
	}
	f.see(t, "常用指令", "周报", "把这周的提交整理成周报", "审查这次改动，列出风险")

	// 点一条填进空着的输入框
	f.click(t, "周报")
	if f.app.pane.draft != "把这周的提交整理成周报" {
		t.Errorf("输入框 = %q", f.app.pane.draft)
	}
	// 删掉一条
	f.attach(t, "常用指令")
	f.clickNamed(t, "更多操作", 1)
	f.click(t, "删除")
	f.see(t, "删除这条常用指令？", "「审查这次改动，列出风险」删除后无法恢复。")
	f.clickButton(t, "删除", 0)
	if got := f.hub.Prompts(); len(got) != 1 || got[0].Name != "周报" {
		t.Errorf("删除后：%+v", got)
	}
}

// 放回输入栏（撤回、没发出去、编辑）时，带 original 的图是那个图片文件的缩小图：挂回文件上，不另放一份；
// 不带的是单独的图。和图的先后无关。
func TestPuttingBackPairsShrunkImagesByOriginal(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	jpg := func(r uint8, original string) coreapi.Image {
		im, err := attach.EncodeImage(picture(40, 30, color.NRGBA{r, 0, 0, 255}, color.NRGBA{0, 0, r, 255}))
		if err != nil {
			t.Fatal(err)
		}
		im.Original = original
		return im
	}
	c, own, a := jpg(30, "/f/2/c.jpg"), jpg(10, ""), jpg(20, "/f/1/a.png")
	p := f.app.pane
	p.putBack(coreapi.Message{Images: []coreapi.Image{c, own, a}, Files: []string{"/f/1/a.png", "@/srv/b.png", "/f/2/c.jpg", "/f/3/d.pdf"}})
	if len(p.att.images) != 1 || !bytes.Equal(p.att.images[0].image.Data, own.Data) || p.att.images[0].thumb == nil {
		t.Fatalf("单独的图：%d 张", len(p.att.images))
	}
	got := map[string][]byte{}
	for _, x := range p.att.files {
		if x.shrunk != nil {
			got[x.entry] = x.shrunk.image.Data
			if !x.picture() || x.shrunk.thumb == nil {
				t.Errorf("「%s」应画成缩略图", x.entry)
			}
		}
	}
	if len(got) != 2 || !bytes.Equal(got["/f/1/a.png"], a.Data) || !bytes.Equal(got["/f/2/c.jpg"], c.Data) {
		t.Errorf("挂回的缩小图：%v", slices.Collect(maps.Keys(got)))
	}
	originals := map[string]string{}
	for _, im := range p.att.pictures() {
		originals[im.Original] = string(im.Data)
	}
	if len(originals) != 3 || originals[""] != string(own.Data) || originals["/f/1/a.png"] != string(a.Data) || originals["/f/2/c.jpg"] != string(c.Data) {
		t.Errorf("再发时的图：%v", slices.Collect(maps.Keys(originals)))
	}

	// 编辑时先放回文件、图后取回来再放：照样按 original 挂回去；少了一张图时其余的不受影响
	p.att.clear()
	p.putBack(coreapi.Message{Files: []string{"/f/1/a.png", "/f/2/c.jpg"}})
	p.putBack(coreapi.Message{Images: []coreapi.Image{c}})
	if len(p.att.images) != 0 || p.att.files[0].shrunk != nil || p.att.files[1].shrunk == nil || !bytes.Equal(p.att.files[1].shrunk.image.Data, c.Data) {
		t.Errorf("少了 a 的缩小图：单独的图 %d 张", len(p.att.images))
	}

	// original 对不上输入栏里的文件：把那个文件加上，图照样显示
	p.att.clear()
	p.putBack(coreapi.Message{Images: []coreapi.Image{a}})
	if len(p.att.files) != 1 || p.att.files[0].entry != "/f/1/a.png" || p.att.files[0].shrunk == nil || len(p.att.images) != 0 {
		t.Errorf("对不上时：文件 %d 个，单独的图 %d 张", len(p.att.files), len(p.att.images))
	}
}

// 排队条：图片文件和它的缩小图算一样，不重复计数。
func TestQueuedPreviewCountsPictureFilesOnce(t *testing.T) {
	m := coreapi.Message{Text: "看看", Images: []coreapi.Image{{Original: "/f/1/a.png"}, {}}, Files: []string{"/f/1/a.png", "/f/2/b.pdf"}}
	if got := queuedPreview(m); got != "看看 +3" {
		t.Errorf("queuedPreview = %q", got)
	}
}
