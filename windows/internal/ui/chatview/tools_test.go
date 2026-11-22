package chatview

import (
	"strings"
	"testing"
)

func TestToolTitles(t *testing.T) {
	cases := map[string]string{
		`{"kind":"tool","id":"1","name":"Bash","input":{"command":"ls -la\nmore","description":""}}`: "ls -la",
		`{"kind":"tool","id":"1","name":"Bash","input":{"command":"ls","description":"列出文件"}}`:       "列出文件",
		`{"kind":"tool","id":"1","name":"Bash"}`:                                                     "执行命令",
		`{"kind":"tool","id":"1","name":"Read","input":{"file_path":"/w/dir/a.go"}}`:                 "读取 a.go",
		`{"kind":"tool","id":"1","name":"NotebookEdit","input":{"notebook_path":"/w/n.ipynb"}}`:      "编辑 n.ipynb",
		`{"kind":"tool","id":"1","name":"Glob","input":{"pattern":"**/*.go"}}`:                       "查找文件 **/*.go",
		`{"kind":"tool","id":"1","name":"WebFetch","input":{"url":"https://go.dev/doc/x"}}`:          "读取网页 go.dev",
		`{"kind":"tool","id":"1","name":"Agent","input":{"prompt":"第一行\n第二行"}}`:                      "子代理：第一行",
		`{"kind":"tool","id":"1","name":"TodoWrite","input":{"todos":[{},{}]}}`:                      "更新任务清单（2 项）",
		`{"kind":"tool","id":"1","name":"TaskUpdate","input":{"taskId":"3","status":"completed"}}`:   "完成任务 #3",
		`{"kind":"tool","id":"1","name":"mcp__github__create_issue"}`:                                "github · create_issue",
		`{"kind":"tool","id":"1","name":"mcp__phone__tap"}`:                                          "手机 · tap",
		`{"kind":"tool","id":"1","name":"SomethingNew"}`:                                             "SomethingNew",
		`{"kind":"tool","id":"1","name":""}`:                                                         "工具",
	}
	for js, want := range cases {
		if got := lookOf(step(t, js)).title; got != want {
			t.Errorf("%s：标题 %q，应为 %q", js, got, want)
		}
	}
}

func TestStepDetail(t *testing.T) {
	look := toolLook{detail: "  ls  "}
	if got := stepDetail(look, "\n结果\n"); got != "ls\n\n结果" {
		t.Errorf("详情 %q", got)
	}
	if got := stepDetail(toolLook{}, "结果"); got != "结果" {
		t.Errorf("没有说明时 %q", got)
	}
	long := strings.Repeat("长", maxResultRunes+5)
	if got := stepDetail(toolLook{}, long); !strings.HasSuffix(got, "\n…") || len([]rune(got)) != maxResultRunes+2 {
		t.Errorf("长结果没截断：%d 字", len([]rune(got)))
	}
	// 没有说明的工具展开后是整份参数
	s := step(t, `{"kind":"tool","id":"1","name":"Grep","input":{"pattern":"x","path":"/w"}}`)
	if d := lookOf(s).detail; !strings.Contains(d, `"pattern": "x"`) || !strings.Contains(d, `"path": "/w"`) {
		t.Errorf("Grep 的参数：%q", d)
	}
}

func TestFirstLineAndBaseName(t *testing.T) {
	if got := firstLine("\n  \n  第一行很长很长  \n", 3); got != "第一行…" {
		t.Errorf("firstLine %q", got)
	}
	for in, want := range map[string]string{"/a/b/": "b", "c": "c", "": "", "/": ""} {
		if got := baseName(in); got != want {
			t.Errorf("baseName(%q) = %q", in, got)
		}
	}
}
