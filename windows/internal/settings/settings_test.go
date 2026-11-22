package settings

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/dzshzx/Zeta/client/sshlink"
	"github.com/dzshzx/Zeta/client/sshtest"
	"github.com/dzshzx/Zeta/windows/internal/update"
)

// composeFile 是 Compose 桌面版（FilePrefStore）写出来的样子：各种类型都有，还有这一版不用的项。
func composeFile(t *testing.T) (dir string, hostKey string) {
	t.Helper()
	hostKey = sshlink.EncodeHostKey(sshtest.NewKey(t).PublicKey())
	hosts := `[{"id":"r-1","name":"工作站","target":"box","home":"/home/zeta","cwd":"/home/zeta/src","via":"direct","hostKey":"` + hostKey + `"},` +
		`{"id":"r-2","hostname":"lab","target":"zeta@lab:2222","home":"/root","cwd":"/root","via":"direct","hostKey":"` + hostKey + `","key":"~/.ssh/lab"}]`
	encoded, _ := json.Marshal(hosts)
	text := `{"remoteHosts":{"t":"s","v":` + string(encoded) + `},` +
		`"backend":{"t":"s","v":"r-2"},"paneAll":{"t":"b","v":true},` +
		`"pinnedSessions":{"t":"set","v":["r-1/aaa","r-2/bbb"]},` +
		`"interfaceScale":{"t":"f","v":1.25},"updateNotified":{"t":"i","v":7},"updateCheckedAt":{"t":"l","v":1700000000000},` +
		`"remoteClaude":{"t":"set","v":["r-1/autoTitle=false","r-2/autoCompact=true"]},` +
		`"contextWindows":{"t":"set","v":["r-1/opus=200000"]},` +
		`"commands":{"t":"s","v":"{\"r-1\":[],\"r-2\":[{\"name\":\"x\"}]}"},` +
		`"prompts":{"t":"s","v":"[{\"text\":\"<b>&\"}]"},"broken":{"x":1}}`
	dir = t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "zeta.json"), []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
	return dir, hostKey
}

func TestComposeSettingsAreRead(t *testing.T) {
	dir, _ := composeFile(t)
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	hosts := s.Hosts()
	if len(hosts) != 2 || hosts[0].Name() != "工作站" || hosts[1].Name() != "lab" || hosts[1].Key != "~/.ssh/lab" {
		t.Errorf("hosts %+v", hosts)
	}
	if s.Backend() != "r-2" || !s.PaneAll() || !reflect.DeepEqual(s.Pins(), []string{"r-1/aaa", "r-2/bbb"}) {
		t.Errorf("backend %q, all %v, pins %v", s.Backend(), s.PaneAll(), s.Pins())
	}
	if v, ok := s.store.Int("updateCheckedAt"); !ok || v != 1700000000000 {
		t.Errorf("long %d %v", v, ok)
	}
	if v, ok := s.store.Int("updateNotified"); !ok || v != 7 {
		t.Errorf("int %d %v", v, ok)
	}
}

func TestWritingKeepsWhatThisVersionDoesNotUse(t *testing.T) {
	dir, _ := composeFile(t)
	s, _ := Load(dir)
	if err := s.SetPins([]string{"r-1/aaa"}); err != nil {
		t.Fatal(err)
	}
	raw := readRaw(t, dir)
	for key, want := range map[string]string{
		"interfaceScale": `{"t":"f","v":1.25}`,
		"prompts":        `{"t":"s","v":"[{\"text\":\"<b>&\"}]"}`,
		"pinnedSessions": `{"t":"set","v":["r-1/aaa"]}`,
	} {
		if got := string(raw[key]); got != want {
			t.Errorf("%s = %s, want %s", key, got, want)
		}
	}
	if _, ok := raw["broken"]; ok {
		t.Error("an entry Compose cannot read was kept")
	}
	if _, err := os.Stat(filepath.Join(dir, "zeta.json.tmp")); !os.IsNotExist(err) {
		t.Error("temporary file left behind")
	}
	again, _ := Load(dir)
	if !reflect.DeepEqual(again.Pins(), []string{"r-1/aaa"}) || len(again.Hosts()) != 2 {
		t.Errorf("reloaded pins %v", again.Pins())
	}
}

