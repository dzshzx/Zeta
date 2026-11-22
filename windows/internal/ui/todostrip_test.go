package ui

import (
	"testing"

	"github.com/dzshzx/Zeta/helper/sessionproto"
)

// todoList 是核心归并好的清单（conv.state 的 todos）：三项里做完一项、正在做一项。
const todoList = `"todos":[
 {"key":"todo-0","content":"读登录页的代码","active_form":"正在读登录页的代码","status":"completed"},
 {"key":"todo-1","content":"修表单校验","active_form":"正在修表单校验","status":"in_progress"},
 {"key":"todo-2","content":"跑测试","active_form":"正在跑测试","status":"pending"}]`

// todoItems 是清单那一轮的条目（预览里画在清单条上面）。
const todoItems = `[{"kind":"user","id":"u1","text":"把登录页修好"},
{"kind":"work","id":"w1","steps":[{"kind":"tool","id":"t1","name":"TodoWrite","status":"success","input":{"todos":[
 {"content":"读登录页的代码","status":"completed","activeForm":"正在读登录页的代码"},
 {"content":"修表单校验","status":"in_progress","activeForm":"正在修表单校验"},
 {"content":"跑测试","status":"pending","activeForm":"正在跑测试"}]}}]}]`

// pushItems 推一份整份的条目。
func (f *chatFixture) pushItems(list string) {
	f.link().Push(`{"type":"conv.items","conv":"w1","full":true,"items":` + list + `}`)
	f.tt.Frame()
}

// 清单条照 conv.state 的 todos 画，不看条目里的清单工具调用。
func TestTodoStripFollowsTheList(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.pushItems(todoItems)
	f.notSee(t, "正在修表单校验") // 条目里有 TodoWrite，核心没给清单时不画
	f.push(`"busy":true,` + todoList)
	f.see(t, "2/3", "正在修表单校验")
	f.notSee(t, "跑测试")
	// 点开是整份清单
	f.click(t, "展开任务清单")
	f.see(t, "读登录页的代码", "跑测试", "正在修表单校验")
	f.click(t, "收起任务清单")
	f.notSee(t, "跑测试")
	// 收掉以后，同一份再推来也不出来；清单变了再出来
	f.click(t, "收掉这份任务清单")
	f.notSee(t, "正在修表单校验")
	f.push(todoList)
	f.notSee(t, "正在修表单校验")
	f.push(`"todos":[{"key":"todo-0","content":"读登录页的代码","active_form":"读登录页的代码","status":"completed"},
		{"key":"todo-1","content":"修表单校验","active_form":"修表单校验","status":"completed"},
		{"key":"todo-2","content":"跑测试","active_form":"跑测试","status":"pending"}]`)
	f.see(t, "2/3", "下一项：跑测试")
	// 全部做完：这一轮还在进行时显示「全部完成」，结束了就不显示
	f.push(`"todos":[{"key":"1","content":"读登录页的代码","active_form":"读登录页的代码","status":"completed"},
		{"key":"2","content":"修表单校验","active_form":"修表单校验","status":"completed"}]`)
	f.see(t, "2/2", "全部完成")
	f.push(`"busy":false`)
	f.notSee(t, "全部完成")
	// 空清单不画
	f.push(`"busy":true,"todos":[]`)
	f.notSee(t, "展开任务清单")
}

func init() {
	// 预览：回答中的任务清单条，点开的样子
	chatScenes["todo-strip"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true,` + todoList)
		f.pushItems(todoItems[:len(todoItems)-1] + `,{"kind":"answer","id":"a1","text":"先看了登录页的代码，表单校验漏掉了邮箱为空的情况，正在补上。"}]`)
		f.click(t, "展开任务清单")
	}
}

// 清单条一行里的说法和「第几项/共几项」：正在做的那一项优先，没在做时是下一项，全部做完是「全部完成」。
func TestTodoSummaryAndPosition(t *testing.T) {
	todo := func(status string) sessionproto.Todo {
		return sessionproto.Todo{Content: status, ActiveForm: "正在 " + status, Status: status}
	}
	for _, c := range []struct {
		list              []sessionproto.Todo
		summary, position string
	}{
		{[]sessionproto.Todo{todo("completed"), todo("in_progress"), todo("pending")}, "正在 in_progress", "2/3"},
		{[]sessionproto.Todo{todo("completed"), todo("pending")}, "下一项：pending", "1/2"},
		{[]sessionproto.Todo{todo("completed"), todo("completed")}, "全部完成", "2/2"},
	} {
		if s, p := todoSummary(c.list), todoPosition(c.list); s != c.summary || p != c.position {
			t.Errorf("%+v：%q %q", c.list, s, p)
		}
	}
	if todosDone(nil) != 0 || todoAt(nil, sessionproto.TodoPending) != -1 {
		t.Error("空清单")
	}
}
