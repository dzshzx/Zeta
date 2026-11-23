package ui

import (
	"testing"
)

// 设备页拨掉「连接」：对话连接关掉，聊天窗格说已断开、给「连接」；设置首页注明已断开。点「连接」在新连接上重新打开那个会话。
func TestConnectSwitch(t *testing.T) {
	f := newChatFixture(t)
	f.click(t, "修登录页")
	first := f.link()
	first.Took()
	f.app.device = f.id
	f.app.open(pageDevice)
	f.tt.Frame()
	f.see(t, "用到这台设备时连上，断了自动重连")
	f.press(t, "连接")
	if !first.Closed() || !f.hub.Off(f.id) {
		t.Fatalf("拨掉以后：连接关了 %v，Off %v", first.Closed(), f.hub.Off(f.id))
	}
	f.see(t, "已断开：Zeta 不连这台设备，它上面的对话在那边照样跑")
	f.notSee(t, "新对话默认") // 断开时没有那台上的 Claude Code 配置
	f.app.open(pageSettings)
	f.tt.Frame()
	f.see(t, "已断开")
	f.app.open(pageHome)
	f.tt.Frame()
	f.see(t, "「box」已断开")
	f.press(t, "连接")
	if f.link() == first || f.hub.Off(f.id) {
		t.Fatal("点了连接没有重新连")
	}
	if open := f.sent("conv.open"); len(open) != 1 {
		t.Fatalf("连上后打开：%v", open)
	}
	f.see(t, "你好，介绍一下自己")
	f.notSee(t, "「box」已断开")
}
