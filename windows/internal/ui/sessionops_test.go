package ui

import (
	"image/png"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/dzshzx/Zeta/helper/sessionproto"
	"github.com/dzshzx/Zeta/windows/internal/hubtest"
	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
)

// opsFixture 是 box 上列着三个会话的侧栏：修登录页（今天）、看看日志（今天）、部署脚本（更早，进程已经结束）。
func newOpsFixture(t *testing.T) *chatFixture {
	t.Helper()
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", File: "/home/box/.claude/projects/-w/s1.jsonl", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14)},
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "看看日志", Cwd: "/srv/logs", Updated: at(10)},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "部署脚本", Cwd: "/srv/deploy", Updated: at(14) - 30*3600*1000, Ended: true},
	}})
	f.tt.Frame()
	return f
}

func (f *chatFixture) menu(t *testing.T, row, item string) {
	t.Helper()
	if err := f.tt.RightClick(row); err != nil {
		t.Fatalf("右键 %q：%v", row, err)
	}
	if err := f.tt.ChooseMenuItem(item); err != nil {
		t.Fatalf("菜单 %q：%v；有 %q", item, err, f.tt.Menu())
	}
	f.tt.Frame()
}

func TestSessionMenu(t *testing.T) {
	f := newOpsFixture(t)
	if err := f.tt.RightClick("看看日志"); err != nil {
		t.Fatal(err)
	}
	if got, want := f.tt.Menu(), []string{"置顶", "重命名", "导出", "归档", "结束对话", "-", "删除"}; !reflect.DeepEqual(got, want) {
		t.Errorf("菜单 %q", got)
	}
	f.tt.CloseMenu()

	// 重命名：框里是原来的标题，改了确定
	f.menu(t, "看看日志", "重命名")
	if f.app.ops.renaming.draft != "看看日志" {
		t.Fatalf("草稿 %q", f.app.ops.renaming.draft)
	}
	f.app.ops.renaming.draft = "  查 nginx   日志 "
	f.tt.Frame()
	f.click(t, "确定")
	f.see(t, "查 nginx 日志") // 核心答的：空白压过
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.rename {"session":"s2","title":"  查 nginx   日志 "}`) {
		t.Errorf("发出 %q", got)
	}

	// 归档：主列表里不见了，底部出现归档的入口，点进去能取消归档
	f.menu(t, "查 nginx 日志", "归档")
	f.notSee(t, "查 nginx 日志")
	f.click(t, "已归档 · 1")
	f.see(t, "已归档 · 1", "查 nginx 日志")
	f.notSee(t, "部署脚本")
	f.menu(t, "查 nginx 日志", "取消归档")
	f.see(t, "部署脚本", "查 nginx 日志") // 没有归档的了：回到全部对话
	if got := f.devices.SessionCalls(); len(got) != 2 || !strings.Contains(got[1], `"archived":false`) {
		t.Errorf("发出 %q", got)
	}
}

func TestDeleteAsksFirst(t *testing.T) {
	f := newOpsFixture(t)
	f.menu(t, "部署脚本", "删除")
	f.see(t, "删除这个对话？", "部署脚本\n会话记录文件会被一并删除，无法恢复。")
	f.click(t, "取消")
	f.tt.Frame()
	if f.shows("删除这个对话？") || len(f.devices.SessionCalls()) != 0 {
		t.Fatal("取消了还删")
	}
	f.menu(t, "部署脚本", "删除")
	f.clickNamed(t, "删除", 0)
	f.tt.Frame()
	f.notSee(t, "部署脚本")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.delete {"session":"s3"}`) {
		t.Errorf("发出 %q", got)
	}

	// 正在进行的也不置灰，交给核心：它说正在进行的没删，放回列表，说一声
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}})
	f.devices.ErrBySession = map[string]error{"s1": hubtest.Refuse("对话正在进行，结束后再删", sessionproto.CodeActive)}
	f.menu(t, "修登录页", "删除")
	f.clickNamed(t, "删除", 0)
	f.tt.Frame()
	f.see(t, "这个对话正在进行", "对话正在进行，结束后再删")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.delete {"session":"s1"}`) {
		t.Errorf("发出 %q", got)
	}
	f.click(t, "好")
	f.see(t, "修登录页")
}

func TestMultiSelect(t *testing.T) {
	f := newOpsFixture(t)
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}})
	// Ctrl+点击加选，不打开
	f.ctrlClick(t, "看看日志")
	f.ctrlClick(t, "部署脚本")
	if f.hub.Chat() != nil {
		t.Error("Ctrl+点击打开了对话")
	}
	if n := len(f.app.chosen()); n != 2 {
		t.Fatalf("选中 %d 个", n)
	}
	// Shift+点击从上次点的那个选到这个
	if err := f.tt.ClickWith(ui.Shift, "修登录页"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	if n := len(f.app.chosen()); n != 3 {
		t.Fatalf("Shift+点击以后选中 %d 个", n)
	}
	// 在选中的行上右键是对这几个一起
	if err := f.tt.RightClick("看看日志"); err != nil {
		t.Fatal(err)
	}
	if got, want := f.tt.Menu(), []string{"归档 3 个对话", "导出 3 个对话", "结束 2 个对话", "-", "删除 3 个对话"}; !reflect.DeepEqual(got, want) {
		t.Errorf("菜单 %q", got)
	}
	// 删除：这边不筛，都交给核心；它说正在进行的（等确认的 s1）没删，说一声，其余照删
	f.devices.ErrBySession = map[string]error{"s1": hubtest.Refuse("对话正在进行，结束后再删", sessionproto.CodeActive)}
	if err := f.tt.ChooseMenuItem("删除 3 个对话"); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
	f.see(t, "删除 3 个对话？")
	f.clickNamed(t, "删除", 0) // 对话框开着时只有它的按钮
	f.tt.Frame()
	got := f.devices.SessionCalls()
	slices.Sort(got) // 几个一起发，先后不定
	if len(got) != 3 || !strings.Contains(got[0], `"s1"`) || !strings.Contains(got[1], `"s2"`) || !strings.Contains(got[2], `"s3"`) {
		t.Errorf("发出 %q", got)
	}
	f.see(t, "这个对话正在进行", "1 个没成功：对话正在进行，结束后再删")
	f.click(t, "好")
	// 核心没删的照原样放回列表，选择取消了
	f.see(t, "修登录页")
	f.notSee(t, "看看日志", "部署脚本")
	if len(f.app.ops.selected) != 0 {
		t.Errorf("删完还选着 %v", f.app.ops.selected)
	}

	f.devices.ErrBySession = nil
	// Delete 键问删不删选中的；Esc 取消选择
	f.ctrlClick(t, "修登录页")
	f.tt.Key(0, ui.KeyDelete)
	f.tt.Frame()
	f.see(t, "删除 1 个对话？")
	f.clickNamed(t, "取消", 0)
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	if len(f.app.ops.selected) != 0 {
		t.Errorf("Esc 以后还选着 %v", f.app.ops.selected)
	}
	// 只选一个时右键是这一个的菜单；归档
	f.ctrlClick(t, "修登录页")
	f.menu(t, "修登录页", "归档")
	f.notSee(t, "修登录页")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.archive {"archived":true,"session":"s1"}`) {
		t.Errorf("发出 %q", got)
	}
}

