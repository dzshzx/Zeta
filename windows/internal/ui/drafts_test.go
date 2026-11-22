package ui

import (
	"encoding/base64"
	"image/color"
	"os"
	"path/filepath"
	"testing"

	"github.com/dzshzx/Zeta/client/drafts"
	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/windows/internal/attach"
	"github.com/dzshzx/Zeta/windows/internal/chat"
	"github.com/dzshzx/Zeta/windows/internal/settings"
	"github.com/egoist/mygo/ui"
)

func TestDraftsFollowEachConversation(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.click(t, "消息")
	f.tt.Type("登录页的按钮")
	// 切到新对话再回来：字还在；新对话有自己的一份
	f.newChat(t)
	if f.app.pane.draft != "" {
		t.Fatalf("新对话带着别人的草稿：%q", f.app.pane.draft)
	}
	f.click(t, "消息")
	f.tt.Type("新对话里没发的")
	f.click(t, "修登录页")
	if f.app.pane.draft != "登录页的按钮" {
		t.Fatalf("回来后输入框是 %q", f.app.pane.draft)
	}
	f.newChat(t)
	if f.app.pane.draft != "新对话里没发的" {
		t.Fatalf("再点新对话：%q", f.app.pane.draft)
	}
	// 发出去了就不再是草稿；拿到会话号以后下一个新对话从空的开始
	f.click(t, "消息")
	f.tt.Key(0, ui.KeyEnter)
	f.push(`"session":"s9"`)
	f.newChat(t)
	if f.app.pane.draft != "" {
		t.Fatalf("发出后的新对话：%q", f.app.pane.draft)
	}
	saved := drafts.Decode(draftsOnDisk(t, f))
	if len(saved) != 1 || saved[0].Key != "s1" || saved[0].Text != "登录页的按钮" {
		t.Fatalf("存盘的：%+v", saved)
	}
}

func TestDraftsSurviveRestart(t *testing.T) {
	f := newChatFixture(t)
	f.devices.Paths = map[string]hostlink.PathKind{"/home/box/.cache/zeta/files/a/报告.pdf": hostlink.PathFile}
	f.click(t, "修登录页")
	f.click(t, "消息")
	f.tt.Type("看看这些")
	jpeg := picture(40, 30, color.NRGBA{0x28, 0x78, 0xDC, 255}, color.NRGBA{0xFA, 0xD2, 0x78, 255})
	f.app.pane.att.images = append(f.app.pane.att.images, &draftImage{id: f.app.pane.att.id(), image: chat.Image{MediaType: "image/png", Data: base64.StdEncoding.EncodeToString(jpeg)}})
	for _, entry := range []string{"/home/box/.cache/zeta/files/a/报告.pdf", "/home/box/.cache/zeta/files/b/已清掉.txt", "@/home/box/web/src/"} {
		f.app.pane.att.files = append(f.app.pane.att.files, &draftFile{id: f.app.pane.att.id(), name: attach.EntryName(entry), size: -1, entry: entry})
	}
	f.tt.Frame()
	f.tt.Advance(draftSaveDelay)
	images, err := os.ReadDir(filepath.Join(f.hub.Settings().DraftsDir(), "drafts"))
	if err != nil || len(images) != 1 {
		t.Fatalf("图片文件：%v %v", images, err)
	}
	// 重开：内存里的没了，从存盘的恢复；传上去又被清掉的文件去掉，引用的不查
	f.newChat(t)
	f.app.drafts = nil
	f.click(t, "修登录页")
	p := f.app.pane
	if p.draft != "看看这些" || len(p.att.images) != 1 || p.att.images[0].thumb == nil || p.att.images[0].image.Data != base64.StdEncoding.EncodeToString(jpeg) {
		t.Fatalf("恢复的：%q，图 %d 张 %v", p.draft, len(p.att.images), len(p.att.images) > 0 && p.att.images[0].thumb != nil)
	}
	var entries []string
	for _, x := range p.att.files {
		entries = append(entries, x.entry)
	}
	if len(entries) != 2 || entries[0] != "/home/box/.cache/zeta/files/a/报告.pdf" || entries[1] != "@/home/box/web/src/" {
		t.Fatalf("恢复的附件：%v", entries)
	}
	f.see(t, "报告.pdf", "src")
	// 清空以后存着的那份和图片文件都删掉
	p.att.clear()
	p.draft = ""
	f.tt.Frame()
	f.tt.Advance(draftSaveDelay)
	if saved := drafts.Decode(draftsOnDisk(t, f)); len(saved) != 0 {
		t.Fatalf("清空后还存着：%+v", saved)
	}
	if images, _ := os.ReadDir(filepath.Join(f.hub.Settings().DraftsDir(), "drafts")); len(images) != 0 {
		t.Fatalf("图片文件没删：%v", images)
	}
}

// draftsOnDisk 是设置目录里存草稿的那一项。
func draftsOnDisk(t *testing.T, f *chatFixture) string {
	t.Helper()
	store, err := settings.Open(filepath.Join(f.hub.Settings().DraftsDir(), "zeta_drafts.json"))
	if err != nil {
		t.Fatal(err)
	}
	text, _ := store.String("drafts")
	return text
}

// 打字时不是每个字都存盘：停下约一秒才存一次；切到别的对话时马上存。
func TestDraftsSaveAfterTypingStops(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	f.click(t, "消息")
	for _, r := range "登录页的按钮再大一点" {
		f.tt.Type(string(r))
		f.tt.Advance(draftSaveDelay / 5)
		if text := draftsOnDisk(t, f); text != "" {
			t.Fatalf("还在打字就存了：%s", text)
		}
	}
	f.tt.Advance(draftSaveDelay)
	if saved := drafts.Decode(draftsOnDisk(t, f)); len(saved) != 1 || saved[0].Text != "登录页的按钮再大一点" {
		t.Fatalf("停下以后存的：%+v", saved)
	}
	f.tt.Type("，颜色也换")
	f.newChat(t)
	if saved := drafts.Decode(draftsOnDisk(t, f)); len(saved) != 1 || saved[0].Text != "登录页的按钮再大一点，颜色也换" {
		t.Fatalf("切走时存的：%+v", saved)
	}
	// 关窗口时也马上存
	f.click(t, "消息")
	f.tt.Type("新对话里的")
	f.app.SaveDrafts()
	if saved := drafts.Decode(draftsOnDisk(t, f)); len(saved) != 2 {
		t.Fatalf("关窗口时存的：%+v", saved)
	}
}

// 编辑消息时存的草稿是开始编辑前输入栏里的，不是编辑中的字；切走时编辑作废，输入栏放回原样。
func TestDraftsIgnoreMessageBeingEdited(t *testing.T) {
	f := openedForActions(t)
	f.click(t, "消息")
	f.tt.Type("还没发的草稿")
	if err := f.tt.RightClick("看看有什么文件"); err != nil {
		t.Fatal(err)
	}
	if err := f.tt.ChooseMenuItem("编辑"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	f.app.pane.draft = "看看有什么 Go 文件"
	f.tt.Frame()
	f.tt.Advance(draftSaveDelay)
	if saved := drafts.Decode(draftsOnDisk(t, f)); len(saved) != 1 || saved[0].Text != "还没发的草稿" {
		t.Fatalf("编辑时存的：%+v", saved)
	}
	f.newChat(t)
	f.click(t, "修登录页")
	if f.app.pane.draft != "还没发的草稿" || f.app.pane.acts.editing != nil {
		t.Fatalf("回来后输入栏：%q", f.app.pane.draft)
	}
}
