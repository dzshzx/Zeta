package transcript

import (
	"bufio"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// 对照用例（../testdata/transcript）：每个 <名字>.ops.jsonl 一行一次调用，<名字>.items.json 是 Kotlin 的 TranscriptReducer
// 跑完得出的条目（app 的 TranscriptParityTest 生成和核对）。Go 版跑同样的调用，得出的条目要和它一模一样。
//
// 调用的写法：{"op":"prefix","value":…}（只能在第一行，换编号前缀）、{"op":"stream","event":…,"now":毫秒}、
// {"op":"assistant","message":…,"history":真假}、{"op":"user","message":…,"replay":真假,"sessionFile":…,"uuid":…,"toolUseResult":…}、
// {"op":"addUser","text":…,"files":[…],"uuid":…}、{"op":"notice","text":…,"error":真假}、{"op":"shell","text":…}、
// {"op":"finish","keepRunningTools":真假}。

const casesDir = "../testdata/transcript"

func decode(t *testing.T, data []byte) any {
	t.Helper()
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		t.Fatalf("解析 %q：%v", data, err)
	}
	return v
}

func values(t *testing.T, data []byte) any {
	t.Helper()
	var v any
	if err := json.Unmarshal(data, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func optString(o obj, key string) *string {
	if s, ok := o[key].(string); ok {
		return &s
	}
	return nil
}

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
		op, _ := decode(t, sc.Bytes()).(map[string]any)
		flag := func(key string) bool { b, _ := op[key].(bool); return b }
		switch strOr(op, "op") {
		case "prefix":
			if n != 1 {
				t.Fatalf("%s:%d：prefix 只能在第一行", path, n)
			}
			r = New(strOr(op, "value"))
		case "stream":
			now, _ := op["now"].(json.Number).Int64()
			r.ApplyStream(object(op, "event"), now)
		case "assistant":
			r.ApplyAssistant(object(op, "message"), flag("history"))
		case "user":
			r.ApplyUser(object(op, "message"), flag("replay"), UserOptions{SessionFile: optString(op, "sessionFile"),
				UUID: optString(op, "uuid"), ToolUseResult: object(op, "toolUseResult")})
		case "addUser":
			var files []string
			for _, f := range array(op, "files") {
				files = append(files, f.(string))
			}
			r.AddUser(strOr(op, "text"), nil, files, optString(op, "uuid"))
		case "notice":
			r.AddNotice(strOr(op, "text"), flag("error"))
		case "shell":
			r.ApplyShell(strOr(op, "text"))
		case "finish":
			r.FinishTurn(flag("keepRunningTools"))
		default:
			t.Fatalf("%s:%d：不认识的调用 %v", path, n, op)
		}
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}
	return r
}

func TestParityWithKotlin(t *testing.T) {
	cases, _ := filepath.Glob(filepath.Join(casesDir, "*.ops.jsonl"))
	if len(cases) == 0 {
		t.Fatalf("%s 下没有对照用例", casesDir)
	}
	for _, path := range cases {
		name := strings.TrimSuffix(filepath.Base(path), ".ops.jsonl")
		t.Run(name, func(t *testing.T) {
			wantRaw, err := os.ReadFile(strings.TrimSuffix(path, ".ops.jsonl") + ".items.json")
			if err != nil {
				t.Fatalf("没有 Kotlin 一侧生成的期望结果（跑 app 的 TranscriptParityTest 生成）：%v", err)
			}
			gotRaw, err := json.MarshalIndent(run(t, path).Items, "", "  ")
			if err != nil {
				t.Fatal(err)
			}
			// 数字按数值比：两边的 JSON 库对同一个数可能写法不同（2.50 和 2.5）
			if !reflect.DeepEqual(values(t, gotRaw), values(t, wantRaw)) {
				t.Errorf("和 Kotlin 不一样\nGo：\n%s\nKotlin：\n%s", gotRaw, wantRaw)
			}
		})
	}
}
