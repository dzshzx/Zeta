package ui

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

func TestFindInConversation(t *testing.T) {
	f := newChatFixture(t)
	f.showTurn(t, false)
	f.tt.Key(ui.Cmd, ui.KeyF)
	f.tt.Frame()
	f.see(t, "查提问和回答，不含工具步骤和思考")
	f.notSee(t, "发送") // 查找栏顶替输入栏
	// 焦点已经在查找框里：提问里两处（gradlew、tools/gradle），回答里的行内代码一处；代码块不查。从最新的一处开始
	f.tt.Type("GRADLE")
	f.tt.Frame()
	f.see(t, "3/3")
	f.tt.Key(0, ui.KeyEnter)
	f.tt.Frame()
	f.see(t, "1/3")
	f.tt.Key(ui.Shift, ui.KeyEnter)
	f.click(t, "上一个")
	f.see(t, "2/3")
	f.click(t, "下一个")
	f.see(t, "3/3")
	f.click(t, "在对话中查找")
	f.tt.Type("x")
	f.tt.Frame()
	f.see(t, "无结果")
	// Esc 关掉，输入栏回来；再打开是空的
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	f.see(t, "发送")
	f.notSee(t, "无结果")
	if f.app.pane.list.FindQuery() != "" {
		t.Error("关掉后还在高亮")
	}
}

func TestFindLoadsEarlierRecords(t *testing.T) {
	f := newChatFixture(t)
	f.showTurn(t, false)
	f.push(`"earlier":120`)
	f.tt.Key(ui.Cmd, ui.KeyF)
	f.tt.Frame()
	if got := f.sent("conv.earlier"); len(got) != 1 || !strings.Contains(got[0], `"rest":true`) {
		t.Fatalf("载完更早的：%v", got)
	}
	f.tt.Type("没有的词")
	f.tt.Frame()
	f.see(t, "查找中", "查提问和回答，不含工具步骤和思考；正在载入更早的 120 条…")
	f.push(`"earlier":0`)
	f.see(t, "无结果", "查提问和回答，不含工具步骤和思考")
	if got := f.sent("conv.earlier"); len(got) != 0 {
		t.Fatalf("载完了还在要：%v", got)
	}
}

func TestFindScrollsToTheMatch(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	var list []string
	for i := range 60 {
		text := "第 " + strconv.Itoa(i) + " 条，随便说点什么，凑够一屏"
		if i == 3 {
			text = "最早那条里有个暗号：青鸟"
		}
		list = append(list, `{"kind":"user","id":"u`+strconv.Itoa(i)+`","text":"`+text+`"}`)
	}
	f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":[` + strings.Join(list, ",") + `]}`)
	f.tt.Advance(2 * time.Second)
	f.tt.Frame()
	f.notSee(t, "最早那条里有个暗号：青鸟")
	f.tt.Key(ui.Cmd, ui.KeyF)
	f.tt.Type("青鸟")
	f.tt.Advance(time.Second)
	f.tt.Frame()
	f.see(t, "1/1")
	if r, ok := f.tt.Find("最早那条里有个暗号：青鸟"); !ok || r.Y < 0 || r.Y > 860 {
		t.Fatalf("没滚到命中的那条：%v %v", r, ok)
	}
}

func TestFindScrollsWithinALongAnswer(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	var paras []string
	for i := range 80 {
		p := "第 " + strconv.Itoa(i) + " 段，随便说点什么。"
		if i == 70 {
			p = "藏在后面的暗号：青鸟。"
		}
		paras = append(paras, p)
	}
	f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":[{"kind":"user","id":"u1","text":"说长一点"},` +
		`{"kind":"answer","id":"a1","text":"` + strings.Join(paras, `\n\n`) + `","streaming":false},{"kind":"user","id":"u2","text":"好"},` +
		`{"kind":"answer","id":"a2","text":"` + strings.Repeat(`凑数的一段。\n\n`, 40) + `","streaming":false}]}`)
	f.tt.Advance(2 * time.Second)
	f.tt.Frame()
	f.tt.Key(ui.Cmd, ui.KeyF)
	f.tt.Type("青鸟")
	f.tt.Advance(time.Second)
	f.tt.Frame()
	f.see(t, "1/1")
	if r, ok := f.tt.Find("藏在后面的暗号：青鸟。"); !ok || r.Y < 40 || r.Y > 780 {
		t.Fatalf("没滚到命中的那段：%v %v", r, ok)
	}
}
