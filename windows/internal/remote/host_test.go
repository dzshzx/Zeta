package remote

import (
	"reflect"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/sshtest"
	"golang.org/x/crypto/ssh"
)

func TestParseTarget(t *testing.T) {
	cases := map[string]string{
		"zeta@example.com":          "zeta@example.com",
		" ssh://zeta@example.com/ ": "zeta@example.com",
		"zeta@example.com:2222":     "zeta@example.com:2222",
		"zeta@[::1]:2222":           "zeta@[::1]:2222",
		"zeta@[::1]":                "zeta@[::1]",
		"a@b@host":                  "",
		"zeta@host:0":               "",
		"zeta@host:70000":           "",
		"zeta@-host":                "",
		"@host":                     "",
		"-z@host":                   "",
		"zeta@ho st":                "",
		"box":                       "",
	}
	for in, want := range cases {
		got := ""
		if target, ok := ParseTarget(in); ok {
			got = target.String()
		}
		if got != want {
			t.Errorf("ParseTarget(%q) = %q, want %q", in, got, want)
		}
	}
	if target, _ := ParseTarget("zeta@[::1]:2222"); target.Addr() != "[::1]:2222" {
		t.Errorf("Addr = %q", target.Addr())
	}
}

func TestTargetForAcceptsAliasesAndAddresses(t *testing.T) {
	for in, want := range map[string]string{"box": "box", " box ": "box", "zeta@h:22": "zeta@h", "-box": "", "a b": "", "h:22": "", "x'y": ""} {
		got, ok := TargetFor(in)
		if (want == "") == ok || got != want {
			t.Errorf("TargetFor(%q) = %q, %v; want %q", in, got, ok, want)
		}
	}
}

// hostKey 是一把真的主机公钥（base64）。
func hostKey(t *testing.T) string {
	return EncodeHostKey(newSigner(t).PublicKey())
}

func newSigner(t *testing.T) ssh.Signer { return sshtest.NewKey(t) }

func TestDecodeHostsKeepsWhatKotlinKeeps(t *testing.T) {
	key := hostKey(t)
	text := `[
	{"id":"r-1","name":"zeta@box","target":"zeta@box","home":"/home/zeta","cwd":"/work","via":"direct","hostKey":"` + key + `","key":"~/.ssh/k"},
	{"id":"r-2","name":" 工作站 ","hostname":"ws","target":"box","home":"/home/z","cwd":"relative","via":"direct","hostKey":"` + key + `"},
	{"id":"r-3","target":"ssh://zeta@phone:8022","home":"/home/zeta","cwd":"/home/zeta","via":"debian","hostKey":"` + key + `","key":"~/.ssh/k"},
	{"id":"r-1","target":"zeta@dup","home":"/h","via":"direct","hostKey":"` + key + `"},
	{"id":"x-4","target":"zeta@box","home":"/h","via":"direct","hostKey":"` + key + `"},
	{"id":"r-5","target":"zeta@box","home":"/h","via":"direct"},
	{"id":"r-6","target":"zeta@box","home":"/h","via":"direct","hostKey":"bm90IGEga2V5"},
	{"id":"r-7","target":"zeta@box","home":"relative","via":"direct","hostKey":"` + key + `"},
	{"id":"r-8","target":"bad target","home":"/h","via":"direct","hostKey":"` + key + `"},
	"not an object",
	{"id":"r-9","target":"zeta@box","home":"/h","via":"direct","hostKey":"` + key + `","extra":1}
	]`
	hosts := DecodeHosts(text)
	var ids []string
	for _, h := range hosts {
		ids = append(ids, h.ID)
	}
	if want := []string{"r-1", "r-2", "r-3", "r-9"}; !reflect.DeepEqual(ids, want) {
		t.Fatalf("ids %v, want %v", ids, want)
	}
	// 早先没起名字时存的是地址，不算用户起的
	if h := hosts[0]; h.CustomName != "" || h.Name() != "zeta@box" || h.DefaultCwd != "/work" || h.Key != "~/.ssh/k" {
		t.Errorf("r-1 = %+v", h)
	}
	if h := hosts[1]; h.CustomName != "工作站" || h.Hostname != "ws" || h.DefaultCwd != "/home/z" || h.Name() != "工作站" {
		t.Errorf("r-2 = %+v", h)
	}
	// 经 Debian 的不带指纹和私钥（手机上加的，电脑上连不了，但留在清单里）
	if h := hosts[2]; h.Via != ViaDebian || h.HostKey != "" || h.Key != "" {
		t.Errorf("r-3 = %+v", h)
	}
	if DecodeHosts("not json") != nil || DecodeHosts("") != nil {
		t.Error("a broken list decodes to hosts")
	}
}

func TestEncodeHostsRoundTripsInKotlinOrder(t *testing.T) {
	key := hostKey(t)
	hosts := []Host{
		{ID: "r-1", CustomName: "工作站", Target: "zeta@box:2222", Home: "/home/zeta", DefaultCwd: "/home/zeta/src", Via: ViaDirect, HostKey: key, Hostname: "box", Key: "~/.ssh/k"},
		{ID: "r-2", Target: "box", Home: "/root", DefaultCwd: "/root", Via: ViaDirect, HostKey: key},
	}
	text := EncodeHosts(hosts)
	if !strings.HasPrefix(text, `[{"id":"r-1","name":"工作站","hostname":"box","target":"zeta@box:2222","home":"/home/zeta","cwd":"/home/zeta/src","via":"direct","hostKey":"`) {
		t.Errorf("encoded %s", text)
	}
	if strings.Contains(text, `"name":""`) || strings.Contains(text, `"key":""`) {
		t.Errorf("empty fields written: %s", text)
	}
	if got := DecodeHosts(text); !reflect.DeepEqual(got, hosts) {
		t.Errorf("round trip %+v", got)
	}
}

func TestFingerprintMatchesOpenSSH(t *testing.T) {
	signer := newSigner(t)
	if got, want := Fingerprint(EncodeHostKey(signer.PublicKey())), ssh.FingerprintSHA256(signer.PublicKey()); got != want || !strings.HasPrefix(got, "SHA256:") {
		t.Errorf("fingerprint %q, want %q", got, want)
	}
	if Fingerprint("junk") != "" {
		t.Error("junk has a fingerprint")
	}
}

func TestNameProblem(t *testing.T) {
	if p := NameProblem(strings.Repeat("名", 41), nil); p != "名字太长了" {
		t.Errorf("long: %q", p)
	}
	if p := NameProblem(" 工作站 ", []string{"工作站"}); p != "已经有叫「工作站」的设备了" {
		t.Errorf("taken: %q", p)
	}
	if p := NameProblem("", []string{""}); p != "" {
		t.Errorf("empty: %q", p)
	}
}

func TestNewID(t *testing.T) {
	id := NewID()
	if !strings.HasPrefix(id, "r-") || len(id) != 10 || id == NewID() {
		t.Errorf("id %q", id)
	}
}
