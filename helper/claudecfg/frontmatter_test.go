package claudecfg

import (
	"slices"
	"testing"
)

// 前言的解析和写法：手机版原来的 FrontmatterTest 搬过来的几样（块标量、折行、引号、列表、下一层的键）。

func TestSplitFrontNeedsBothFences(t *testing.T) {
	if f, b, ok := splitFront("---\na: 1\n---\nbody"); !ok || f != "a: 1" || b != "body" {
		t.Errorf("split %q %q %v", f, b, ok)
	}
	if _, _, ok := splitFront("---\na: 1\nno end"); ok {
		t.Error("没有结尾的 --- 也当成了前言")
	}
	if _, _, ok := splitFront("text\n---\n"); ok {
		t.Error("不在开头的 --- 也当成了前言")
	}
	if f, _, ok := splitFront("---\r\na: 1\r\n---\r\nbody"); !ok || f != "a: 1\r" {
		t.Errorf("crlf %q %v", f, ok)
	}
}

func TestParseFrontScalarsListsAndBlocks(t *testing.T) {
	f := parseFront(`name: code-reviewer
description: "Reviews code: finds bugs\nand smells"
quoted: 'it''s'
tools: [Read, Grep]
list:
  - a
  - "b c"
block: |
  line one
  line two
folded: >-
  one
  two
metadata:
  type: feedback
plain: value # comment
wrapped: first part
  second part
crlf: yes` + "\r")
	texts := map[string]string{
		"name":        "code-reviewer",
		"description": "Reviews code: finds bugs\nand smells",
		"quoted":      "it's",
		"block":       "line one\nline two",
		"folded":      "one two",
		"plain":       "value",
		"wrapped":     "first part second part",
		"crlf":        "yes",
	}
	for key, want := range texts {
		if got := f.get(key); got != want {
			t.Errorf("%s: %q, want %q", key, got, want)
		}
	}
	if v := f.values["tools"]; !v.isList || !slices.Equal(v.items, []string{"Read", "Grep"}) {
		t.Errorf("tools %+v", v)
	}
	if v := f.values["list"]; !v.isList || !slices.Equal(v.items, []string{"a", "b c"}) {
		t.Errorf("list %+v", v)
	}
	if v := f.values["metadata"]; v.fields["type"] != "feedback" {
		t.Errorf("metadata %+v", v)
	}
}

func TestYamlValueQuotesOnlyWhenNeeded(t *testing.T) {
	cases := map[string]string{
		"simple words": "simple words",
		"Read, Grep":   "Read, Grep",
		"a: b":         `"a: b"`,
		"line\nbreak":  `"line\nbreak"`,
		"yes":          `"yes"`,
		"123":          `"123"`,
		"- dash":       `"- dash"`,
		"":             `""`,
	}
	for in, want := range cases {
		if got := yamlValue(in); got != want {
			t.Errorf("yamlValue(%q) = %s, want %s", in, got, want)
		}
	}
	// 写出去再读回来不变
	for _, text := range []string{"a: b # c", "引号\"和'单引号", "tab\there", " lead", "#hash"} {
		if got := parseFront("k: " + yamlValue(text)).get("k"); got != text {
			t.Errorf("往返 %q 得到 %q", text, got)
		}
	}
}
