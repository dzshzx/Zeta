package ui

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/attach"
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
	if got := len(f.app.pane.att.images); got != 2 || (*asked)[0] != "选择图片" {
		t.Fatalf("选了两张图，输入栏里有 %d 张（问的是 %v）", got, *asked)
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
		}
		Files []string
	}
	if err := json.Unmarshal([]byte(sent[0]), &m); err != nil {
		t.Fatal(err)
	}
	if m.Text != "看看这些" || len(m.Images) != 2 || m.Images[0].MediaType != "image/jpeg" {
		t.Errorf("文字和图：%+v", m)
	}
	want := []string{"/home/box/.cache/zeta/files/00000001/需求说明.pdf", "@/home/box/.cache/zeta/files/00000002/资料/"}
	if strings.Join(m.Files, "|") != strings.Join(want, "|") {
		t.Errorf("files = %v, want %v", m.Files, want)
	}
	if up := f.devices.Uploaded(); len(up) != 2 || up[0].Limit != attach.MaxBytes || !up[1].Folder {
		t.Errorf("传上去的：%+v", up)
	}
	if at := f.app.pane.att; len(at.images)+len(at.files) != 0 {
		t.Errorf("发完输入栏里还有附件：%+v", at)
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
	f.see(t, "有些内容没有加进来", "「整盘备份.img」有 200 MB，超过 200 MB 的上限，没有传上去。")
	if up := f.devices.Uploaded(); len(up) != 1 || filepath.Base(up[0].Local) != "坏的.png" {
		t.Errorf("读不了的图当文件传，太大的不传：%+v", up)
	}
	f.click(t, "好")
	f.notSee(t, "有些内容没有加进来")
}

func TestPastingAnImageOrDroppingFiles(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.tt.SetClipboardData(transfer.New(transfer.NewItem(transfer.Bytes(transfer.PNG,
		picture(64, 64, color.NRGBA{200, 60, 60, 255}, color.NRGBA{255, 255, 255, 255})))))
	f.click(t, "消息")
	f.tt.Key(ui.Ctrl, ui.KeyV)
	if len(f.app.pane.att.images) != 1 || f.app.pane.draft != "" {
		t.Fatalf("粘贴图片：%d 张，输入框 %q", len(f.app.pane.att.images), f.app.pane.draft)
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
	if len(f.app.pane.att.images) != 2 || len(f.devices.Uploaded()) != 1 {
		t.Errorf("拖进来：%d 张图，传了 %d 个文件", len(f.app.pane.att.images), len(f.devices.Uploaded()))
	}
}

func TestEnteringAPathOnTheDevice(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.devices.Paths = map[string]hostlink.PathKind{"/home/box/notes": hostlink.PathFolder, "/srv/a.md": hostlink.PathFile}
	f.attach(t, "输入路径")
	f.see(t, "引用「box」上的路径")
	for input, want := range map[string]string{
		"notes":    "要输入以 / 开头的绝对路径",
		"/w/\"x\"": "路径里不能有双引号或控制字符",
		"/nope":    "没有这个路径：/nope",
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
	f.see(t, "还没有常用指令。点「新建」写一条。点一条就把它填进输入框，可以改了再发。")
	f.click(t, "新建")
	f.see(t, "新建常用指令")
	f.click(t, "内容")
	f.tt.Type("审查这次改动，列出风险")
	f.click(t, "保存")
	f.click(t, "新建")
	f.click(t, "名字")
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
	f.see(t, "删除这条常用指令？", "「审查这次改动，列出风险」删除后找不回来。")
	f.clickButton(t, "删除", 0)
	if got := f.hub.Prompts(); len(got) != 1 || got[0].Name != "周报" {
		t.Errorf("删除后：%+v", got)
	}
}
