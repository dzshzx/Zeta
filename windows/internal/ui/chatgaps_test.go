package ui

import (
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/helper/usage"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// longChat 推来一段翻不完一屏的对话（20 轮），等跟到底部的动画走完。
func (f *chatFixture) longChat() {
	var rows []string
	for i := range 20 {
		rows = append(rows, fmt.Sprintf(`{"kind":"user","id":"u%d","text":"第 %d 个问题"},`+
			`{"kind":"answer","id":"a%d","text":"第 %d 个回答\n\n第二段\n\n第三段","streaming":false}`, i, i+1, i, i+1))
	}
	f.link().Push(`{"type":"conv.items","conv":"w1","at":0,"remove":0,"items":[` + strings.Join(rows, ",") + `]}`)
	f.push(`"session":"s0"`)
	f.tt.Advance(2 * time.Second)
}

// 往上翻看历史时输入栏上方出「回到底部」，点了回到最新一条、接着跟随新内容；在底部时没有它。
func TestBackToBottom(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.longChat()
	if f.shows("回到底部") {
		t.Fatal("在底部时就有「回到底部」")
	}
	list, ok := f.tt.Find("对话内容")
	if !ok {
		t.Fatal("没有聊天列表")
	}
	f.tt.Scroll(list.X+list.W/2, list.Y+list.H/2, 0, -3000)
	f.tt.Advance(time.Second)
	button, ok := f.tt.Find("回到底部")
	if !ok {
		t.Fatalf("往上翻了没有「回到底部」：%q", f.tt.Texts())
	}
	// 浮在列表底边、横着居中，在输入栏上面
	composer, _ := f.tt.Find("消息")
	if mid := button.X + button.W/2; mid < list.X+list.W/2-2 || mid > list.X+list.W/2+2 {
		t.Errorf("「回到底部」在 %v，列表在 %v：要横着居中", button, list)
	}
	if button.Y+button.H > composer.Y {
		t.Errorf("「回到底部」在 %v，输入框在 %v：要在输入栏上面", button, composer)
	}
	// 翻上去以后来了新内容：不跟着走，按钮还在
	f.link().Push(`{"type":"conv.items","conv":"w1","at":40,"remove":0,"items":[{"kind":"user","id":"u99","text":"新的问题"}]}`)
	f.tt.Advance(time.Second)
	if !f.shows("回到底部") {
		t.Fatal("来了新内容按钮没了")
	}
	f.click(t, "回到底部")
	f.tt.Advance(time.Second)
	if f.shows("回到底部") {
		t.Fatal("点了以后没回到底部")
	}
	if !f.app.pane.list.AtEnd() {
		t.Fatal("列表不在底部")
	}
	// 恢复跟随：再来新内容照样跟到底
	f.link().Push(`{"type":"conv.items","conv":"w1","at":41,"remove":0,"items":[{"kind":"answer","id":"a99","text":"新的回答\n\n一\n\n二\n\n三","streaming":false}]}`)
	f.tt.Advance(time.Second)
	if f.shows("回到底部") || !f.app.pane.list.AtEnd() {
		t.Fatal("回到底部以后不再跟随新内容")
	}
}

// 一轮里 15 秒没有新动静时等待行后面写「已等待 N 秒」，每秒走；核心推来新的最近活动时刻就重新算，等你回应时、答完了不写。
func TestIdleHint(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"busy":true,"last_activity":1000`)
	f.see(t, "正在回答")
	f.tt.Advance(14 * time.Second)
	f.notSee(t, "已等待")
	f.tt.Advance(time.Second)
	f.see(t, "正在回答 · 已等待 15 秒")
	f.tt.Advance(5 * time.Second)
	f.see(t, "正在回答 · 已等待 20 秒")
	// 有了新输出：重新算
	f.push(`"last_activity":21000`)
	f.notSee(t, "已等待")
	// 核心的一句后面照样写
	f.push(`"status":"正在压缩上下文"`)
	f.tt.Advance(16 * time.Second)
	f.see(t, "正在压缩上下文 · 已等待 16 秒")
	// 等你回应时不写；答完以后从那时重新算
	f.push(`"status":"","prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash","input":{"command":"ls"},"notice":""}]`)
	f.tt.Advance(30 * time.Second)
	f.notSee(t, "已等待")
	f.push(`"prompts":[]`)
	f.notSee(t, "已等待")
	f.tt.Advance(15 * time.Second)
	f.see(t, "正在回答 · 已等待 15 秒")
	// 答完了不写，下一轮从头算
	f.push(`"busy":false`)
	f.notSee(t, "已等待", "正在回答")
	f.push(`"busy":true`)
	f.tt.Advance(10 * time.Second)
	f.notSee(t, "已等待")
}

// 后台子代理要的确认写明是哪个子代理（核心按发起它的 Agent 调用填的说明）；没有说明时写「后台子代理」，主对话的不写。
func TestSubagentPermissionNamesTheAgent(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	perm := func(extra string) string {
		return `"busy":true,"prompts":[{"kind":"permission","request_id":"p1","tool_name":"Bash",` +
			`"input":{"command":"go test ./...","description":"跑测试"},"notice":""` + extra + `}]`
	}
	f.push(perm(`,"agent_id":"a1","agent_label":"检查测试覆盖"`))
	f.see(t, "允许这次操作？", "来自子代理：检查测试覆盖")
	f.push(perm(`,"agent_id":"a1"`))
	f.see(t, "来自子代理：后台子代理")
	f.push(perm(""))
	f.see(t, "允许这次操作？")
	f.notSee(t, "来自子代理")
}

// 用量弹出框的上下文一节：进度条随程度变色（同输入栏的圆环），说明按程度写，带自动压缩点占窗口的比例、压缩次数和「模型 · 思考强度」。
func TestUsageContextSection(t *testing.T) {
	for _, c := range []struct {
		name, usage string
		see         []string
		notSee      []string
		button      string
	}{
		{"实测", `"context_tokens":42000,"context_window":200000,"model":"claude-opus-5-5","applied_effort":"high","auto_compact":true,` +
			`"compact_at":167000,"compactions":2,"fraction":0.21,"compact_fraction":0.835,"level":"normal"`,
			[]string{"42K / 200K · 21.0%", "按上一轮实测；快到上限时 Claude Code 会自动压缩。", "自动压缩点：167K（窗口的 83%）。", "已压缩 2 次。", "Opus 5.5 · 思考 高"},
			[]string{"估算", "快满了"}, "上下文 21%"},
		{"估算", `"context_tokens":42000,"context_window":200000,"context_estimated":true,"fraction":0.21,"level":"normal"`,
			[]string{"Claude Code 的估算，下一轮后按实测；快到上限时它会自动压缩。"}, []string{"自动压缩点", "已压缩", "思考 "}, "上下文 21%"},
		{"快满了", `"context_tokens":190000,"context_window":200000,"auto_compact":true,"compact_at":167000,"fraction":0.95,"compact_fraction":0.835,"level":"critical"`,
			[]string{"快满了，Claude Code 马上会自动压缩；也可以现在手动压缩。", "自动压缩点：167K（窗口的 83%）。"}, nil, "上下文 95%"},
		{"关了自动压缩", `"context_tokens":120000,"context_window":200000,"auto_compact":false,"fraction":0.6,"level":"warning"`,
			[]string{"自动压缩已关闭（Zeta 或 Claude Code 的设置），快满时手动压缩。"}, []string{"自动压缩点"}, "上下文 60%"},
		{"关了自动压缩又快满了", `"context_tokens":190000,"context_window":200000,"auto_compact":false,"fraction":0.95,"level":"critical"`,
			[]string{"快满了，自动压缩已关闭，请手动压缩，不然很快发不出去。"}, nil, "上下文 95%"},
		{"还没发", ``, []string{"发出第一条消息后显示。"}, []string{"实测"}, "上下文"},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := newChatFixture(t)
			f.newChat(t)
			sep := ""
			if c.usage != "" {
				sep = ","
			}
			f.push(`"usage":{` + c.usage + sep + `"total":{}}`)
			f.click(t, c.button)
			f.see(t, c.see...)
			f.notSee(t, c.notSee...)
		})
	}
}

// 弹出框里上下文的进度条和输入栏的圆环按同一个规则变色：快满了红、该注意了黄、平时是主色。看条子左端填上的那一段的颜色。
func TestUsageBarFollowsLevel(t *testing.T) {
	k := widget.For(false)
	for level, want := range map[usage.Level]ui.Color{usage.LevelNormal: k.Primary, usage.LevelWarning: k.Warning, usage.LevelCritical: k.Error} {
		t.Run(string(level), func(t *testing.T) {
			f := newChatFixture(t)
			f.newChat(t)
			f.push(`"usage":{"context_tokens":150000,"context_window":200000,"fraction":0.75,"level":"` + string(level) + `","total":{}}`)
			f.click(t, "上下文 75%")
			r, ok := f.tt.Find("上下文用量")
			if !ok {
				t.Fatalf("没有进度条：%q", f.tt.Texts())
			}
			// 测试器的像素比是 1，Find 给的 DIP 和画面的像素一一对应；离开圆角取左边一点、竖着的中间
			got := f.tt.Image().RGBAAt(int(r.X+r.W/4), int(r.Y+r.H/2))
			if got.R != want.R || got.G != want.G || got.B != want.B {
				t.Errorf("条子是 %v，要 %v", got, want)
			}
		})
	}
}
