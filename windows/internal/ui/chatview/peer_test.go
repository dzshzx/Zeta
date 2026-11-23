package chatview

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// peerItems 是预览用的：别的会话发来的一短一长两条，夹着用户消息和回答。
func peerItems(t *testing.T) []items.Item {
	long := "「6 号馆西墙综合电器区」不是用户口述的，是我从一张场馆平面图上读出来再推断的。README 已改正，并把场馆图补进仓。\n" +
		"- 场馆图：src/booth3d/hall.jpg。\n- 图上写着的：有一个红框，画在最上一排、西2门与西1门之间。\n" +
		"- 我推断的：红框 5 格 × 2 格，对应 15 米 × 6 米，所以是我们的展位。\n- 第 24 页如果只写图上有的，可以写「6 号馆，西2门与西1门之间」。"
	return decodeItems(t, `[
		{"kind":"user","id":"u1","text":"把展位图补进说明"},
		{"kind":"answer","id":"a1","text":"好的，我先问一下做平面图的那个会话。"},
		{"kind":"user","id":"p1","peer":"home-repo-e8","text":`+quote(long)+`},
		{"kind":"user","id":"p2","peer":"fleet-state-3","text":"收到，我这边不动 README。"}]`)
}

func quote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// 另一个会话发来的消息：靠右的卡片，顶上「来自会话 名字」，正文不在用户气泡里；长的默认收着，点标题展开；右键复制正文。
func TestPeerMessage(t *testing.T) {
	long := strings.Repeat("一行说明\n", 6) + "最后一行"
	ch := New()
	ch.SetItems(decodeItems(t, `[
		{"kind":"user","id":"p1","peer":"home-repo-e8","text":"README 已改正"},
		{"kind":"user","id":"p2","peer":"fleet-state-1","text":`+quote(long)+`},
		{"kind":"user","id":"u","text":"我自己说的"}]`))
	tt := newTester(ch, 900, 1400, false)
	tt.Frame()
	for _, s := range []string{"来自会话 home-repo-e8", "README 已改正", "来自会话 fleet-state-1", "我自己说的"} {
		if !tt.HasText(s) {
			t.Fatalf("没有 %q：%q", s, tt.Texts())
		}
	}
	short, _ := tt.Access("README 已改正")
	mine, _ := tt.Access("我自己说的")
	if short.Bounds.X+short.Bounds.W < 600 || mine.Bounds.X+mine.Bounds.W < 600 {
		t.Errorf("会话消息、用户消息都该靠右：%v %v", short.Bounds, mine.Bounds)
	}
	if _, ok := tt.Access("来自会话 home-repo-e8"); !ok {
		t.Fatal("短的没有标题")
	}
	if b, ok := tt.Access("展开、收起全文"); ok && b.Name == "来自会话 home-repo-e8" {
		t.Error("短的不该能展开")
	}
	folded, _ := tt.Access(long)
	if err := tt.Click("来自会话 fleet-state-1"); err != nil {
		t.Fatal(err)
	}
	tt.Frame()
	if opened, _ := tt.Access(long); opened.Bounds.H <= folded.Bounds.H {
		t.Errorf("展开后正文没变高：%v → %v", folded.Bounds, opened.Bounds)
	}
	if err := tt.RightClick("README 已改正"); err != nil {
		t.Fatal(err)
	}
	if err := tt.ChooseMenuItem("复制"); err != nil {
		t.Fatal(err)
	}
	if got := tt.Clipboard(); got != "README 已改正" {
		t.Errorf("复制了 %q", got)
	}
}
