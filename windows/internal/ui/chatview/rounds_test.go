package chatview

import (
	"fmt"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
	"github.com/egoist/mygo/ui"
)

// roundsItems 是三轮：第一轮的操作挂在工作过程下面，第二轮只有灰色提示（没有 round_last），第三轮挂在回答下面。
func roundsItems(t *testing.T) []items.Item {
	return decodeItems(t, `[
{"kind":"notice","id":"n0","text":"开头的提示"},
{"kind":"user","id":"u1","text":"一","round_last":"w1"},{"kind":"work","id":"w1","steps":[]},
{"kind":"user","id":"u2","text":"二"},{"kind":"notice","id":"n2","text":"灰色提示"},
{"kind":"user","id":"u3","text":"三","round_last":"a3"},{"kind":"answer","id":"a3","text":"答"}]`)
}

// 各轮挂在哪一项下面、之后还有几轮在条目换了时算好：SetItems、Splice 都重算，越界的 Splice 不改。
func TestRoundsAreIndexedWhenItemsChange(t *testing.T) {
	ch := New()
	ch.Actions = func(*ui.Context, items.Item, int) {}
	ch.SetItems(roundsItems(t))
	at := func() string {
		out := ""
		for row, it := range ch.items {
			if user, later, ok := ch.roundAt(row); ok {
				out += fmt.Sprintf("%s→%s+%d ", it.ID, user.ID, later)
			}
		}
		return out
	}
	if got := at(); got != "w1→u1+2 a3→u3+0 " {
		t.Errorf("挂操作的：%s", got)
	}
	if ch.laterThan(1) != 2 || ch.laterThan(3) != 1 || ch.laterThan(5) != 0 {
		t.Errorf("之后几轮：%d %d %d", ch.laterThan(1), ch.laterThan(3), ch.laterThan(5))
	}
	// 删掉第二轮
	if err := ch.Splice(3, 2, nil); err != nil {
		t.Fatal(err)
	}
	if got := at(); got != "w1→u1+1 a3→u3+0 " || ch.laterThan(1) != 1 {
		t.Errorf("删掉一轮后：%s", got)
	}
	if ch.Splice(99, 1, nil) == nil || at() != "w1→u1+1 a3→u3+0 " {
		t.Errorf("越界的 Splice 改了：%s", at())
	}
	// 没给 Actions 时不挂
	ch.Actions = nil
	if got := at(); got != "" {
		t.Errorf("没给 Actions：%s", got)
	}
}

// 操作排只在挂操作的那一项下面建；UserMenu、Actions 拿到之后还有几轮。
func TestActionsGetLaterRounds(t *testing.T) {
	ch := New()
	rows := map[string]bool{}
	ch.Actions = func(c *ui.Context, user items.Item, later int) {
		rows[fmt.Sprintf("%s+%d", user.ID, later)] = true
		ui.Text(c, "操作 "+user.ID)
	}
	var menu []string
	ch.UserMenu = func(_ *ui.Menu, user items.Item, later int) {
		menu = append(menu, fmt.Sprintf("%s+%d", user.ID, later))
	}
	ch.SetItems(roundsItems(t))
	tt := newTester(ch, 900, 2000, false)
	tt.Frame()
	if fmt.Sprint(rows) != "map[u1+2:true u3+0:true]" || !tt.HasText("操作 u1") || !tt.HasText("操作 u3") {
		t.Errorf("操作排：%v", rows)
	}
	if err := tt.RightClick("二"); err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(menu) != "[u2+1]" {
		t.Errorf("右键菜单：%v", menu)
	}
}

// Header 画在第一条上面（没有条目时也画），Footer 画在最后一条下面；Framed 时条目装在卡片里，内容照样在。
func TestHeaderFooterAndFrame(t *testing.T) {
	ch := New()
	ch.Header = func(c *ui.Context) { ui.Text(c, "头部") }
	ch.Footer = func(c *ui.Context) { ui.Text(c, "尾部") }
	tt := newTester(ch, 900, 1200, false)
	tt.Frame()
	if !tt.HasText("头部") || tt.HasText("尾部") {
		t.Errorf("没有条目时：%q", tt.Texts())
	}
	ch.Framed = true
	ch.SetItems(longConversation("f", 2))
	tt.Frame()
	texts := tt.Texts()
	if len(texts) < 2 || texts[1] != "头部" || texts[len(texts)-1] != "尾部" || !tt.HasText("第 f1 个问题") {
		t.Errorf("有条目时：%q", texts)
	}
	head, _ := tt.Find("头部")
	first, _ := tt.Find("第 f0 个问题")
	if head.Y >= first.Y {
		t.Errorf("头部不在第一条上面：%v %v", head, first)
	}
}
