package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFixUTF8ReplacesMaximalSubparts(t *testing.T) {
	cases := map[string]string{
		"ok 中文":                 "ok 中文",
		"a\xe4\xb8b":            "a\ufffdb",           // 截断的三字节字符算一段
		"\xff\xfe":              "\ufffd\ufffd",       // 非法首字节各算一段
		"\xed\xa0\x80":          "\ufffd\ufffd\ufffd", // 代理区编码：首字节后面就不合法
		"\xf0\x9f\x98":          "\ufffd",
		"\xf0\x9f\x98\x80":      "😀",
		"\xc0\xaf":              "\ufffd\ufffd", // 过长编码
		"x\xf4\x90\x80\x80y":    "x\ufffd\ufffd\ufffd\ufffdy",
		"\xe0\x9f\x80 \xc3\x28": "\ufffd\ufffd\ufffd \ufffd(",
	}
	for in, want := range cases {
		if got := string(fixUTF8([]byte(in))); got != want {
			t.Errorf("fixUTF8(%q) = %q, want %q", in, got, want)
		}
	}
	if got := firstInvalidUTF8([]byte("中文\xe4\xb8")); got != 6 {
		t.Errorf("firstInvalidUTF8 = %d", got)
	}
}

func TestRuneHelpersCountCharacters(t *testing.T) {
	s := strings.Repeat("长", maxText+5)
	clipped := clipText(s)
	if !strings.HasSuffix(clipped, truncMark) || runeLen(strings.TrimSuffix(clipped, truncMark)) != maxText {
		t.Errorf("clipText kept %d characters", runeLen(strings.TrimSuffix(clipped, truncMark)))
	}
	if clipText(strings.Repeat("长", maxText)) != strings.Repeat("长", maxText) {
		t.Error("clipText cut a text of exactly maxText characters")
	}
	if got := runePrefix("中文abc", 3); got != "中文a" {
		t.Errorf("runePrefix = %q", got)
	}
	if got := dropRunes("会话😀.jsonl", 6); got != "会话😀" {
		t.Errorf("dropRunes = %q", got)
	}
	if got := dropRunes("abc", 6); got != "" {
		t.Errorf("dropRunes short = %q", got)
	}
	if got := oneLine("  a \n b\t c  ", 3); got != "a b…" {
		t.Errorf("oneLine = %q", got)
	}
}

func TestUserTextRestoresCommandsAndDropsInjected(t *testing.T) {
	cases := []struct {
		content any
		want    string
	}{
		{"<command-name>/model</command-name>\n<command-message>model</command-message>\n<command-args>opus</command-args>", "/model opus"},
		{"<command-name>compact</command-name>", "/compact"},
		{"<command-name>　/x </command-name><command-args> a b </command-args>", "/x a b"},
		{"<command-name>", ""},
		{"<system-reminder>x</system-reminder>", ""},
		{"[Request interrupted by user]", ""},
		{"  hello  ", "hello"},
		{"<bash-input>ls -la</bash-input>", "! ls -la"},
		{"<bash-stdout>out</bash-stdout><bash-stderr></bash-stderr>", ""},
		{[]any{obj{"type": "text", "text": "a"}, obj{"type": "image"}, obj{"type": "text", "text": "b"}}, "a\nb"},
		{[]any{obj{"type": "tool_result", "content": "x"}}, ""},
		{obj{"type": "text"}, ""},
		{nil, ""},
	}
	for _, c := range cases {
		if got := userText(c.content); got != c.want {
			t.Errorf("userText(%#v) = %q, want %q", c.content, got, c.want)
		}
	}
}

func TestAttachedNames(t *testing.T) {
	cases := map[string]string{
		"[Attached file: /home/u/a.txt]":         "a.txt",
		`[Attached folder: @"/srv/data/"]`:       "data",
		`[Attached file: @"/"]`:                  "/",
		`[Attached file: @"a"b"]`:                `a"b`,
		"[Attached folder: plain/dir///]":        "dir",
		"not [Attached file: x]":                 "",
		"[Attached file: x] trailing":            "",
		"[Attached thing: x]":                    "",
		`[Attached file: @"/a b/c d.txt"]`:       "c d.txt",
		"[Attached file: /tmp/x.txt]\nnext line": "",
	}
	for line, want := range cases {
		got, ok := attachedName(line)
		if ok != (want != "") || got != want {
			t.Errorf("attachedName(%q) = %q, %v", line, got, ok)
		}
	}
	if got := previewText("[Attached file: /a/b.txt]\n[Attached file: /c]"); got != "b.txt" {
		t.Errorf("previewText only attachments = %q", got)
	}
	if got := previewText("[Attached file: /a/b.txt]\n  看图 "); got != "看图" {
		t.Errorf("previewText = %q", got)
	}
}

func TestParseJSONKeepsNumbersAndRejectsTrailingData(t *testing.T) {
	v, ok := parseObject([]byte(`{"a":1e6,"b":12345678901234567890,"c":1.50,"d":-0}` + "\r\n"))
	if !ok {
		t.Fatal("not parsed")
	}
	var buf bytes.Buffer
	o := newEmitter(&buf)
	o.out(v)
	o.flush()
	for _, want := range []string{`"a":1e6`, `"b":12345678901234567890`, `"c":1.50`, `"d":-0`} {
		if !strings.Contains(buf.String(), want) {
			t.Errorf("output %s lost %s", buf.String(), want)
		}
	}
	for _, bad := range []string{`{"a":1} x`, `{"a":1}{}`, `[1]`, ``, `{"a":NaN}`, "\ufeff{}"} {
		if _, ok := parseObject([]byte(bad)); ok {
			t.Errorf("parseObject(%q) accepted", bad)
		}
	}
	if m, ok := parseObject([]byte(`{"t":"a` + "\xff" + `b"}`)); !ok || m["t"] != "a\ufffdb" {
		t.Errorf("invalid UTF-8 inside a string: %#v", m)
	}
	if m, ok := parseObject([]byte(`{"s":"\ud800x"}`)); !ok || m["s"] != "\ufffdx" {
		t.Errorf("lone surrogate escape: %#v", m)
	}
	// 重名的键：后一个整个替换前一个，不合并、不因类型不同而报错
	m, ok := parseObject([]byte(`{"a":{"x":1},"a":{"y":2},"b":1,"b":"s"}`))
	if a := asObj(m["a"]); !ok || len(a) != 1 || a["y"] == nil || m["b"] != "s" {
		t.Errorf("duplicate keys: %#v", m)
	}
}

