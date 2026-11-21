package main

import (
	"archive/tar"
	"bytes"
	"io"
	"regexp"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestParse(t *testing.T) {
	good := [][]string{
		{"build"}, {"start"}, {"start", "1220", "2656", "520"}, {"shot", "a.png"}, {"tap", "1", "2"},
		{"swipe", "1", "2", "3", "4"}, {"swipe", "1", "2", "3", "4", "300"}, {"text", "hi"}, {"key", "KEYCODE_BACK"},
		{"prefs", "a", "b_c"}, {"prefs-set", "cwd=/tmp", "phoneDisplay:int=3"}, {"fake", "x.jsonl"}, {"fake", "--off"},
		{"status"}, {"stop"}, {"--release", "stop"}, {"sh", "ls"}, {"sh", "--stdin", "cat"}, {"debian", "--stdin", "cat"},
		{"nodes"}, {"nodes", "^继续$"}, {"tap-text", "结束"},
	}
	for _, argv := range good {
		if _, err := parse(argv); err != nil {
			t.Errorf("%q: %v", argv, err)
		}
	}
	bad := [][]string{
		{}, {"nope"}, {"start", "1"}, {"start", "1", "2"}, {"tap", "1"}, {"tap", "1", "x"}, {"swipe", "1", "2", "3"},
		{"key", "3; reboot"}, {"prefs"}, {"prefs", "a=b"}, {"prefs-set", "a b=c"}, {"prefs-set", "n:int=x"},
		{"--release", "prefs-set", "a=b"}, {"--release", "fake", "x"}, {"sh"}, {"sh", "--stdin"}, {"sh", "a", "b"},
		{"tap-text"}, {"nodes", "("}, {"--release", "nodes"}, {"status", "extra"},
	}
	for _, argv := range bad {
		if _, err := parse(argv); err == nil {
			t.Errorf("%q should be rejected before touching the device", argv)
		}
	}
	c, _ := parse([]string{"sh", "--release", "--stdin", "cat > x"})
	if !c.release || !c.stdin || c.args[0] != "cat > x" {
		t.Errorf("flags: %+v", c)
	}
}

const prefsXML = `<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="cwd">/home/zeta</string>
    <int name="phoneDisplay" value="7" />
    <string name="commands">[{"a":1},
{"b":"x"}]</string>
    <boolean name="dark" value="true" />
</map>
`

func TestPrefSpan(t *testing.T) {
	cases := map[string]string{
		"cwd":          "    <string name=\"cwd\">/home/zeta</string>\n",
		"phoneDisplay": "    <int name=\"phoneDisplay\" value=\"7\" />\n",
		"commands":     "    <string name=\"commands\">[{\"a\":1},\n{\"b\":\"x\"}]</string>\n",
		"dark":         "    <boolean name=\"dark\" value=\"true\" />\n",
	}
	for key, want := range cases {
		s, e, ok := prefSpan(prefsXML, key)
		if !ok || prefsXML[s:e] != want {
			t.Errorf("%s: got %q", key, prefsXML[s:e])
		}
	}
	if _, _, ok := prefSpan(prefsXML, "cw"); ok {
		t.Error("a key must match the whole name")
	}
	if _, _, ok := prefSpan(prefsXML, "c.d"); ok {
		t.Error("a key is not a pattern")
	}
}

func TestPrefsSetAndRestore(t *testing.T) {
	orig := map[string]*string{}
	text, keys := applyPrefs(prefsXML, []string{"cwd=/a&<b>", "phoneDisplay:int=12", "fresh=1"}, orig)
	if !slices.Equal(keys, []string{"cwd", "phoneDisplay", "fresh"}) {
		t.Errorf("keys %v", keys)
	}
	for _, want := range []string{`<string name="cwd">/a&amp;&lt;b&gt;</string>`, `<int name="phoneDisplay" value="12" />`, `<string name="fresh">1</string>`} {
		if strings.Count(text, want) != 1 {
			t.Errorf("missing %s in\n%s", want, text)
		}
	}
	if strings.Contains(text, "/home/zeta") || strings.Contains(text, `value="7"`) || !strings.HasSuffix(text, "</map>\n") {
		t.Errorf("old values must be replaced, </map> kept:\n%s", text)
	}
	if orig["fresh"] != nil || orig["cwd"] == nil || *orig["cwd"] != "    <string name=\"cwd\">/home/zeta</string>\n" {
		t.Errorf("orig %v", orig)
	}
	// 第二次改同一个键：原值还是最早那一份
	text, _ = applyPrefs(text, []string{"cwd=/b"}, orig)
	if !strings.Contains(*orig["cwd"], "/home/zeta") {
		t.Errorf("second change must keep the first original: %q", *orig["cwd"])
	}
	back := restorePrefs(text, orig)
	for _, want := range []string{"/home/zeta", `name="commands"`, `name="dark"`} {
		if !strings.Contains(back, want) {
			t.Errorf("restore lost %s:\n%s", want, back)
		}
	}
	if strings.Contains(back, "fresh") || strings.Contains(back, "phoneDisplay") {
		t.Errorf("restore must drop new keys and phoneDisplay:\n%s", back)
	}
}

func TestScenario(t *testing.T) {
	lines, err := scenario([]byte("{\"steps\":[]}\n\n  \n{\"title\":\"t\",\"steps\":[{\"text\":\"a\"}]}\r\n"), "s.jsonl")
	if err != nil || len(lines) != 2 || lines[1] != `{"title":"t","steps":[{"text":"a"}]}` {
		t.Errorf("%q %v", lines, err)
	}
	for in, want := range map[string]string{
		"{\"steps\":[]}\nnot json\n": "第 2 行不是 JSON",
		"[1]\n":                      "第 1 行不是 JSON",
		"{\"steps\":{}}\n":           "第 1 行没有 steps",
		"{\"steps\":null}\n":         "第 1 行没有 steps",
		"{}\n":                       "第 1 行没有 steps",
	} {
		if _, err := scenario([]byte(in), "s.jsonl"); err == nil || !strings.Contains(err.Error(), want) {
			t.Errorf("%q: %v", in, err)
		}
	}
}

func TestFakeArchive(t *testing.T) {
	data := fakeArchive([]byte("#!/bin/sh\n"), []string{"a", "b"}, time.Unix(1000, 0))
	tr := tar.NewReader(bytes.NewReader(data))
	var got []string
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(tr)
		got = append(got, hdr.Name+" "+string(rune('0'+hdr.Mode&0o7))+" "+string(body))
	}
	want := []string{"fake-claude.sh 5 #!/bin/sh\n", "scenario.jsonl 4 a\nb\n"}
	if !slices.Equal(got, want) {
		t.Errorf("%q", got)
	}
}

