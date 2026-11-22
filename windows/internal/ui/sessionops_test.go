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
)

// opsFixture 是 box 上列着三个会话的侧栏：修登录页（今天）、看看日志（今天）、部署脚本（更早）。
func newOpsFixture(t *testing.T) *chatFixture {
	t.Helper()
	f := newChatFixture(t)
	f.devices.Push(f.id, hostlink.SessionsUpdate{Full: true, Upsert: []hostlink.Session{
		{ID: "s1", File: "/home/box/.claude/projects/-w/s1.jsonl", Title: "修登录页", Cwd: "/home/box/web", Updated: at(14)},
		{ID: "s2", File: "/home/box/.claude/projects/-w/s2.jsonl", Title: "看看日志", Cwd: "/srv/logs", Updated: at(10)},
		{ID: "s3", File: "/home/box/.claude/projects/-w/s3.jsonl", Title: "部署脚本", Cwd: "/srv/deploy", Updated: at(14) - 30*3600*1000},
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
	if got, want := f.tt.Menu(), []string{"选择", "置顶", "重命名", "导出", "归档", "-", "删除"}; !reflect.DeepEqual(got, want) {
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
	f.see(t, "查 nginx 日志")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.rename {"session":"s2","title":"查 nginx 日志"}`) {
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

	// 正在进行的：只提示
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s2": {State: "answering"}})
	f.tt.Frame()
	f.menu(t, "看看日志", "删除")
	f.see(t, "这个对话正在进行", "知道了")
	f.click(t, "知道了")
	if len(f.devices.SessionCalls()) != 0 {
		t.Error("正在进行的删了")
	}
}

func TestMultiSelect(t *testing.T) {
	f := newOpsFixture(t)
	f.devices.PushActivity(f.id, map[string]hostlink.Activity{"s1": {State: "waiting"}})
	f.menu(t, "看看日志", "选择")
	f.see(t, "已选 1 个", "全选", "归档", "导出", "删除")
	f.notSee(t, "搜索对话")
	// 点一行是勾选，不打开
	f.click(t, "部署脚本")
	f.see(t, "已选 2 个")
	if f.hub.Chat() != nil {
		t.Error("多选时点一行打开了")
	}
	f.click(t, "全选")
	f.see(t, "已选 3 个", "全不选")
	// 删除：正在进行的（等确认）不删，注明
	f.clickNamed(t, "删除", 0)
	f.see(t, "删除 2 个对话？", "另有 1 个正在进行的对话不会删除。")
	f.clickNamed(t, "删除", 0) // 对话框开着时只有它的按钮
	f.tt.Frame()
	got := f.devices.SessionCalls()
	slices.Sort(got) // 几个一起发，先后不定
	if len(got) != 2 || !strings.Contains(got[0], `"s2"`) || !strings.Contains(got[1], `"s3"`) {
		t.Errorf("发出 %q", got)
	}
	f.see(t, "修登录页", "搜索对话")
	f.notSee(t, "看看日志", "部署脚本", "已选")

	// 批量归档后退出多选
	f.menu(t, "修登录页", "选择")
	f.click(t, "归档")
	f.tt.Frame()
	f.notSee(t, "修登录页", "已选")
	if got := f.devices.SessionCalls(); len(got) != 1 || !strings.Contains(got[0], `sessions.archive {"archived":true,"session":"s1"}`) {
		t.Errorf("发出 %q", got)
	}
}

func TestExportSavesWhereTheUserChose(t *testing.T) {
	f := newOpsFixture(t)
	dir := t.TempDir()
	var asked []string
	f.app.ops.save = func(name string) (string, error) {
		asked = append(asked, name)
		return filepath.Join(dir, "out.md"), nil
	}
	f.menu(t, "部署脚本", "导出")
	data, err := os.ReadFile(filepath.Join(dir, "out.md"))
	if err != nil || string(data) != "# 对话 s3\n\n正文\n" {
		t.Fatalf("存下 %q、%v", data, err)
	}
	if len(asked) != 1 || asked[0] != "Zeta-对话 s3-20261122-1500.md" {
		t.Errorf("建议的文件名 %q", asked)
	}
	// 几个一起导出：存好后退出多选；取消保存时什么都不做
	f.menu(t, "部署脚本", "选择")
	f.click(t, "修登录页")
	f.click(t, "导出")
	f.tt.Frame()
	if len(asked) != 2 || asked[1] != "Zeta-2个对话-20261122-1500.md" || f.app.ops.selecting {
		t.Errorf("导出几个：%q，多选 %v", asked, f.app.ops.selecting)
	}
	f.app.ops.save = func(string) (string, error) { return "", nil }
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
			f.menu(t, "看看日志", "选择")
			f.click(t, "部署脚本")
		},
		"session-delete": func(t *testing.T, f *chatFixture) { f.menu(t, "部署脚本", "删除") },
		"session-handoff": func(t *testing.T, f *chatFixture) {
			f.click(t, "修登录页")
			f.tt.Frame()
			f.devices.Occupants = map[string]string{f.hub.Chat().Conv.Session(): `{"status":"waiting","waiting_for":"Bash","hands_over":true}`}
			f.push(`"blocked":true`)
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
