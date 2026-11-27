package ui

import (
	"fmt"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/client/hostlink"
	"github.com/egoist/mygo/ui"
)

// 侧栏的会话列表只建看得见的几行（sideRows）；看不见的照样能全选、滚过去能看到。
func TestSidebarBuildsOnlyRowsInView(t *testing.T) {
	f := newChatFixture(t)
	const n = 300
	var list []hostlink.Session
	for i := range n {
		id := fmt.Sprintf("s%03d", i)
		list = append(list, hostlink.Session{ID: id, File: "/home/box/.claude/projects/-w/" + id + ".jsonl",
			Title: fmt.Sprintf("会话 %03d", i), Cwd: "/srv/work", Updated: now.UnixMilli() - int64(i)*60_000})
	}
	f.devices.Push(f.id, hostlink.SessionsUpdate{Upsert: list})
	f.tt.Frame()
	f.tt.Frame()
	built := 0
	for _, text := range f.tt.Texts() {
		if strings.HasPrefix(text, "会话 ") {
			built++
		}
	}
	if !f.tt.HasText("会话 000") || f.tt.HasText(fmt.Sprintf("会话 %03d", n-1)) || built > 2*60 {
		t.Fatalf("%d 个会话建了 %d 段会话标题（最后一个建了：%v）", n, built, f.tt.HasText(fmt.Sprintf("会话 %03d", n-1)))
	}

	if err := f.tt.ClickWith(ui.Cmd, "会话 000"); err != nil {
		t.Fatal(err)
	}
	f.tt.Key(ui.Cmd, ui.KeyA)
	f.tt.Frame()
	if got, want := len(f.app.chosen()), len(f.app.visible()); got != want || want <= n {
		t.Fatalf("Ctrl+A 选中 %d 个，列着 %d 个", got, want)
	}

	f.app.sideList.ScrollToEnd()
	f.tt.Frame()
	f.tt.Frame()
	if !f.tt.HasText(fmt.Sprintf("会话 %03d", n-1)) || f.tt.HasText("会话 150") { // 点过的「会话 000」有焦点，List 留着它
		t.Fatalf("滚到底以后界面上：%q", f.tt.Texts())
	}
}