func TestRemovingAHostForgetsItsSettings(t *testing.T) {
	dir, _ := composeFile(t)
	s, _ := Load(dir)
	if err := s.SetHosts(s.Hosts()[1:]); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.store.Strings(keyRemoteClaude); !reflect.DeepEqual(got, []string{"r-2/autoCompact=true"}) {
		t.Errorf("remoteClaude %v", got)
	}
	text, _ := s.store.String(keyCommands)
	var commands map[string]any
	_ = json.Unmarshal([]byte(text), &commands)
	var ids []string
	for id := range commands {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	if !reflect.DeepEqual(ids, []string{"r-2"}) {
		t.Errorf("commands %s", text)
	}
	// 置顶不跟着删，同 Kotlin 版
	if len(s.Pins()) != 2 {
		t.Errorf("pins %v", s.Pins())
	}
}

func TestMissingAndBrokenFiles(t *testing.T) {
	s, err := Load(t.TempDir())
	if err != nil || s.Backend() != Local || s.PaneAll() || s.Hosts() != nil || s.Pins() != nil {
		t.Errorf("empty settings: %v", err)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "zeta.json")
	if err := os.WriteFile(path, []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	s, err = Load(dir)
	if err == nil || s.Backend() != Local {
		t.Errorf("broken file: %v", err)
	}
	// 坏了的照样能写，写完是好的（同 Compose 版）
	if err := s.SetBackend("r-1"); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(path); !strings.Contains(string(data), `"backend":{"t":"s","v":"r-1"}`) {
		t.Errorf("rewritten %s", data)
	}
}

func TestPinKey(t *testing.T) {
	if PinKey(Local, "abc") != "abc" || PinKey("r-1", "abc") != "r-1/abc" {
		t.Error("pin keys")
	}
	if hostOf("abc") != Local || hostOf("r-1/abc") != "r-1" {
		t.Error("hostOf")
	}
}

func readRaw(t *testing.T, dir string) map[string]json.RawMessage {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(dir, "zeta.json"))
	if err != nil {
		t.Fatal(err)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		t.Fatal(err)
	}
	return raw
}

// 关于页和自动更新用的设置就是这份。
var _ update.Prefs = (*Settings)(nil)

func TestUpdatePrefsUseComposeKeysAndTypes(t *testing.T) {
	dir, _ := composeFile(t)
	s, _ := Load(dir)
	// composeFile 里存过 updateCheckedAt（l）、updateNotified（i），没存过 autoUpdateCheck
	if !s.AutoUpdateCheck() || s.UpdateCheckedAt().UnixMilli() != 1700000000000 || s.UpdateNotified() != 7 {
		t.Errorf("read %v %v %d", s.AutoUpdateCheck(), s.UpdateCheckedAt(), s.UpdateNotified())
	}
	s.SetAutoUpdateCheck(false)
	s.SetUpdateCheckedAt(time.UnixMilli(1800000000123))
	s.SetUpdateNotified(42)
	raw := readRaw(t, dir)
	for key, want := range map[string]string{
		"autoUpdateCheck": `{"t":"b","v":false}`,
		"updateCheckedAt": `{"t":"l","v":1800000000123}`,
		"updateNotified":  `{"t":"i","v":42}`,
	} {
		if got := string(raw[key]); got != want {
			t.Errorf("%s = %s, want %s", key, got, want)
		}
	}
	empty, _ := Load(t.TempDir())
	if !empty.AutoUpdateCheck() || !empty.UpdateCheckedAt().IsZero() || empty.UpdateNotified() != 0 {
		t.Error("defaults")
	}
}

// 外观同 Compose 版：键 dark 是布尔值，跟随系统时没有这个键。
func TestDarkUsesComposeKey(t *testing.T) {
	dir := t.TempDir()
	s, err := Load(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, set := s.Dark(); set {
		t.Fatal("没设过时应该跟随系统")
	}
	dark := true
	if err := s.SetDark(&dark); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(dir, "zeta.json"))
	if err != nil || !strings.Contains(string(data), `"dark":{"t":"b","v":true}`) {
		t.Fatalf("写出来的：%s %v", data, err)
	}
	if err := s.SetDark(nil); err != nil {
		t.Fatal(err)
	}
	if again, _ := Load(dir); again != nil {
		if _, set := again.Dark(); set {
			t.Fatal("改回跟随系统后还留着 dark")
		}
	}
}
