package chatview

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/dzshzx/Zeta/hub/chat/items"
	"github.com/dzshzx/Zeta/windows/internal/ui/widget"
	"github.com/egoist/mygo/ui"
)

// helperFixtures 读 helper 的归约器对照用例产出的条目（helper/testdata/transcript），和 serve 推来的同一种 JSON。
// 按文件名（去掉 .items.json）排。
func helperFixtures(t *testing.T) map[string][]items.Item {
	t.Helper()
	// 经 windows/testdata 里指向 helper/ 的符号链接读：模块目录外的路径 go test 的缓存不认
	paths, err := filepath.Glob("../../../testdata/helper/testdata/transcript/*.items.json")
	if err != nil {
		t.Fatal(err)
	}
	if len(paths) == 0 {
		t.Fatal("找不到 helper/testdata/transcript/*.items.json")
	}
	out := map[string][]items.Item{}
	for _, p := range paths {
		data, err := os.ReadFile(p)
		if err != nil {
			t.Fatal(err)
		}
		list, err := items.Decode(data)
		if err != nil {
			t.Fatalf("%s: %v", p, err)
		}
		out[strings.TrimSuffix(filepath.Base(p), ".items.json")] = list
	}
	return out
}

// testPNG 是一张 w×h 的渐变图。
func testPNG(t *testing.T, w, h int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.Set(x, y, color.RGBA{uint8(60 + 160*x/w), uint8(120 + 100*y/h), 200, 255})
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// decodeItems 把 JSON 写的条目解码成列表，测试里的条目都照 serve 推来的 JSON 写。
func decodeItems(t *testing.T, js string) []items.Item {
	t.Helper()
	list, err := items.Decode([]byte(js))
	if err != nil {
		t.Fatal(err)
	}
	return list
}

func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// demoItems 是一段什么都有的对话：带附件和图的用户消息、想了几秒的思考、成功和失败的工具调用、带图的结果、
// 照 structuredPatch 画的改动、新建的文件、只按新旧字符串对比的改动、Markdown 回答、提示。
func demoItems(t *testing.T) []items.Item {
	inline := base64.StdEncoding.EncodeToString(testPNG(t, 320, 200))
	return decodeItems(t, `[
	{"kind":"user","id":"u1","text":"帮我把配置读取改成支持环境变量覆盖，顺便看看这张截图里的报错。",
	 "images":[{"kind":"inline","key":"shot","data":"`+inline+`"}],
	 "files":["@/work/project/config.go","@/work/project/docs/"],"uuid":"uuid-1"},
	{"kind":"work","id":"w1","steps":[
		{"kind":"thinking","id":"th1","text":"先看看 **config.go** 现在怎么读配置，再决定覆盖的顺序。","running":false,"seconds":4},
		{"kind":"tool","id":"t1","name":"Read","input":{"file_path":"/work/project/config.go"},"status":"success",
		 "result":"package config\n\nfunc Load(path string) (*Config, error) {\n\treturn parse(path)\n}"},
		{"kind":"tool","id":"t2","name":"Bash","input":{"command":"go test ./config/...","description":"跑配置包的测试"},
		 "status":"failed","result":"--- FAIL: TestLoad (0.00s)\n    config_test.go:12: port = 0, want 8080\nFAIL"},
		{"kind":"tool","id":"t3","name":"Edit","input":{"file_path":"/work/project/config.go","old_string":"\treturn parse(path)","new_string":"\tc, err := parse(path)\n\tif err != nil {\n\t\treturn nil, err\n\t}\n\treturn c.overrideFromEnv(), nil"},
		 "status":"success","result":"已修改","patch":{"created":false,"hunks":[{"lines":[
			{"kind":"context","text":"func Load(path string) (*Config, error) {","old":3,"new":3},
			{"kind":"removed","text":"\treturn parse(path)","old":4},
			{"kind":"added","text":"\tc, err := parse(path)","new":4},
			{"kind":"added","text":"\tif err != nil {","new":5},
			{"kind":"added","text":"\t\treturn nil, err","new":6},
			{"kind":"added","text":"\t}","new":7},
			{"kind":"added","text":"\treturn c.overrideFromEnv(), nil","new":8},
			{"kind":"context","text":"}","old":5,"new":9}]}]}},
		{"kind":"tool","id":"t4","name":"Write","input":{"file_path":"/work/project/env.go","content":"package config\n\nimport \"os\"\n\n// overrideFromEnv 用环境变量盖过文件里的值。\nfunc (c *Config) overrideFromEnv() *Config {\n\tif v := os.Getenv(\"APP_PORT\"); v != \"\" {\n\t\tc.Port = v\n\t}\n\treturn c\n}\n"},
		 "status":"success","result":"已写入","patch":{"created":true,"hunks":[]}},
		{"kind":"tool","id":"t5","name":"Read","input":{"file_path":"/work/project/screenshot.png"},"status":"success","result":"读到图片",
		 "images":[{"kind":"session","file":"/home/u/.claude/projects/-work-project/s.jsonl","ref":"12.0.1"}]}
	]},
	{"kind":"answer","id":"a1","text":`+jsonString(demoAnswer)+`,"streaming":false},
	{"kind":"user","id":"u2","text":"把日志级别也加上","files":[],"images":[]},
	{"kind":"work","id":"w2","steps":[
		{"kind":"tool","id":"t6","name":"Edit","input":{"file_path":"/work/project/env.go","old_string":"\treturn c\n}","new_string":"\tif v := os.Getenv(\"APP_LOG\"); v != \"\" {\n\t\tc.LogLevel = v\n\t}\n\treturn c\n}"},"status":"success","result":"已修改"},
		{"kind":"tool","id":"t7","name":"Agent","input":{"description":"检查其他读取配置的地方","prompt":"找出所有直接读 config 文件的地方"},"status":"running"}
	]},
	{"kind":"notice","id":"n1","text":"已停止","error":false},
	{"kind":"notice","id":"n2","text":"API Error: 529 服务过载，请稍后再试","error":true}
	]`)
}

// manyEdits 是一个改了好几个文件的工作组：折叠时只露出前几处。
func manyEdits(t *testing.T, n int) []items.Item {
	var steps []string
	for k := range n {
		name := "file" + strconv.Itoa(k) + ".go"
		steps = append(steps, `{"kind":"tool","id":"e`+strconv.Itoa(k)+`","name":"Edit","status":"success",
			"input":{"file_path":"/work/project/`+name+`","old_string":"a := 1","new_string":"a := 2"}}`)
	}
	return decodeItems(t, `[{"kind":"user","id":"u","text":"改一下"},{"kind":"work","id":"w","steps":[`+strings.Join(steps, ",")+`]}]`)
}

// longConversation 是 n 轮问答，用来翻页、跟随底部。
func longConversation(prefix string, n int) []items.Item {
	var out []items.Item
	for k := range n {
		id := prefix + strconv.Itoa(k)
		out = append(out,
			items.Item{Kind: items.User, ID: id + "-q", Text: "第 " + id + " 个问题"},
			items.Item{Kind: items.Answer, ID: id + "-a", Text: "第 " + id + " 个回答。\n\n" + strings.Repeat("内容很长的一段回答，", 12)})
	}
	return out
}

// newTester 在无窗口的测试窗里画 ch；不要动画，排版每次一样。
func newTester(ch *Chat, w, h int, dark bool) *ui.Tester {
	// 和窗口里一样用 COUI 的主题
	view := func(c *ui.Context) {
		c.SetTheme(widget.Theme(c.Theme().Dark))
		ui.Column(c).Fill().Background(c.Theme().Background).Children(func() {
			ch.View(c).Grow(1).FillWidth()
		})
	}
	return ui.NewTesterWith(view, w, h, ui.TesterOptions{Dark: dark, Preferences: ui.Preferences{ReduceMotion: true}})
}

// settleImages 等图片都载入完，再画一帧。
func settleImages(ch *Chat, tt *ui.Tester) {
	tt.Frame()
	ch.images.pending.Wait()
	tt.Frame()
}

// demoAnswer 是 demoItems 里的回答：列表、行内代码和表格。
const demoAnswer = "改好了：\n\n1. `Load` 读完文件后调用 `overrideFromEnv`；\n2. 新增 `env.go`，目前支持 `APP_PORT`。\n\n" +
	"| 变量 | 作用 |\n|---|---|\n| APP_PORT | 盖过 port |\n\n截图里的报错是端口没读到，改完以后测试通过。"
