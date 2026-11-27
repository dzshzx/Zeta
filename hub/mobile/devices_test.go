package mobile

import (
	"encoding/json"
	"strconv"
	"sync"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
)

// addDevice 探测并添加 me@box，答它的编号。
func addDevice(t *testing.T, c *Core) string {
	t.Helper()
	p := call[Probed](t, c, "probe", map[string]string{"target": "me@box"})
	call[map[string]bool](t, c, "add", map[string]string{"token": p.Token})
	return call[State](t, c, "state", nil).Devices[0].ID
}

func device(t *testing.T, c *Core, id string) DeviceInfo {
	t.Helper()
	for _, d := range call[[]DeviceInfo](t, c, "devices", nil) {
		if d.ID == id {
			return d
		}
	}
	t.Fatalf("没有设备 %s", id)
	return DeviceInfo{}
}

// failed 是调 method 答的出错原因；没出错时测试失败。
func failed(t *testing.T, c *Core, method string, params any) string {
	t.Helper()
	raw, _ := json.Marshal(params)
	var v struct{ Error string }
	if err := json.Unmarshal(c.Call(method, raw), &v); err != nil || v.Error == "" {
		t.Fatalf("%s 没出错", method)
	}
	return v.Error
}

// 改名：起了名字用它，清空回到设备自己的名字；和别的设备重名的不行。
func TestDeviceRename(t *testing.T) {
	c, _ := newCore(t)
	id := addDevice(t, c)
	call[map[string]bool](t, c, "device.rename", map[string]string{"id": id, "name": " 工作站 "})
	if d := device(t, c, id); d.Name != "工作站" || d.CustomName != "工作站" || d.Original != "me@box" {
		t.Fatalf("改名后 %+v", d)
	}
	call[map[string]bool](t, c, "device.rename", map[string]string{"id": id, "name": ""})
	if d := device(t, c, id); d.Name != "me@box" || d.CustomName != "" {
		t.Fatalf("清空名字后 %+v", d)
	}
	failed(t, c, "device.rename", map[string]string{"id": "gone", "name": "x"})
}

// 默认工作目录：先到设备上核对，进得去才存（~ 换成家目录）；进不去答原因不存；「连接」关着时答核对不了，由界面决定仍然保存。
func TestDeviceCwd(t *testing.T) {
	c, d := newCore(t)
	d.DirProblems = map[string]string{"/srv/no": "进不去"}
	id := addDevice(t, c)
	home := device(t, c, id).Home

	got := call[CwdCheck](t, c, "device.checkcwd", map[string]string{"id": id, "path": "~/work/"})
	if got.Path != home+"/work" || got.Problem != "" || got.Unchecked != "" || got.Off {
		t.Fatalf("进得去的 %+v", got)
	}
	if cwd := device(t, c, id).DefaultCwd; cwd != home+"/work" {
		t.Fatalf("没存上：%q", cwd)
	}
	got = call[CwdCheck](t, c, "device.checkcwd", map[string]string{"id": id, "path": "/srv/no"})
	if cwd := device(t, c, id).DefaultCwd; got.Problem != "进不去" || cwd != home+"/work" {
		t.Fatalf("进不去的 %+v，存着 %q", got, cwd)
	}
	failed(t, c, "device.checkcwd", map[string]string{"id": id, "path": "rel"})

	call[map[string]bool](t, c, "connect", map[string]any{"id": id, "on": false})
	got = call[CwdCheck](t, c, "device.checkcwd", map[string]string{"id": id, "path": "/opt"})
	if !got.Off || device(t, c, id).DefaultCwd != home+"/work" {
		t.Fatalf("关着时 %+v", got)
	}
	call[map[string]bool](t, c, "device.setcwd", map[string]string{"id": id, "path": "/opt"})
	if cwd := device(t, c, id).DefaultCwd; cwd != "/opt" {
		t.Fatalf("仍然保存后 %q", cwd)
	}
	if checked := d.Checked(); len(checked) != 2 {
		t.Fatalf("到设备上核对了 %q", checked)
	}
}

// 「常规」的自动加载网络图片存进设置。
func TestPrefs(t *testing.T) {
	c, _ := newCore(t)
	if call[Prefs](t, c, "prefs", nil).AutoLoadRemoteImages {
		t.Fatal("默认就开着")
	}
	call[map[string]bool](t, c, "prefs.set", map[string]bool{"auto_load_remote_images": true})
	if !call[Prefs](t, c, "prefs", nil).AutoLoadRemoteImages || !c.hub.Settings().AutoLoadRemoteImages() {
		t.Fatal("没存上")
	}
}

// quotaAnswer 是 quota.get 的应答：relay 不空时在用中转，否则是用了 42% 的订阅额度。
func quotaAnswer(relay string) json.RawMessage {
	if relay != "" {
		return json.RawMessage(`{"snapshot":null,"relay":"` + relay + `"}`)
	}
	return json.RawMessage(`{"snapshot":{"windows":[{"kind":"five_hour","group":"session","used":0.42,"severity":"warning",` +
		`"label":"5 小时"}],"updatedAt":"2099-01-01T00:00:00Z"},"relay":""}`)
}

// 首页那一行：在用中转时写中转名、不写额度；在用订阅时写最要紧的窗口用了多少。
func TestDeviceHint(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	var mu sync.Mutex
	relay := "r1"
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			if call.Method == "quota.get" {
				mu.Lock()
				defer mu.Unlock()
				return quotaAnswer(relay), nil
			}
			if call.Method == "accounts.list" { // 和 quota.get 说的一致：读到账号时也告诉额度在用的是不是中转
				mu.Lock()
				defer mu.Unlock()
				return json.RawMessage(`{"accounts":[],"relays":[{"name":"r1","current":` + strconv.FormatBool(relay != "") + `}]}`), nil
			}
			return json.RawMessage(`{}`), nil
		})
	}
	id := addDevice(t, c)
	call[map[string]bool](t, c, "settings.open", nil)
	until(t, c, changed, func() bool { // 账号和额度都读完：晚到的那份会改额度认的中转
		r := c.hub.Remote(id)
		_, loaded := r.Accounts.State()
		return loaded && !r.Quota.Refreshing() && r.Quota.Relay() == "r1"
	})
	if h := device(t, c, id); h.Relay != "r1" || h.Used != -1 {
		t.Fatalf("在用中转 %+v", h)
	}
	mu.Lock()
	relay = ""
	mu.Unlock()
	c.Do(func() { c.hub.Remote(id).Quota.Refresh(true) })
	until(t, c, changed, func() bool { r := c.hub.Remote(id); return !r.Quota.Refreshing() && r.Quota.Relay() == "" })
	if h := device(t, c, id); h.Relay != "" || h.Used != 42 {
		t.Fatalf("在用订阅 %+v", h)
	}
}