// ctrlClick 按着 Ctrl 点一行。
func (f *chatFixture) ctrlClick(t *testing.T, row string) {
	t.Helper()
	if err := f.tt.ClickWith(ui.Cmd, row); err != nil {
		t.Fatal(err)
	}
	f.tt.Frame()
}

func TestExportSavesWhereTheUserChose(t *testing.T) {
	f := newOpsFixture(t)
	var asked []string
	var data []byte
	f.app.saveFile = func(title, name string, filters []mygo.FileFilter, content []byte) (string, error) {
		if title != "导出对话" || len(filters) != 1 || filters[0].Extensions[0] != "md" {
			t.Errorf("保存框 %q、%+v", title, filters)
		}
		asked, data = append(asked, name), content
		return "out.md", nil
	}
	f.menu(t, "部署脚本", "导出")
	if string(data) != "# 对话 s3\n\n正文\n" {
		t.Fatalf("存下 %q", data)
	}
	if len(asked) != 1 || asked[0] != "Zeta-对话 s3-20261122-1500.md" {
		t.Errorf("建议的文件名 %q", asked)
	}
	// 几个一起导出：存好后取消选择；取消保存时什么都不做
	f.ctrlClick(t, "部署脚本")
	f.ctrlClick(t, "修登录页")
	f.menu(t, "部署脚本", "导出 2 个对话")
	f.tt.Frame()
	if len(asked) != 2 || asked[1] != "Zeta-2个对话-20261122-1500.md" || len(f.app.ops.selected) != 0 {
		t.Errorf("导出几个：%q，还选着 %v", asked, f.app.ops.selected)
	}
	f.app.saveFile = func(string, string, []mygo.FileFilter, []byte) (string, error) { return "", nil }
	f.menu(t, "部署脚本", "导出")
	f.notSee(t, "导出没成功")
}

