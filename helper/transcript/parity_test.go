package transcript

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// 对照用例（../testdata/transcript）：每个 <名字>.ops.jsonl 一行一次调用，<名字>.items.json 是跑完得出的条目。改了归约器或加了
// 用例后 ZETA_RECORD_TRANSCRIPT=1 跑一次 TestMatchesRecorded（重写各个 .items.json，这一次算失败），看过 git diff 再不带它跑一次。
// 期望结果里有的写出了全部字段，Go 版省掉零值，所以比之前两边都去掉 null、false、空字符串和空数组。
//
// 一行的写法见 op：prefix（只能在第一行，换编号前缀）、stream、assistant、user、addUser、notice、shell、finish。
type op struct {
	Op               string         `json:"op"`
	Value            string         `json:"value"`
	Event            StreamEvent    `json:"event"`
	Now              int64          `json:"now"`
	Message          Message        `json:"message"`
	History          bool           `json:"history"`
	Replay           bool           `json:"replay"`
	SessionFile      string         `json:"sessionFile"`
	UUID             string         `json:"uuid"`
	ToolUseResult    *ToolUseResult `json:"toolUseResult"`
	Text             string         `json:"text"`
	Files            []string       `json:"files"`
	Error            bool           `json:"error"`
	KeepRunningTools bool           `json:"keepRunningTools"`
}

const casesDir = "../testdata/transcript"

func run(t *testing.T, path string) *Reducer {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	r := New("")
	sc := bufio.NewScanner(f)
	sc.Buffer(nil, 1<<20)
	for n := 1; sc.Scan(); n++ {
		var o op
		if err := json.Unmarshal(sc.Bytes(), &o); err != nil {
			t.Fatalf("%s:%d：%v", path, n, err)
		}
		switch o.Op {
		case "prefix":
			if n != 1 {
				t.Fatalf("%s:%d：prefix 只能在第一行", path, n)
			}
			r = New(o.Value)
		case "stream":
			r.ApplyStream(o.Event, o.Now)
		case "assistant":
			r.ApplyAssistant(o.Message, o.History)
		case "user":
			r.ApplyUser(o.Message, o.Replay, UserOptions{SessionFile: o.SessionFile, UUID: o.UUID, Result: o.ToolUseResult})
		case "addUser":
			r.AddUser(o.Text, nil, o.Files, o.UUID)
		case "notice":
			r.AddNotice(o.Text, o.Error)
		case "shell":
			r.ApplyShell(o.Text)
		case "finish":
			r.FinishTurn(o.KeepRunningTools)
		default:
			t.Fatalf("%s:%d：不认识的调用 %q", path, n, o.Op)
		}
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}
	return r
}

// normalized 是 JSON 的值去掉了零值字段：对象里值为 null、false、""、[] 的键不要。
func normalized(t *testing.T, data []byte) any {
	t.Helper()
	var v any
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatal(err)
	}
	return dropZero(v)
}

func dropZero(v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, e := range x {
			if a, ok := e.([]any); e == nil || e == false || e == "" || ok && len(a) == 0 {
				delete(x, k)
				continue
			}
			x[k] = dropZero(e)
		}
	case []any:
		for i, e := range x {
			x[i] = dropZero(e)
		}
	}
	return v
}

func TestMatchesRecorded(t *testing.T) {
	record := os.Getenv("ZETA_RECORD_TRANSCRIPT") == "1"
	cases, _ := filepath.Glob(filepath.Join(casesDir, "*.ops.jsonl"))
	if len(cases) == 0 {
		t.Fatalf("%s 下没有对照用例", casesDir)
	}
	for _, path := range cases {
		t.Run(strings.TrimSuffix(filepath.Base(path), ".ops.jsonl"), func(t *testing.T) {
			expected := strings.TrimSuffix(path, ".ops.jsonl") + ".items.json"
			got, err := json.MarshalIndent(run(t, path).Items, "", "  ")
			if err != nil {
				t.Fatal(err)
			}
			if record {
				if err := os.WriteFile(expected, append(got, '\n'), 0o644); err != nil {
					t.Fatal(err)
				}
				// 录制时不算通过：变量留在环境里的话，核对就什么也没核对
				t.Fatal("已重写期望结果；去掉 ZETA_RECORD_TRANSCRIPT 再跑一次核对")
			}
			want, err := os.ReadFile(expected)
			if err != nil {
				t.Fatalf("没有期望结果（ZETA_RECORD_TRANSCRIPT=1 跑一次录下）：%v", err)
			}
			if !reflect.DeepEqual(normalized(t, got), normalized(t, want)) {
				t.Errorf("和录下的不一样\n现在：\n%s\n录下的：\n%s", got, want)
			}
		})
	}
}
