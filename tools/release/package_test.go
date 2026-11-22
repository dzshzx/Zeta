package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func sampleUpdate() update {
	return update{VersionCode: 1234, VersionName: "0.7.1", Tag: "v0.7.1", PublishedAt: "2026-11-21T08:00:00Z",
		Notes: formatNotes([]string{"feat(a): 一", "fix(b): 二"}), Certificate: newCert, SourceCommit: "c0ffee"}
}

// writeSample 在临时目录里写一套资产，APK 的内容是 "apk"，MSI 的是 "msi"。
func writeSample(t *testing.T) (string, update) {
	t.Helper()
	apk := filepath.Join(t.TempDir(), "signed.apk")
	if err := os.WriteFile(apk, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	msi := filepath.Join(t.TempDir(), "Zeta.msi")
	if err := os.WriteFile(msi, []byte("msi"), 0o644); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	u, err := writeAssets(dir, apk, msi, "o/r", sampleUpdate())
	if err != nil {
		t.Fatal(err)
	}
	return dir, u
}

func TestWriteAssets(t *testing.T) {
	dir, u := writeSample(t)
	sum, _ := fileHash(filepath.Join(dir, "Zeta-v0.7.1.apk"))
	entry, _ := os.ReadFile(filepath.Join(dir, "Zeta-v0.7.1.apk.sha256"))
	if string(entry) != sum+"  Zeta-v0.7.1.apk\n" {
		t.Errorf(".sha256 is %q, want the sha256sum format", entry)
	}
	msiSum, _ := fileHash(filepath.Join(dir, "Zeta-v0.7.1.msi"))
	if entry, _ := os.ReadFile(filepath.Join(dir, "Zeta-v0.7.1.msi.sha256")); string(entry) != msiSum+"  Zeta-v0.7.1.msi\n" {
		t.Errorf("the MSI .sha256 is %q", entry)
	}
	data, err := os.ReadFile(filepath.Join(dir, updateFile))
	if err != nil {
		t.Fatal(err)
	}
	var fields map[string]any
	if err := json.Unmarshal(data, &fields); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{
		"versionCode": 1234.0, "versionName": "0.7.1", "tag": "v0.7.1", "publishedAt": "2026-11-21T08:00:00Z",
		"notes": "- feat(a): 一\n- fix(b): 二", "apk": "Zeta-v0.7.1.apk",
		"url":  "https://github.com/o/r/releases/download/v0.7.1/Zeta-v0.7.1.apk",
		"size": 3.0, "sha256": sum, "certificateSha256": newCert, "sourceCommit": "c0ffee",
	}
	for key, value := range want {
		if fields[key] != value {
			t.Errorf("update.json %s = %v, want %v", key, fields[key], value)
		}
	}
	if len(fields) != len(want) {
		t.Errorf("update.json has %d fields, want %d: %v", len(fields), len(want), fields)
	}
	if got, err := checkAssets(dir, "0.7.1", true); err != nil || got != u {
		t.Errorf("the written assets check out: %v, %+v", err, got)
	}
}

func TestCheckAssetsCatchesTampering(t *testing.T) {
	cases := []struct {
		name   string
		change func(dir string) error
		want   string
	}{
		{"an extra file", func(dir string) error { return os.WriteFile(filepath.Join(dir, "notes.md"), nil, 0o644) }, "应该正好是"},
		{"a changed APK", func(dir string) error {
			return os.WriteFile(filepath.Join(dir, "Zeta-v0.7.1.apk"), []byte("apk!"), 0o644)
		}, "与 .sha256 里的"},
		{"a changed MSI", func(dir string) error {
			return os.WriteFile(filepath.Join(dir, "Zeta-v0.7.1.msi"), []byte("msi!"), 0o644)
		}, "与 .sha256 里的"},
		{"no MSI", func(dir string) error { return os.Remove(filepath.Join(dir, "Zeta-v0.7.1.msi")) }, "应该正好是"},
		{"update.json for another version", func(dir string) error {
			return rewriteUpdate(dir, func(u *update) { u.VersionName = "0.7.2" })
		}, "versionName"},
		{"update.json with another size", func(dir string) error { return rewriteUpdate(dir, func(u *update) { u.Size++ }) }, "size"},
		{"update.json pointing elsewhere", func(dir string) error {
			return rewriteUpdate(dir, func(u *update) { u.URL = "https://example.com/Zeta-v0.7.1.apk" })
		}, "url"},
		{"update.json without a certificate", func(dir string) error { return rewriteUpdate(dir, func(u *update) { u.Certificate = "" }) }, "certificateSha256"},
	}
	for _, c := range cases {
		dir, _ := writeSample(t)
		if err := c.change(dir); err != nil {
			t.Fatal(err)
		}
		if _, err := checkAssets(dir, "0.7.1", true); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v", c.name, err)
		}
	}
	dir, _ := writeSample(t)
	if _, err := checkAssets(dir, "0.7.2", true); err == nil {
		t.Error("assets of another version fail")
	}
	// tools/install.sh 只下手机的那几个：不带 MSI 也核对得过；发布的整套少了 MSI 不行
	for _, name := range []string{"Zeta-v0.7.1.msi", "Zeta-v0.7.1.msi.sha256"} {
		if err := os.Remove(filepath.Join(dir, name)); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := checkAssets(dir, "0.7.1", false); err != nil {
		t.Errorf("the phone assets alone check out: %v", err)
	}
	if _, err := checkAssets(dir, "0.7.1", true); err == nil {
		t.Error("a release without the MSI fails")
	}
}

func rewriteUpdate(dir string, change func(*update)) error {
	path := filepath.Join(dir, updateFile)
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var u update
	if err := json.Unmarshal(data, &u); err != nil {
		return err
	}
	change(&u)
	data, err = json.Marshal(u)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}

func TestReleaseBody(t *testing.T) {
	body := releaseBody(sampleUpdate())
	for _, want := range []string{"Zeta 0.7.1（versionCode 1234）\n", "- source_commit: `c0ffee`\n", "`" + newCert + "`", "## 改动\n\n- feat(a): 一\n- fix(b): 二\n", "Windows 桌面版：`Zeta-v0.7.1.msi`"} {
		if !strings.Contains(body, want) {
			t.Errorf("body lacks %q:\n%s", want, body)
		}
	}
	if formatNotes(nil) != "- 没有新的提交" {
		t.Error("an empty span still has a line")
	}
}

func TestCommitTitles(t *testing.T) {
	calls := fakeCommands(t, map[string]string{"git log": "feat(a): 一\nfix(b): 二"})
	titles, err := commitTitles("v0.7.0", "c0ffee")
	if err != nil || strings.Join(titles, "|") != "feat(a): 一|fix(b): 二" {
		t.Errorf("%q %v", titles, err)
	}
	if (*calls)[0] != "git log --reverse --no-merges --format=%s v0.7.0..c0ffee" {
		t.Errorf("call %q", (*calls)[0])
	}
	if _, err := commitTitles("", "c0ffee"); err != nil || (*calls)[1] != "git log --reverse --no-merges --format=%s c0ffee" {
		t.Errorf("without a previous tag the whole history counts: %q", (*calls)[1])
	}
}

func TestRunPackage(t *testing.T) {
	fakeRepo(t, oldCert+"\n"+newCert+"\n")
	apk := filepath.Join(t.TempDir(), "signed.apk")
	if err := os.WriteFile(apk, []byte("apk"), 0o644); err != nil {
		t.Fatal(err)
	}
	msi := filepath.Join(t.TempDir(), "Zeta.msi")
	if err := os.WriteFile(msi, []byte("msi"), 0o644); err != nil {
		t.Fatal(err)
	}
	fakeCommands(t, map[string]string{
		"apksigner lineage": lineageOut,
		"apksigner verify":  verifyRotated,
		"aapt2 dump":        badging(packageName, "1234", "0.7.1"),
		"git tag --list":    "v0.7.0\nv0.7.1",
		"git log":           "feat(a): 一",
	})
	summaryFile := filepath.Join(t.TempDir(), "summary.md")
	t.Setenv("GITHUB_STEP_SUMMARY", summaryFile)
	out := t.TempDir()
	args := []string{"-apk", apk, "-msi", msi, "-version", "0.7.1", "-version-code", "1234", "-commit", "c0ffee", "-repo", "o/r", "-out", out}
	printed := captureStdout(t, func() error { return runPackage(args) })
	u, err := checkAssets(out, "0.7.1", true)
	if err != nil || u.Notes != "- feat(a): 一" || u.Certificate != newCert || u.VersionCode != 1234 {
		t.Errorf("assets %+v, %v", u, err)
	}
	summary, _ := os.ReadFile(summaryFile)
	if string(summary) != printed || !strings.Contains(printed, "| Zeta-v0.7.1.apk | 3 | `") || !strings.Contains(printed, "| Zeta-v0.7.1.msi | 3 | `") {
		t.Errorf("summary:\n%s", summary)
	}

	// 版本对不上就不写资产
	args[5] = "0.7.2"
	args[len(args)-1] = t.TempDir()
	if err := runPackage(args); err == nil || !strings.Contains(err.Error(), "versionName") {
		t.Errorf("a version mismatch fails: %v", err)
	}
}