// TestSessionOpsPreviews 在设了 ZETA_PREVIEW_DIR 时把侧栏的右键菜单、多选、删除确认和接管卡片画成 PNG（浅色、深色）。
func TestSessionOpsPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	scenes := map[string]func(t *testing.T, f *chatFixture){
		"session-menu": func(t *testing.T, f *chatFixture) {
			if err := f.tt.RightClick("看看日志"); err != nil {
				t.Fatal(err)
			}
		},
		"session-select": func(t *testing.T, f *chatFixture) {
			f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}})
			f.ctrlClick(t, "看看日志")
			f.ctrlClick(t, "部署脚本")
		},
		"session-delete": func(t *testing.T, f *chatFixture) { f.menu(t, "部署脚本", "删除") },
		"session-handoff": func(t *testing.T, f *chatFixture) {
			f.click(t, "修登录页")
			f.tt.Frame()
			f.push(`"blocked":true,"can_rewind":false,"occupant":{"status":"waiting","waiting_for":"Bash","hands_over":true}`)
		},
	}
	for name, setup := range scenes {
		for _, dark := range []bool{false, true} {
			f := newOpsFixture(t)
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			setup(t, f)
			f.tt.Advance(2 * time.Second)
			f.tt.Frame()
			suffix := "light"
			if dark {
				suffix = "dark"
			}
			out, err := os.Create(filepath.Join(dir, name+"-"+suffix+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, withCaptionButtons(f.tt.Image(), dark)); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}

// 结束对话（同手机版的结束对话、终端里的 /exit）：还在运行的会话右键里才有；闲着的直接结束（sessions.end），
// 还在进行的先问，别处运行的问过以后经 sessions.end_elsewhere 停掉那边；结束了的马上标成已结束。
func TestEndConversation(t *testing.T) {
	f := newOpsFixture(t)
	if err := f.tt.RightClick("部署脚本"); err != nil {
		t.Fatal(err)
	}
	if slices.Contains(f.tt.Menu(), "结束对话") {
		t.Errorf("已结束的会话菜单里有结束对话：%q", f.tt.Menu())
	}
	f.tt.CloseMenu()

	// 闲着的：不问，直接结束
	f.menu(t, "看看日志", "结束对话")
	if f.shows("结束这个对话？") {
		t.Fatal("闲着的也问了")
	}
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.end {"session":"s2"}`) {
		t.Fatalf("发出 %q", got)
	}
	if !f.ended("s2") {
		t.Error("结束了没标成已结束")
	}

	// 还在回答的：先问，取消了不结束
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "answering"}})
	f.tt.Frame()
	f.menu(t, "修登录页", "结束对话")
	f.see(t, "结束这个对话？", "它还在进行（在回答、等你确认或有后台任务），结束会一并停掉。对话内容保留，可以从历史对话里接着聊。")
	f.clickNamed(t, "取消", 0)
	f.tt.Frame()
	if got := f.devices.SessionCalls(); len(got) != 0 {
		t.Fatalf("取消了还发 %q", got)
	}
	f.menu(t, "修登录页", "结束对话")
	f.clickNamed(t, "结束", 0)
	f.tt.Frame()
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.end {"session":"s1"}`) {
		t.Fatalf("发出 %q", got)
	}

	// 别处运行的：说明换成停掉那边的进程，经 sessions.end_elsewhere
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: []hostlink.Session{
		{ID: "s4", File: "/home/box/.claude/projects/-w/s4.jsonl", Title: "终端里开着的", Cwd: "/w", Updated: at(15), External: true},
	}})
	f.tt.Frame()
	f.menu(t, "终端里开着的", "结束对话")
	f.see(t, "它正在别处运行（比如终端里开着的 Claude Code），结束会停掉那边的进程，那边在做的也一并停掉。对话内容保留，可以从历史对话里接着聊。")
	f.clickNamed(t, "结束", 0)
	f.tt.Frame()
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.end_elsewhere {"session":"s4"}`) {
		t.Fatalf("发出 %q", got)
	}
}

// 多选时「结束 N 个对话」只算、只结束还在运行的；其中有还在进行的先问。
func TestEndSelectedConversations(t *testing.T) {
	f := newOpsFixture(t)
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}})
	f.ctrlClick(t, "修登录页")
	f.ctrlClick(t, "看看日志")
	f.ctrlClick(t, "部署脚本")
	f.menu(t, "看看日志", "结束 2 个对话")
	f.see(t, "结束 2 个对话？", "其中有还在进行（在回答、等你确认或有后台任务）或在别处运行的，结束会一并停掉。对话内容保留，可以从历史对话里接着聊。")
	f.clickNamed(t, "结束", 0)
	f.tt.Frame()
	got := f.devices.SessionCalls()
	slices.Sort(got)
	if len(got) != 2 || !strings.Contains(got[0], `sessions.end {"session":"s1"}`) || !strings.Contains(got[1], `sessions.end {"session":"s2"}`) {
		t.Fatalf("发出 %q", got)
	}
	if len(f.app.ops.selected) != 0 {
		t.Errorf("结束了还选着 %v", f.app.ops.selected)
	}

	// 选中的都结束了：批量菜单里不再有这一项
	f.ctrlClick(t, "看看日志")
	f.ctrlClick(t, "部署脚本")
	if err := f.tt.RightClick("看看日志"); err != nil {
		t.Fatal(err)
	}
	if slices.ContainsFunc(f.tt.Menu(), func(s string) bool { return strings.HasPrefix(s, "结束") }) {
		t.Errorf("都结束了还有：%q", f.tt.Menu())
	}
}

// ended：列表里会话 id 标成了已结束。
func (f *chatFixture) ended(id string) bool {
	for _, s := range f.hub.Sessions() {
		if s.ID == id {
			return s.Ended
		}
	}
	return false
}
