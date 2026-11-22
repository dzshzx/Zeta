package chat

import (
	"encoding/json"
	"testing"

	"github.com/dzshzx/Zeta/windows/internal/chat/items"
)

// work 是一个工作组，各步由 JSON 写成（同 serve 推来的格式）。
func work(t *testing.T, steps ...string) items.Item {
	t.Helper()
	it := items.Item{Kind: items.Work, ID: "w"}
	for _, s := range steps {
		var step items.Step
		if err := json.Unmarshal([]byte(s), &step); err != nil {
			t.Fatal(err)
		}
		it.Steps = append(it.Steps, step)
	}
	return it
}

func describe(l TodoList) []string {
	var out []string
	for _, t := range l {
		out = append(out, t.Key+":"+string(t.Status)+":"+t.Content+"/"+t.ActiveForm)
	}
	return out
}

func sameStrings(t *testing.T, what string, got []string, want ...string) {
	t.Helper()
	if len(got) != len(want) {
		t.Fatalf("%s = %q，应为 %q", what, got, want)
	}
	for i := range got {
		if got[i] != want[i] {
			t.Fatalf("%s = %q，应为 %q", what, got, want)
		}
	}
}

func TestTodoWriteKeepsTheLastList(t *testing.T) {
	list := Todos([]items.Item{
		work(t, `{"kind":"tool","id":"a","name":"TodoWrite","status":"success","input":{"todos":[{"content":"读代码","status":"pending","activeForm":"正在读代码"}]}}`),
		{Kind: items.Answer, Text: "好"},
		work(t, `{"kind":"tool","id":"b","name":"TodoWrite","status":"success","input":{"todos":[
			{"content":"读代码","status":"completed","activeForm":"正在读代码"},
			{"content":"跑测试","status":"in_progress","activeForm":"正在跑测试"},
			{"content":"  ","status":"pending"},
			{"content":"提交","status":"什么"}]}}`),
		// 失败的调用不算
		work(t, `{"kind":"tool","id":"c","name":"TodoWrite","status":"failed","input":{"todos":[]}}`),
	}, nil)
	sameStrings(t, "清单", describe(list), "todo-0:completed:读代码/正在读代码", "todo-1:in_progress:跑测试/正在跑测试", "todo-3:pending:提交/提交")
	if list.Done() != 1 || list.AllDone() || list.Current().Content != "跑测试" || list.Next().Content != "提交" {
		t.Fatalf("进度：done %d current %+v next %+v", list.Done(), list.Current(), list.Next())
	}
	if Todos(nil, nil) != nil {
		t.Fatal("没有清单时应为 nil")
	}
}

func TestTaskToolsBuildTheList(t *testing.T) {
	list := Todos([]items.Item{work(t,
		`{"kind":"tool","id":"c1","name":"TaskCreate","status":"success","input":{"subject":"读代码","activeForm":"正在读代码"},"result":"Task #1 created successfully: 读代码"}`,
		`{"kind":"tool","id":"c2","name":"TaskCreate","status":"success","input":{"subject":"跑测试"},"result":"Task #2 created successfully: 跑测试"}`,
		`{"kind":"tool","id":"c3","name":"TaskCreate","status":"running","input":{"subject":"提交"}}`,
		`{"kind":"tool","id":"u1","name":"TaskUpdate","status":"success","input":{"taskId":"1","status":"completed"}}`,
		`{"kind":"tool","id":"u2","name":"TaskUpdate","status":"success","input":{"taskId":"2","status":"in_progress","subject":"跑全部测试"}}`,
		`{"kind":"tool","id":"w","name":"TodoWrite","status":"success","input":{"todos":[{"content":"被盖过","status":"pending"}]}}`,
	)}, nil)
	sameStrings(t, "清单", describe(list), "1:completed:读代码/正在读代码", "2:in_progress:跑全部测试/跑全部测试", "c3:pending:提交/提交")

	// 全部做完以后再新建：新的一份，旧的不再显示；删掉的不列
	list = Todos([]items.Item{work(t,
		`{"kind":"tool","id":"c1","name":"TaskCreate","status":"success","input":{"subject":"旧的"},"result":"Task #1 created successfully: 旧的"}`,
		`{"kind":"tool","id":"u1","name":"TaskUpdate","status":"success","input":{"taskId":"1","status":"completed"}}`,
		`{"kind":"tool","id":"c2","name":"TaskCreate","status":"success","input":{"subject":"新的"},"result":"Task #2 created successfully: 新的"}`,
		`{"kind":"tool","id":"c3","name":"TaskCreate","status":"success","input":{"subject":"删掉的"},"result":"Task #3 created successfully: 删掉的"}`,
		`{"kind":"tool","id":"u3","name":"TaskUpdate","status":"success","input":{"taskId":"3","status":"deleted"}}`,
		`{"kind":"tool","id":"u9","name":"TaskUpdate","status":"success","input":{"taskId":"9","status":"in_progress"}}`,
	)}, nil)
	sameStrings(t, "新清单", describe(list), "2:pending:新的/新的", "9:in_progress:任务 #9/任务 #9")
}

// todo_seed 是没载入的历史里的清单工具调用（helper/history.go 的 todoSeed 给的样子）：接在条目前面算。
func TestTodoSeedFromEarlierHistory(t *testing.T) {
	seed := TodoSeed(json.RawMessage(`[
		{"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"toolu_1","name":"TaskCreate","input":{"subject":"读代码"}}]}},
		{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Task #1 created successfully: 读代码"}]}},
		{"type":"assistant","message":{"id":"m1","content":[{"type":"tool_use","id":"toolu_2","name":"TaskCreate","input":{"subject":"跑测试"}}]}},
		{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_2","content":"Task #2 created successfully: 跑测试"}]}}
	]`))
	if len(seed) != 2 || seed[0].Name != "TaskCreate" || seed[0].Status != items.ToolSuccess {
		t.Fatalf("seed：%+v", seed)
	}
	list := Todos([]items.Item{work(t, `{"kind":"tool","id":"u","name":"TaskUpdate","status":"success","input":{"taskId":"1","status":"completed"}}`)}, seed)
	sameStrings(t, "清单", describe(list), "1:completed:读代码/读代码", "2:pending:跑测试/跑测试")
	if TodoSeed(json.RawMessage(`[]`)) != nil || TodoSeed(nil) != nil || TodoSeed(json.RawMessage(`{`)) != nil {
		t.Fatal("空的、坏的 seed 应为 nil")
	}
}

func TestTodoSignatureFollowsTheContent(t *testing.T) {
	a := TodoList{{Key: "1", Content: "读代码", Status: TodoPending}}
	b := TodoList{{Key: "1", Content: "读代码", Status: TodoCompleted}}
	if a.Signature() == b.Signature() || a.Signature() != (TodoList{{Key: "1", Content: "读代码", Status: TodoPending, ActiveForm: "x"}}).Signature() {
		t.Fatal("签名应只随编号、状态、内容变")
	}
}