func TestShJoin(t *testing.T) {
	got := shJoin([]string{"timeout", "60", "", "a b", "it's", "/x/y.sh", "$HOME"})
	want := `timeout 60 '' 'a b' 'it'"'"'s' /x/y.sh '$HOME'`
	if got != want {
		t.Errorf("got %s", got)
	}
}

func TestParseNodes(t *testing.T) {
	list := "610\t80\t\t点按展开控制\n1102\t84\t\t更多\n100\t250\t继续执行 下一步\t\n\n"
	nodes, err := parseNodes(list)
	if err != nil || len(nodes) != 3 || nodes[0] != (node{610, 80, "", "点按展开控制"}) || nodes[2].text != "继续执行 下一步" {
		t.Fatalf("%v %v", nodes, err)
	}
	if nodes[1].String() != "1102\t84\t\t更多" {
		t.Errorf("a node prints back as its line: %q", nodes[1].String())
	}
	if hit := matching(nodes, regexp.MustCompile(`^继续`)); len(hit) != 1 || hit[0].x != 100 {
		t.Errorf("text match: %v", hit)
	}
	if hit := matching(nodes, regexp.MustCompile(`展开`)); len(hit) != 1 || hit[0].y != 80 {
		t.Errorf("description match, topmost first: %v", hit)
	}
	if all := matching(nodes, regexp.MustCompile(``)); len(all) != 3 {
		t.Errorf("an empty pattern lists every node: %v", all)
	}
	for _, bad := range []string{"1\t2\tonly three\n", "x\t2\ta\tb\n"} {
		if _, err := parseNodes(bad); err == nil {
			t.Errorf("%q should be rejected", bad)
		}
	}
}
