package claudecfg_test

import (
	"slices"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/claudecfg"
)

// fakePrefs 是内存里的本地设置，方法同 *settings.Store。
type fakePrefs struct {
	bools   map[string]bool
	ints    map[string]int64
	strings map[string][]string
}

func (f *fakePrefs) BoolOr(k string, def bool) bool {
	if v, ok := f.bools[k]; ok {
		return v
	}
	return def
}

func (f *fakePrefs) IntOr(k string, def int64) int64 {
	if v, ok := f.ints[k]; ok {
		return v
	}
	return def
}

func (f *fakePrefs) StringsOr(k string, def []string) []string {
	if v, ok := f.strings[k]; ok {
		return v
	}
	return def
}

func (f *fakePrefs) SetStrings(k string, v []string) error { f.strings[k] = v; return nil }

// 每台设备的自动压缩、自动标题存在 remoteClaude，没设过的照手机本机的那几项。
func TestDeviceSettings(t *testing.T) {
	p := &fakePrefs{bools: map[string]bool{"autoTitle": false}, ints: map[string]int64{"autoCompactPercent": 70}, strings: map[string][]string{
		"remoteClaude": {"other/autoCompact=false"},
	}}
	d := claudecfg.DeviceSettings(p, "h1")
	if !d.AutoCompact() || d.AutoTitle() || d.CompactPercent() != 70 {
		t.Fatalf("fallbacks %v %v %v", d.AutoCompact(), d.AutoTitle(), d.CompactPercent())
	}
	if err := d.SetAutoCompact(false); err != nil {
		t.Fatal(err)
	}
	_ = d.SetCompactPercent(0)
	_ = d.SetAutoTitle(true)
	want := []string{"h1/autoCompact=false", "h1/autoCompactPercent=default", "h1/autoTitle=true", "other/autoCompact=false"}
	if !slices.Equal(p.strings["remoteClaude"], want) {
		t.Errorf("stored %v", p.strings["remoteClaude"])
	}
	if d.AutoCompact() || d.CompactPercent() != 0 || !d.AutoTitle() {
		t.Error("stored values not read back")
	}
}
