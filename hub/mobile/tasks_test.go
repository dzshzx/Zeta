package mobile

import (
	"encoding/json"
	"testing"

	"github.com/dzshzx/Zeta/hub/hubtest"
)

// 后台任务跟着 state 交；watch 让核心开始推输出，推来后 taskoutput 取到去掉转义的文字；stoptask 交给核心。
func TestTasks(t *testing.T) {
	c, d, changed := newWatchedCore(t)
	d.OnChat = func(l *hubtest.ChatLink) {
		l.Connect()
		l.OnCall(func(call hubtest.Call) (json.RawMessage, error) {
			if call.Method == "conv.open" {
				for _, line := range hubtest.Recorded("open", call.Conv()) {
					l.Push(line)
				}
			}
			return nil, nil
		})
	}
	id := addDevice(t, c, d)
	call[map[string]bool](t, c, "new", map[string]string{"backend": id})
	until(t, c, changed, func() bool { ch := c.hub.Chat(); return ch != nil && !ch.Opening && ch.Conv != nil })
	conv := c.hub.Chat().Conv.Name()
	l := d.Chat(id)
	l.Push(`{"type":"conv.state","conv":"` + conv + `","tasks":[{"id":"t1","type":"local_bash","kind":"command","description":"跑测试","started_at":1,"state":"running"}],` +
		`"todos":[{"key":"1","content":"写代码","active_form":"在写代码","status":"in_progress"}]}`)
	var st State
	until(t, c, changed, func() bool { st = c.state(); return st.Chat != nil && len(st.Chat.Tasks) == 1 })
	if st.Chat.Tasks[0].ID != "t1" || len(st.Chat.Todos) != 1 || st.Chat.Todos[0].ActiveForm != "在写代码" {
		t.Fatalf("任务 %+v 清单 %+v", st.Chat.Tasks, st.Chat.Todos)
	}
	if out := call[TaskOutput](t, c, "taskoutput", map[string]string{"task": "t1"}); out.Got {
		t.Fatalf("没看着就有输出 %+v", out)
	}
	call[map[string]bool](t, c, "watch", map[string]any{"kind": "output", "id": "t1", "on": true})
	until(t, c, changed, func() bool { return took(l, "conv.task_output") })
	l.Push(`{"type":"conv.task_output","conv":"` + conv + `","task":"t1","text":"\u001b[32mok\u001b[0m\n","total_bytes":3}`)
	var out TaskOutput
	until(t, c, changed, func() bool { _, got := c.hub.Chat().Conv.TaskOutput("t1"); return got })
	out = call[TaskOutput](t, c, "taskoutput", map[string]string{"task": "t1"})
	if out.Text != "ok" {
		t.Fatalf("输出 %+v", out)
	}
	call[map[string]bool](t, c, "watch", map[string]any{"kind": "subagent", "id": "tu1", "on": true})
	until(t, c, changed, func() bool { _, ok := c.hub.Chat().Conv.Subagent("tu1"); return ok })
	if p := call[SubagentPage](t, c, "subagent", map[string]string{"tool_use_id": "tu1"}); !p.Watched {
		t.Fatalf("子代理 %+v", p)
	}
	call[map[string]bool](t, c, "stoptask", map[string]string{"task": "t1"})
	until(t, c, changed, func() bool { return took(l, "conv.stop_task") })
}

func took(l *hubtest.ChatLink, method string) bool {
	for _, x := range l.Took() {
		if x.Method == method {
			return true
		}
	}
	return false
}