func TestTruthyFollowsJSONEmptiness(t *testing.T) {
	falsy := []any{nil, false, "", json.Number("0"), json.Number("-0.0"), json.Number("0e5"), []any{}, obj{}}
	truthy_ := []any{true, "x", json.Number("1e-3"), json.Number("1e400"), []any{nil}, obj{"a": nil}}
	for _, v := range falsy {
		if truthy(v) {
			t.Errorf("truthy(%#v)", v)
		}
	}
	for _, v := range truthy_ {
		if !truthy(v) {
			t.Errorf("!truthy(%#v)", v)
		}
	}
	if !sameValue(json.Number("1.0"), json.Number("1")) || sameValue("1", json.Number("1")) || !sameValue(nil, nil) {
		t.Error("sameValue")
	}
}

func TestSpacedJSONKeepsKeyOrder(t *testing.T) {
	got, ok := spacedJSON([]byte(`{"b":1,"a":{"z":[1,2.5,"x\"y\\z\u0001"],"y":null},"c":true,"d":"中文\t","e":[],"f":{}}`))
	want := `{"b": 1, "a": {"z": [1, 2.5, "x\"y\\z\u0001"], "y": null}, "c": true, "d": "中文\t", "e": [], "f": {}}`
	if !ok || got != want {
		t.Errorf("spacedJSON = %s", got)
	}
	if q := string(appendQuoted(nil, "a\"\\\n\r\t\b\f\x1f é")); q != `"a\"\\\n\r\t\b\f\u001f`+" é\"" {
		t.Errorf("appendQuoted = %s", q)
	}
}

func TestRealPathResolvesLikeTheOldHelper(t *testing.T) {
	dir := t.TempDir()
	real := realPath(dir)
	must := func(err error) {
		if err != nil {
			t.Fatal(err)
		}
	}
	must(os.MkdirAll(filepath.Join(dir, "a/b"), 0o755))
	must(os.Symlink("a/b", filepath.Join(dir, "rel")))
	must(os.Symlink(filepath.Join(dir, "a"), filepath.Join(dir, "abs")))
	must(os.Symlink("loop2", filepath.Join(dir, "loop1")))
	must(os.Symlink("loop1", filepath.Join(dir, "loop2")))
	cases := map[string]string{
		dir + "/rel/c":           real + "/a/b/c",
		dir + "/abs/b/../x":      real + "/a/x",
		dir + "/rel/../../a":     real + "/a",
		dir + "/missing/../a/./": real + "/a",
		dir + "/loop1/x":         real + "/loop1/x",
		dir + "//a///b":          real + "/a/b",
		"/../..":                 "/",
	}
	for in, want := range cases {
		if got := realPath(in); got != want {
			t.Errorf("realPath(%q) = %q, want %q", in, got, want)
		}
	}
	if normPath("//a/./b/../c/") != "//a/c" || normPath("///a") != "/a" || normPath("/a/..") != "/" {
		t.Error("normPath")
	}
	if extension("/a/.png") != "" || extension("/a/..png") != "" || extension("/a.b/c") != "" || extension("x.tar.gz") != ".gz" {
		t.Error("extension")
	}
}

func TestDecodeTextFallsBackFromUTF8ToGB18030(t *testing.T) {
	gbk := []byte{0xd6, 0xd0, 0xce, 0xc4} // “中文”
	cases := []struct {
		data      []byte
		truncated bool
		want      string
	}{
		{[]byte("中文"), false, "中文"},
		{gbk, false, "中文"},
		{[]byte{0x81, 0x30, 0x81, 0x30, 0x94, 0x39, 0xfc, 0x36}, false, "\u0080😀"}, // 四字节码：基本平面、辅助平面
		{[]byte("中文\xe4\xb8"), true, "中文"},                                         // 截在半个字上
		{[]byte("中文\xe4"), false, "中文\ufffd"},                                      // 不是截断的，也不是 GB18030（末尾半个码）：回落 UTF-8
		{[]byte{0xaa, 0xa1, 0xa3, 0xa0}, false, "\ufffd\u3000"},                    // 用户自定义区：x/text 解成 U+FFFD（旧版 CPython 解成私用区字符）
		{[]byte("ab\x80c"), false, "ab\ufffdc"},                                    // 单个 0x80 不是 GB18030
		{append([]byte("x"), 0x81, 0x7f), false, "x\ufffd\x7f"},                    // 尾字节不合法
		{[]byte{0x84, 0x31, 0xa5, 0x30}, false, "\ufffd1\ufffd0"},                  // 基本平面段之后没有字
		{[]byte{0xd6, 0xd0, 0xce}, true, ""},                                       // 截断且坏字节在最后三个之内：留前面合法的（这里一个都没有）
	}
	for _, c := range cases {
		if got := decodeText(c.data, c.truncated); got != c.want {
			t.Errorf("decodeText(% x, %v) = %q, want %q", c.data, c.truncated, got, c.want)
		}
	}
}
