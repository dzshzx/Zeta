package chat

import (
	"reflect"
	"testing"
)

// conv.state 的 todos 解成清单，进度按状态数。
func TestTodosFromState(t *testing.T) {
	_, client, conv, _ := opened(t)
	client.Handle(line(`{"type":"conv.state","conv":"w1","todos":[
		{"key":"1","content":"读代码","active_form":"正在读代码","status":"completed"},
		{"key":"2","content":"跑测试","active_form":"正在跑测试","status":"in_progress"},
		{"key":"3","content":"提交","active_form":"提交","status":"pending"}]}`))
	l := conv.Take().State.Todos
	want := TodoList{
		{Key: "1", Content: "读代码", ActiveForm: "正在读代码", Status: TodoCompleted},
		{Key: "2", Content: "跑测试", ActiveForm: "正在跑测试", Status: TodoInProgress},
		{Key: "3", Content: "提交", ActiveForm: "提交", Status: TodoPending},
	}
	if !reflect.DeepEqual(l, want) {
		t.Fatalf("清单 %+v", l)
	}
	if l.Done() != 1 || l.AllDone() || l.Current().Content != "跑测试" || l.Next().Content != "提交" {
		t.Fatalf("进度：done %d current %+v next %+v", l.Done(), l.Current(), l.Next())
	}
	client.Handle(line(`{"type":"conv.state","conv":"w1","todos":[]}`))
	if l := conv.Take().State.Todos; len(l) != 0 || !l.AllDone() || l.Current() != nil || l.Next() != nil {
		t.Fatalf("空清单 %+v", l)
	}
}
