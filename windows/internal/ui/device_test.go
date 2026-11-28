package ui

import (
	"image/png"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/egoist/mygo/i18n"
	"github.com/egoist/mygo/ui"
)

// 改默认工作目录：先到设备上看进不进得去。进不去时输入框留着、写明原因，不存；换个进得去的才存。
func TestDefaultCwdIsCheckedOnTheDevice(t *testing.T) {
	f := newFixture(t)
	f.devices.DirProblems = map[string]string{"/srv/gone": "「box」上没有 /srv/gone 这个目录"}
	x := f.withHosts(t, "box")[0]
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.tt.Frame()

	f.click(t, "默认工作目录")
	f.app.edit.cwd = "/srv/gone"
	f.tt.Frame()
	f.click(t, "确定")
	f.see(t, "「box」上没有 /srv/gone 这个目录。请更换目录，或先在设备上创建该目录。")
	if !f.app.edit.moving {
		t.Fatal("进不去时输入框要留着")
	}
	if got, _ := f.hub.Host(x.ID); got.DefaultCwd != "/home/box" {
		t.Fatalf("进不去也存了：%q", got.DefaultCwd)
	}
	// 改了字，原因不再挂着
	f.app.edit.cwd = "/srv/ok"
	f.tt.Frame()
	f.notSee(t, "「box」上没有 /srv/gone 这个目录。请更换目录，或先在设备上创建该目录。")
	f.click(t, "确定")
	if got, _ := f.hub.Host(x.ID); got.DefaultCwd != "/srv/ok" || f.app.edit.moving {
		t.Fatalf("进得去要存上并关掉输入框：%q %v", got.DefaultCwd, f.app.edit.moving)
	}
	if !slices.Equal(f.devices.Checked(), []string{"/srv/gone", "/srv/ok"}) {
		t.Fatalf("到设备上查的：%q", f.devices.Checked())
	}

	// 没改就确定：不查，直接关上
	f.click(t, "默认工作目录")
	f.click(t, "确定")
	if len(f.devices.Checked()) != 2 || f.app.edit.moving {
		t.Fatalf("没改也去查了：%q", f.devices.Checked())
	}
}

// 设备没连上时核对不了：说明原因，用户选仍然保存或取消。
func TestDefaultCwdWhenTheDeviceIsOff(t *testing.T) {
	f := newFixture(t)
	x := f.withHosts(t, "box")[0]
	if err := f.hub.SetConnected(x.ID, false); err != nil {
		t.Fatal(err)
	}
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.tt.Frame()

	f.click(t, "默认工作目录")
	f.app.edit.cwd = "/srv/a"
	f.tt.Frame()
	f.click(t, "确定")
	f.see(t, "无法核对这个目录", "无法连接「box」，暂时无法确认 /srv/a 是否可访问：「box」已断开。若仍然保存，新建对话时如无法访问会再提示。")
	f.click(t, "取消")
	if got, _ := f.hub.Host(x.ID); got.DefaultCwd != "/home/box" {
		t.Fatalf("取消了也存了：%q", got.DefaultCwd)
	}

	f.click(t, "默认工作目录")
	f.app.edit.cwd = "/srv/b"
	f.tt.Frame()
	f.click(t, "确定")
	f.clickButton(t, "仍然保存", 0)
	f.tt.Frame()
	if got, _ := f.hub.Host(x.ID); got.DefaultCwd != "/srv/b" {
		t.Fatalf("仍然保存没存上：%q", got.DefaultCwd)
	}
	if len(f.devices.Checked()) != 0 {
		t.Fatalf("断开的设备不该去连：%q", f.devices.Checked())
	}
}

// 核对期间关掉输入框：之后回来的结果不用，不存。
func TestDefaultCwdCheckAfterCancelIsDropped(t *testing.T) {
	f := newFixture(t)
	x := f.withHosts(t, "box")[0]
	var finish func()
	f.app.run = func(fn func()) { finish = fn } // 后台的活先攒着，取消以后再跑
	f.app.device = x.ID
	f.app.open(pageDevice)
	f.tt.Frame()
	f.click(t, "默认工作目录")
	f.app.edit.cwd = "/srv/late"
	f.tt.Frame()
	f.click(t, "确定")
	f.see(t, "正在检查「box」上的 /srv/late 是否可访问…")
	if a, _ := f.tt.AccessByRole(ui.RoleButton, "确定"); !a.Disabled {
		t.Error("核对时还能再点确定")
	}
	f.tt.Key(0, ui.KeyEscape)
	f.tt.Frame()
	finish()
	f.tt.Frame()
	if got, _ := f.hub.Host(x.ID); got.DefaultCwd != "/home/box" {
		t.Fatalf("取消后回来的结果也存了：%q", got.DefaultCwd)
	}
}

// TestDeviceCwdPreviews 在设了 ZETA_PREVIEW_DIR 时把改默认工作目录的两种情况画成 PNG：进不去时的输入框、连不上时的确认框。
func TestDeviceCwdPreviews(t *testing.T) {
	dir := os.Getenv("ZETA_PREVIEW_DIR")
	if dir == "" {
		t.Skip("ZETA_PREVIEW_DIR 没设，不画预览")
	}
	for name, off := range map[string]bool{"device-cwd-problem": false, "device-cwd-unchecked": true} {
		for _, look := range previewLooks {
			i18n.SetLocaleFunc(nil) // 场景按源语言的文字点控件，画之前再换语言
			dark := look.dark
			f := newFixture(t)
			f.devices.DirProblems = map[string]string{"/srv/gone": "「box」上没有 /srv/gone 这个目录"}
			x := f.withHosts(t, "box")[0]
			if off {
				_ = f.hub.SetConnected(x.ID, false)
			}
			f.tt.SetTitleBar(windowTitleBar)
			f.tt.SetDark(dark)
			f.app.device = x.ID
			f.app.open(pageDevice)
			f.tt.Frame()
			f.click(t, "默认工作目录")
			f.app.edit.cwd = "/srv/gone"
			f.tt.Frame()
			f.click(t, "确定")
			f.tt.Move(1275, 5)
			f.tt.Advance(time.Second)
			f.tt.Frame()
			look.speak(t, f.tt)
			f.tt.Frame()
			f.tt.Frame()
			out, err := os.Create(filepath.Join(dir, name+"-"+look.suffix()+".png"))
			if err != nil {
				t.Fatal(err)
			}
			if err := png.Encode(out, f.tt.Image()); err != nil {
				t.Fatal(err)
			}
			_ = out.Close()
		}
	}
}
