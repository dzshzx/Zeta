package ui

import (
	"testing"
)

// todoItems 是一组条目：一次 TodoWrite，三项里做完一项、正在做一项。
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

func TestTodoStripFollowsTheList(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"busy":true`)
	f.pushItems(todoItems)
	f.see(t, "2/3", "正在修表单校验")
	f.notSee(t, "跑测试")
	// 点开是整份清单
	f.click(t, "展开任务清单")
	f.see(t, "读登录页的代码", "跑测试", "正在修表单校验")
	f.click(t, "收起任务清单")
	f.notSee(t, "跑测试")
	// 收掉以后清单变了再出来
	f.click(t, "收掉这份任务清单")
	f.notSee(t, "正在修表单校验")
	f.pushItems(`[{"kind":"work","id":"w1","steps":[{"kind":"tool","id":"t1","name":"TodoWrite","status":"success","input":{"todos":[
		{"content":"读登录页的代码","status":"completed"},{"content":"修表单校验","status":"completed"},{"content":"跑测试","status":"pending"}]}}]}]`)
	f.see(t, "2/3", "下一项：跑测试")
	// 全部做完：这一轮还在进行时显示「全部完成」，结束了就不显示
	done := `[{"kind":"work","id":"w1","steps":[{"kind":"tool","id":"t1","name":"TodoWrite","status":"success","input":{"todos":[
		{"content":"读登录页的代码","status":"completed"},{"content":"修表单校验","status":"completed"}]}}]}]`
	f.pushItems(done)
	f.see(t, "2/2", "全部完成")
	f.push(`"busy":false`)
	f.notSee(t, "全部完成")
}

// 清单是在没载入的更早历史里建的：从 todo_seed 接上。
func TestTodoStripFromSeed(t *testing.T) {
	f := newChatFixture(t)
	f.newChat(t)
	f.push(`"todo_seed":[{"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"toolu_1","name":"TaskCreate","input":{"subject":"写迁移脚本","activeForm":"正在写迁移脚本"}}]}},
		{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"Task #1 created successfully: 写迁移脚本"}]}}]`)
	f.see(t, "0/1", "下一项：写迁移脚本")
	f.pushItems(`[{"kind":"work","id":"w1","steps":[{"kind":"tool","id":"t1","name":"TaskUpdate","status":"success","input":{"taskId":"1","status":"in_progress"}}]}]`)
	f.see(t, "1/1", "正在写迁移脚本")
}

func init() {
	// 预览：回答中的任务清单条，点开的样子
	chatScenes["todo-strip"] = func(t *testing.T, f *chatFixture) {
		f.newChat(t)
		f.push(`"busy":true`)
		f.pushItems(todoItems[:len(todoItems)-1] + `,{"kind":"answer","id":"a1","text":"先看了登录页的代码，表单校验漏掉了邮箱为空的情况，正在补上。"}]`)
		f.click(t, "展开任务清单")
	}
}
