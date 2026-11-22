package update

import (
	"strings"
	"testing"
)

const sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

// manifestJSON 是一份 tools/release 写的那样的清单，含手机和桌面版的全部字段；edit 在拼好之前改掉其中几项。
func manifestJSON(edit map[string]string) string {
	fields := [][2]string{
		{"versionCode", `812`},
		{"versionName", `"0.7.6"`},
		{"tag", `"v0.7.6"`},
		{"publishedAt", `"2026-11-21T08:00:00Z"`},
		{"notes", `"- feat: 一\n- fix: 二"`},
		{"apk", `"Zeta-v0.7.6.apk"`},
		{"url", `"https://github.com/dzshzx/Zeta/releases/download/v0.7.6/Zeta-v0.7.6.apk"`},
		{"size", `13000000`},
		{"sha256", `"` + sha + `"`},
		{"certificateSha256", `"` + sha + `"`},
		{"sourceCommit", `"abc"`},
		{"msi", `"Zeta-v0.7.6.msi"`},
		{"msiUrl", `"https://github.com/dzshzx/Zeta/releases/download/v0.7.6/Zeta-v0.7.6.msi"`},
		{"msiSize", `12345678`},
		{"msiSha256", `"` + sha + `"`},
	}
	var parts []string
	for _, f := range fields {
		v, ok := edit[f[0]]
		if !ok {
			v = f[1]
		}
		if v != "" {
			parts = append(parts, `"`+f[0]+`":`+v)
		}
	}
	return "{" + strings.Join(parts, ",") + `,"later":{"x":1}}`
}

func TestParseManifest(t *testing.T) {
	m, err := ParseManifest([]byte(manifestJSON(nil)))
	if err != nil {
		t.Fatal(err)
	}
	want := Package{
		File:   "Zeta-v0.7.6.msi",
		URL:    "https://github.com/dzshzx/Zeta/releases/download/v0.7.6/Zeta-v0.7.6.msi",
		Size:   12345678,
		SHA256: sha,
	}
	if m.VersionCode != 812 || m.VersionName != "0.7.6" || m.Notes != "- feat: 一\n- fix: 二" ||
		m.PublishedAt != "2026-11-21T08:00:00Z" || m.MSI == nil || *m.MSI != want {
		t.Errorf("got %+v, msi %+v", m, m.MSI)
	}
}

func TestParseManifestBad(t *testing.T) {
	for _, tc := range []struct {
		name, text, want string
	}{
		{"not json", "<html>", "不是 JSON 对象"},
		{"array", "[]", "不是 JSON 对象"},
		{"null", "null", "不是 JSON 对象"},
		{"no code", manifestJSON(map[string]string{"versionCode": ""}), "versionCode"},
		{"code as string", manifestJSON(map[string]string{"versionCode": `"812"`}), "versionCode"},
		{"code fraction", manifestJSON(map[string]string{"versionCode": `8.5`}), "versionCode"},
		{"code zero", manifestJSON(map[string]string{"versionCode": `0`}), "versionCode"},
		{"code too big", manifestJSON(map[string]string{"versionCode": `2147483648`}), "versionCode"},
		{"dev name", manifestJSON(map[string]string{"versionName": `"0.7.6-dev.1+gabc"`}), "versionName"},
		{"no name", manifestJSON(map[string]string{"versionName": ""}), "versionName"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := ParseManifest([]byte(tc.text))
			if err == nil || !strings.HasPrefix(err.Error(), "更新清单格式不对：") || !strings.Contains(err.Error(), tc.want) {
				t.Errorf("error %v, want one about %s", err, tc.want)
			}
		})
	}
}

// 桌面版的四项缺一项或哪项不合规，清单照样能用，只是没有 MSI（手机那边不受影响）。
func TestParseManifestWithoutMSI(t *testing.T) {
	for name, edit := range map[string]map[string]string{
		"v0.7.5 and earlier": {"msi": "", "msiUrl": "", "msiSize": "", "msiSha256": ""},
		"no msiSha256":       {"msiSha256": ""},
		"upper-case sha":     {"msiSha256": `"` + strings.ToUpper(sha) + `"`},
		"short sha":          {"msiSha256": `"abc"`},
		"not an msi":         {"msi": `"Zeta-v0.7.6.exe"`},
		"path in name":       {"msi": `"../Zeta.msi"`},
		"ftp url":            {"msiUrl": `"ftp://example.com/Zeta.msi"`},
		"size as string":     {"msiSize": `"12345678"`},
		"size zero":          {"msiSize": `0`},
		"size too big":       {"msiSize": `3000000000`},
		"null size":          {"msiSize": `null`},
	} {
		t.Run(name, func(t *testing.T) {
			m, err := ParseManifest([]byte(manifestJSON(edit)))
			if err != nil {
				t.Fatal(err)
			}
			if m.MSI != nil || m.VersionCode != 812 {
				t.Errorf("msi %+v, code %d", m.MSI, m.VersionCode)
			}
		})
	}
}

func TestVersion(t *testing.T) {
	for _, tc := range []struct {
		code, name string
		want       Version
		release    bool
	}{
		{"812", "0.7.6", Version{812, "0.7.6"}, true},
		{"815", "0.7.7-dev.3+gabc1234", Version{815, "0.7.7-dev.3+gabc1234"}, false},
		{"", "", Version{0, "未知"}, false}, // go run、go test 编的
		{"abc", "0.7.6", Version{0, "未知"}, false},
		{"0", "0.7.6", Version{0, "未知"}, false},
		{"812", "", Version{0, "未知"}, false},
		{"812", "v0.7.6", Version{812, "v0.7.6"}, false},
	} {
		v := parseVersion(tc.code, tc.name)
		if v != tc.want || v.Release() != tc.release {
			t.Errorf("parseVersion(%q, %q) = %+v release %v, want %+v release %v", tc.code, tc.name, v, v.Release(), tc.want, tc.release)
		}
	}
	if Current() != (Version{0, "未知"}) {
		t.Errorf("a test build is %+v, want a development build", Current())
	}
}
