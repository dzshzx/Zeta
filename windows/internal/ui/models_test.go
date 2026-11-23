package ui

import (
	"encoding/json"
	"slices"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/hubtest"
)

// reportedModels 是假核心在 conv.prepare 后推来的模型清单（Claude Code 2.1.329 报的样子，同 chat 包测试里的）。
const reportedModels = `[{"value":"default","displayName":"Default (recommended)","resolvedModel":"claude-opus-5-5"},` +
	`{"value":"opus","displayName":"Opus 5.5","resolvedModel":"claude-opus-5-5"},` +
	`{"value":"sonnet","displayName":"Sonnet 5.5","resolvedModel":"claude-sonnet-5-5"},` +
	`{"value":"claude-opus-4-6","displayName":"Opus 4.6","resolvedModel":"claude-opus-4-6"},` +
	`{"value":"claude-sonnet-4-6","displayName":"Sonnet 4.6","resolvedModel":"claude-sonnet-4-6"}]`

// withPrepare 让假核心答 conv.prepare：推来模型清单；别的照设置页的假核心答。返回发来过的方法（按先后）。
func withPrepare(f *settingsFixture) *[]string {
	var methods []string
	f.devices.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(c hubtest.Call) (json.RawMessage, error) {
			methods = append(methods, c.Method)
			switch c.Method {
			case "conv.open":
				for _, line := range hubtest.Recorded("open", c.Conv()) {
					l.Push(line)
				}
				return nil, nil
			case "conv.prepare":
				l.Push(`{"type":"conv.state","conv":"` + c.Conv() + `","process":true,"models":` + reportedModels + `}`)
				return nil, nil
			case "conv.end", "conv.close":
				return nil, nil
			}
			return f.answer(c)
		})
	}
	return &methods
}

// 没开对话时打开设备页：去那台上开一个不发消息的对话要模型清单，要到后结束进程、放开对话；默认模型的下拉换成真实清单，
// 旧版本收在「更多模型」里。再打开设备页不再去要。
func TestDefaultModelUsesTheDevicesModels(t *testing.T) {
	f := newSettingsFixture(t)
	methods := withPrepare(f)
	f.serve.Write(t, ".claude/settings.json", `{"model":"sonnet"}`)
	f.withHosts(t, "box")
	f.tt.SetSize(1280, 1600)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.until(t, "要到清单、读到默认", func() bool { return f.shows("Sonnet 5.5") })
	convCalls := func() []string {
		var out []string
		for _, m := range *methods {
			if m == "conv.open" || m == "conv.prepare" || m == "conv.end" || m == "conv.close" {
				out = append(out, m)
			}
		}
		return out
	}
	if want := []string{"conv.open", "conv.prepare", "conv.end", "conv.close"}; !slices.Equal(convCalls(), want) {
		t.Fatalf("要清单发的：%q，要 %q", convCalls(), want)
	}
	f.notSee(t, "正在向这台设备上的 Claude Code 要模型清单，先列出常用的几个。")

	f.press(t, "默认模型")
	f.see(t, "Claude Code 内置的默认", "Opus 5.5", "更多模型")
	f.notSee(t, "Default (recommended)", "Opus 4.6")
	f.click(t, "更多模型")
	f.see(t, "Opus 4.6", "Sonnet 4.6")
	f.click(t, "Opus 4.6")
	f.until(t, "存上", func() bool { return f.shows("Opus 4.6") })

	// 换到常规再回来：记着清单，不再去要
	f.click(t, "常规")
	f.clickButton(t, "box", 0)
	f.until(t, "读到默认", func() bool { return f.shows("Opus 4.6") })
	if n := len(convCalls()); n != 4 {
		t.Fatalf("又去要了：%q", convCalls())
	}
}

// 要不到（这里的假核心不认 conv.prepare）时照旧列常用的别名，「正在要」的说明去掉。
func TestDefaultModelWhenTheDeviceCannotReport(t *testing.T) {
	f := newSettingsFixture(t)
	f.withHosts(t, "box")
	f.tt.SetSize(1280, 1600)
	f.click(t, "设置")
	f.clickButton(t, "box", 0)
	f.until(t, "读到新对话默认、没要到清单", func() bool {
		return f.shows("Claude Code 内置的默认") && !f.tt.HasText("正在向这台设备上的 Claude Code 要模型清单")
	})
	f.press(t, "默认模型")
	f.see(t, "Opus", "Sonnet", "Haiku", "Fable")
	f.notSee(t, "更多模型")
}

// 输入栏的模型菜单也分两层（同设置里的默认模型）：上层是各系列最新的，旧版本在「更多模型」里；选了旧版本，那一项右边写着它。
func TestComposerModelMenuSplitsOlder(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"process":true,"models":` + reportedModels)
	f.click(t, "默认")
	f.see(t, "Opus 5.5", "Sonnet 5.5", "更多模型")
	f.notSee(t, "Opus 4.6")
	f.click(t, "更多模型")
	f.clickMenuItem(t, "Opus 4.6")
	got := f.link().Took()
	if len(got) != 1 || got[0].Method != "conv.model" || got[0].JSON() != `{"conv":"w1","value":"claude-opus-4-6"}` {
		t.Fatalf("选旧版本：%+v", got)
	}
	f.push(`"model":"claude-opus-4-6"`)
	f.click(t, "Opus 4.6") // 按钮上换成了它；点开菜单
	texts := f.tt.Texts()
	n := slices.Index(texts, "更多模型")
	for n >= 0 && n+1 < len(texts) && texts[n+1] == "更多模型" { // 读屏名和字各一条
		n++
	}
	if n < 0 || n+1 >= len(texts) || texts[n+1] != "Opus 4.6" {
		t.Fatalf("更多模型旁边没写选中的旧版本：%q", texts)
	}
}
